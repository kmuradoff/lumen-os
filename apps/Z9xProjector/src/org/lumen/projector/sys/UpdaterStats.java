package org.lumen.projector.sys;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * The anonymous statistics switch for the projector settings. Its one owner is org.lumen.updater (Stats, its own
 * storage); this only reads and sets it through the updater's StateProvider (call "stats" / "stats_set", signature
 * permission org.lumen.updater.permission.OTA_STATE). The setup wizard shows the same switch. Binder calls: not on
 * the main thread.
 */
public final class UpdaterStats {
    private UpdaterStats() {}

    private static final Uri UPDATER = Uri.parse("content://org.lumen.updater");

    /** The switch, or null without an updater or without a statistics server (then the settings show no row). */
    public static Boolean get(Context c) {
        return ask(c, "stats", null);
    }

    /** Sets the switch; the value the updater holds afterwards, or null without an updater. */
    public static Boolean set(Context c, boolean on) {
        return ask(c, "stats_set", on ? "1" : "0");
    }

    private static Boolean ask(Context c, String method, String arg) {
        try {
            Bundle b = c.getContentResolver().call(UPDATER, method, arg, null);
            // "available" false: no statistics server in this build, so no switch (an older updater has no key)
            return b == null || !b.getBoolean("available", true) ? null : b.getBoolean("on");
        } catch (Throwable t) {
            Log.i("UpdaterStats", method + ": " + t);
            return null;
        }
    }
}
