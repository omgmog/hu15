package hu.hu15;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/** Wheel/unit media keys that Android delivers as MEDIA_BUTTON broadcasts instead of key events. */
public final class MediaButtonReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        KeyEvent e = (KeyEvent) i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        Log15.i("mediabutton", "broadcast " + e);
        Pipe p = Pipe.current;
        if (e != null && p != null && p.session != null) p.session.sendKey(e.getKeyCode(), e.getAction() == KeyEvent.ACTION_DOWN);
        if (isOrderedBroadcast()) abortBroadcast();
    }
}
