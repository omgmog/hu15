#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Fake Android Auto *phone* for tests without a phone (TCP instead of USB). Plays the phone side of a whole session:
version exchange -> TLS 1.2 server -> AuthComplete -> service discovery -> channel opens -> sensor/input/video/audio setup
-> streams an H.264 file + PCM sine -> prints touch events from the HU -> bye-bye.
It is our reading of the protocol (taken from HeadUnit Revived), not Google's; a real phone is the final judge.
Usage: scripts/fake_phone.py [--port 5277] [--video scripts/test_800x480.h264] [--seconds 6] [--wait-touch N]
(the emulator reaches its host as 10.0.2.2)"""
import argparse, math, os, select, socket, ssl, struct, sys, time

D = os.path.dirname(os.path.abspath(__file__))
CERTS = os.path.join(D, "fake_phone_certs")
CH_CTR, CH_SEN, CH_VID, CH_INP, CH_AU1, CH_AU2, CH_AUD = 0, 1, 2, 3, 4, 5, 6

# ---- protobuf helpers (wire format only) ----
def varint(v):
    out = b""
    while v > 0x7f:
        out += bytes([(v & 0x7f) | 0x80]); v >>= 7
    return out + bytes([v])
def f_var(n, v): return varint(n << 3) + varint(v)
def f_len(n, b): return varint(n << 3 | 2) + varint(len(b)) + b
def parse(b):
    """-> list of (field, wire, value) ; value int for varint, bytes for len-delimited"""
    i, out = 0, []
    def rd():
        nonlocal i
        r = s = 0
        while True:
            x = b[i]; i += 1; r |= (x & 0x7f) << s; s += 7
            if not x & 0x80: return r
    while i < len(b):
        k = rd(); f, w = k >> 3, k & 7
        if w == 0: out.append((f, w, rd()))
        elif w == 2:
            l = rd(); out.append((f, w, b[i:i + l])); i += l
        elif w == 1: out.append((f, w, b[i:i + 8])); i += 8
        elif w == 5: out.append((f, w, b[i:i + 4])); i += 4
        else: raise ValueError("bad wire type")
    return out
def get(fields, n, default=None):
    for f, w, v in fields:
        if f == n: return v
    return default

class Phone:
    def __init__(self, c):
        self.c = c; c.settimeout(10)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ctx.maximum_version = ssl.TLSVersion.TLSv1_2
        ctx.set_ciphers("DEFAULT:@SECLEVEL=0")
        ctx.load_cert_chain(os.path.join(CERTS, "srv.crt"), os.path.join(CERTS, "srv.key"))
        ctx.verify_mode = ssl.CERT_OPTIONAL            # ask for the HU client cert like a real phone
        ctx.load_verify_locations(os.path.join(CERTS, "hu.crt"))
        ctx.verify_flags |= ssl.VERIFY_X509_PARTIAL_CHAIN
        self.inc, self.out = ssl.MemoryBIO(), ssl.MemoryBIO()
        self.s = ctx.wrap_bio(self.inc, self.out, server_side=True)
        self.q = []          # received (ch, type, payload) not yet consumed
        self.frag = {}       # ch -> bytearray
        self.rbuf = b""
        self.eof = False

    def recvn(self, n):
        while len(self.rbuf) < n:
            r = self.c.recv(65536)
            if not r: raise EOFError("HU closed the connection")
            self.rbuf += r
        b, self.rbuf = self.rbuf[:n], self.rbuf[n:]
        return b

    def plain_send(self, ch, flags, typ, payload):
        self.c.sendall(struct.pack(">BBHH", ch, flags, len(payload) + 2, typ) + payload)

    def plain_recv(self):
        ch, flags, ln = struct.unpack(">BBH", self.recvn(4))
        body = self.recvn(ln)
        return ch, flags, struct.unpack(">H", body[:2])[0], body[2:]

    def handshake(self):
        ch, fl, typ, p = self.plain_recv()
        assert typ == 1, f"expected version request, got {typ}"
        print(f"version request {p.hex()}", flush=True)
        self.plain_send(0, 3, 2, struct.pack(">HHH", 1, 2, 0))
        while True:
            try:
                self.s.do_handshake(); break
            except ssl.SSLWantReadError:
                d = self.out.read()
                if d: self.plain_send(0, 3, 3, d)
                ch, fl, typ, p = self.plain_recv()
                assert typ == 3, f"expected handshake frame, got {typ}"
                self.inc.write(p)
        d = self.out.read()
        if d: self.plain_send(0, 3, 3, d)
        peer = self.s.getpeercert(binary_form=True)
        print(f"TLS done: {self.s.version()} {self.s.cipher()[0]} client_cert={'yes' if peer else 'NONE'}", flush=True)
        ch, fl, typ, p = self.plain_recv()   # the CAR sends AuthComplete after TLS, like a real phone expects
        assert typ == 4, f"expected AuthComplete from the HU, got type {typ}"
        print(f"AuthComplete from HU: {p.hex()}", flush=True)

    def send(self, ch, typ, payload):
        plain = struct.pack(">H", typ) + payload
        pieces = [plain[i:i + 16000] for i in range(0, len(plain), 16000)] or [b""]
        for i, piece in enumerate(pieces):
            first, last = i == 0, i == len(pieces) - 1
            if first and last: fl = 0x0f if (ch != 0 and 1 <= typ <= 26) else 0x0b
            else: fl = (0x08 | (1 if first else 0) | (2 if last else 0))
            self.s.write(piece); enc = self.out.read()
            hdr = struct.pack(">BBH", ch, fl, len(enc))
            if fl == 0x09: hdr += struct.pack(">I", len(plain))
            self.c.sendall(hdr + enc)

    def pump(self, timeout):
        """Read at most whatever is available within timeout seconds; fill self.q with complete messages."""
        end = time.time() + timeout
        while True:
            left = max(0, end - time.time())
            if self.eof: return
            if len(self.rbuf) < 4:
                r, _, _ = select.select([self.c], [], [], left)
                if not r: return
                try: d = self.c.recv(65536)
                except ConnectionResetError: d = b""
                if not d:
                    self.eof = True; return   # expect() decides whether a missing message matters
                self.rbuf += d
                continue
            ch, fl, ln = struct.unpack(">BBH", self.rbuf[:4])
            need = 4 + (4 if fl == 0x09 else 0) + ln
            if len(self.rbuf) < need:
                self.c.settimeout(max(left, 0.5))
                try: d = self.c.recv(65536)
                finally: self.c.settimeout(10)
                if not d: raise EOFError("HU closed the connection")
                self.rbuf += d
                continue
            off = 8 if fl == 0x09 else 4
            body, self.rbuf = self.rbuf[off:need], self.rbuf[need:]
            self.inc.write(body)
            pt = self.s.read(65536)
            if fl & 1: self.frag[ch] = bytearray()
            self.frag.setdefault(ch, bytearray()).extend(pt)
            if fl & 2:
                m = bytes(self.frag.pop(ch))
                self.q.append((ch, struct.unpack(">H", m[:2])[0], m[2:]))
            if time.time() >= end and not self.rbuf: return

    def expect(self, ch, typ, timeout=5):
        end = time.time() + timeout
        while time.time() < end:
            for i, (c, t, p) in enumerate(self.q):
                if c == ch and t == typ:
                    del self.q[i]; return p
            if self.eof: raise EOFError(f"HU closed the connection before ch={ch} type={typ:#x}")
            self.pump(0.2)
        raise TimeoutError(f"no message ch={ch} type={typ:#x}; queue={[(c, hex(t)) for c, t, _ in self.q]}")

def nals(data):
    """Split Annex B into NAL units (with 4-byte start code)."""
    idx, i = [], 0
    while True:
        j = data.find(b"\x00\x00\x01", i)
        if j < 0: break
        idx.append(j - 1 if j > 0 and data[j - 1] == 0 else j); i = j + 3
    idx.append(len(data))
    return [data[a:b] for a, b in zip(idx, idx[1:])]
def nal_type(n):
    k = n.find(b"\x01") + 1
    return n[k] & 0x1f

def access_units(data):
    cur, out = [], []
    for n in nals(data):
        cur.append(n)
        if nal_type(n) in (1, 5): out.append(b"".join(cur)); cur = []
    return out

def sensor_types(sd):
    for f, w, v in sd:
        if f == 1:
            svc = parse(v)
            if get(svc, 1) == CH_SEN:
                return [get(parse(x), 1) for f2, w2, x in parse(get(svc, 2)) if f2 == 1]
    return []

def interactive(ph, a, drain, unacked, sess, touch_events):
    """Live video from a fake phone: dark scene, one expanding square per touch (down/move/up), encoded by ffmpeg+x264 Baseline."""
    import queue, subprocess, threading
    import numpy as np
    from PIL import Image, ImageDraw
    W, H, FPS = 800, 480, 30
    enc = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                            "-c:v", "libx264", "-profile:v", "baseline", "-preset", "ultrafast", "-tune", "zerolatency", "-pix_fmt", "yuv420p",
                            "-x264-params", "cabac=0:bframes=0:keyint=30:scenecut=0:repeat-headers=1:threads=1:rc-lookahead=0", "-f", "h264", "-"],
                           stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    aus = queue.Queue()
    def reader():  # one slice per frame (threads=1), so a VCL NAL ends an access unit; hold each NAL until the next start code proves it complete
        buf, pend = b"", []
        while True:
            d = enc.stdout.read1(65536)
            if not d: return
            buf += d
            starts = []
            i = 0
            while True:
                j = buf.find(b"\x00\x00\x01", i)
                if j < 0: break
                starts.append(j - 1 if j > 0 and buf[j - 1] == 0 else j); i = j + 3
            for a_, b_ in zip(starts, starts[1:]):
                nal = buf[a_:b_]; pend.append(nal)
                if nal_type(nal) in (1, 5): aus.put(b"".join(pend)); pend = []
            if starts: buf = buf[starts[-1]:]
    threading.Thread(target=reader, daemon=True).start()

    base = np.zeros((H, W, 3), np.uint8); base[:] = (18, 22, 34)
    base[::40, :] = (34, 40, 58); base[:, ::40] = (34, 40, 58)
    squares, last_move, last = [], 0, None
    t0 = time.time(); sent = 0; sps_sent = False; lifetime = 0.9
    while time.time() - t0 < a.seconds:
        now = time.time()
        drain()
        while touch_events:
            act, x, y, pid = touch_events.pop(0); last = (act, x, y)
            if act == 2 and now - last_move < 0.06: continue
            if act == 2: last_move = now
            squares.append((x, y, now, (255, 90, 90) if act == 0 else (90, 220, 255) if act == 2 else (255, 220, 70), 260 if act == 1 else 200))
        fr = base.copy()
        for (x, y, ts, col, maxs) in squares:
            half = int(6 + maxs * (now - ts) / lifetime / 2)
            x0, x1, y0, y1 = max(0, x - half), min(W, x + half), max(0, y - half), min(H, y + half)
            if x1 <= x0 or y1 <= y0: continue
            fade = 1 - (now - ts) / lifetime
            c = np.array(col, np.float32) * fade + np.array((18, 22, 34), np.float32) * (1 - fade)
            for (ya, yb, xa, xb) in ((y0, min(y0 + 4, y1), x0, x1), (max(y1 - 4, y0), y1, x0, x1), (y0, y1, x0, min(x0 + 4, x1)), (y0, y1, max(x1 - 4, x0), x1)):
                fr[ya:yb, xa:xb] = c
        squares = [q for q in squares if now - q[2] < lifetime]
        img = Image.fromarray(fr); dr = ImageDraw.Draw(img)
        dr.text((12, 10), f"fake phone  t={now - t0:5.1f}s  frames={sent}  last touch={last}", fill=(220, 225, 240))
        enc.stdin.write(np.asarray(img).tobytes()); enc.stdin.flush()
        try:
            while True:
                au = aus.get(timeout=0.002 if sent else 0.5)
                if not sps_sent:
                    ph.send(CH_VID, 1, b"".join(n for n in nals(au) if nal_type(n) in (7, 8))); sps_sent = True
                while unacked[CH_VID] >= sess[CH_VID][1]:
                    ph.pump(0.02); drain()
                ph.send(CH_VID, 0, struct.pack(">Q", int((time.time() - t0) * 1e6)) + au); unacked[CH_VID] += 1; sent += 1
        except queue.Empty:
            pass
        time.sleep(max(0, 1 / FPS - (time.time() - now)))
    enc.stdin.close(); enc.terminate()
    print(f"interactive done: {sent} frames sent", flush=True)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=5277)
    ap.add_argument("--video", default=os.path.join(D, "test_800x480.h264"))
    ap.add_argument("--seconds", type=float, default=6)
    ap.add_argument("--wait-touch", type=int, default=0, help="after streaming, wait for this many HU touch events")
    ap.add_argument("--interactive", action="store_true", help="render live video: an expanding square at every touch (x264 encoded on the fly), for --seconds")
    ap.add_argument("--wait-key", type=int, default=0, help="after streaming, wait for this many HU key events")
    ap.add_argument("--mic", action="store_true", help="expect the mic service (ch7); open it and require >=3 PCM chunks (needs --ez mic true)")
    ap.add_argument("--bt", action="store_true", help="expect the Bluetooth service (ch8) in discovery (needs --ez bt true)")
    a = ap.parse_args()
    ls = socket.socket(); ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ls.bind(("0.0.0.0", a.port)); ls.listen(1); ls.settimeout(300)
    print(f"fake phone listening on :{a.port}", flush=True)
    c, addr = ls.accept(); print("HU connected from", addr, flush=True)
    ph = Phone(c); ph.handshake()
    ok = True
    def check(name, cond, detail=""):
        nonlocal ok
        print(("PASS " if cond else "FAIL ") + name + (" " + detail if detail else ""), flush=True)
        ok = ok and cond

    ph.send(0, 5, f_len(4, b"fake-phone") + f_len(5, b"python"))
    sd = parse(ph.expect(0, 6))
    chans = []
    for f, w, v in sd:
        if f == 1: chans.append(get(parse(v), 1))
    check("service discovery response", len(chans) >= 5, f"services={chans} make={get(sd,2)} model={get(sd,3)}")
    for ch in chans:
        ph.send(ch, 7, f_var(1, 0) + f_var(2, ch))
        st = get(parse(ph.expect(ch, 8)), 1)
        check(f"channel {ch} open", st == 0)
    ph.expect(CH_SEN, 0x8003)
    check("driving status event", True)
    check("media playback (9) + navigation (10) announced", 9 in chans and 10 in chans)
    check("night sensor offered", 10 in sensor_types(sd), f"sensors={sensor_types(sd)}")
    if a.bt: check("bluetooth service announced", 8 in chans, f"address={[get(parse(get(parse(v),6)),1) for f,w,v in sd if f==1 and get(parse(v),1)==8]}")
    check("mic service " + ("announced" if a.mic else "absent (default)"), (7 in chans) == a.mic)
    ph.send(CH_SEN, 0x8001, f_var(1, 13) + f_var(2, 0)); check("sensor start", get(parse(ph.expect(CH_SEN, 0x8002)), 1) == 0)
    ph.send(CH_SEN, 0x8001, f_var(1, 10) + f_var(2, 0)); ph.expect(CH_SEN, 0x8002)
    night = parse(ph.expect(CH_SEN, 0x8003)); check("night mode event", get(night, 10) is not None, f"is_night={get(parse(get(night,10)),1)}")
    ph.send(CH_INP, 0x8002, f_var(1, 3)); check("input binding", get(parse(ph.expect(CH_INP, 0x8003)), 1) == 0)
    ph.send(0, 11, f_var(1, 12345)); check("ping", get(parse(ph.expect(0, 12)), 1) == 12345)
    ph.send(0, 18, f_var(1, 1)); check("audio focus grant", get(parse(ph.expect(0, 19)), 1) == 1)
    ph.send(0, 13, f_var(1, 2)); check("nav focus", get(parse(ph.expect(0, 14)), 1) == 2)

    # media channels
    sess = {}
    for ch, codec in ((CH_VID, 3), (CH_AUD, 1), (CH_AU1, 1), (CH_AU2, 1)):
        ph.send(ch, 0x8000, f_var(1, codec))
        cfg = parse(ph.expect(ch, 0x8003))
        check(f"media setup ch{ch}", get(cfg, 1) == 2, f"maxUnacked={get(cfg,2)}")
        sess[ch] = (ch, get(cfg, 2))
        ph.send(ch, 0x8001, f_var(1, ch) + f_var(2, 0))
    ph.expect(CH_VID, 0x8008); check("video focus notification", True)

    # stream video + audio
    unacked = {CH_VID: 0, CH_AUD: 0}
    acked = {CH_VID: 0, CH_AUD: 0}
    def drain():
        ph.pump(0)
        keep = []
        for c_, t_, p_ in ph.q:
            if t_ == 0x8004 and c_ in unacked:
                unacked[c_] = max(0, unacked[c_] - 1); acked[c_] += 1
            elif c_ == CH_INP and t_ == 0x8001:
                rep = parse(p_)
                if get(rep, 4) is not None:
                    k = parse(get(parse(get(rep, 4)), 1)); keys.append(k)
                    print(f"KEY code={get(k,1)} down={get(k,2)}", flush=True)
                else:
                    touches.append(p_)
                    r = parse(get(rep, 3))
                    ptrs = [parse(v_) for f_, w_, v_ in r if f_ == 1]
                    for ptr in ptrs: touch_events.append((get(r, 3), get(ptr, 1), get(ptr, 2), get(ptr, 3)))
                    print(f"TOUCH action={get(r,3)} idx={get(r,2,0)} " + " ".join(f"[id{get(q,3)} {get(q,1)},{get(q,2)}]" for q in ptrs), flush=True)
            elif c_ == 7 and t_ == 0:
                mic_chunks.append(len(p_) - 8)
                ph.send(7, 0x8004, f_var(1, mic_sid) + f_var(2, 1))
            else: keep.append((c_, t_, p_))
        ph.q[:] = keep
    touches = []; keys = []; mic_chunks = []; mic_sid = 0; touch_events = []
    if a.interactive:
        try: interactive(ph, a, drain, unacked, sess, touch_events)
        except EOFError: print('HU left', flush=True)
        print('RESULT: PASS' if ok else 'RESULT: FAIL', flush=True); sys.exit(0 if ok else 1)
    data = open(a.video, "rb").read()
    aus = access_units(data)
    sps_pps = b"".join(n for n in nals(aus[0]) if nal_type(n) in (7, 8))
    ph.send(CH_VID, 1, sps_pps)
    t0 = time.time(); fi = 0; sent_aud = 0
    n_frames = min(len(aus), int(a.seconds * 30))
    for fi in range(n_frames):
        due = t0 + fi / 30
        while time.time() < due: drain(); time.sleep(0.002)
        while unacked[CH_VID] >= sess[CH_VID][1]:
            ph.pump(0.05); drain()
            if time.time() - due > 5: raise TimeoutError("video acks stalled")
        ph.send(CH_VID, 0, struct.pack(">Q", int((time.time() - t0) * 1e6)) + aus[fi]); unacked[CH_VID] += 1
        if fi % 3 == 0:  # 20 ms of 48 kHz stereo sine per 3 frames ~ 100 ms chunks
            n = 4800
            pcm = b"".join(struct.pack("<hh", v, v) for v in (int(8000 * math.sin(2 * math.pi * 440 * (sent_aud + k) / 48000)) for k in range(n)))
            sent_aud += n
            if unacked[CH_AUD] < sess[CH_AUD][1]:
                ph.send(CH_AUD, 0, struct.pack(">Q", int((time.time() - t0) * 1e6)) + pcm); unacked[CH_AUD] += 1
    time.sleep(0.3); drain()
    if a.mic:
        ph.send(7, 0x8005, f_var(1, 1) + f_var(4, 2))
        resp = parse(ph.expect(7, 0x8006)); st = get(resp, 1); mic_sid = get(resp, 2, 0)
        check("mic open response", st == 0, f"status={st if st < 2**31 else st - 2**64} session={mic_sid}")
        if st == 0:
            end = time.time() + 6
            while len(mic_chunks) < 3 and time.time() < end: drain(); time.sleep(0.05)
            check("mic PCM chunks received", len(mic_chunks) >= 3, f"{len(mic_chunks)} x {mic_chunks[:1]} bytes")
            ph.send(7, 0x8005, f_var(1, 0)); check("mic close response", get(parse(ph.expect(7, 0x8006)), 1) == 0)
    check("video frames acked", acked[CH_VID] >= n_frames - sess[CH_VID][1], f"{acked[CH_VID]}/{n_frames}")
    check("audio chunks acked", acked[CH_AUD] > 0, f"{acked[CH_AUD]}")
    if a.wait_touch:
        end = time.time() + 60
        while len(touches) < a.wait_touch and time.time() < end: drain(); time.sleep(0.05)
        check("touch events received", len(touches) >= a.wait_touch, f"{len(touches)}")
    if a.wait_key:
        end = time.time() + 60
        while len(keys) < a.wait_key and time.time() < end: drain(); time.sleep(0.05)
        check("key events received", len(keys) >= a.wait_key, f"{len(keys)}")
    ph.send(0, 15, f_var(1, 1)); check("bye-bye response", ph.expect(0, 16) is not None)
    print("RESULT:", "PASS" if ok else "FAIL", flush=True)
    sys.exit(0 if ok else 1)

main()
