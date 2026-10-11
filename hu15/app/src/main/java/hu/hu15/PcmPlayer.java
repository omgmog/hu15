// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.concurrent.ArrayBlockingQueue;

/** One AudioTrack per AA audio channel, fed from a bounded queue so a slow sink can never stall the session read loop. */
public final class PcmPlayer implements Runnable {
    private final AudioTrack track;
    private final ArrayBlockingQueue<byte[]> q = new ArrayBlockingQueue<byte[]>(24); // ~2.4 s of 100 ms chunks; oldest dropped when full
    private volatile boolean stop;
    public long written, dropped;
    private final Thread th;

    public PcmPlayer(int stream, int rate, int channels) {
        int cfg = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(rate, cfg, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack(stream, rate, cfg, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, rate * channels * 2 / 5), AudioTrack.MODE_STREAM);
        track.play();
        th = new Thread(this, "pcm-" + rate);
        th.start();
    }

    public void write(byte[] d, int off, int len) {
        byte[] c = new byte[len];
        System.arraycopy(d, off, c, 0, len);
        while (!q.offer(c)) { q.poll(); dropped++; }
    }

    @Override public void run() {
        try {
            while (!stop) {
                byte[] b = q.take();
                track.write(b, 0, b.length);
                written += b.length;
            }
        } catch (InterruptedException ignored) { }
    }

    public void release() {
        stop = true;
        th.interrupt();
        try { track.pause(); track.flush(); track.stop(); } catch (IllegalStateException ignored) { }
        track.release();
    }
}
