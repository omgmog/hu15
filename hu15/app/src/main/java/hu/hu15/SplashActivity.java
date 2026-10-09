// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.animation.AlphaAnimation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Launcher screen: the Android Auto mark (Google, public domain, trademarked) and the name for a moment, then the menu. */
public class SplashActivity extends Activity {
    private final Handler h = new Handler();
    private int hold = 1300;
    private final Runnable go = new Runnable() { @Override public void run() {
        startActivity(new Intent(SplashActivity.this, MainActivity.class));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Ui.BG);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.aa_logo);
        logo.setAdjustViewBounds(true);
        root.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 170), Ui.dp(this, 174)));
        TextView name = Ui.text(this, "HU15", 34, Ui.TEXT, true);
        name.setPadding(0, Ui.dp(this, 18), 0, 0);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView tag = Ui.text(this, "Android Auto for Honda CONNECT", 15, Ui.TEXT2, false);
        tag.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(tag, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        hold = getIntent().getIntExtra("hold", 1300); // test hook: am start ... --ei hold 8000
        setContentView(root);
        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
        fade.setDuration(500);
        root.startAnimation(fade);
    }

    @Override protected void onResume() { super.onResume(); h.postDelayed(go, hold); }
    @Override protected void onPause() { h.removeCallbacks(go); super.onPause(); }
}
