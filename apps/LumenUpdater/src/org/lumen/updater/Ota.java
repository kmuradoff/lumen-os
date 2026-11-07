// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;
import android.util.Log;

import java.io.File;

/**
 * Constants and device facts (read-only system properties) of the Lumen OS updater.
 *
 * Device-neutral fork of apps/Z9xUpdater Ota.java: every device fact
 * comes from the image's ro.lumen.* properties, nothing names a projector. The device wire id
 * (ro.lumen.ota.device) and the manifest URL (ro.lumen.ota.manifest_url) have no default: without them nothing
 * is applicable and LumenApp hides the settings row (fail closed). The init services and the runtime properties
 * of the boot gate keep their z9x names (sys.z9x.*, REBOOT_REASON z9x-ota): BootReceiver and Outcome read them
 * unchanged.
 */
public final class Ota {
    private Ota() {}

    public static final String TAG = "LumenUpdater";
    public static final String VERSION = "1.0";
    public static final String AUTHOR = "kmuradoff";
    public static final String REPO = "Project-Lumen-OS/lumen-os";
    public static final String PROJECT_URL = "https://github.com/" + REPO;
    /** Guide sections of the one-time vbmeta step and of "Rescue" (language picked at runtime). */
    public static final String GUIDE_VBMETA_EN = guide("en", "vbmeta");
    public static final String GUIDE_VBMETA_RU = guide("ru", "vbmeta");
    public static final String GUIDE_RESCUE_EN = guide("en", "rescue");
    public static final String GUIDE_RESCUE_RU = guide("ru", "rescue");
    public static final String USER_AGENT = "Lumen-OS-Updater/" + VERSION;
    public static final String OTACERTS = "/system/etc/security/otacerts.zip";

    public static final String PERM_STATE = "org.lumen.updater.permission.OTA_STATE";
    public static final String ACTION_STATE = "org.lumen.updater.action.OTA_STATE";
    public static final String REBOOT_REASON = "z9x-ota";
    /** org.lumen.projector power hook (receiver permission org.lumen.projector.permission.OTA_STATE). */
    public static final String PROJECTOR_PKG = "org.lumen.projector";
    public static final String PROJECTOR_ACTION_OTA_STATE = "org.lumen.projector.action.OTA_STATE";
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

    /**
     * The device wire id of this image (ro.lumen.ota.device): a manifest must name exactly this device. Empty =
     * this image takes no update (fail closed).
     */
    public static String device() {
        return prop("ro.lumen.ota.device").trim();
    }

    /** ro.lumen.version_code, else derived from ro.lumen.version. */
    public static int currentVersionCode() {
        String vc = prop("ro.lumen.version_code");
        try {
            if (!vc.isEmpty()) return Integer.parseInt(vc.trim());
        } catch (NumberFormatException ignored) {
        }
        return versionCode(prop("ro.lumen.version"));
    }

    /**
     * Version code of x.y.z[-betaN|-rcN]: x*1000000 + y*10000 + z*100 + n, n = N for betaN, 50+N
     * for rcN, 99 for the final. So 1.0.8-beta1 = 1000801, 1.0.8-rc2 = 1000852, 1.0.8 = 1000899 and a final is
     * always newer than its betas. Anything else is 0 (never newer than an update).
     */
    public static int versionCode(String v) {
        if (v == null) return 0;
        String s = v.trim(), mark = "";
        int dash = s.indexOf('-');
        if (dash >= 0) {
            mark = s.substring(dash + 1);
            s = s.substring(0, dash);
        }
        String[] p = s.split("\\.");
        if (p.length < 1 || p.length > 3) return 0;
        try {
            int major = Integer.parseInt(p[0]);
            int minor = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            int patch = p.length > 2 ? Integer.parseInt(p[2]) : 0;
            if (major < 0 || minor < 0 || minor > 99 || patch < 0 || patch > 99) return 0;
            int n;
            if (mark.isEmpty()) n = 99;
            else if (mark.matches("beta[1-9][0-9]?")) n = Integer.parseInt(mark.substring(4));
            else if (mark.matches("rc[1-9][0-9]?")) n = 50 + Integer.parseInt(mark.substring(2));
            else return 0;
            if (n == 50 || n > 99 || (!mark.isEmpty() && n == 99) || (mark.startsWith("beta") && n > 50)) return 0;
            return major * 1000000 + minor * 10000 + patch * 100 + n;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The version name of a version code of {@link #versionCode(String)}: 1000801 -> "1.0.8-beta1". */
    public static String versionName(int code) {
        if (code <= 0) return "?";
        int n = code % 100;
        String v = (code / 1000000) + "." + (code / 10000 % 100) + "." + (code / 100 % 100);
        if (n == 99 || n == 0 || n == 50) return v;
        return n > 50 ? v + "-rc" + (n - 50) : v + "-beta" + n;
    }

    public static String currentVersion() {
        String v = prop("ro.lumen.version");
        return v.isEmpty() ? "?" : v;
    }

    public static String buildId() { return prop("ro.lumen.build_id"); }

    /** ro.lumen.ota.manifest_url (https only); "" without a device or a URL: nothing is checked. */
    public static String imageManifestUrl() {
        String u = prop("ro.lumen.ota.manifest_url").trim();
        return !device().isEmpty() && u.startsWith("https://") ? u : "";
    }

    /** The user's "Beta updates" choice (Store, the app's own storage); null = not chosen = the image's channel. */
    private static volatile Boolean sBeta;

    static void setBeta(Boolean on) { sBeta = on; }

    /**
     * {stable, beta} manifest URLs of the channel pair an image of manifest URL {@code image} belongs to, or null
     * when it has none: update-&lt;device&gt;-nogms.json (stable) and update-&lt;device&gt;-test.json (beta) in the
     * same folder (the HB: both on the fixed pre-release tag z9x-hb), whichever of the two the image reads.
     */
    static String[] channels(String image) {
        String d = device(), f = channelFile(image);
        if (d.isEmpty() || f.isEmpty()) return null;
        String stable = "update-" + d + "-nogms.json", beta = "update-" + d + "-test.json";
        if (!f.equals(stable) && !f.equals(beta)) return null;
        int q = image.indexOf('?');
        String dir = (q >= 0 ? image.substring(0, q) : image);
        dir = dir.substring(0, dir.lastIndexOf('/') + 1);
        return new String[] {dir + stable, dir + beta};
    }

    /** True when this image has a beta channel: only then the updater shows the "Beta updates" switch. */
    public static boolean betaAvailable() { return channels(imageManifestUrl()) != null; }

    /** True when the beta channel is read: the user's choice, else whether the image itself reads it. */
    public static boolean betaOn() { return betaOn(imageManifestUrl(), sBeta); }

    static boolean betaOn(String image, Boolean choice) {
        String[] c = channels(image);
        if (c == null) return false;
        return choice != null ? choice : channelFile(c[1]).equals(channelFile(image));
    }

    /**
     * The manifest to read: the stable or the beta channel (betaOn), else the image's own ("" without a device or
     * a URL). Turning beta off never downgrades: a manifest is offered only with a version_code above the
     * installed one (Preflight.applicable), so a beta stays until the stable channel has a newer version.
     */
    public static String manifestUrl() { return manifestUrl(imageManifestUrl(), sBeta); }

    static String manifestUrl(String image, Boolean choice) {
        String[] c = channels(image);
        if (c == null) return image;
        return betaOn(image, choice) ? c[1] : c[0];
    }

    /**
     * The file name of the manifest this updater reads (update-<channel>.json): the USB file to look for, and the
     * channel a manifest must carry (an edition never installs another edition's update, and a beta manifest is
     * refused while beta updates are off). "" when the image has no manifest URL (no USB file matches, every
     * manifest is refused).
     */
    public static String channelFile() {
        return channelFile(manifestUrl());
    }

    static String channelFile(String url) {
        String f = url == null ? "" : url;
        int q = f.indexOf('?');
        if (q >= 0) f = f.substring(0, q);
        f = f.substring(f.lastIndexOf('/') + 1);
        return f.startsWith("update-") && f.endsWith(".json") && f.length() > 12 ? f : "";
    }

    /**
     * A section of this device's install guide: ro.lumen.ota.guide (the https URL of the guide folder) + "/" +
     * lang + ".md#" + section; without it the folder docs/install/<device wire id> of the project, and without a
     * device the general guide docs/install/<lang>.md.
     */
    static String guide(String lang, String section) {
        String g = prop("ro.lumen.ota.guide").trim();
        while (g.endsWith("/")) g = g.substring(0, g.length() - 1);
        if (!g.startsWith("https://")) {
            String d = device();
            g = PROJECT_URL + "/blob/main/docs/install" + (d.matches("[a-z0-9_-]+") ? "/" + d : "");
        }
        return g + "/" + lang + ".md#" + section;
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

    /** AVB flags of vbmeta_<other> published by the gate's info service; -1 unknown. */
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

    /**
     * Sets a runtime property; false when it is refused (SELinux, or no property service). Never throws: the
     * caller turns false into a clear refusal.
     */
    static boolean setProp(String key, String val) {
        try {
            SystemProperties.set(key, val);
            return true;
        } catch (Throwable t) {
            try {
                Log.w(TAG, key + " not settable: " + t);
            } catch (Throwable ignored) {   // the host test has no android.util.Log
            }
            return false;
        }
    }

    /** Best-effort property for org.lumen.projector (STR / power-off postponed while busy). */
    static void setBusy(Context c, boolean busy, String state) {
        try {
            SystemProperties.set("sys.z9x.ota.busy", busy ? "1" : "0");
        } catch (Throwable t) {
            Log.i(TAG, "sys.z9x.ota.busy not settable: " + t);
        }
        Intent i = new Intent(ACTION_STATE).putExtra("busy", busy).putExtra("state", state);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        c.sendBroadcast(i, PERM_STATE);
        // Contract: org.lumen.projector's power.OtaStateReceiver (explicit, guarded by the
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
