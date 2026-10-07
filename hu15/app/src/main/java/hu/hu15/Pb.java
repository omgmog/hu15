// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import java.io.ByteArrayOutputStream;

/**
 * Tiny protobuf wire encoder/decoder: the ~15 AAP messages we touch don't justify protobuf-java's ~25k extra dex methods on Dalvik 4.0.
 * Field numbers come from HeadUnit Revived's .proto files (control.proto, media.proto, input.proto, sensors.proto).
 */
public final class Pb {
    private final ByteArrayOutputStream o = new ByteArrayOutputStream();

    public Pb varint(int field, long v) { tag(field, 0); raw(v); return this; }
    public Pb bool(int field, boolean v) { return varint(field, v ? 1 : 0); }
    public Pb bytes(int field, byte[] b) { tag(field, 2); raw(b.length); o.write(b, 0, b.length); return this; }
    public Pb str(int field, String s) { try { return bytes(field, s.getBytes("UTF-8")); } catch (Exception e) { throw new RuntimeException(e); } }
    /** Appends already-encoded fields (used to splice a built repeated-field block). */
    public Pb raw(byte[] b) { o.write(b, 0, b.length); return this; }
    public Pb msg(int field, Pb m) { return bytes(field, m.build()); }
    public byte[] build() { return o.toByteArray(); }

    private void tag(int field, int wire) { raw((field << 3) | wire); }
    private void raw(long v) {
        while ((v & ~0x7fL) != 0) { o.write((int) ((v & 0x7f) | 0x80)); v >>>= 7; }
        o.write((int) v);
    }

    /** First varint value of the given field in a message, or dflt if absent. Skips other fields. */
    public static long getVarint(byte[] m, int field, long dflt) {
        int[] p = {0};
        while (p[0] < m.length) {
            long key = readRaw(m, p);
            int f = (int) (key >>> 3), w = (int) (key & 7);
            if (w == 0) { long v = readRaw(m, p); if (f == field) return v; }
            else if (w == 2) { long l = readRaw(m, p); p[0] += (int) l; }
            else if (w == 1) p[0] += 8;
            else if (w == 5) p[0] += 4;
            else return dflt;
        }
        return dflt;
    }

    private static long readRaw(byte[] b, int[] p) {
        long r = 0; int s = 0;
        while (p[0] < b.length) {
            int x = b[p[0]++] & 255;
            r |= (long) (x & 0x7f) << s;
            if ((x & 0x80) == 0) break;
            s += 7;
        }
        return r;
    }
}
