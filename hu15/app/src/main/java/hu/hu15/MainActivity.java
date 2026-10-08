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
        boolean auto = Prefs.get(this, Prefs.AUTO_USB);
        // Connect when asked (extra), when a USB device was plugged in, when the unit relaunched us, or simply on start (auto-connect on).
        if (getIntent().hasExtra("usb") || (auto && tcpHost == null && Pipe.current == null)) connectUsb(); // am start ... --es usb 1
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

    /**
     * Phone-hotspot mode, no Bluetooth: join the phone's hotspot in Android's Wi-Fi settings, switch on Android Auto's
     * developer "Start head unit server" on the phone, then this connects to the hotspot gateway (= the phone) on :5277.
     */
    private void connectHotspot() {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
        android.net.DhcpInfo d = wm == null ? null : wm.getDhcpInfo();
        int gw = d == null ? 0 : d.gateway; // little-endian IPv4
        if (gw == 0) { log("not connected to a Wi-Fi network (join the phone's hotspot first)"); return; }
        String ip = (gw & 255) + "." + ((gw >> 8) & 255) + "." + ((gw >> 16) & 255) + "." + ((gw >> 24) & 255);
        log("Wi-Fi gateway " + ip + " (the phone, if this is its hotspot)");
        connectTcp(ip, 5277);
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

    private static final String ACTION_USB_PERMISSION = "hu.hu15.USB_PERMISSION";

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if ((UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction()) || i.hasExtra("reconnect")) && Prefs.get(this, Prefs.AUTO_USB) && Pipe.current == null) connectUsb();
    }

    private static volatile boolean connecting;
    private static int reconnectTries;                               // consecutive link losses; reset after a session that lasted a minute
    private final android.os.Handler handler = new android.os.Handler();

    /** Retry the USB connection a few times after the link broke by itself (not after a deliberate leave). */
    private void scheduleReconnect() {
        if (!Prefs.get(this, Prefs.AUTO_USB) || reconnectTries >= 5) { if (reconnectTries >= 5) log("giving up reconnecting after 5 tries; replug the phone or tap Connect USB"); return; }
        reconnectTries++;
        log("link lost: reconnecting in 3 s (try " + reconnectTries + " of 5)");
        handler.postDelayed(new Runnable() { @Override public void run() { if (Pipe.current == null) connectUsb(); } }, 3000);
    }

    private void connectUsb() {
        if (connecting) { log("already connecting"); return; }
        connecting = true;
        final UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        new Thread(new Runnable() { @Override public void run() {
            try {
                java.util.Map<String, UsbDevice> devs = um.getDeviceList();
                log("USB devices: " + devs.size());
                for (UsbDevice d : devs.values()) log("  " + UsbTransport.describe(d));
                UsbDevice acc = UsbTransport.findAccessory(um);
                if (acc == null) {
                    UsbDevice phone = null;
                    for (UsbDevice d : devs.values()) if (d.getDeviceClass() != 9 && d.getVendorId() != 0x1d6b) { phone = d; break; } // first device that is not a hub
                    if (phone == null) { log("RESULT 2b: FAIL no USB device to switch"); return; }
                    if (!um.hasPermission(phone)) {
                        log("requesting USB permission for " + phone.getDeviceName() + " (tap OK on the dialog)");
                        requestPermission(um, phone);
                        if (!um.hasPermission(phone)) { log("permission denied/timeout"); return; }
                    }
                    log("switching " + phone.getDeviceName() + " to accessory mode");
                    if (!UsbTransport.switchToAccessory(um, phone)) { log("RESULT 2b: FAIL not an AOA device"); return; }
                    for (int i = 0; i < 6 && acc == null; i++) { Thread.sleep(500); acc = UsbTransport.findAccessory(um); }
                    if (acc == null) { log("RESULT 2b: FAIL phone did not re-appear as accessory (18d1:2d0x)"); return; }
                }
                log("accessory device: " + UsbTransport.describe(acc));
                if (!um.hasPermission(acc)) { requestPermission(um, acc); }
                if (!um.hasPermission(acc)) { log("RESULT 2b: FAIL no permission for accessory device"); return; }
                Transport t = new UsbTransport(um, acc);
                runSession(t);
                t.close();
            } catch (Throwable e) {
                Log15.e("m2b", "usb link failed", e);
                log("RESULT 2b: FAIL " + e);
            } finally {
                connecting = false;
            }
        } }).start();
    }

    private void requestPermission(UsbManager um, final UsbDevice d) throws InterruptedException {
        final Object lock = new Object();
        BroadcastReceiver rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { synchronized (lock) { lock.notifyAll(); } }
        };
        registerReceiver(rx, new IntentFilter(ACTION_USB_PERMISSION));
        um.requestPermission(d, PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0));
        synchronized (lock) { lock.wait(30000); }
        unregisterReceiver(rx);
    }

    /** Handshake, then the full session on this thread until the phone leaves or the link breaks. */
    private void runSession(Transport t) throws Exception {
        AapLink link = new AapLink(this, t);
        link.connect();
        log("TLS up; session running");
        if (t instanceof TcpTransport) ((TcpTransport) t).setReadTimeoutMs(0);          // phone pings regularly in real use; tests may idle
        if (t instanceof UsbTransport) ((UsbTransport) t).setReadDeadlineMs(60000);
        Pipe pipe = new Pipe(Prefs.get(this, Prefs.THREADS2) ? 2 : 1);
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
        final long startMs = System.currentTimeMillis();
        final Session session = new Session(link, pipe, cfg);
        pipe.session = session;
        Pipe.current = pipe;
        startActivity(new Intent(this, ProjectionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        // Keep the Wi-Fi radio and the CPU awake for the whole session; tablets otherwise doze the radio and drop the link.
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getSystemService(WIFI_SERVICE);
        android.net.wifi.WifiManager.WifiLock wl = wm == null ? null : wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hu15");
        android.os.PowerManager.WakeLock cl = ((android.os.PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "hu15");
        try {
            if (wl != null) wl.acquire();
            cl.acquire();
            session.run();
        } finally {
            if (wl != null && wl.isHeld()) wl.release();
            if (cl.isHeld()) cl.release();
        }
        log("session ended; " + pipe.stats);
        if (t instanceof UsbTransport) {
            if (System.currentTimeMillis() - startMs > 60000) reconnectTries = 0;   // a healthy session earns a fresh set of tries
            if (session.linkLost) runOnUiThread(new Runnable() { @Override public void run() { scheduleReconnect(); } });
            else reconnectTries = 0;
        }
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
        button(root, "Connect USB phone", new Runnable() { public void run() { connectUsb(); } });
        button(root, "Phone hotspot", new Runnable() { public void run() { connectHotspot(); } });
        button(root, "Settings", new Runnable() { public void run() { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); } });
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
