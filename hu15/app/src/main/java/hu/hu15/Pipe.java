// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.os.SystemClock;
import android.util.SparseArray;

/** Session.Sink that decodes video into the attached YuvView and plays audio. One live session at a time (static, ponytail). */
public final class Pipe implements Session.Sink {
    public static volatile Pipe current;
    public static volatile YuvView view;       // set by ProjectionActivity while visible
    public volatile Session session;

    private H264Decoder dec;
    private final int threads;

    public Pipe(int threads) { this.threads = threads; }
    private final SparseArray<PcmPlayer> players = new SparseArray<PcmPlayer>();
    private long frames, firstMs, lastLogMs, decodeNs, winFrames, winMaxNs, winBytes, lastFrameMs, winMaxGapMs;
    public volatile String stats = "";

    /** A (re)created view gets the last decoded picture at once; a static phone screen sends no new frames to fill it. */
    public synchronized void attach(YuvView v) {
        view = v;
        if (dec != null && dec.decoded > 0) v.frameReady(dec.frame, dec.width, dec.height);
    }

    @Override public synchronized void videoConfig(byte[] d, int off, int len) {
        if (dec == null) dec = new H264Decoder(threads);
        feed(d, off, len);
    }

    @Override public synchronized void videoFrame(byte[] d, int off, int len) {
        if (dec == null) dec = new H264Decoder(threads);
        feed(d, off, len);
    }

    private void feed(byte[] d, int off, int len) {
        YuvView v = view;
        long t0 = System.nanoTime();
        if (frames == 0 && decodeNs == 0) Log15.i("video", "first decode call, " + len + " B");
        boolean got;
        if (v != null) synchronized (v.lock()) { got = dec.decode(d, off, len); } else got = dec.decode(d, off, len);
        long dn = System.nanoTime() - t0;
        decodeNs += dn; if (dn > winMaxNs) winMaxNs = dn;
        if (!got) return;
        long now = SystemClock.elapsedRealtime();
        if (frames == 0) { firstMs = now; Log15.i("video", "first picture " + dec.width + "x" + dec.height); }
        frames++; winFrames++; winBytes += len;
        long nowMs = SystemClock.elapsedRealtime();
        if (lastFrameMs != 0 && nowMs - lastFrameMs > winMaxGapMs) winMaxGapMs = nowMs - lastFrameMs;
        lastFrameMs = nowMs;
        if (v != null) v.frameReady(dec.frame, dec.width, dec.height);
        if (now - lastLogMs > 2000) {
            long dt = lastLogMs == 0 ? 2000 : now - lastLogMs;
            Log15.i("video", String.format("last %.1f s: %.1f fps, %d KB, decode max %d ms, longest gap %d ms", dt / 1000.0, winFrames * 1000.0 / dt, winBytes / 1024, winMaxNs / 1000000, winMaxGapMs));
            winFrames = 0; winMaxNs = 0; winBytes = 0; winMaxGapMs = 0;
            lastLogMs = now;
            stats = String.format("pic=%d  %.1f fps  decode avg %.1f ms  drawn=%d", frames, frames * 1000.0 / Math.max(1, now - firstMs),
                    decodeNs / 1e6 / frames, v == null ? 0 : v.drawn);
            Log15.i("video", stats);
        }
    }

    @Override public synchronized void audioStart(int chan, int rate, int bits, int channels) {
        PcmPlayer p = players.get(chan);
        if (p != null) p.release();
        players.put(chan, new PcmPlayer(android.media.AudioManager.STREAM_MUSIC, rate, channels));
        Log15.i("audio", "start ch" + chan);
    }

    @Override public void audioPcm(int chan, byte[] d, int off, int len) {
        PcmPlayer p;
        synchronized (this) { p = players.get(chan); }
        if (p != null) p.write(d, off, len);
    }

    @Override public synchronized void audioStop(int chan) {
        PcmPlayer p = players.get(chan);
        if (p != null) { Log15.i("audio", "ch" + chan + " written=" + p.written + " dropped=" + p.dropped); p.release(); players.remove(chan); }
    }

    @Override public synchronized void ended(String why) {
        for (int i = 0; i < players.size(); i++) players.valueAt(i).release();
        players.clear();
        if (dec != null) { dec.release(); dec = null; }
        Log15.i("pipe", "ended: " + why + " | " + stats);
        if (current == this) current = null;
        final YuvView v = view;
        if (v != null) v.post(new Runnable() { @Override public void run() { ((android.app.Activity) v.getContext()).finish(); } });
    }
}
