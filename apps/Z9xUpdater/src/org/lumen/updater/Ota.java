// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

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
    /**
     * Used only when ro.z9x.ota.manifest_url is missing or not https (every Lumen image sets it): the published
     * edition's manifest update-public-nogms.json on the update channel, the fixed pre-release tag z9x, as the
     * images from 1.0.8 on read it (releases/latest only up to 1.0.7). The tag holds no update-stable.json.
     */
    public static final String DEFAULT_MANIFEST_URL =
            PROJECT_URL + "/releases/download/z9x/update-public-nogms.json";
    /**
     * Beta channel of the published edition (Lumen OS without Google, update-public-nogms.json): its own manifest
     * beside the stable one on the channel tag z9x (packages on the pre-release z9x-&lt;version&gt;, version
     * x.y.z or x.y.z-betaN). With beta on, a check reads both and offers the higher version (Channels). The other
     * editions have no beta channel.
     */
    public static final String BETA_MANIFEST_URL = PROJECT_URL + "/releases/download/z9x/update-public-beta.json";
    /** The Z9X install guide (docs/install/z9x/&lt;lang&gt;.md, anchors vbmeta and rescue). */
    static final String GUIDE = PROJECT_URL + "/blob/main/docs/install/z9x/";
    /** Guide section of the one-time vbmeta step (language picked at runtime). */
    public static final String GUIDE_VBMETA_EN = GUIDE + "en.md#vbmeta";
    public static final String GUIDE_VBMETA_RU = GUIDE + "ru.md#vbmeta";
    /** Guide section "Rescue" (lumen-install.sh rescue, keeps the data): shown with a gate problem. */
    public static final String GUIDE_RESCUE_EN = GUIDE + "en.md#rescue";
    public static final String GUIDE_RESCUE_RU = GUIDE + "ru.md#rescue";
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

    /** The manifest this image was built to read (ro.z9x.ota.manifest_url, https only). */
    public static String imageManifestUrl() {
        String u = prop("ro.z9x.ota.manifest_url");
        return u.startsWith("https://") ? u : DEFAULT_MANIFEST_URL;
    }

    /** The user's "Beta updates" choice (Store, the app's own storage); null = not chosen = the image's channel. */
    private static volatile Boolean sBeta;

    static void setBeta(Boolean on) { sBeta = on; }

    /**
     * {stable, beta} manifest URLs of the edition an image of manifest URL {@code image} belongs to, or null when the
     * edition has no beta channel. The no-Google edition: its own file, and update-public-beta.json on the tag z9x.
     */
    static String[] channels(String image) {
        return "update-public-nogms.json".equals(channelFile(image)) ? new String[] {image, BETA_MANIFEST_URL} : null;
    }

    /** True when this edition has a beta channel: only then the updater shows the "Beta updates" switch. */
    public static boolean betaAvailable() { return channels(imageManifestUrl()) != null; }

    /** True when the beta channel is read: the user's choice, else whether the image itself reads it. */
    public static boolean betaOn() { return betaOn(imageManifestUrl(), sBeta); }

    static boolean betaOn(String image, Boolean choice) {
        String[] c = channels(image);
        if (c == null) return false;
        return choice != null ? choice : c[1].equals(image);
    }

    /**
     * The main manifest of the chosen channel: the stable or the beta one of this edition (betaOn), else the image's
     * own. A check with beta on reads the stable one too (Channels.read). Turning beta off never downgrades: a
     * manifest is offered only with a version_code above the installed one (Preflight.applicable), so a beta stays
     * until the stable channel has a newer version.
     */
    public static String manifestUrl() { return manifestUrl(imageManifestUrl(), sBeta); }

    static String manifestUrl(String image, Boolean choice) {
        String[] c = channels(image);
        if (c == null) return image;
        return betaOn(image, choice) ? c[1] : c[0];
    }

    /**
     * The file name of the manifest this updater reads (update-stable.json, update-public.json,
     * update-public-nogms.json: docs/ota.md "Variants", or the edition's beta update-public-beta.json). With beta
     * on a check accepts the stable file too (Channels.files / accepts: the USB files and the channels a manifest
     * may carry; an edition never installs another edition's update, a beta manifest is refused while beta
     * updates are off).
     */
    public static String channelFile() {
        return channelFile(manifestUrl());
    }

    static String channelFile(String url) {
        String f = url == null ? "" : url;
        int q = f.indexOf('?');
        if (q >= 0) f = f.substring(0, q);
        f = f.substring(f.lastIndexOf('/') + 1);
        return f.startsWith("update-") && f.endsWith(".json") ? f : "update-stable.json";
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
