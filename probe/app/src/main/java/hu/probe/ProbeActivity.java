// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.probe;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ConfigurationInfo;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Milestone 0: dumps device facts to screen and to probe.txt, logs USB attach/detach live. */
public class ProbeActivity extends Activity {
    private TextView out;
    private String gl = "(GL not queried yet)";
    private File logFile;

    private final BroadcastReceiver usbRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            UsbDevice d = (UsbDevice) i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            append("EVENT " + i.getAction() + " " + describe(d));
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Button rescan = new Button(this);
        rescan.setText("Re-scan + save");
        rescan.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { scan(); }
        });
        root.addView(rescan);
        ScrollView sv = new ScrollView(this);
        out = new TextView(this);
        out.setTextSize(12);
        sv.addView(out);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // 1x1 GL view just to read GL_RENDERER/VERSION/EXTENSIONS
        GLSurfaceView g = new GLSurfaceView(this);
        g.setEGLContextClientVersion(2);
        g.setRenderer(new GLSurfaceView.Renderer() {
            @Override public void onSurfaceCreated(GL10 gl10, EGLConfig c) {
                gl = "GL_RENDERER=" + gl10.glGetString(GL10.GL_RENDERER) + "\nGL_VENDOR=" + gl10.glGetString(GL10.GL_VENDOR)
                        + "\nGL_VERSION=" + gl10.glGetString(GL10.GL_VERSION) + "\nGL_EXT=" + gl10.glGetString(GL10.GL_EXTENSIONS);
            }
            @Override public void onSurfaceChanged(GL10 gl10, int w, int h) { }
            @Override public void onDrawFrame(GL10 gl10) { }
        });
        root.addView(g, new LinearLayout.LayoutParams(1, 1));
        setContentView(root);

        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbRx, f);
        out.postDelayed(new Runnable() { @Override public void run() { scan(); } }, 1500); // let GL init first
        String n = getIntent().getStringExtra("notify"); // am start -n hu.probe/.ProbeActivity --es notify "text"
        if (n != null) {
            android.app.Notification nt = new android.app.Notification.Builder(this).setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("HU Probe").setContentText(n).getNotification();
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, nt);
        }
    }

    @Override protected void onDestroy() { super.onDestroy(); unregisterReceiver(usbRx); }

    private void scan() {
        StringBuilder s = new StringBuilder();
        s.append("== HU PROBE m0 ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        s.append("SDK_INT=").append(Build.VERSION.SDK_INT).append(" release=").append(Build.VERSION.RELEASE).append('\n');
        s.append("MODEL=").append(Build.MODEL).append(" MANUF=").append(Build.MANUFACTURER).append(" BRAND=").append(Build.BRAND)
                .append(" DEVICE=").append(Build.DEVICE).append(" BOARD=").append(Build.BOARD).append(" HARDWARE=").append(Build.HARDWARE).append('\n');
        s.append("FINGERPRINT=").append(Build.FINGERPRINT).append('\n');
        s.append("CPU_ABI=").append(Build.CPU_ABI).append(" CPU_ABI2=").append(Build.CPU_ABI2).append('\n');
        s.append("cores=").append(Runtime.getRuntime().availableProcessors()).append(" maxHeapMB=")
                .append(Runtime.getRuntime().maxMemory() >> 20).append('\n');
        DisplayMetrics dm = getResources().getDisplayMetrics();
        s.append("display=").append(dm.widthPixels).append('x').append(dm.heightPixels).append(" density=").append(dm.density)
                .append(" dpi=").append(dm.densityDpi).append('\n');
        PackageManager pm = getPackageManager();
        String[] feats = {"android.hardware.usb.host", "android.hardware.usb.accessory", "android.hardware.wifi", "android.hardware.wifi.direct",
                "android.hardware.bluetooth", "android.hardware.microphone", "android.hardware.touchscreen", "android.hardware.touchscreen.multitouch",
                "android.hardware.touchscreen.multitouch.distinct", "android.hardware.location.gps"};
        for (String ft : feats) s.append("feature ").append(ft).append('=').append(pm.hasSystemFeature(ft)).append('\n');
        ConfigurationInfo ci = ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).getDeviceConfigurationInfo();
        s.append("reqGlEsVersion=0x").append(Integer.toHexString(ci.reqGlEsVersion)).append('\n');
        s.append(gl).append('\n');
        s.append("-- installer present: ").append(!pm.queryIntentActivities(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(android.net.Uri.parse("file:///x.apk"), "application/vnd.android.package-archive"), 0).isEmpty()).append('\n');
        try {
            Object ns = android.provider.Settings.Secure.getInt(getContentResolver(), android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS);
            s.append("unknown_sources=").append(ns).append('\n');
        } catch (Exception e) { s.append("unknown_sources=?").append(e).append('\n'); }
        s.append("-- USB host device list\n");
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        try {
            Map<String, UsbDevice> m = um.getDeviceList();
            s.append("count=").append(m.size()).append('\n');
            for (UsbDevice d : m.values()) s.append(describe(d)).append('\n');
        } catch (Throwable t) { s.append("getDeviceList failed: ").append(t).append('\n'); }
        s.append("-- /proc/cpuinfo\n").append(read("/proc/cpuinfo", 60));
        s.append("-- /proc/meminfo (head)\n").append(read("/proc/meminfo", 4));
        s.append("-- /proc/version\n").append(read("/proc/version", 2));
        s.append("-- /sys/bus/usb/devices\n");
        File[] ud = new File("/sys/bus/usb/devices").listFiles();
        if (ud == null) s.append("(not listable)\n"); else for (File f : ud) s.append(f.getName()).append('\n');
        s.append("-- /dev/bus/usb\n");
        File[] du = new File("/dev/bus/usb").listFiles();
        if (du == null) s.append("(not listable)\n"); else for (File f : du) s.append(f.getName()).append('\n');
        out.setText(s);
        save(s.toString(), false);
    }

    private static String describe(UsbDevice d) {
        if (d == null) return "null";
        return String.format(Locale.US, "name=%s vid=%04x pid=%04x class=%d sub=%d ifaces=%d", d.getDeviceName(),
                d.getVendorId(), d.getProductId(), d.getDeviceClass(), d.getDeviceSubclass(), d.getInterfaceCount());
    }

    private static String read(String path, int maxLines) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new FileReader(path));
            String l; int n = 0;
            while ((l = r.readLine()) != null && n++ < maxLines) sb.append(l).append('\n');
            r.close();
        } catch (Exception e) { sb.append("(").append(e).append(")\n"); }
        return sb.toString();
    }

    private void append(String line) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + line + "\n";
        out.append(t);
        save(t, true);
    }

    /** Tries /sdcard/hu-probe/probe.txt, then app-private files dir. Shows path on screen. */
    private void save(String text, boolean app) {
        File[] dirs = {new File(Environment.getExternalStorageDirectory(), "hu-probe"), getFilesDir()};
        for (File d : dirs) {
            try {
                d.mkdirs();
                File f = new File(d, "probe.txt");
                FileWriter w = new FileWriter(f, app);
                w.write(text);
                w.close();
                logFile = f;
                if (!app) out.append("\nSAVED TO " + f.getAbsolutePath() + "\n");
                return;
            } catch (Exception ignored) { }
        }
        if (!app) out.append("\nCOULD NOT SAVE - copy text from screen\n");
    }
}
