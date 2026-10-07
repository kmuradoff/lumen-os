// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;
import android.util.Log;

import java.io.File;

/** Constants and device facts (read-only system properties) of the Lumen OS updater. */
public final class Ota {
    private Ota() {}

    public static final String TAG = "LumenUpdater";
    public static final String VERSION = "1.0";
    public static final String AUTHOR = "kmuradoff";
    public static final String REPO = "kmuradoff/lumen-os";
    public static final String PROJECT_URL = "https://github.com/" + REPO;
    public static final String DEFAULT_MANIFEST_URL =
            PROJECT_URL + "/releases/latest/download/update-stable.json";
    /** Guide section of the one-time vbmeta step (language picked at runtime). */
    public static final String GUIDE_VBMETA_EN = PROJECT_URL + "/blob/main/docs/install/en.md#vbmeta";
    public static final String GUIDE_VBMETA_RU = PROJECT_URL + "/blob/main/docs/install/ru.md#vbmeta";
    public static final String USER_AGENT = "Lumen-OS-Updater/" + VERSION;
    public static final String OTACERTS = "/system/etc/security/otacerts.zip";

    public static final String PERM_STATE = "org.z9x.updater.permission.OTA_STATE";
    public static final String ACTION_STATE = "org.z9x.updater.action.OTA_STATE";
    public static final String REBOOT_REASON = "z9x-ota";
    /** org.z9x.projector power hook (receiver permission org.z9x.projector.permission.OTA_STATE). */
    public static final String PROJECTOR_PKG = "org.z9x.projector";
    public static final String PROJECTOR_ACTION_OTA_STATE = "org.z9x.projector.action.OTA_STATE";
    public static final String NOTIF_CHANNEL = "updates";

    /** update_engine status codes (system/update_engine/client_library/include/update_engine/update_status.h). */
    public static final int UE_IDLE = 0, UE_CHECKING = 1, UE_AVAILABLE = 2, UE_DOWNLOADING = 3,
            UE_VERIFYING = 4, UE_FINALIZING = 5, UE_NEED_REBOOT = 6, UE_REPORTING = 7,
            UE_ROLLBACK = 8, UE_DISABLED = 9, UE_CLEANUP = 11;
    /** update_engine error codes we explain (common/error_code.h). */
    public static final int ERR_SUCCESS = 0, ERR_NOT_ENOUGH_SPACE = 60, ERR_UPDATED_BUT_NOT_ACTIVE = 52,
            ERR_TIMESTAMP = 51, ERR_USER_CANCELED = 48;

    public static String prop(String key) {
        try {
            return SystemProperties.get(key, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** ro.z9x.version_code, else derived from ro.z9x.version ("1.0" -> 10000). */
    public static int currentVersionCode() {
        String vc = prop("ro.z9x.version_code");
        try {
            if (!vc.isEmpty()) return Integer.parseInt(vc.trim());
        } catch (NumberFormatException ignored) {
        }
        return versionCode(prop("ro.z9x.version"));
    }

    public static int versionCode(String v) {
        if (v == null) return 0;
        String[] p = v.trim().split("\\.");
        try {
            int major = p.length > 0 ? Integer.parseInt(p[0]) : 0;
            int minor = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            int patch = p.length > 2 ? Integer.parseInt(p[2]) : 0;
            return major * 10000 + minor * 100 + patch;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String currentVersion() {
        String v = prop("ro.z9x.version");
        return v.isEmpty() ? "?" : v;
    }

    public static String buildId() { return prop("ro.z9x.build_id"); }

    public static String manifestUrl() {
        String u = prop("ro.z9x.ota.manifest_url");
        return u.startsWith("https://") ? u : DEFAULT_MANIFEST_URL;
    }

    /** "_a"/"_b" of the running slot. */
    public static String slot() {
        String s = prop("ro.boot.slot_suffix");
        return s.isEmpty() ? prop("sys.z9x.slot") : s;
    }

    public static String slotLetter(String suffix) {
        return suffix != null && suffix.length() == 2 ? suffix.substring(1).toUpperCase() : "?";
    }

    public static String otherSlot() {
        String s = slot();
        return "_a".equals(s) ? "_b" : "_b".equals(s) ? "_a" : "";
    }

    /** AVB flags of vbmeta_<other> published by z9x_ota.sh info; -1 unknown. */
    public static int otherVbmetaFlags() {
        String f = prop("sys.z9x.vbmeta_other.flags");
        try {
            return Integer.parseInt(f.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static File dir(Context c) {
        File d = new File(c.createDeviceProtectedStorageContext().getNoBackupFilesDir(), "ota");
        if (!d.isDirectory() && !d.mkdirs()) Log.w(TAG, "cannot create " + d);
        // update_engine (root) reads the small metadata file by path: keep the dir traversable
        d.setExecutable(true, false);
        d.getParentFile().setExecutable(true, false);
        return d;
    }

    /** Best-effort property for org.z9x.projector (STR / power-off postponed while busy). */
    static void setBusy(Context c, boolean busy, String state) {
        try {
            SystemProperties.set("sys.z9x.ota.busy", busy ? "1" : "0");
        } catch (Throwable t) {
            Log.i(TAG, "sys.z9x.ota.busy not settable: " + t);
        }
        Intent i = new Intent(ACTION_STATE).putExtra("busy", busy).putExtra("state", state);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        c.sendBroadcast(i, PERM_STATE);
        // W2 contract (PLAN C16): org.z9x.projector's power.OtaStateReceiver (explicit, guarded by the
        // projector's signature permission OTA_STATE) keeps the SoC out of STR while busy; the property
        // above is only a best-effort duplicate (it needs the property to be settable).
        try {
            Intent p = new Intent(PROJECTOR_ACTION_OTA_STATE).setPackage(PROJECTOR_PKG)
                    .putExtra("busy", busy)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            c.sendBroadcast(p);
        } catch (Throwable t) {
            Log.i(TAG, "projector OTA_STATE: " + t);
        }
    }
}
