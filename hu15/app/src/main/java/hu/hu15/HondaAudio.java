// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.content.Context;

/** Honda ModeMgr audio handoff (what OpenDroidAuto does for PREINSTALL apps), by reflection so it is a no-op elsewhere. Only useful with the PREINSTALL permission type. */
final class HondaAudio {
    private static final int IDX = 213, REQUEST = 31, CONFIRM = 7, NOTIFY = 3; // 213 = "3rd party" audio mode; values from OpenDroidAuto
    private static boolean held;

    static synchronized void request(Context c) {
        if (held) return;
        Object m = c.getSystemService("ModeMgrService");
        if (m == null) { Log15.i("audio", "ModeMgrService missing; no Honda handoff"); return; }
        call(m, "sendModeMgrOnReq", REQUEST);
        try { Thread.sleep(200); } catch (InterruptedException ignored) { }
        call(m, "sendModeMgrOnCnf", CONFIRM);
        call(m, "notifyModeMgrStatus", NOTIFY);
        held = true;
    }

    static synchronized void release(Context c) {
        if (!held) return;
        Object m = c.getSystemService("ModeMgrService");
        if (m != null) {
            call(m, "sendModeMgrOffReq", REQUEST);
            try { Thread.sleep(200); } catch (InterruptedException ignored) { }
            call(m, "sendModeMgrOffCnf", CONFIRM);
        }
        held = false;
    }

    private static void call(Object m, String name, int arg) {
        try { Log15.i("audio", "ModeMgr " + name + " -> " + m.getClass().getMethod(name, int.class, int.class).invoke(m, IDX, arg)); }
        catch (Throwable t) { Log15.e("audio", "ModeMgr " + name, t); }
    }
}
