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

/** Main screen: Pair / Advanced, and the connection logic behind them. */
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
        if (getIntent().hasExtra("usb") || (auto && paired() && tcpHost == null && Pipe.current == null)) connectUsb(); // am start ... --es usb 1
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

    /** The unit has no file manager: copy hu15.log onto any writable removable mount (first one that takes it). */
    private void exportLog() {
        if (Log15.file == null) { log("no log file"); return; }
        java.util.ArrayList<java.io.File> cand = new java.util.ArrayList<java.io.File>();
        for (String root : new String[] {"/storage", "/mnt"}) {
            java.io.File[] a = new java.io.File(root).listFiles();
            if (a == null) continue;
            for (java.io.File d : a) {
                String n = d.getName().toLowerCase(java.util.Locale.US);
                if (n.equals("sdcard") || n.equals("emulated") || n.equals("obb") || n.equals("asec") || n.equals("secure") || n.equals("shell") || n.equals("sdcard0")) continue;
                cand.add(d);
                java.io.File[] c = d.listFiles(); // usb mounts are sometimes one level down
                if (c != null) for (java.io.File e : c) if (e.isDirectory()) cand.add(e);
            }
        }
        String name = "hu15-" + new java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US).format(new java.util.Date()) + ".log";
        for (java.io.File d : cand) {
            if (!d.isDirectory() || !d.canWrite()) continue;
            java.io.File f = new java.io.File(d, name);
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(Log15.file);
                java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                o.getFD().sync(); o.close(); in.close();
                log("log copied to " + f + " (" + f.length() + " B)");
                return;
            } catch (java.io.IOException ex) { log("failed " + f + ": " + ex.getMessage()); }
        }
        StringBuilder sb = new StringBuilder("no writable USB mount found. Looked at:");
        for (java.io.File d : cand) sb.append(' ').append(d).append(d.canWrite() ? "(w)" : "(ro)");
        log(sb.toString());
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
        if ((UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction()) || i.hasExtra("reconnect")) && Prefs.get(this, Prefs.AUTO_USB) && paired() && Pipe.current == null) connectUsb();
        refreshMenu();
    }

    private static volatile boolean connecting;
    private static final int GOOGLE_VID = 0x18d1;
    private volatile int pendingPairVid = -1;
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
                pendingPairVid = -1;
                setStatus("Looking for your phone…");
                if (acc != null) { // left over from an earlier session: the phone may still be talking to a dead one, so reset the port first
                    String old = acc.getDeviceName();
                    if (!um.hasPermission(acc)) requestPermission(um, acc);
                    int r = UsbTransport.reset(um, acc);
                    log("accessory device already present (" + old + "), USB reset -> " + r);
                    if (r < 0) UsbTransport.switchToAccessory(um, acc); // no native reset: ask for the accessory switch again
                    acc = null;
                    Thread.sleep(1500);
                    for (int i = 0; i < 20 && acc == null; i++) { // wait for a NEW device name (or the phone back in normal mode)
                        UsbDevice n = UsbTransport.findAccessory(um);
                        if (n != null && !n.getDeviceName().equals(old)) acc = n;
                        else if (UsbTransport.findPhone(um) != null) break;
                        else Thread.sleep(500);
                    }
                    if (acc == null && UsbTransport.findPhone(um) == null) acc = UsbTransport.findAccessory(um); // nothing changed: try the old one
                }
                if (acc == null) {
                    boolean switched = false;
                    for (UsbDevice cand : um.getDeviceList().values()) {
                        if (!UsbTransport.isPhoneCandidate(cand)) continue; // not hubs, not the USB stick
                        if (!um.hasPermission(cand)) {
                            log("requesting USB permission for " + cand.getDeviceName() + " (tap OK on the dialog)");
                            requestPermission(um, cand);
                            if (!um.hasPermission(cand)) { log("permission denied/timeout for " + cand.getDeviceName()); continue; }
                        }
                        log("switching " + cand.getDeviceName() + " to accessory mode");
                        if (UsbTransport.switchToAccessory(um, cand)) { switched = true; pendingPairVid = cand.getVendorId(); break; }
                        log("not an AOA device, trying the next");
                    }
                    if (!switched) { log("RESULT 2b: FAIL no phone found that speaks AOA (plugged in? USB debugging prompt?)"); setStatus("No phone found. Plug it in with a data cable and unlock it."); retryLater(); return; }
                    for (int i = 0; i < 20 && acc == null; i++) { Thread.sleep(500); acc = UsbTransport.findAccessory(um); }
                    if (acc == null) { log("RESULT 2b: FAIL phone did not re-appear as accessory (18d1:2d0x)"); retryLater(); return; }
                }
                if (pendingPairVid < 0) pendingPairVid = Prefs.pairedVid(MainActivity.this) >= 0 ? Prefs.pairedVid(MainActivity.this) : GOOGLE_VID; // already in accessory mode: keep the known phone, else Google's id
                log("accessory device: " + UsbTransport.describe(acc));
                if (!um.hasPermission(acc)) { requestPermission(um, acc); }
                if (!um.hasPermission(acc)) { log("RESULT 2b: FAIL no permission for accessory device"); return; }
                Transport t = new UsbTransport(um, acc);
                runSession(t);
                t.close();
            } catch (Throwable e) {
                Log15.e("m2b", "usb link failed", e);
                log("RESULT 2b: FAIL " + e);
                retryLater();
            } finally {
                connecting = false;
                runOnUiThread(new Runnable() { @Override public void run() { refreshMenu(); } });
            }
        } }).start();
    }

    /** After a failed attempt that follows a link loss, try again a few times (the phone often re-appears a moment later). */
    private void retryLater() {
        if (reconnectTries > 0) runOnUiThread(new Runnable() { @Override public void run() { scheduleReconnect(); } });
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
        if (pendingPairVid >= 0 && t instanceof UsbTransport) { Prefs.setPaired(this, pendingPairVid); setStatus("Connected"); }
        if (t instanceof TcpTransport) ((TcpTransport) t).setReadTimeoutMs(0);          // phone pings regularly in real use; tests may idle
        if (t instanceof UsbTransport) ((UsbTransport) t).setReadDeadlineMs(60000);
        Pipe pipe = new Pipe(Prefs.get(this, Prefs.THREADS2) ? 2 : 1, Prefs.astream(this));
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
        final android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        final android.media.AudioManager.OnAudioFocusChangeListener focus = new android.media.AudioManager.OnAudioFocusChangeListener() {
            @Override public void onAudioFocusChange(int c) { Log15.i("audio", "focus change " + c); }
        };
        try {
            if (wl != null) wl.acquire();
            cl.acquire();
            am.requestAudioFocus(focus, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN); // tell the unit's audio manager we own the music stream, and hand it back below
            if (Prefs.astream(this) == 2) HondaAudio.request(this);
            session.run();
        } finally {
            am.abandonAudioFocus(focus);
            HondaAudio.release(this);
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

    // ---- UI: a two-button menu (Pair / Advanced) and the Advanced screen with all the debug tools ----

    private android.widget.FrameLayout frame;
    private View menuView, advView;
    private LinearLayout primaryBtn;
    private TextView status;

    private void setStatus(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { if (status != null) status.setText(s); } });
    }

    private boolean paired() { return Prefs.pairedVid(this) >= 0; }

    /** Pair vs. launch: "paired" = a phone with this vendor id completed a session here before (USB gives no serial on API 15). */
    private void refreshMenu() {
        if (primaryBtn == null) return;
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        UsbDevice acc = UsbTransport.findAccessory(um), ph = UsbTransport.findPhone(um);
        boolean connected = acc != null || ph != null;
        boolean known = connected && paired() && (acc != null || ph.getVendorId() == Prefs.pairedVid(this));
        if (Pipe.current != null) { Ui.setBigButton(primaryBtn, "Android Auto", "Running. Tap to return to the screen"); }
        else if (known) { Ui.setBigButton(primaryBtn, "Start Android Auto", "Your phone is connected"); status.setText("Ready"); }
        else if (connected) { Ui.setBigButton(primaryBtn, "Pair phone", "Phone detected. Tap to set it up"); status.setText("Unlock the phone and accept the Android Auto prompt"); }
        else { Ui.setBigButton(primaryBtn, "Pair phone", "Plug in your phone with a USB cable"); status.setText(paired() ? "Waiting for your phone" : "Not paired yet"); }
    }

    private void show(boolean advanced) {
        menuView.setVisibility(advanced ? View.GONE : View.VISIBLE);
        advView.setVisibility(advanced ? View.VISIBLE : View.GONE);
    }

    @Override protected void onResume() { super.onResume(); refreshMenu(); }

    @Override public void onBackPressed() {
        if (advView != null && advView.getVisibility() == View.VISIBLE) show(false); else super.onBackPressed();
    }

    private LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, w); }

    private void buildUi() {
        frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(Ui.BG);

        // --- menu: logo on the left, the two buttons on the right ---
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.HORIZONTAL);
        menu.setPadding(Ui.dp(this, 32), Ui.dp(this, 24), Ui.dp(this, 32), Ui.dp(this, 24));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(android.view.Gravity.CENTER);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.aa_logo);
        brand.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 153)));
        TextView name = Ui.text(this, "HU15", 30, Ui.TEXT, true);
        name.setPadding(0, Ui.dp(this, 14), 0, 0);
        name.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        brand.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView ver = Ui.text(this, versionName(), 13, Ui.TEXT2, false);
        ver.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        brand.addView(ver, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        menu.addView(brand, weight(1f));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        primaryBtn = Ui.bigButton(this, "Pair phone", "", true);
        primaryBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (Pipe.current != null) startActivity(new Intent(MainActivity.this, ProjectionActivity.class)); else connectUsb();
        } });
        actions.addView(primaryBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 120)));
        LinearLayout adv = Ui.bigButton(this, "Advanced", "Connection tools, settings and logs", false);
        adv.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { show(true); } });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 84));
        alp.topMargin = Ui.dp(this, 14);
        actions.addView(adv, alp);
        status = Ui.text(this, "", 14, Ui.TEXT2, false);
        status.setPadding(Ui.dp(this, 6), Ui.dp(this, 16), 0, 0);
        actions.addView(status);
        menu.addView(actions, weight(1.15f));
        menuView = menu;

        // --- advanced: header with a back chevron, tools on the left, log card on the right ---
        LinearLayout advRoot = new LinearLayout(this);
        advRoot.setOrientation(LinearLayout.VERTICAL);
        advRoot.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView back = Ui.text(this, "‹  Advanced", 22, Ui.TEXT, true);
        back.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 22), Ui.dp(this, 8));
        back.setClickable(true);
        back.setFocusable(true);
        back.setBackgroundDrawable(Ui.pressable(this, Ui.BG, Ui.CARD_HI, 12, 0));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { show(false); } });
        head.addView(back);
        advRoot.addView(head, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 6), Ui.dp(this, 14), 0);
        list.addView(tool("Connect USB phone", "Switch the phone to accessory mode and start", new Runnable() { public void run() { connectUsb(); } }));
        list.addView(tool("Run self-test", "Check that TLS 1.2 is available on this unit", new Runnable() { public void run() { selfTest(); } }));
        final android.widget.EditText host = new android.widget.EditText(this);
        host.setSingleLine(true);
        host.setTextColor(Ui.TEXT);
        host.setHintTextColor(Ui.TEXT2);
        host.setBackgroundDrawable(Ui.shape(this, Ui.CARD, 12, Ui.OUTLINE));
        host.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        host.setText(getSharedPreferences("hu15", MODE_PRIVATE).getString("tcp_host", "10.0.2.2")); // 10.0.2.2 = the emulator's host
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = Ui.dp(this, 8);
        list.addView(host, hlp);
        list.addView(tool("Connect TCP :5277", "Fake phone or a head-unit server on that host", new Runnable() { public void run() {
            String h = host.getText().toString().trim();
            getSharedPreferences("hu15", MODE_PRIVATE).edit().putString("tcp_host", h).commit();
            connectTcp(h, 5277);
        } }));
        list.addView(tool("Phone hotspot", "Connect to the Wi-Fi gateway (the phone)", new Runnable() { public void run() { connectHotspot(); } }));
        list.addView(tool("Settings", "Mic, Bluetooth, night mode, navigation bar", new Runnable() { public void run() { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); } }));
        list.addView(tool("Copy log to USB stick", "Writes hu15.log to the first writable stick", new Runnable() { public void run() { exportLog(); } }));
        list.addView(tool("Forget paired phone", "The next connection will ask to pair again", new Runnable() { public void run() { Prefs.setPaired(MainActivity.this, -1); refreshMenu(); log("forgot paired phone"); } }));
        ScrollView lsv = new ScrollView(this);
        lsv.addView(list);
        body.addView(lsv, weight(1f));
        out = new TextView(this);
        out.setTextSize(11);
        out.setTextColor(Ui.TEXT2);
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        out.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundDrawable(Ui.shape(this, Ui.CARD, 14, Ui.OUTLINE));
        sv.addView(out);
        body.addView(sv, weight(1f));
        advRoot.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        advView = advRoot;
        advView.setVisibility(View.GONE);

        frame.addView(menuView);
        frame.addView(advView);
        setContentView(frame);
    }

    private View tool(String title, String sub, final Runnable r) {
        LinearLayout row = Ui.row(this, title, sub);
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { r.run(); } });
        return row;
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
