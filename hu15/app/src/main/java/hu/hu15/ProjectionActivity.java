// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Full-screen projection surface: shows decoded frames, forwards touches to the phone in 800x480 coordinates. */
public class ProjectionActivity extends Activity {
    private YuvView view;
    private final Handler handler = new Handler();
    private Runnable backHold, cornerHold;
    private boolean backFired;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Pipe.current == null) { // restarted by the unit ("last mode") with no live session: reconnect from the main screen
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("reconnect", true));
            finish();
            return;
        }
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new YuvView(this);
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return touch(v, e); }
        });
        // Hide the on-screen nav bar (API 14+). Android shows it again on any touch, so re-hide shortly after it reappears.
        final Handler h = handler;
        final Runnable hide = new Runnable() { @Override public void run() { hideBars(); } };
        view.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            @Override public void onSystemUiVisibilityChange(int v) {
                if ((v & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) { h.removeCallbacks(hide); h.postDelayed(hide, 4000); }
            }
        });
        FrameLayout f = new FrameLayout(this);
        f.addView(view);
        setContentView(f);
    }

    private boolean touch(View v, MotionEvent e) {
        Pipe p = Pipe.current;
        if (e.getActionMasked() != MotionEvent.ACTION_MOVE) Log15.i("touch", "action=" + e.getActionMasked() + " " + e.getX() + "," + e.getY() + " session=" + (p != null && p.session != null));
        Session s = p == null ? null : p.session;
        if (s == null || v.getWidth() == 0) return true;
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_CANCEL && cornerHold != null) handler.removeCallbacks(cornerHold);
        int action = a == MotionEvent.ACTION_DOWN ? 0 : a == MotionEvent.ACTION_UP ? 1 : a == MotionEvent.ACTION_MOVE ? 2
                : a == MotionEvent.ACTION_POINTER_DOWN ? 5 : a == MotionEvent.ACTION_POINTER_UP ? 6 : -1;
        if (action < 0) return true;
        int n = e.getPointerCount(), idx = a == MotionEvent.ACTION_MOVE ? 0 : e.getActionIndex();
        int[] r = YuvView.videoRect(v.getWidth(), v.getHeight());
        int[] xs = new int[n], ys = new int[n], ids = new int[n];
        for (int k = 0; k < n; k++) {
            float fx = (e.getX(k) - r[0]) * Session.WIDTH / r[2], fy = (e.getY(k) - r[1]) * Session.HEIGHT / r[3];
            if (k == idx && action == 0 && (fx < 0 || fy < 0 || fx >= Session.WIDTH || fy >= Session.HEIGHT)) return true; // press in the black bars: ignore
            xs[k] = Math.max(0, Math.min(Session.WIDTH - 1, (int) fx));
            ys[k] = Math.max(0, Math.min(Session.HEIGHT - 1, (int) fy));
            ids[k] = e.getPointerId(k);
        }
        // Hold the top-right corner 1.5 s = leave projection (for units with no BACK/MENU key)
        if (action == 0 && xs[idx] > Session.WIDTH - 80 && ys[idx] < 70) {
            final Session ss = s;
            handler.postDelayed(cornerHold = new Runnable() { @Override public void run() { ss.byeBye(); } }, 1500);
        } else if (cornerHold != null && (action == 1 || action == 3 || action == 6)) {
            handler.removeCallbacks(cornerHold);
        }
        s.sendTouch(action, idx, xs, ys, ids);
        return true;
    }

    private void hideBars() {
        if (!Prefs.get(this, Prefs.HIDE_NAV)) return;
        // LAYOUT_* flags (API 16+) keep the window full-size when the bar reappears, so the picture never rescales; ignored on API 15.
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LOW_PROFILE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    private WheelKeys wheel;

    private boolean key(int code, boolean down) {
        Log15.i("key", (down ? "down " : "up ") + code + " " + KeyEvent.keyCodeToString(code));
        Pipe p = Pipe.current;
        Session s = p == null ? null : p.session;
        if (s == null) return false;
        if (code == KeyEvent.KEYCODE_MENU) { if (down) s.byeBye(); return true; } // MENU = leave projection
        if (code == KeyEvent.KEYCODE_BACK) { // tap = Back on the phone, hold 1 s = leave projection
            final Session ss = s;
            if (down) {
                backFired = false;
                handler.postDelayed(backHold = new Runnable() { @Override public void run() { backFired = true; ss.byeBye(); } }, 1000);
            } else {
                handler.removeCallbacks(backHold);
                if (!backFired) { s.sendKey(KeyEvent.KEYCODE_BACK, true); s.sendKey(KeyEvent.KEYCODE_BACK, false); }
            }
            return true;
        }
        // Honda wheel keys arrive as odd negative codes (same ones OpenDroidAuto maps): left/right = previous/next, source = play/pause (+/- are volume, see onKeyDown)
        if (code == -65532) code = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        else if (code == -65533) code = KeyEvent.KEYCODE_MEDIA_NEXT;
        else if (code == -65531) code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        else if (code == -65522) code = KeyEvent.KEYCODE_SEARCH; // voice button (seen once on the drive of 2026-10-10); Android Auto starts the assistant on SEARCH. -65526 is still unidentified
        switch (code) {
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                s.sendKey(code, down);
                return true;
            default: return false;
        }
    }

    private static boolean wheelVolume(int code) { return code == -65535 || code == -65534; } // wheel + / -
    private int volLogged;

    /** Our steering-menu registration takes the wheel's volume keys too, so hand them back to the unit's own volume (ModeMgrService, what OpenDroidAuto calls), else Android's. */
    private void volume(boolean up) {
        if (volLogged++ < 3) Log15.i("wheel", "volume " + (up ? "up" : "down") + " via " + (getSystemService("ModeMgrService") != null ? "ModeMgrService" : "AudioManager"));
        try {
            Object m = getSystemService("ModeMgrService");
            if (m != null) { m.getClass().getMethod("reqModeMgrSteeringVolCmd", boolean.class).invoke(m, up); return; }
        } catch (Throwable t) { if (volLogged < 6) Log15.e("wheel", "ModeMgrService volume", t); }
        ((android.media.AudioManager) getSystemService(AUDIO_SERVICE)).adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                up ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (wheelVolume(code)) { volume(code == -65535); return true; } // repeats allowed: hold to ramp
        return e.getRepeatCount() == 0 && key(code, true) || super.onKeyDown(code, e);
    }
    @Override public boolean onKeyUp(int code, KeyEvent e) { return wheelVolume(code) || key(code, false) || super.onKeyUp(code, e); }

    @Override protected void onResume() { super.onResume(); if (view == null) return; Pipe p = Pipe.current; if (p != null) p.attach(view); else Pipe.view = view; view.onResume();
        if (wheel == null) wheel = new WheelKeys(this);
        wheel.start();
        ((android.media.AudioManager) getSystemService(AUDIO_SERVICE)).registerMediaButtonEventReceiver(new android.content.ComponentName(this, MediaButtonReceiver.class)); }
    @Override protected void onPause() { if (wheel != null) wheel.stop(); ((android.media.AudioManager) getSystemService(AUDIO_SERVICE)).unregisterMediaButtonEventReceiver(new android.content.ComponentName(this, MediaButtonReceiver.class)); Pipe.view = null; if (view != null) view.onPause(); super.onPause(); }
}
