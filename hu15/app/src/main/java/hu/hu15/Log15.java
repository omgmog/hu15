// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Logs to logcat and to hu15.log (/sdcard/hu15/ if writable, else app files dir). The head unit has no adb, so the file is the product. */
public final class Log15 {
    private static FileWriter w;
    public static File file;

    public static synchronized void init(Context c) {
        File[] dirs = {new File(Environment.getExternalStorageDirectory(), "hu15"), c.getFilesDir()};
        for (File d : dirs) {
            try {
                d.mkdirs();
                file = new File(d, "hu15.log");
                w = new FileWriter(file, true);
                i("log", "opened " + file);
                return;
            } catch (IOException ignored) { }
        }
        file = null;
    }

    public static synchronized void i(String tag, String msg) {
        Log.i("hu15", tag + ": " + msg);
        if (w == null) return;
        try {
            w.write(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + tag + ": " + msg + "\n");
            w.flush();
        } catch (IOException ignored) { }
    }

    public static void e(String tag, String msg, Throwable t) { i(tag, "ERROR " + msg + " " + Log.getStackTraceString(t)); }
}
