// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.security.Provider;
import java.security.Security;
import java.util.Arrays;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

/** Debug screen: self-test, connect over TCP or USB, settings. */
public class MainActivity extends Activity {
    private TextView out;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log15.init(this);
        MicCapture.synthetic = getIntent().getBooleanExtra("fakemic", false);
        for (String k : new String[]{Prefs.MIC, Prefs.BT, Prefs.RHD, Prefs.THREADS2, Prefs.AUTO_USB}) // test hook: am start ... --ez mic true
            if (getIntent().hasExtra(k)) Prefs.set(this, k, getIntent().getBooleanExtra(k, false));
        buildUi();
        selfTest();
        String tcpHost = getIntent().getStringExtra("tcp"); // am start -n hu.hu15/.MainActivity --es tcp 10.0.2.2
        if (tcpHost != null) connectTcp(tcpHost, 5277);
    }

    private void say(String s) { Log15.i("selftest", s); out.append(s + "\n"); }

    private void selfTest() {
        out.setText("");
        say("HU15 " + versionName() + "  SDK_INT=" + Build.VERSION.SDK_INT + "  abi=" + Build.CPU_ABI + "/" + Build.CPU_ABI2);
        say("log file: " + Log15.file);
        say("platform TLS: " + platformProtocols());
        try {
            Provider p = org.conscrypt.Conscrypt.newProvider();
            say("Conscrypt provider loaded: " + p.getName() + " " + p.getVersion());
            Security.insertProviderAt(p, 1);
            SSLContext ctx = SSLContext.getInstance("TLSv1.2", p);
            ctx.init(null, null, null);
            SSLEngine e = ctx.createSSLEngine();
            say("Conscrypt supported protocols: " + Arrays.toString(e.getSupportedProtocols()));
            say("RESULT: PASS (TLS 1.2 available via Conscrypt)");
        } catch (Throwable t) {
            Log15.e("selftest", "Conscrypt failed", t);
            say("RESULT: FAIL " + t);
        }
    }

    private void connectTcp(final String host, final int port) {
        new Thread(new Runnable() { @Override public void run() {
            try {
                log("connecting " + host + ":" + port);
                Transport t = new TcpTransport(host, port);
                runSession(t);
                t.close();
            } catch (Throwable e) {
                Log15.e("m2", "link failed", e);
                log("RESULT 2a: FAIL " + e);
            }
        } }).start();
    }

    /** Handshake, then the full session on this thread, logging what the phone sends (no video or audio output yet). */
    private void runSession(Transport t) throws Exception {
        AapLink link = new AapLink(this, t);
        link.connect();
        log("TLS up; session running");
        if (t instanceof TcpTransport) ((TcpTransport) t).setReadTimeoutMs(0);          // phone pings regularly in real use; tests may idle
        Session.Config cfg = new Session.Config();
        cfg.mic = Prefs.get(this, Prefs.MIC);
        cfg.rhd = Prefs.get(this, Prefs.RHD);
        cfg.night = Prefs.isNight(this);
        if (Prefs.get(this, Prefs.BT)) {
            android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            cfg.btAddress = bt == null ? null : bt.getAddress();
            if (cfg.btAddress == null) cfg.btAddress = getIntent().getStringExtra("btaddr"); // test hook for devices without a BT adapter
            log("bluetooth address: " + cfg.btAddress);
        }
        final int[] n = new int[2];
        new Session(link, new Session.Sink() {
            @Override public void videoConfig(byte[] d, int off, int len) { log("video config, " + len + " B"); }
            @Override public void videoFrame(byte[] d, int off, int len) { if (n[0]++ % 100 == 0) log("video frame " + n[0] + ", " + len + " B"); }
            @Override public void audioStart(int chan, int rate, int bits, int channels) { log("audio start ch" + chan + " " + rate + " Hz x" + channels); }
            @Override public void audioPcm(int chan, byte[] d, int off, int len) { n[1] += len; }
            @Override public void audioStop(int chan) { log("audio stop ch" + chan + ", " + n[1] + " B"); }
            @Override public void ended(String why) { log("session ended: " + why); }
        }, cfg).run();
    }

    private void log(final String s) {
        Log15.i("m2", s);
        runOnUiThread(new Runnable() { @Override public void run() { out.append(s + "\n"); } });
    }

    // ---- UI: a column of buttons and a log ----

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);
        button(root, "Run self-test", new Runnable() { public void run() { selfTest(); } });
        final android.widget.EditText host = new android.widget.EditText(this);
        host.setSingleLine(true);
        host.setText(getSharedPreferences("hu15", MODE_PRIVATE).getString("tcp_host", "10.0.2.2")); // 10.0.2.2 = the emulator's host
        root.addView(host);
        button(root, "Connect TCP :5277", new Runnable() { public void run() {
            String h = host.getText().toString().trim();
            getSharedPreferences("hu15", MODE_PRIVATE).edit().putString("tcp_host", h).commit();
            connectTcp(h, 5277);
        } });
        out = new TextView(this);
        out.setTextSize(11);
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(out);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void button(LinearLayout root, String label, final Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { r.run(); } });
        root.addView(b);
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    private static String platformProtocols() {
        try {
            return Arrays.toString(SSLContext.getDefault().createSSLEngine().getSupportedProtocols());
        } catch (Throwable t) { return t.toString(); }
    }
}
