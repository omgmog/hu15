// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class TcpTransport implements Transport {
    private final Socket s = new Socket();
    private DataInputStream in;
    private OutputStream out;

    public TcpTransport(String host, int port) throws IOException {
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setSoTimeout(10000);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    public void setReadTimeoutMs(int ms) { try { s.setSoTimeout(ms); } catch (IOException ignored) { } }

    @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); out.flush(); }
    @Override public void readFully(byte[] b, int off, int len) throws IOException { in.readFully(b, off, len); }
    @Override public void close() { try { s.close(); } catch (IOException ignored) { } }
}
