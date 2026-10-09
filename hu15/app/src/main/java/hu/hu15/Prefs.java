// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings. Defaults are conservative: anything that needs hardware we have not verified on the head unit is off. */
public final class Prefs {
    public static final String MIC = "mic", BT = "bt", RHD = "rhd", AUTO_USB = "auto_usb", NIGHT = "night", THREADS2 = "threads2", HIDE_NAV = "hide_nav";
    public static final int NIGHT_DAY = 0, NIGHT_NIGHT = 1, NIGHT_AUTO = 2;

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("hu15", Context.MODE_PRIVATE); }

    public static boolean get(Context c, String k) { return p(c).getBoolean(k, AUTO_USB.equals(k)); } // only auto-USB defaults on
    public static void set(Context c, String k, boolean v) { p(c).edit().putBoolean(k, v).commit(); }
    /** Vendor id of the phone that last completed a session (-1 = none yet). USB gives no serial on API 15, so this is as precise as "paired" gets. */
    public static int pairedVid(Context c) { return p(c).getInt("paired_vid", -1); }
    public static void setPaired(Context c, int vid) { p(c).edit().putInt("paired_vid", vid).commit(); }


    public static int night(Context c) { return p(c).getInt(NIGHT, NIGHT_AUTO); }
    public static void setNight(Context c, int v) { p(c).edit().putInt(NIGHT, v).commit(); }

    /** Night flag to report to the phone right now. Auto = 19:00-07:00 by the unit's clock (no light sensor assumed). */
    public static boolean isNight(Context c) {
        int n = night(c);
        if (n != NIGHT_AUTO) return n == NIGHT_NIGHT;
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 19 || h < 7;
    }
}
