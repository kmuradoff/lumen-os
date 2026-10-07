package org.z9x.home.proj;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import org.z9x.home.App;

/**
 * Lumen Home's only links to org.z9x.projector and org.z9x.updater. Every call is an explicit,
 * signature-permission protected broadcast or provider call; Lumen Home itself never talks to any HAL
 * (all hardware work, its whitelists and safety gates stay inside the projector).
 *
 * Contracts (home/SPEC.md 5.6, 8.1, 15; PLAN C1, C17):
 *  - org.z9x.projector.action.SHOW_PANEL (perm SHOW_PANEL): extra "section" = QuickPanel.SECTION_*,
 *    or extra "action" = autofocus | lamp_standby | sleep_timer; no extra = the quick panel grid.
 *  - org.z9x.projector.action.SHOW_SOURCE (perm SHOW_SOURCE): the input overlay.
 *  - org.z9x.projector.action.SET_LAUNCHER (perm SET_LAUNCHER): extra "mode" (and "target") = z9x | classic.
 *  - content://org.z9x.projector.standbystate call("standby_state") -> {standby} (perm STANDBY_STATE).
 *  - content://org.z9x.updater/state (perm org.z9x.updater.permission.OTA_STATE): call("state") or query,
 *    field "state"; "ready" (or "downloaded", "reboot_required") shows the "Update ready" badge.
 */
public final class ProjectorBridge {
    private ProjectorBridge() {}

    public static final String PROJECTOR = "org.z9x.projector";
    public static final String UPDATER = "org.z9x.updater";
    public static final String ACTION_SHOW_PANEL = "org.z9x.projector.action.SHOW_PANEL";
    public static final String ACTION_SHOW_SOURCE = "org.z9x.projector.action.SHOW_SOURCE";
    public static final String ACTION_SET_LAUNCHER = "org.z9x.projector.action.SET_LAUNCHER";
    public static final String ACTION_STANDBY_CHANGED = "org.z9x.projector.action.STANDBY_CHANGED";
    public static final String PERM_STANDBY = "org.z9x.projector.permission.STANDBY_STATE";
    private static final Uri STANDBY_URI = Uri.parse("content://org.z9x.projector.standbystate");
    private static final Uri UPDATER_URI = Uri.parse("content://org.z9x.updater/state");

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
}
