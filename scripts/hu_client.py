#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Head-unit side in Python, for poking a REAL Android Auto phone (head unit server on the emulator) without rebuilding the Android app.
Does what hu15's Session does: version -> TLS client -> AuthComplete -> answers the phone's service discovery -> logs everything it sends.
Usage: adb forward tcp:5278 tcp:5277; scripts/hu_client.py [--port 5278] [--skip audio_sys,audio_speech,mpb,nav,sensor_night,input_keys] [--seconds 20]
Prints the phone's raw ServiceDiscoveryRequest so we can see what it asks for."""
import argparse, os, select, socket, ssl, struct, sys, time

D = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(D, "..", "hu15", "app", "src", "main", "res", "raw")

def varint(v):
    if v < 0: v += 1 << 64
    out = b""
    while v > 0x7f: out += bytes([(v & 0x7f) | 0x80]); v >>= 7
    return out + bytes([v])
def f_var(n, v): return varint(n << 3) + varint(v)
def f_len(n, b): return varint(n << 3 | 2) + varint(len(b)) + (b if isinstance(b, bytes) else b.encode())
def parse(b):
    i, out = 0, []
    def rd():
        nonlocal i
        r = s = 0
        while True:
            x = b[i]; i += 1; r |= (x & 0x7f) << s; s += 7
            if not x & 0x80: return r
    while i < len(b):
        k = rd(); f, w = k >> 3, k & 7
        if w == 0: out.append((f, 0, rd()))
        elif w == 2:
            l = rd(); out.append((f, 2, b[i:i + l])); i += l
        elif w == 1: out.append((f, 1, b[i:i + 8])); i += 8
        elif w == 5: out.append((f, 5, b[i:i + 4])); i += 4
        else: raise ValueError(f"wire {w}")
    return out

def dump(b, depth=0, maxd=4):
    """Readable nested dump of a protobuf blob (guesses sub-messages)."""
    pad = "  " * depth
    try: fields = parse(b)
    except Exception: fields = None
    if not fields: print(pad + repr(b[:60])); return
    for f, w, v in fields:
        if w == 0: print(f"{pad}{f}: {v}")
        elif w == 2:
            try: s = v.decode("utf-8"); ok = s.isprintable() and len(s) > 0
            except Exception: ok = False
            if ok and not (depth < maxd and len(v) > 1 and _is_msg(v)): print(f'{pad}{f}: "{s}"')
            elif depth < maxd and _is_msg(v): print(f"{pad}{f} {{"); dump(v, depth + 1, maxd); print(pad + "}")
            else: print(f"{pad}{f}: <{len(v)} bytes {v[:16].hex()}>")
        else: print(f"{pad}{f}: <wire{w}> {v.hex()}")
def _is_msg(v):
    try: fs = parse(v); return len(fs) > 0 and all(0 < f < 4096 for f, _, _ in fs)
    except Exception: return False

def build_sdr(skip, add=(), vtype=1):
    def audio(ch, stype, rate, chans):
        return f_var(1, ch) + f_len(3, f_var(1, 1) + f_var(2, stype) + f_len(3, f_var(1, rate) + f_var(2, 16) + f_var(3, chans)))
    sensors = f_len(1, f_var(1, 13)) + (b"" if "sensor_night" in skip else f_len(1, f_var(1, 10)))
    keys = b"".join(f_var(1, k) for k in ([] if "input_keys" in skip else [3, 4, 19, 20, 21, 22, 23, 66]))
    vcfg = f_var(1, 1) + f_var(2, 2) + f_var(3, 0) + f_var(4, 0) + f_var(5, 160) + f_var(8, 10000) + f_var(10, 3)
    svcs = [f_var(1, 1) + f_len(2, sensors),
            f_var(1, 2) + f_len(3, f_var(1, 3) + f_len(4, vcfg) + f_var(5, 1)),
            f_var(1, 3) + f_len(4, keys + f_len(2, f_var(1, 800) + f_var(2, 480))),
            audio(6, 3, 48000, 2)]
    if "audio_speech" not in skip: svcs.append(audio(4, 1, 16000, 1))
    if "audio_sys" not in skip: svcs.append(audio(5, 2, 16000, 1))
    if "mpb" not in skip: svcs.append(f_var(1, 9) + f_len(9, b""))
    if "nav" not in skip: svcs.append(f_var(1, 10) + f_len(8, f_var(1, 1000) + f_var(2, 2)))
    if "mic" in add: svcs.append(f_var(1, 7) + f_len(5, f_var(1, 1) + f_len(2, f_var(1, 16000) + f_var(2, 16) + f_var(3, 1))))
    if "bt" in add: svcs.append(f_var(1, 8) + f_len(6, f_len(1, "00:11:22:33:44:55") + f_var(2, 2) + f_var(2, 4)))
    r = b"".join(f_len(1, s) for s in svcs)
    r += f_len(2, "Honda") + f_len(3, "HR-V") + f_len(4, "2018") + f_len(5, "hu15-0001") + f_var(6, 0) + f_len(7, "hu15") + f_len(8, "hu15")
    r += f_len(9, "1") + f_len(10, "0.1.0") + f_var(11, 0) + f_var(12, 0) + f_var(13, 0) + f_len(14, "hu15")
    r += f_len(17, f_len(1, "Honda") + f_len(2, "HR-V") + f_len(3, "2018") + f_len(4, "hu15-0001") + f_len(5, "hu15") + f_len(6, "hu15") + f_len(7, "1") + f_len(8, "0.1.0") + f_var(9, vtype))
    return r

class Conn:
    def __init__(self, host, port):
        self.c = socket.create_connection((host, port), 5); self.c.settimeout(10); self.rbuf = b""
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.minimum_version = ctx.maximum_version = ssl.TLSVersion.TLSv1_2
        ctx.set_ciphers("DEFAULT:@SECLEVEL=0"); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
        pem = open(os.path.join(RAW, "privkey")).read()
        ctx.load_cert_chain(os.path.join(RAW, "cert"), keyfile=os.path.join(RAW, "privkey")) if "BEGIN" in pem else None
        self.inc, self.out = ssl.MemoryBIO(), ssl.MemoryBIO()
        self.s = ctx.wrap_bio(self.inc, self.out, server_side=False, server_hostname="android-auto")
        self.frag = {}
    def recvn(self, n):
        while len(self.rbuf) < n:
            r = self.c.recv(65536)
            if not r: raise EOFError("phone closed the connection")
            self.rbuf += r
        b, self.rbuf = self.rbuf[:n], self.rbuf[n:]; return b
    def plain_send(self, ch, flags, typ, payload): self.c.sendall(struct.pack(">BBHH", ch, flags, len(payload) + 2, typ) + payload)
    def plain_recv(self):
        ch, fl, ln = struct.unpack(">BBH", self.recvn(4)); b = self.recvn(ln)
        return ch, fl, struct.unpack(">H", b[:2])[0], b[2:]
    def handshake(self):
        self.plain_send(0, 3, 1, bytes([0, 1, 0, 2]))
        ch, fl, typ, p = self.plain_recv(); assert typ == 2, typ
        print(f"version response: {p.hex()} -> phone speaks AAP {struct.unpack('>H', p[:2])[0]}.{struct.unpack('>H', p[2:4])[0]} status {struct.unpack('>H', p[4:6])[0]}")
        while True:
            try: self.s.do_handshake(); break
            except ssl.SSLWantReadError:
                d = self.out.read()
                if d: self.plain_send(0, 3, 3, d)
                ch, fl, typ, p = self.plain_recv(); assert typ == 3, f"expected TLS frame, got {typ}"; self.inc.write(p)
        d = self.out.read()
        if d: self.plain_send(0, 3, 3, d)
        print(f"TLS up: {self.s.version()} {self.s.cipher()[0]}")
        self.plain_send(0, 3, 4, bytes([8, 0]))  # AuthComplete from the car
    def send(self, ch, typ, payload):
        plain = struct.pack(">H", typ) + payload
        fl = 0x0f if (ch != 0 and 1 <= typ <= 26) else 0x0b
        self.s.write(plain); enc = self.out.read()
        self.c.sendall(struct.pack(">BBH", ch, fl, len(enc)) + enc)
    def recv(self, timeout):
        """-> (ch, type, payload) or None on timeout"""
        end = time.time() + timeout
        while True:
            left = end - time.time()
            if len(self.rbuf) < 4:
                r, _, _ = select.select([self.c], [], [], max(0, left))
                if not r: return None
                d = self.c.recv(65536)
                if not d: raise EOFError("phone closed the connection")
                self.rbuf += d; continue
            ch, fl, ln = struct.unpack(">BBH", self.rbuf[:4]); off = 8 if fl == 0x09 else 4; need = off + ln
            while len(self.rbuf) < need:
                d = self.c.recv(65536)
                if not d: raise EOFError("phone closed the connection")
                self.rbuf += d
            body, self.rbuf = self.rbuf[off:need], self.rbuf[need:]
            self.inc.write(body); pt = self.s.read(65536)
            if fl & 1: self.frag[ch] = bytearray()
            self.frag.setdefault(ch, bytearray()).extend(pt)
            if fl & 2:
                m = bytes(self.frag.pop(ch)); return ch, struct.unpack(">H", m[:2])[0], m[2:]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1"); ap.add_argument("--port", type=int, default=5278)
    ap.add_argument("--skip", default=""); ap.add_argument("--add", default=""); ap.add_argument("--video-out", default=""); ap.add_argument("--vtype", type=int, default=1); ap.add_argument("--seconds", type=float, default=20)
    ap.add_argument("--dump-all", action="store_true", help="dump every received message body")
    a = ap.parse_args(); skip = set(x for x in a.skip.split(",") if x)
    c = Conn(a.host, a.port); c.handshake()
    end = time.time() + a.seconds
    sid, stats = {}, {}
    out = open(a.video_out, 'wb') if a.video_out else None
    try:
        while time.time() < end:
            m = c.recv(1.0)
            if m is None: continue
            ch, typ, p = m
            if not (ch in (2, 4, 5, 6) and typ in (0, 1)): print(f"<- ch{ch} type=0x{typ:x} len={len(p)}")
            if ch == 0 and typ == 5:
                print("== phone ServiceDiscoveryRequest:"); dump(p)
                sdr = build_sdr(skip, set(x for x in a.add.split(',') if x), a.vtype); c.send(0, 6, sdr); print(f"-> sent ServiceDiscoveryResponse ({len(sdr)} B, skip={sorted(skip)})")
            elif typ == 7:
                c.send(ch, 8, f_var(1, 0)); print(f"-> channel {ch} open ok")
                if ch == 1: c.send(1, 0x8003, f_len(13, f_var(1, 0)))
            elif ch == 0 and typ == 11: c.send(0, 12, f_var(1, dict((f, v) for f, w, v in parse(p)).get(1, 0)))
            elif ch == 0 and typ == 18: c.send(0, 19, f_var(1, 1))
            elif ch == 0 and typ == 13: c.send(0, 14, f_var(1, 2))
            elif typ == 0x8000:
                c.send(ch, 0x8003, f_var(1, 2) + f_var(2, 16) + f_var(3, 0)); print(f"-> media config ch{ch}")
                if ch == 2: c.send(2, 0x8008, f_var(1, 1) + f_var(2, 1)); print("-> video focus PROJECTED")
                else: c.send(0, 19, f_var(1, 1) + f_var(2, 1))
            elif typ == 0x8001 and ch in (2, 4, 5, 6):
                sid[ch] = dict((f, v) for f, w, v in parse(p)).get(1, 0); print(f"   media start ch{ch} session={sid[ch]}")
            elif ch in (2, 4, 5, 6) and typ in (0, 1):
                stats[ch] = stats.get(ch, 0) + 1
                if ch == 2 and out is not None: out.write(p[8:] if typ == 0 else p)
                c.send(ch, 0x8004, f_var(1, sid.get(ch, 0)) + f_var(2, 1))
            elif typ == 0x8001 and ch == 1: c.send(1, 0x8002, f_var(1, 0))
            elif typ == 0x8002 and ch == 3: c.send(3, 0x8003, f_var(1, 0))
            elif a.dump_all: dump(p)
    except EOFError as e:
        print("!!", e)
    print("media messages by channel:", stats)
    print("done")

main()
