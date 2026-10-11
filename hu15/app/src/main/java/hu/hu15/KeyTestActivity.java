// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.LinkedList;

/** Advanced > Key tester: a tile per key that might exist (lit while held, tinted once seen) and a terminal with the raw code of every key event. */
public class KeyTestActivity extends Activity {
    private static final int SEEN = 0xFF1F5F3A, COLS = 5, MAX_LINES = 150;
    private final HashMap<Integer, TextView> tiles = new HashMap<Integer, TextView>();
    private final LinkedList<String> lines = new LinkedList<String>();
    private LinearLayout grid, other;
    private TextView term;
    private ScrollView termScroll;
    private WheelKeys wheel;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 10);
        root.setPadding(p, p, p, p);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "Key tester  -  press a button; BACK is captured too, use Close", 16, Ui.TEXT, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(button("Clear", new Runnable() { public void run() { clear(); } }));
        bar.addView(button("Close", new Runnable() { public void run() { finish(); } }));
        root.addView(bar);

        LinearLayout body = new LinearLayout(this);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        bl.topMargin = p;
        root.addView(body, bl);

        ScrollView gs = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        gs.addView(col);
        body.addView(gs, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3));
        grid = section(col, "Honda codes");
        for (int c = -65535; c >= -65510; c--) tile(grid, c, honda(c));
        LinearLayout android = section(col, "Android keys");
        int[] ak = {KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_POWER};
        for (int c : ak) tile(android, c, null);
        other = section(col, "Other (codes not listed above appear here)");

        termScroll = new ScrollView(this);
        termScroll.setBackgroundDrawable(Ui.shape(this, 0xFF05080A, 10, Ui.OUTLINE));
        term = new TextView(this);
        term.setTypeface(Typeface.MONOSPACE);
        term.setTextSize(13);
        term.setTextColor(0xFF7CFC9A);
        term.setPadding(p, p, p, p);
        termScroll.addView(term);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2);
        tl.leftMargin = p;
        body.addView(termScroll, tl);
        setContentView(root);
        println("waiting for keys...");
    }

    private static String honda(int c) {
        switch (c) {
            case -65535: return "Wheel +";
            case -65534: return "Wheel -";
            case -65533: return "Wheel right";
            case -65532: return "Wheel left";
            case -65531: return "Source";
            case -65526: return "unknown";
            case -65522: return "voice?";
            default: return null;
        }
    }

    private View button(String t, final Runnable r) {
        TextView v = Ui.text(this, t, 15, Ui.TEXT, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));
        v.setClickable(true);
        v.setFocusable(true);
        v.setBackgroundDrawable(Ui.pressable(this, Ui.CARD, Ui.CARD_HI, 12, Ui.OUTLINE));
        v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { r.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout section(LinearLayout parent, String name) {
        TextView h = Ui.text(this, name, 13, Ui.TEXT2, true);
        h.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        parent.addView(h);
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        parent.addView(s);
        return s;
    }

    /** Adds a tile to a section, five per row. */
    private void tile(LinearLayout section, int code, String label) {
        LinearLayout row = section.getChildCount() == 0 ? null : (LinearLayout) section.getChildAt(section.getChildCount() - 1);
        if (row == null || row.getChildCount() >= COLS) {
            row = new LinearLayout(this);
            section.addView(row);
        }
        TextView t = new TextView(this);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(11);
        t.setText((label != null ? label : code < 0 ? "?" : KeyEvent.keyCodeToString(code).replace("KEYCODE_", "")) + "\n" + code);
        t.setBackgroundDrawable(Ui.shape(this, Ui.CARD, 8, Ui.OUTLINE));
        t.setPadding(2, Ui.dp(this, 6), 2, Ui.dp(this, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(2, 2, 2, 2);
        row.addView(t, lp);
        tiles.put(code, t);
    }

    private void light(int code, boolean down) {
        TextView t = tiles.get(code);
        if (t == null) { tile(other, code, null); t = tiles.get(code); }
        t.setBackgroundDrawable(Ui.shape(this, down ? Ui.PRIMARY : SEEN, 8, Ui.OUTLINE));
    }

    private void println(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() {
            lines.add(s);
            while (lines.size() > MAX_LINES) lines.removeFirst();
            StringBuilder b = new StringBuilder();
            for (String l : lines) b.append(l).append('\n');
            term.setText(b);
            termScroll.post(new Runnable() { @Override public void run() { termScroll.fullScroll(View.FOCUS_DOWN); } });
        } });
    }

    private void clear() {
        lines.clear();
        term.setText("");
        for (TextView t : tiles.values()) t.setBackgroundDrawable(Ui.shape(this, Ui.CARD, 8, Ui.OUTLINE));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down && e.getRepeatCount() > 0) return true; // holds repeat; one line per press is enough
        String line = String.format("%tT.%<tL %s %d %s", System.currentTimeMillis(), down ? "DOWN" : "UP  ", code, KeyEvent.keyCodeToString(code));
        Log15.i("keytest", line);
        println(line);
        light(code, down);
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        WheelKeys.listener = new WheelKeys.Listener() { @Override public void line(String s) { println(s); } };
        if (wheel == null) wheel = new WheelKeys(this);
        wheel.start();
    }

    @Override protected void onPause() {
        WheelKeys.listener = null;
        if (wheel != null) wheel.stop();
        super.onPause();
    }
}
