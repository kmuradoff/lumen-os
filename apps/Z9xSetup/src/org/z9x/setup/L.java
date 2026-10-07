package org.z9x.setup;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/** One log line per event under the tag Z9xSetup (setup/SPEC.md section 8). */
public final class L {
    public static final String TAG = "Z9xSetup";

    private L() {}

    /** Milliseconds since this process started (the "t=" of the step lines). */
    public static long sinceStart() {
        return SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime();
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
    }

    public static void w(String msg) {
        Log.w(TAG, msg);
    }

    public static void w(String msg, Throwable t) {
        Log.w(TAG, msg + ": " + t);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }

    /** 8 hex chars of a non-reversible hash, so SSIDs never appear in logs. */
    public static String hash(String s) {
        if (s == null) return "null";
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return String.format(java.util.Locale.ROOT, "%08x", h);
    }
}
