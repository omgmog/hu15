// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tiny Material-style kit built from plain views: API 15 has no AppCompat/Material, and this app is two screens. */
final class Ui {
    static final int BG = 0xFF0E1116, CARD = 0xFF1A1F27, CARD_HI = 0xFF262D38, OUTLINE = 0xFF2C333D;
    static final int PRIMARY = 0xFF1AA1F4, PRIMARY_HI = 0xFF0E7DC2, TEXT = 0xFFE8EAED, TEXT2 = 0xFF9AA0A6, ON_PRIMARY = 0xFFFFFFFF;

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable shape(Context c, int fill, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0) g.setStroke(Math.max(1, dp(c, 1)), strokeColor);
        return g;
    }

    /** Fill that darkens/lightens while pressed or focused (rotary/d-pad focus on the unit shows the same highlight). */
    static StateListDrawable pressable(Context c, int fill, int hi, float radiusDp, int stroke) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_pressed }, shape(c, hi, radiusDp, stroke));
        s.addState(new int[] { android.R.attr.state_focused }, shape(c, hi, radiusDp, PRIMARY));
        s.addState(new int[] {}, shape(c, fill, radiusDp, stroke));
        return s;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Big tappable card-button: a title and a one-line subtitle. Returns the container; titles/subtitles are tagged views 0/1. */
    static LinearLayout bigButton(Context c, String title, String sub, boolean primary) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(c, 24), dp(c, 14), dp(c, 24), dp(c, 14));
        b.setClickable(true);
        b.setFocusable(true);
        b.setBackgroundDrawable(primary ? pressable(c, PRIMARY, PRIMARY_HI, 20, 0) : pressable(c, CARD, CARD_HI, 20, OUTLINE));
        b.addView(text(c, title, primary ? 28 : 22, primary ? ON_PRIMARY : TEXT, true));
        TextView st = text(c, sub, 14, primary ? 0xCCFFFFFF : TEXT2, false);
        st.setPadding(0, dp(c, 4), 0, 0);
        b.addView(st);
        return b;
    }

    static void setBigButton(LinearLayout b, String title, String sub) {
        ((TextView) b.getChildAt(0)).setText(title);
        ((TextView) b.getChildAt(1)).setText(sub);
    }

    /** One list row for the Advanced screen. */
    static LinearLayout row(Context c, String title, String sub) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(c, 18), dp(c, 10), dp(c, 18), dp(c, 10));
        r.setClickable(true);
        r.setFocusable(true);
        r.setBackgroundDrawable(pressable(c, CARD, CARD_HI, 14, OUTLINE));
        r.addView(text(c, title, 17, TEXT, true));
        if (sub != null) r.addView(text(c, sub, 12, TEXT2, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 8);
        r.setLayoutParams(lp);
        return r;
    }
}
