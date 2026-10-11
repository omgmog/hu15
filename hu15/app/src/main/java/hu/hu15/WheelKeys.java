package hu.hu15;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.fujitsu_ten.displayaudio.steeringmenuservice.service.ISteeringMenuService;
import com.fujitsu_ten.displayaudio.steeringmenuservice.service.ISteeringMenuServiceCallback;

/**
 * Honda steering-wheel keys. The unit keeps them for itself unless an app binds its steering-menu service, registers a callback
 * and announces "steering menu displayed" (the way OpenDroidAuto, GPL-3.0, does it); then they arrive as ordinary key events.
 * Needs android.permission.ADA_SERVICE_ACCESS. m4v experiment: everything is logged under "wheel".
 */
final class WheelKeys {
    private static final int IDX = 260; // OpenDroidAuto default: 258 source, 263 source header, 259 disp, 260 menu, 265 launcher, 266 imid
    private final Context ctx;
    private ISteeringMenuService svc;
    private boolean bound;
    private int shown, announced;
    private final ISteeringMenuServiceCallback cb = new ISteeringMenuServiceCallback.Stub() {
        @Override public boolean onFinishView(boolean a, boolean b) { Log15.i("wheel", "onFinishView " + a + " " + b); tell("service: onFinishView " + a + " " + b); return true; }
        @Override public void onShowView() { if (shown++ < 3) Log15.i("wheel", "onShowView (further ones not logged)"); announce(1); }
        @Override public boolean onSteeringSWDown(int type) { Log15.i("wheel", "onSteeringSWDown " + type); tell("service: onSteeringSWDown " + type); return true; }
    };
    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ISteeringMenuService.Stub.asInterface(b);
            Log15.i("wheel", "steering menu service connected");
            try { svc.registerCallbackEx(cb, IDX); Log15.i("wheel", "callback registered idx " + IDX); } catch (Throwable t) { Log15.e("wheel", "registerCallbackEx", t); }
            announce(1);
        }
        @Override public void onServiceDisconnected(ComponentName n) { Log15.i("wheel", "service disconnected"); svc = null; }
    };

    /** Key tester hook: gets the steering service's own callbacks as text lines. */
    interface Listener { void line(String s); }
    static volatile Listener listener;
    private static void tell(String s) { Listener l = listener; if (l != null) l.line(s); }

    WheelKeys(Context c) { ctx = c; }

    void start() {
        if (bound) return;
        try {
            bound = ctx.bindService(new Intent(ISteeringMenuService.class.getName()), conn, Context.BIND_AUTO_CREATE);
            Log15.i("wheel", "bindService -> " + bound);
        } catch (Throwable t) { Log15.e("wheel", "bindService (permission ADA_SERVICE_ACCESS?)", t); }
    }

    void stop() {
        if (svc != null) {
            try { svc.unregisterCallbackEx(cb, IDX); announce(0); } catch (Throwable t) { Log15.e("wheel", "unregister", t); }
        }
        if (bound) { try { ctx.unbindService(conn); } catch (Throwable ignored) { } bound = false; }
        svc = null;
    }

    private void announce(int mode) {
        try { if (svc != null) { svc.notifySteeringMenuDispMode(IDX, mode); if (announced++ < 3) Log15.i("wheel", "notifySteeringMenuDispMode " + IDX + "," + mode); } } catch (Throwable t) { Log15.e("wheel", "notify", t); }
    }
}
