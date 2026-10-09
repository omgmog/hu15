// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import java.nio.ByteBuffer;

/** Software H.264 (Baseline) decoder: OpenH264 via JNI, output tightly packed I420 in {@link #frame}. API 15 has no MediaCodec. */
public final class H264Decoder {
    static { System.loadLibrary("hu15n"); }
    private static native long nInit(int threads);
    private static native int nDecode(long h, byte[] nal, int off, int len, ByteBuffer out, int cap, int[] info);
    private static native void nFree(long h);

    public static final int MAX_W = 1280, MAX_H = 720; // ponytail: 800x480 is all we announce; headroom only
    private long h;
    // two output buffers: the decoder writes one while the GL thread uploads the other, so decode and upload overlap (they used to take turns under one lock)
    private final ByteBuffer[] bufs = { ByteBuffer.allocateDirect(MAX_W * MAX_H * 3 / 2), ByteBuffer.allocateDirect(MAX_W * MAX_H * 3 / 2) };
    private int cur;
    public volatile ByteBuffer frame = bufs[0]; // the latest decoded picture; stays valid until the decode after next
    public int width, height;
    private final int[] info = new int[2];
    public long decoded, failed;

    public H264Decoder(int threads) {
        h = nInit(threads);
        if (h == 0) throw new IllegalStateException("openh264 init failed");
    }

    /** @return true if frame/width/height hold a new picture. The result is in {@link #frame}; it is not overwritten by the next decode. */
    public boolean decode(byte[] d, int off, int len) {
        int r = nDecode(h, d, off, len, bufs[cur], bufs[cur].capacity(), info);
        if (r < 0) { failed++; return false; }
        if (r == 0) return false;
        width = info[0]; height = info[1]; decoded++;
        frame = bufs[cur]; cur ^= 1;
        return true;
    }

    public void release() { if (h != 0) { nFree(h); h = 0; } }
}
