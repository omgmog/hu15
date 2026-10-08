// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/** 16 kHz mono PCM16 capture in 4096-byte chunks (128 ms, the size HeadUnit Revived sends). */
public final class MicCapture implements Runnable {
    public static final int CHUNK = 4096;
    public interface Callback { void chunk(byte[] pcm); }

    /** Test hook (am start --ez fakemic true): a 440 Hz tone instead of AudioRecord, for emulators without an input device. */
    public static volatile boolean synthetic;
    private final AudioRecord rec;
    private final Callback cb;
    private volatile boolean stop;
    private Thread th;

    public MicCapture(Callback cb) {
        this.cb = cb;
        if (synthetic) { rec = null; return; }
        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        rec = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 2));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); throw new IllegalStateException("AudioRecord not initialised (no mic / permission?)"); }
    }

    public void start() {
        if (rec != null) rec.startRecording();
        th = new Thread(this, "mic");
        th.start();
    }

    @Override public void run() {
        byte[] b = new byte[CHUNK];
        long t = 0;
        while (!stop && rec == null) { // synthetic tone, paced like real capture
            for (int i = 0; i < CHUNK / 2; i++, t++) {
                short v = (short) (6000 * Math.sin(2 * Math.PI * 440 * t / 16000));
                b[2 * i] = (byte) v; b[2 * i + 1] = (byte) (v >> 8);
            }
            cb.chunk(b.clone());
            try { Thread.sleep(128); } catch (InterruptedException e) { return; }
        }
        while (!stop) {
            int off = 0;
            while (off < CHUNK && !stop) {
                int n = rec.read(b, off, CHUNK - off);
                if (n <= 0) { stop = true; break; }
                off += n;
            }
            if (off == CHUNK) cb.chunk(b.clone());
        }
    }

    public void release() {
        stop = true;
        if (rec == null) return;
        try { rec.stop(); } catch (IllegalStateException ignored) { }
        rec.release();
    }
}
