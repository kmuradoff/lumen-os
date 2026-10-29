package org.lumen.home.proj;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import org.lumen.home.App;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Lumen Home's only links to org.lumen.projector and org.lumen.updater. Every call is an explicit,
 * signature-permission protected broadcast or provider call; Lumen Home itself never talks to any HAL
 * (all hardware work, its whitelists and safety gates stay inside the projector).
 *
 * Contracts (home/SPEC.md 5.6, 8.1, 15; PLAN C1, C17):
 *  - org.lumen.projector.action.SHOW_PANEL (perm SHOW_PANEL): extra "section" = QuickPanel.SECTION_*,
 *    or extra "action" = autofocus | lamp_standby | sleep_timer; no extra = the quick panel grid.
 *  - org.lumen.projector.action.SHOW_SOURCE (perm SHOW_SOURCE): the input overlay.
 *  - org.lumen.projector.action.SET_LAUNCHER (perm SET_LAUNCHER): extra "mode" (and "target") = z9x | classic.
 *  - content://org.lumen.projector.standbystate call("standby_state") -> {standby} (perm STANDBY_STATE).
 *  - content://org.lumen.updater/state (perm org.lumen.updater.permission.OTA_STATE): call("state") or query,
 *    field "state"; "ready" (or "downloaded", "reboot_required") shows the "Update ready" badge.
 *  - Optional: content://org.lumen.projector.device (read only, no permission),
 *    only on projectors whose projector app has it (one with an internal battery or with Projector tab
 *    tiles it cannot serve). call("battery") -> {level int 0..100 (-1 while present is false), adapter,
 *    charging, full, present, eco}; notifyChange on content://org.lumen.projector.device/battery at every
 *    change. call("caps") -> {unsupported: String[]} = tile intents of data/InputSource.tiles to hide.
 *    Used only when the authority belongs to the system app org.lumen.projector (any app could declare it);
 *    without it (the Z9X) Home looks it up once and changes nothing.
 */
public final class ProjectorBridge {
    private ProjectorBridge() {}

    public static final String PROJECTOR = "org.lumen.projector";
    public static final String UPDATER = "org.lumen.updater";
    public static final String ACTION_SHOW_PANEL = "org.lumen.projector.action.SHOW_PANEL";
    public static final String ACTION_SHOW_SOURCE = "org.lumen.projector.action.SHOW_SOURCE";
    public static final String ACTION_SET_LAUNCHER = "org.lumen.projector.action.SET_LAUNCHER";
    public static final String ACTION_STANDBY_CHANGED = "org.lumen.projector.action.STANDBY_CHANGED";
    public static final String PERM_STANDBY = "org.lumen.projector.permission.STANDBY_STATE";
    private static final Uri STANDBY_URI = Uri.parse("content://org.lumen.projector.standbystate");
    private static final Uri UPDATER_URI = Uri.parse("content://org.lumen.updater/state");

    /** "section:x", "action:x", "settings" or "" (the grid). */
    public static void showPanel(Context c, String target) {
        if ("settings".equals(target)) {
            openAndroidSettings(c);
            return;
        }
        Intent i = new Intent(ACTION_SHOW_PANEL).setPackage(PROJECTOR);
        if (target != null && target.startsWith("section:")) i.putExtra("section", target.substring(8));
        else if (target != null && target.startsWith("action:")) i.putExtra("action", target.substring(7));
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        c.sendBroadcast(i);
        Log.i(App.TAG, "projector panel " + (target == null || target.isEmpty() ? "grid" : target));
    }

    public static void showSource(Context c) {
        Intent i = new Intent(ACTION_SHOW_SOURCE).setPackage(PROJECTOR).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        c.sendBroadcast(i);
    }

    public static void setLauncher(Context c, String mode) {
        Intent i = new Intent(ACTION_SET_LAUNCHER).setPackage(PROJECTOR)
                .putExtra("mode", mode).putExtra("target", mode)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        c.sendBroadcast(i);
        Log.i(App.TAG, "set-launcher mode=" + mode);
    }

    public static void openAndroidSettings(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Log.w(App.TAG, "settings: " + t);
        }
    }

    /** Lamp-only standby of the projector (io thread). */
    public static boolean standby(Context c) {
        try (ContentProviderClient cl = c.getContentResolver().acquireUnstableContentProviderClient(STANDBY_URI)) {
            if (cl == null) return false;
            Bundle b = cl.call("standby_state", null, null);
            return b != null && b.getBoolean("standby", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the updater has an update ready to install (io thread). */
    public static boolean updateReady(Context c) {
        String s = null;
        try (ContentProviderClient cl = c.getContentResolver().acquireUnstableContentProviderClient(UPDATER_URI)) {
            if (cl == null) return false;
            try {
                Bundle b = cl.call("state", null, null);
                if (b != null) s = b.getString("state");
            } catch (Throwable ignored) {
            }
            if (s == null) {
                try (Cursor cu = cl.query(UPDATER_URI, null, null, null, null)) {
                    if (cu != null && cu.moveToFirst()) {
                        int i = cu.getColumnIndex("state");
                        if (i >= 0) s = cu.getString(i);
                    }
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return s != null && (s.equals("ready") || s.equals("downloaded") || s.equals("reboot_required"));
    }

    public static void openUpdater(Context c) {
        try {
            c.startActivity(new Intent("android.settings.SYSTEM_UPDATE_SETTINGS").setPackage(UPDATER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Log.w(App.TAG, "updater: " + t);
        }
    }

    // ------------------------------------------------------------------ device state (optional provider)

    public static final String DEVICE_AUTHORITY = "org.lumen.projector.device";
    /** Notified by the projector app whenever one of the battery values changes. */
    public static final Uri DEVICE_BATTERY_URI = Uri.parse("content://" + DEVICE_AUTHORITY + "/battery");
    private static volatile int sDevice;   // 0 = not looked up yet, 1 = there, -1 = none (looked up once)

    /** The internal battery as the projector app reports it. Immutable. */
    public static final class Battery {
        public final int level;            // 0..100
        public final boolean adapter;      // the power adapter is in
        public final boolean charging;     // adapter in and not full yet

        Battery(int level, boolean adapter, boolean charging) {
            this.level = level;
            this.adapter = adapter;
            this.charging = charging;
        }
    }

    /**
     * True when org.lumen.projector (a system app) has the device-state provider. A PackageManager lookup only
     * (no provider start, no "failed to find provider" log), once per process: the provider comes with
     * the image. A failed lookup is not remembered.
     */
    public static boolean hasDeviceState(Context c) {
        int d = sDevice;
        if (d != 0) return d > 0;
        try {
            ProviderInfo pi = c.getPackageManager().resolveContentProvider(DEVICE_AUTHORITY, 0);
            boolean ok = pi != null && PROJECTOR.equals(pi.packageName) && pi.applicationInfo != null
                    && (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            sDevice = ok ? 1 : -1;
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The internal battery (io thread). Null without the provider, while it reports none (present false)
     * and on any failure (projector app restarting, an exception, a permission refused): no battery mark.
     */
    public static Battery battery(Context c) {
        if (!hasDeviceState(c)) return null;
        try (ContentProviderClient cl = c.getContentResolver().acquireUnstableContentProviderClient(DEVICE_AUTHORITY)) {
            if (cl == null) return null;
            Bundle b = cl.call("battery", null, null);
            if (b == null || !b.getBoolean("present", false)) return null;
            int level = b.getInt("level", -1);
            if (level < 0) return null;
            return new Battery(Math.min(100, level), b.getBoolean("adapter", false), b.getBoolean("charging", false));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Projector tab tiles this projector does not support (io thread), by tile intent ("section:eye", ...)
     * as data/InputSource.tiles names them. Empty without the provider and on any failure: every tile.
     */
    public static Set<String> unsupportedTiles(Context c) {
        if (!hasDeviceState(c)) return Collections.emptySet();
        try (ContentProviderClient cl = c.getContentResolver().acquireUnstableContentProviderClient(DEVICE_AUTHORITY)) {
            if (cl == null) return Collections.emptySet();
            Bundle b = cl.call("caps", null, null);
            String[] a = b != null ? b.getStringArray("unsupported") : null;
            if (a == null || a.length == 0) return Collections.emptySet();
            HashSet<String> out = new HashSet<>();
            for (String s : a) if (s != null && !s.isEmpty()) out.add(s);
            return out;
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }
}
