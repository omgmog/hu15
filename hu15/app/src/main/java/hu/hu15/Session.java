// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Head-unit side of an Android Auto session after the TLS handshake: answers the phone's control traffic,
 * opens the channels we announce, acks media, and hands video/audio payloads to the sinks.
 * Channel ids and message types are from HeadUnit Revived (Channel.kt, control.proto, media.proto, input.proto, sensors.proto).
 */
public final class Session implements Runnable {
    public static final int CH_CTR = 0, CH_SEN = 1, CH_VID = 2, CH_INP = 3, CH_AU1 = 4, CH_AU2 = 5, CH_AUD = 6, CH_MIC = 7, CH_BTH = 8, CH_MPB = 9, CH_NAV = 10;
    public static final int WIDTH = 800, HEIGHT = 480;
    private static final String TAG = "session";

    public interface Sink {
        /** SPS/PPS (Annex B) from the codec-config message. */
        void videoConfig(byte[] d, int off, int len);
        /** One access unit (Annex B). */
        void videoFrame(byte[] d, int off, int len);
        void audioStart(int chan, int rate, int bits, int channels);
        void audioPcm(int chan, byte[] d, int off, int len);
        void audioStop(int chan);
        void ended(String why);
    }

    /** What this session announces to the phone. */
    public static final class Config {
        public boolean mic, rhd;
        public String btAddress;   // null/empty = no Bluetooth service
        public boolean night;      // initial night-mode value (re-sent on sensor start)
    }

    private final AapLink link;
    private final Sink sink;
    private final Config cfg;
    private MicCapture mic;
    private int micId, micWindow;
    private final java.util.concurrent.atomic.AtomicInteger micUnacked = new java.util.concurrent.atomic.AtomicInteger();
    private long micChunks, micDropped;
    private final Map<Integer, Integer> sessionIds = new HashMap<Integer, Integer>();
    private volatile boolean stop;
    /** True when the session ended because the link broke (read error, EOF), not because we or the phone said bye-bye. */
    public volatile boolean linkLost;
    private long framesIn, videoBytes;
    private final long startMs = System.currentTimeMillis();
    private volatile long lastRxMs = System.currentTimeMillis();
    private volatile int lastRxCh, lastRxType;
    private long rxWaitNs, startNs = System.nanoTime();
    private long pings, pingsSent, pongs, pongMaxMs, pongSumMs;
    private volatile long lastPongMs = System.currentTimeMillis();

    public Session(AapLink link, Sink sink, Config cfg) { this.link = link; this.sink = sink; this.cfg = cfg; }

    public void stop() { stop = true; }

    @Override public void run() {
        // fragments of different channels interleave on the wire, so reassemble per channel (a global buffer dropped and mixed messages)
        java.util.HashMap<Integer, ByteArrayOutputStream> bufs = new java.util.HashMap<Integer, ByteArrayOutputStream>();
        java.util.HashMap<Integer, Integer> types = new java.util.HashMap<Integer, Integer>();
        int reasmDrops = 0;
        Thread mon = new Thread(new Runnable() { @Override public void run() { // link health: log silent gaps so a stalled link can be told from a static screen
            long reported = 0;
            while (!stop) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                long gap = System.currentTimeMillis() - lastRxMs;
                if (gap >= 3000 && System.currentTimeMillis() - reported >= 3000) {
                    reported = System.currentTimeMillis();
                    Log15.i(TAG, "link quiet for " + gap / 1000 + " s (last rx ch" + lastRxCh + " type 0x" + Integer.toHexString(lastRxType) + ", pings so far " + pings + ")");
                }
            }
        } }, "link-monitor");
        mon.setDaemon(true);
        mon.start();
        // Keep traffic flowing every 2 s: keeps a tablet's Wi-Fi radio awake when the phone's screen is static, and measures the round trip.
        Thread pinger = new Thread(new Runnable() { @Override public void run() {
            while (!stop) {
                try { Thread.sleep(2000); send(CH_CTR, 11, new Pb().varint(1, System.nanoTime() / 1000).build()); pingsSent++; }
                catch (InterruptedException e) { return; } catch (Throwable t) { return; }
                long silent = System.currentTimeMillis() - lastPongMs;
                if (silent > 8000 && pingsSent % 5 == 0) Log15.i(TAG, "no ping reply for " + silent / 1000 + " s (sent " + pingsSent + ", answered " + pongs + ")");
                if (silent > 15000) { // dead phone/link: say bye so the phone drops its session (else a replug resumes the frozen one), end ours and let the app reconnect
                    Log15.i(TAG, "no ping reply for " + silent / 1000 + " s: ending the session");
                    linkLost = true;
                    byeBye();
                    return;
                }
            }
        } }, "pinger");
        pinger.setDaemon(true);
        pinger.start();
        String why = "stopped";
        try {
            while (!stop) {
                long w0 = System.nanoTime();
                AapLink.Frame f = link.readEncrypted();
                rxWaitNs += System.nanoTime() - w0;
                lastRxMs = System.currentTimeMillis();
                ByteArrayOutputStream buf = bufs.get(f.chan);
                if (buf == null) { buf = new ByteArrayOutputStream(); bufs.put(f.chan, buf); }
                if ((f.flags & 1) != 0) {
                    if (buf.size() > 0 && ++reasmDrops <= 5) Log15.i(TAG, "ch" + f.chan + ": new message started before the last one finished (" + buf.size() + " B dropped)");
                    buf.reset(); types.put(f.chan, f.type); lastRxCh = f.chan; lastRxType = f.type;
                }
                buf.write(f.payload, 0, f.payload.length);
                if ((f.flags & 2) != 0) {
                    byte[] p = buf.toByteArray();
                    buf.reset();
                    Integer type = types.get(f.chan);
                    framesIn++;
                    if (type != null && !dispatch(f.chan, type, p)) { why = "bye-bye"; break; }
                }
            }
        } catch (Throwable t) {
            if (!stop) { Log15.e(TAG, "read loop", t); why = t.toString(); linkLost = true; }
        }
        stop = true;
        stopMic();
        Log15.i(TAG, "pings sent " + pingsSent + ", answered " + pongs + ", avg rtt " + (pongs == 0 ? 0 : pongSumMs / pongs) + " ms, max " + pongMaxMs + " ms");
        Log15.i(TAG, "session ended: " + why + " after " + framesIn + " messages, " + videoBytes + " video bytes, mic chunks=" + micChunks + " (dropped " + micDropped + ")");
        sink.ended(why);
    }

    /** @return false when the session should end. */
    private boolean dispatch(int ch, int type, byte[] p) throws Exception {
        if (type == 7) { // ChannelOpenRequest, valid on any channel
            Log15.i(TAG, "channel open " + ch);
            send(ch, 8, new Pb().varint(1, 0).build()); // MessageStatus.SUCCESS
            if (ch == CH_SEN) send(CH_SEN, 0x8003, new Pb().msg(13, new Pb().varint(1, 0)).build()); // SensorBatch.driving_status UNRESTRICTED
            return true;
        }
        if (ch == CH_CTR) return control(type, p);
        switch (ch) {
            case CH_SEN:
                if (type == 0x8001) { // SensorRequest -> success
                    long st = Pb.getVarint(p, 1, -1);
                    Log15.i(TAG, "sensor start type=" + st);
                    send(ch, 0x8002, new Pb().varint(1, 0).build());
                    if (st == 10) send(CH_SEN, 0x8003, new Pb().msg(10, new Pb().bool(1, cfg.night)).build()); // NIGHT -> SensorBatch.night_mode
                } else Log15.i(TAG, "sensor msg 0x" + Integer.toHexString(type));
                return true;
            case CH_INP:
                if (type == 0x8002) send(ch, 0x8003, new Pb().varint(1, 0).build()); // BindingRequest -> BindingResponse success
                else Log15.i(TAG, "input msg 0x" + Integer.toHexString(type));
                return true;
            case CH_VID: case CH_AU1: case CH_AU2: case CH_AUD:
                return media(ch, type, p);
            case CH_MIC:
                return micMsg(type, p);
            case CH_BTH: case CH_MPB: case CH_NAV: // announced so the phone enables calls / metadata; we only log them (HeadUnit Revived does the same for BT)
                Log15.i(TAG, "ch" + ch + " msg 0x" + Integer.toHexString(type) + " len=" + p.length);
                return true;
            default:
                Log15.i(TAG, "ignored msg 0x" + Integer.toHexString(type) + " on channel " + ch);
                return true;
        }
    }

    private boolean control(int type, byte[] p) throws Exception {
        switch (type) {
            case 5: // ServiceDiscoveryRequest
                Log15.i(TAG, "service discovery request, " + p.length + " bytes");
                send(CH_CTR, 6, serviceDiscoveryResponse());
                return true;
            case 12: { // PingResponse: round trip of our own ping
                long rtt = (System.nanoTime() / 1000 - Pb.getVarint(p, 1, 0)) / 1000;
                lastPongMs = System.currentTimeMillis();
                pongs++; pongSumMs += rtt; if (rtt > pongMaxMs) pongMaxMs = rtt;
                if (pongs % 15 == 1) Log15.i(TAG, "ping rtt " + rtt + " ms (avg " + pongSumMs / pongs + ", max " + pongMaxMs + ", " + pongs + " answered), waiting for the phone " + 100 * rxWaitNs / Math.max(1, System.nanoTime() - startNs) + "% of the time");
                return true;
            }
            case 11: // PingRequest -> PingResponse(timestamp)
                pings++;
                send(CH_CTR, 12, new Pb().varint(1, Pb.getVarint(p, 1, 0)).build());
                return true;
            case 13: // NavFocusRequest -> NAV_FOCUS_PROJECTED
                send(CH_CTR, 14, new Pb().varint(1, 2).build());
                return true;
            case 15: // ByeByeRequest
                Log15.i(TAG, "phone says bye-bye, reason=" + Pb.getVarint(p, 1, -1));
                send(CH_CTR, 16, new byte[0]);
                return false;
            case 17: Log15.i(TAG, "voice session " + Pb.getVarint(p, 1, -1)); return true;
            case 18: { // AudioFocusRequest: always grant. request 4=release -> LOSS(3), else GAIN(1) / GAIN_TRANSIENT(2)
                long req = Pb.getVarint(p, 1, 1);
                int state = req == 4 ? 3 : req == 2 ? 2 : 1;
                send(CH_CTR, 19, new Pb().varint(1, state).build());
                return true;
            }
            default:
                Log15.i(TAG, "unhandled control type " + type + " len=" + p.length);
                return true;
        }
    }

    private boolean media(int ch, int type, byte[] p) throws Exception {
        switch (type) {
            case 0x8000: { // MediaSetupRequest -> Config READY
                Log15.i(TAG, "media setup ch" + ch + " codec=" + Pb.getVarint(p, 1, -1));
                send(ch, 0x8003, new Pb().varint(1, 2).varint(2, ch == CH_VID ? 16 : 16).varint(3, 0).build());
                if (ch == CH_VID) send(CH_VID, 0x8008, new Pb().varint(1, 1).bool(2, true).build()); // VideoFocus PROJECTED, unsolicited
                else send(CH_CTR, 19, new Pb().varint(1, 1).bool(2, true).build()); // AudioFocus GAIN, unsolicited
                return true;
            }
            case 0x8001: { // Start
                int sid = (int) Pb.getVarint(p, 1, 0);
                sessionIds.put(ch, sid);
                Log15.i(TAG, "media start ch" + ch + " session=" + sid);
                if (ch != CH_VID) sink.audioStart(ch, ch == CH_AUD ? 48000 : 16000, 16, ch == CH_AUD ? 2 : 1);
                return true;
            }
            case 0x8002: // Stop
                Log15.i(TAG, "media stop ch" + ch);
                if (ch != CH_VID) sink.audioStop(ch);
                return true;
            case 0x8007: // VideoFocusRequest from phone
                Log15.i(TAG, "video focus request mode=" + Pb.getVarint(p, 2, -1));
                send(CH_VID, 0x8008, new Pb().varint(1, Pb.getVarint(p, 2, 1)).bool(2, false).build());
                return true;
            case 0: case 1: { // media data (8-byte timestamp + payload) / codec config (payload only)
                int off = type == 0 ? 8 : 0;
                if (p.length < off) return true;
                if (ch == CH_VID) {
                    videoBytes += p.length;
                    if (type == 1) sink.videoConfig(p, off, p.length - off); else sink.videoFrame(p, off, p.length - off);
                } else if (type == 0) sink.audioPcm(ch, p, off, p.length - off);
                Integer sid = sessionIds.get(ch);
                send(ch, 0x8004, new Pb().varint(1, sid == null ? 0 : sid).varint(2, 1).build()); // Ack(session, 1)
                return true;
            }
            default:
                Log15.i(TAG, "unhandled media type 0x" + Integer.toHexString(type) + " on ch" + ch);
                return true;
        }
    }

    private boolean micMsg(int type, byte[] p) throws Exception {
        switch (type) {
            case 0x8005: { // MicrophoneRequest{open=1, max_unacked=4} -> MicrophoneResponse{status=1, session_id=2}
                boolean open = Pb.getVarint(p, 1, 0) != 0;
                if (!open) { stopMic(); send(CH_MIC, 0x8006, new Pb().varint(1, 0).varint(2, micId).build()); return true; }
                if (!cfg.mic) { Log15.i(TAG, "mic requested but disabled in settings"); send(CH_MIC, 0x8006, new Pb().varint(1, -7).varint(2, micId).build()); return true; }
                stopMic();
                Log15.i(TAG, "mic requested, opening");
                micWindow = (int) Math.max(1, Math.min(16, Pb.getVarint(p, 4, 2)));
                micUnacked.set(0);
                // On a worker thread: AudioRecord can block for good on the Honda unit, and it must never stall the read loop (a stalled loop = dead link; seen on "ok Google" with the unit mic enabled).
                new Thread(new Runnable() { @Override public void run() {
                    try {
                        MicCapture m = new MicCapture(new MicCapture.Callback() { @Override public void chunk(byte[] pcm) { micChunk(pcm); } });
                        micId++;
                        send(CH_MIC, 0x8006, new Pb().varint(1, 0).varint(2, micId).build()); // response before the first data, as HUR does
                        mic = m;
                        m.start();
                        Log15.i(TAG, "mic open session=" + micId + " window=" + micWindow);
                    } catch (Throwable t) {
                        Log15.e(TAG, "mic start", t);
                        mic = null;
                        try { send(CH_MIC, 0x8006, new Pb().varint(1, -7).varint(2, micId).build()); } catch (Throwable ignored) { }
                    }
                } }, "mic-open").start();
                return true;
            }
            case 0x8004: // Ack from phone
                micUnacked.addAndGet(-(int) Pb.getVarint(p, 2, 1));
                if (micUnacked.get() < 0) micUnacked.set(0);
                return true;
            default:
                Log15.i(TAG, "mic msg 0x" + Integer.toHexString(type));
                return true;
        }
    }

    private void micChunk(byte[] pcm) {
        if (micUnacked.get() >= micWindow) { micDropped++; return; } // phone not keeping up: drop, never block capture
        micUnacked.incrementAndGet();
        micChunks++;
        byte[] m = new byte[8 + pcm.length];
        long us = System.nanoTime() / 1000;
        for (int i = 0; i < 8; i++) m[i] = (byte) (us >>> (56 - 8 * i));
        System.arraycopy(pcm, 0, m, 8, pcm.length);
        try { send(CH_MIC, 0, m); } catch (Throwable t) { Log15.e(TAG, "mic send", t); }
    }

    private void stopMic() {
        MicCapture m = mic;
        mic = null;
        if (m != null) m.release();
    }

    /** Hardware key (Android keycode, same numbering as AA). */
    public void sendKey(int keycode, boolean down) {
        try {
            Pb key = new Pb().varint(1, keycode).bool(2, down).varint(3, 0).bool(4, false);
            send(CH_INP, 0x8001, new Pb().varint(1, System.nanoTime()).msg(4, new Pb().msg(1, key)).build());
        } catch (Throwable t) { Log15.e(TAG, "key send", t); }
    }

    /** Leave the session: tell the phone bye-bye, then close the link ourselves after a moment so we can never hang waiting for its reply. */
    public void byeBye() {
        try { send(CH_CTR, 15, new Pb().varint(1, 1).build()); } catch (Throwable t) { Log15.e(TAG, "byebye", t); }
        stop = true;
        new Thread(new Runnable() { @Override public void run() {
            try { Thread.sleep(1200); } catch (InterruptedException ignored) { }
            link.close();
        } }, "byebye-close").start();
    }

    public void send(int ch, int type, byte[] payload) throws Exception { link.sendEncrypted(ch, type, payload); }

    /**
     * Touch report in head-unit pixels. Every active pointer goes in every report (what the phone expects for moves);
     * action: 0 down, 1 up, 2 move, 5 pointer-down, 6 pointer-up; actionIndex = which entry in xs/ys/ids changed.
     */
    public void sendTouch(int action, int actionIndex, int[] xs, int[] ys, int[] ids) {
        try {
            Pb touch = new Pb();
            for (int i = 0; i < xs.length; i++) touch.msg(1, new Pb().varint(1, xs[i]).varint(2, ys[i]).varint(3, ids[i]));
            touch.varint(2, actionIndex).varint(3, action);
            send(CH_INP, 0x8001, new Pb().varint(1, System.nanoTime()).msg(3, touch).build());
        } catch (Throwable t) { Log15.e(TAG, "touch send", t); }
    }

    private byte[] serviceDiscoveryResponse() {
        Pb svcs = new Pb();
        // sensor source: driving status (phone demands it) + night mode
        svcs.msg(1, new Pb().varint(1, CH_SEN).msg(2, new Pb().msg(1, new Pb().varint(1, 13)).msg(1, new Pb().varint(1, 10))));
        // video sink: H.264 BP, 800x480@30, 160 dpi, no margins
        Pb vcfg = new Pb().varint(1, 1).varint(2, 2).varint(3, 0).varint(4, 0).varint(5, 160).varint(8, 10000).varint(10, 3);
        svcs.msg(1, new Pb().varint(1, CH_VID).msg(3, new Pb().varint(1, 3).msg(4, vcfg).bool(5, true)));
        // input: touchscreen + a few keys (HOME, BACK, DPAD*, ENTER, media)
        Pb in = new Pb();
        for (int k : new int[]{3, 4, 19, 20, 21, 22, 23, 66, 84, 85, 87, 88, 126, 127}) in.varint(1, k);
        in.msg(2, new Pb().varint(1, WIDTH).varint(2, HEIGHT));
        svcs.msg(1, new Pb().varint(1, CH_INP).msg(4, in));
        // audio sinks, PCM only: media 48k stereo, speech/system 16k mono
        svcs.msg(1, audio(CH_AUD, 3, 48000, 2));
        svcs.msg(1, audio(CH_AU1, 1, 16000, 1));
        svcs.msg(1, audio(CH_AU2, 2, 16000, 1));
        if (cfg.mic) svcs.msg(1, new Pb().varint(1, CH_MIC).msg(5, new Pb().varint(1, 1).msg(2, new Pb().varint(1, 16000).varint(2, 16).varint(3, 1))));
        if (cfg.btAddress != null && cfg.btAddress.length() > 0)
            svcs.msg(1, new Pb().varint(1, CH_BTH).msg(6, new Pb().str(1, cfg.btAddress).varint(2, 2).varint(2, 4))); // pairing: numeric comparison, PIN
        svcs.msg(1, new Pb().varint(1, CH_MPB).msg(9, new Pb()));                                  // media playback status (metadata only)
        svcs.msg(1, new Pb().varint(1, CH_NAV).msg(8, new Pb().varint(1, 1000).varint(2, 2)));     // navigation status, image codes only
        byte[] s = svcs.build();
        Pb r = new Pb();
        r.raw(s);
        r.str(2, "Honda").str(3, "HR-V").str(4, "2018").str(5, "hu15-0001").varint(6, cfg.rhd ? 1 : 0)
         .str(7, "hu15").str(8, "hu15").str(9, "1").str(10, "0.1.0").bool(11, false).bool(12, false).varint(13, 0).str(14, "hu15")
         .msg(17, new Pb().str(1, "Honda").str(2, "HR-V").str(3, "2018").str(4, "hu15-0001").str(5, "hu15").str(6, "hu15").str(7, "1").str(8, "0.1.0").varint(9, cfg.mic ? 1 : 3)); // vehicle_type: real Android Auto 17.9 hangs up unless a mic service exists OR the vehicle is a motorcycle (3 = phone uses its own mic)
        return r.build();
    }

    private static Pb audio(int ch, int streamType, int rate, int channels) {
        return new Pb().varint(1, ch).msg(3, new Pb().varint(1, 1).varint(2, streamType)
                .msg(3, new Pb().varint(1, rate).varint(2, 16).varint(3, channels)));
    }
}
