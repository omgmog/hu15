// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.Map;

/**
 * USB host side of Android Open Accessory: switch the phone into accessory mode, then talk over its two bulk endpoints.
 * Only android.hardware.usb calls that exist on API 12 are used (no bulkTransfer offset variant, that is API 18).
 */
public final class UsbTransport implements Transport {
    private static final String TAG = "usb";
    private static final int TIMEOUT_MS = 10000;   // OpenDroidAuto uses 10 s on Honda units. A short bulk-read timeout can make Linux drop data already received for that transfer.
    private static final int CHUNK = 16384;
    private static final int GOOGLE_VID = 0x18d1;

    private final UsbDeviceConnection conn;
    private final UsbInterface iface;
    private final UsbEndpoint in, out;
    private final byte[] rbuf = new byte[CHUNK];
    private int rpos, rlen;
    private volatile boolean closed;
    private int readDeadlineMs = 15000;

    static { try { System.loadLibrary("hu15n"); } catch (Throwable ignored) { } }
    private static native int nReset(int fd);

    /** A device that could be the phone: not a hub, not already an accessory, not mass storage (the unit's USB stick). */
    public static boolean isPhoneCandidate(UsbDevice d) {
        if (isAccessory(d) || d.getVendorId() == 0x1d6b || d.getDeviceClass() == 9) return false;
        for (int i = 0; i < d.getInterfaceCount(); i++) if (d.getInterface(i).getInterfaceClass() == 8) return false;
        return true;
    }

    public static UsbDevice findPhone(UsbManager m) {
        for (UsbDevice d : m.getDeviceList().values()) if (isPhoneCandidate(d)) return d;
        return null;
    }

    /** USBDEVFS_RESET: same as unplug+replug for the phone, which drops a stale accessory session. @return ioctl result, -1 on failure, -2 without the native library. */
    public static int reset(UsbManager m, UsbDevice d) {
        UsbDeviceConnection c = m.openDevice(d);
        if (c == null) return -1;
        try { return nReset(c.getFileDescriptor()); } catch (UnsatisfiedLinkError e) { return -2; } finally { c.close(); }
    }

    /** True if the device is already in accessory mode (VID 18d1, PID 2d00..2d05). */
    public static boolean isAccessory(UsbDevice d) {
        return d.getVendorId() == GOOGLE_VID && d.getProductId() >= 0x2d00 && d.getProductId() <= 0x2d05;
    }

    public static String describe(UsbDevice d) {
        StringBuilder s = new StringBuilder(String.format("%s vid=%04x pid=%04x class=%d ifaces=%d", d.getDeviceName(),
                d.getVendorId(), d.getProductId(), d.getDeviceClass(), d.getInterfaceCount()));
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            s.append(String.format("\n    if%d class=%d sub=%d proto=%d eps=%d", f.getId(), f.getInterfaceClass(), f.getInterfaceSubclass(),
                    f.getInterfaceProtocol(), f.getEndpointCount()));
            for (int e = 0; e < f.getEndpointCount(); e++) {
                UsbEndpoint ep = f.getEndpoint(e);
                s.append(String.format(" [ep%02x type=%d mps=%d]", ep.getAddress(), ep.getType(), ep.getMaxPacketSize()));
            }
        }
        return s.toString();
    }

    /** Sends the AOA handshake. The phone then drops off the bus and re-appears as an accessory (VID 18d1). */
    public static boolean switchToAccessory(UsbManager m, UsbDevice d) {
        UsbDeviceConnection c = m.openDevice(d);
        if (c == null) { Log15.i(TAG, "openDevice failed (permission?)"); return false; }
        try {
            byte[] v = new byte[2];
            int n = c.controlTransfer(UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR, 51, 0, 0, v, 2, TIMEOUT_MS);
            int ver = n == 2 ? ((v[1] & 255) << 8) | (v[0] & 255) : -1;
            Log15.i(TAG, "AOA GET_PROTOCOL -> len=" + n + " version=" + ver);
            if (ver < 1) return false;
            String[] s = {"Android", "Android Auto", "Android Auto", "2.0.1", "https://developer.android.com/auto/index.html", "HU-AAAAAA001"};
            for (int i = 0; i < s.length; i++) {
                byte[] b = (s[i] + "\0").getBytes();   // AOA wants NUL-terminated strings (OpenDroidAuto does this too)
                int r = c.controlTransfer(UsbConstants.USB_TYPE_VENDOR, 52, 0, i, b, b.length, TIMEOUT_MS);
                if (r < 0) { Log15.i(TAG, "SEND_STRING " + i + " failed " + r); return false; }
            }
            int r = c.controlTransfer(UsbConstants.USB_TYPE_VENDOR, 53, 0, 0, new byte[0], 0, TIMEOUT_MS);
            Log15.i(TAG, "AOA START -> " + r + " (negative is normal if the phone re-enumerated mid-transfer)");
            return true;
        } finally { c.close(); }
    }

    public static UsbDevice findAccessory(UsbManager m) {
        for (Map.Entry<String, UsbDevice> e : m.getDeviceList().entrySet()) if (isAccessory(e.getValue())) return e.getValue();
        return null;
    }

    public UsbTransport(UsbManager m, UsbDevice d) throws IOException {
        conn = m.openDevice(d);
        if (conn == null) throw new IOException("openDevice returned null (no permission?)");
        UsbInterface fi = null; UsbEndpoint ei = null, eo = null;
        for (int i = 0; i < d.getInterfaceCount() && fi == null; i++) {
            UsbInterface f = d.getInterface(i);
            UsbEndpoint a = null, b = null;
            for (int e = 0; e < f.getEndpointCount(); e++) {
                UsbEndpoint ep = f.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) a = ep; else b = ep;
            }
            if (a != null && b != null) { fi = f; ei = a; eo = b; }
        }
        if (fi == null) { conn.close(); throw new IOException("no interface with bulk IN+OUT endpoints"); }
        if (!conn.claimInterface(fi, true)) { conn.close(); throw new IOException("claimInterface failed"); }
        iface = fi; in = ei; out = eo;
        Log15.i(TAG, "opened accessory iface " + fi.getId() + " in=" + String.format("%02x", in.getAddress()) + " out=" + String.format("%02x", out.getAddress()));
    }

    @Override public void write(byte[] b, int off, int len) throws IOException {
        byte[] tmp = new byte[Math.min(len, CHUNK)];
        while (len > 0) {
            int n = Math.min(len, CHUNK);
            System.arraycopy(b, off, tmp, 0, n);
            int r = conn.bulkTransfer(out, tmp, n, 5000);
            if (r != n) throw new IOException("bulk write " + r + " of " + n);
            off += n; len -= n;
        }
    }

    @Override public void readFully(byte[] b, int off, int len) throws IOException {
        long deadline = System.currentTimeMillis() + readDeadlineMs;
        while (len > 0) {
            if (rpos == rlen) {
                if (closed) throw new IOException("closed");
                int n = conn.bulkTransfer(in, rbuf, rbuf.length, TIMEOUT_MS);
                if (n > 0) { rpos = 0; rlen = n; deadline = System.currentTimeMillis() + readDeadlineMs; }
                else if (System.currentTimeMillis() > deadline) throw new IOException("bulk read timeout");
                else if (n < 0) continue; // timeout slice, keep waiting until the overall deadline
                continue;
            }
            int n = Math.min(len, rlen - rpos);
            System.arraycopy(rbuf, rpos, b, off, n);
            rpos += n; off += n; len -= n;
        }
    }

    public void setReadDeadlineMs(int ms) { readDeadlineMs = ms; }

    @Override public void close() {
        closed = true;
        try { conn.releaseInterface(iface); } catch (Throwable ignored) { }
        conn.close();
    }
}
