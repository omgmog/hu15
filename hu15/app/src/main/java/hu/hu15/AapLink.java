// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Android Auto wire protocol, head-unit side, below the protobuf layer: framing, version exchange, TLS 1.2 client handshake
 * tunnelled in type-3 frames, then encrypted frames.
 * Plain frame:     [chan][flags][len=payload+2 (2B BE)][type (2B BE)][payload]
 * Encrypted frame: [chan][flags][len=ciphertext (2B BE)][TLS record(s) of: type (2B BE) + payload]
 */
public final class AapLink {
    private static final String TAG = "aap";
    private final Transport t;
    private final SSLEngine engine;
    private final ByteBuffer app, net;
    private final Object wrapLock = new Object(), writeLock = new Object(); // SSLEngine wrap/unwrap are not safe concurrently

    public AapLink(Context c, Transport t) throws Exception {
        this.t = t;
        SSLContext ctx = SSLContext.getInstance("TLS", org.conscrypt.Conscrypt.newProvider());
        ctx.init(new KeyManager[]{keyManager(c)}, new TrustManager[]{new TrustAll()}, new SecureRandom());
        engine = ctx.createSSLEngine("android-auto", 5277);
        engine.setUseClientMode(true);
        app = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize() + 50);
        net = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
    }

    /** Version request/response, then TLS handshake, then AuthComplete from the phone. */
    public void connect() throws Exception {
        sendPlain(0, 3, 1, new byte[]{0, 1, 0, 2});
        Frame v = readPlain();
        Log15.i(TAG, "version response type=" + v.type + " payload=" + hex(v.payload));
        if (v.type != 2 || v.payload.length < 6) throw new IOException("bad version response");
        Log15.i(TAG, "phone speaks AAP " + (((v.payload[0] & 255) << 8) | (v.payload[1] & 255)) + "." + (((v.payload[2] & 255) << 8) | (v.payload[3] & 255))
                + " status=" + (((v.payload[4] & 255) << 8) | (v.payload[5] & 255)));
        handshake();
        // The CAR sends AuthComplete (status 0) once TLS is up; the phone waits for it.
        sendPlain(0, 3, 4, new byte[]{8, 0});
        Log15.i(TAG, "sent AuthComplete");
    }

    private void handshake() throws Exception {
        engine.beginHandshake();
        byte[] pending = new byte[0];
        int wraps = 0, unwraps = 0;
        while (true) {
            HandshakeStatus hs = engine.getHandshakeStatus();
            if (hs == HandshakeStatus.FINISHED || hs == HandshakeStatus.NOT_HANDSHAKING) break;
            if (hs == HandshakeStatus.NEED_TASK) {
                Runnable r;
                while ((r = engine.getDelegatedTask()) != null) r.run();
            } else if (hs == HandshakeStatus.NEED_WRAP) {
                net.clear();
                engine.wrap(ByteBuffer.allocate(0), net);
                net.flip();
                byte[] out = new byte[net.remaining()];
                net.get(out);
                if (out.length > 0) sendPlain(0, 3, 3, out); // Conscrypt reports NEED_WRAP once with nothing to send
                wraps++;
            } else { // NEED_UNWRAP
                if (pending.length == 0) pending = readHandshakeFrame();
                ByteBuffer in = ByteBuffer.wrap(pending);
                app.clear();
                SSLEngineResult r = engine.unwrap(in, app);
                unwraps++;
                if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    pending = concat(pending, readHandshakeFrame()); // partial TLS record, need the next frame
                } else if (r.getStatus() == SSLEngineResult.Status.OK) {
                    pending = new byte[in.remaining()];
                    in.get(pending);
                } else throw new IOException("handshake unwrap " + r.getStatus());
            }
        }
        Log15.i(TAG, "TLS handshake done (" + wraps + " wraps, " + unwraps + " unwraps) protocol=" + engine.getSession().getProtocol()
                + " cipher=" + engine.getSession().getCipherSuite());
    }

    private byte[] readHandshakeFrame() throws IOException {
        Frame f = readPlain();
        if (f.type != 3) throw new IOException("expected SSL handshake frame, got type " + f.type);
        return f.payload;
    }

    /**
     * Sends [type][payload] encrypted on channel chan. flags 0x0b = first|last|encrypted; 0x0f adds the "control" bit,
     * which HeadUnit Revived sets for channel-level control types (1..26) on non-zero channels (e.g. ChannelOpenResponse).
     */
    public void sendEncrypted(int chan, int type, byte[] payload) throws Exception {
        int flags = (chan != 0 && type >= 1 && type <= 26) ? 0x0f : 0x0b;
        ByteBuffer pt = ByteBuffer.allocate(2 + payload.length);
        pt.putShort((short) type).put(payload).flip();
        // wrap and write under ONE lock: TLS records carry a sequence number, so two threads (session acks, UI touches, mic)
        // must not wrap A then B but write B then A, or the peer sees a bad MAC.
        synchronized (writeLock) {
            byte[] ct;
            synchronized (wrapLock) {
                net.clear();
                engine.wrap(pt, net);
                net.flip();
                ct = new byte[net.remaining()];
                net.get(ct);
            }
            byte[] f = new byte[4 + ct.length];
            f[0] = (byte) chan; f[1] = (byte) flags; f[2] = (byte) (ct.length >> 8); f[3] = (byte) ct.length;
            System.arraycopy(ct, 0, f, 4, ct.length);
            t.write(f, 0, f.length);
        }
    }

    /**
     * Reads one encrypted frame (one fragment). type is -1 unless this is a first fragment (flags bit 0), because only the
     * first fragment's plaintext starts with the 2-byte type. A first-but-not-last fragment (flags 0x09) carries a 4-byte total length
     * after the header, read here and returned in totalLen.
     */
    public Frame readEncrypted() throws Exception {
        byte[] h = new byte[4];
        t.readFully(h, 0, 4);
        int flags = h[1] & 255;
        int len = ((h[2] & 255) << 8) | (h[3] & 255);
        int total = 0;
        if (flags == 0x09) {
            byte[] tl = new byte[4];
            t.readFully(tl, 0, 4);
            total = ((tl[0] & 255) << 24) | ((tl[1] & 255) << 16) | ((tl[2] & 255) << 8) | (tl[3] & 255);
        }
        byte[] ct = new byte[len];
        t.readFully(ct, 0, len);
        Frame f = new Frame();
        f.chan = h[0] & 255; f.flags = flags; f.totalLen = total;
        synchronized (wrapLock) {
            app.clear();
            SSLEngineResult r = engine.unwrap(ByteBuffer.wrap(ct), app);
            if (r.getStatus() != SSLEngineResult.Status.OK) throw new IOException("unwrap " + r.getStatus());
            app.flip();
            f.type = -1;
            if ((flags & 1) != 0) f.type = app.getShort() & 0xffff;
            f.payload = new byte[app.remaining()];
            app.get(f.payload);
        }
        return f;
    }

    public void close() { t.close(); }

    public static final class Frame { public int chan, flags, type, totalLen; public byte[] payload; }

    private void sendPlain(int chan, int flags, int type, byte[] payload) throws IOException {
        byte[] f = new byte[6 + payload.length];
        f[0] = (byte) chan; f[1] = (byte) flags;
        int l = payload.length + 2;
        f[2] = (byte) (l >> 8); f[3] = (byte) l; f[4] = (byte) (type >> 8); f[5] = (byte) type;
        System.arraycopy(payload, 0, f, 6, payload.length);
        t.write(f, 0, f.length);
    }

    private Frame readPlain() throws IOException {
        byte[] h = new byte[4];
        t.readFully(h, 0, 4);
        int len = ((h[2] & 255) << 8) | (h[3] & 255);
        if (len < 2) throw new IOException("bad frame length " + len);
        byte[] b = new byte[len];
        t.readFully(b, 0, len);
        Frame f = new Frame();
        f.chan = h[0] & 255; f.flags = h[1] & 255;
        f.type = ((b[0] & 255) << 8) | (b[1] & 255);
        f.payload = new byte[len - 2];
        System.arraycopy(b, 2, f.payload, 0, len - 2);
        return f;
    }

    // --- helpers ---

    private static KeyManager keyManager(Context c) throws Exception {
        InputStream ci = c.getResources().openRawResource(R.raw.cert);
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(ci);
        ci.close();
        String pem = new String(readAll(c.getResources().openRawResource(R.raw.privkey)), "US-ASCII")
                .replace("\n", "").replace("\r", "").replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "");
        java.security.PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(pem, Base64.DEFAULT)));
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null);
        ks.setKeyEntry("hu", key, new char[0], new java.security.cert.Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        return kmf.getKeyManagers()[0];
    }

    private static final class TrustAll implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
