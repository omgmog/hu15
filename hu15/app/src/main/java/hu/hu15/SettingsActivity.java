// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** Plain-widget settings (no AndroidX on API 15). Changes apply to the next connection. */
public class SettingsActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(16, 16, 16, 16);
        box(l, Prefs.AUTO_USB, "Connect automatically when a USB device is plugged in");
        box(l, Prefs.MIC, "Use the unit's microphone for voice (off = the phone's own mic; needs a working mic on the unit)");
        box(l, Prefs.BT, "Tell the phone this unit's Bluetooth address (hands-free calls)");
        box(l, Prefs.RHD, "Right-hand drive");
        box(l, Prefs.HIDE_NAV, "Hide the on-screen navigation bar (tablets only; before Android 4.4 a touch brings it back)");
        box(l, Prefs.THREADS2, "2 video decoder threads (experimental; hung on the x86 emulator)");
        TextView t = new TextView(this);
        t.setText("Night mode");
        l.addView(t);
        RadioGroup g = new RadioGroup(this);
        String[] names = {"Day", "Night", "Auto (night 19:00-07:00)"};
        for (int i = 0; i < names.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(names[i]);
            r.setId(100 + i);
            g.addView(r);
        }
        g.check(100 + Prefs.night(this));
        g.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup group, int id) { Prefs.setNight(SettingsActivity.this, id - 100); }
        });
        l.addView(g);
        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        setContentView(sv);
    }

    private void box(LinearLayout l, final String key, String text) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setChecked(Prefs.get(this, key));
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton v, boolean on) { Prefs.set(SettingsActivity.this, key, on); }
        });
        l.addView(c);
    }
}
