// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persistent updater state (device-protected SharedPreferences, survives reboots and OTAs) plus an
 * in-process change bus for the UI. All writes use apply(); readers always see the latest value
 * in this process.
 */
public final class Store {
    public enum State { IDLE, CHECKING, AVAILABLE, DOWNLOADING, PAUSED, DOWNLOADED, INSTALLING, READY, ERROR }

    public static final int AUTO_OFF = 0, AUTO_NOTIFY = 1, AUTO_DOWNLOAD = 2;

    private static Store sInstance;
    private static final CopyOnWriteArrayList<Runnable> sListeners = new CopyOnWriteArrayList<>();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private final SharedPreferences p;

    private Store(Context c) {
        p = c.getApplicationContext().createDeviceProtectedStorageContext()
                .getSharedPreferences("ota", Context.MODE_PRIVATE);
    }

    public static synchronized Store get(Context c) {
        if (sInstance == null) sInstance = new Store(c);
        return sInstance;
    }

    public static void listen(Runnable r) { sListeners.add(r); }
    public static void unlisten(Runnable r) { sListeners.remove(r); }

    public void changed() {
        sMain.post(() -> { for (Runnable r : sListeners) r.run(); });
    }

    // ---- state
    public State state() {
        try {
            return State.valueOf(p.getString("state", "IDLE"));
        } catch (IllegalArgumentException e) {
            return State.IDLE;
        }
    }

    public void setState(State s) {
        p.edit().putString("state", s.name()).apply();
        changed();
    }

    /** Error message: string resource name + optional argument (shown in the current language). */
    public void setError(String resName, String arg) {
        p.edit().putString("state", State.ERROR.name()).putString("err", resName).putString("err_arg", arg).apply();
        changed();
    }

    public String errorRes() { return p.getString("err", ""); }
    public String errorArg() { return p.getString("err_arg", ""); }

    // ---- manifest + chosen package
    public void setOffer(String manifestJson, String packageJson, boolean usb, String usbPath) {
        p.edit().putString("manifest", manifestJson).putString("pkg", packageJson)
                .putBoolean("usb", usb).putString("usb_path", usbPath)
                .putLong("dl_bytes", 0).putString("dl_etag", "").apply();
        changed();
    }

    public String manifestJson() { return p.getString("manifest", ""); }
    public String packageJson() { return p.getString("pkg", ""); }
    public boolean fromUsb() { return p.getBoolean("usb", false); }
    public String usbPath() { return p.getString("usb_path", ""); }

    public void clearOffer() {
        p.edit().remove("manifest").remove("pkg").remove("usb").remove("usb_path")
                .remove("dl_bytes").remove("dl_total").remove("dl_etag").remove("progress").apply();
        changed();
    }

    // ---- download progress
    public long dlBytes() { return p.getLong("dl_bytes", 0); }
    public long dlTotal() { return p.getLong("dl_total", 0); }
    public String dlEtag() { return p.getString("dl_etag", ""); }
    public long dlSpeed() { return p.getLong("dl_speed", 0); }

    public void setDownload(long bytes, long total, String etag, long speed) {
        p.edit().putLong("dl_bytes", bytes).putLong("dl_total", total).putString("dl_etag", etag)
                .putLong("dl_speed", speed).apply();
        changed();
    }

    // ---- install progress 0..1
    public float progress() { return p.getFloat("progress", 0f); }

    public void setProgress(float f) {
        p.edit().putFloat("progress", f).apply();
        changed();
    }

    // ---- check bookkeeping
    public long lastCheck() { return p.getLong("last_check", 0); }
    public void setLastCheck(long t) { p.edit().putLong("last_check", t).apply(); changed(); }

    // ---- settings
    public int autoMode() { return p.getInt("auto", AUTO_DOWNLOAD); }
    public void setAutoMode(int m) { p.edit().putInt("auto", m).apply(); changed(); }

    /** The user allowed, once, that an update copies XGIMI firmware unchanged into the other slot. */
    public boolean firmwareConsent() { return p.getBoolean("consent_fw", false); }
    public void setFirmwareConsent(boolean b) { p.edit().putBoolean("consent_fw", b).apply(); }

    // ---- what we expect after the restart
    public void setPending(String targetBuildId, String targetVersion, String fromBuildId, String fromVersion) {
        p.edit().putString("pend_target", targetBuildId).putString("pend_version", targetVersion)
                .putString("pend_from", fromBuildId).putString("pend_from_version", fromVersion).apply();
    }

    public String pendingTarget() { return p.getString("pend_target", ""); }
    public String pendingVersion() { return p.getString("pend_version", ""); }
    public String pendingFromVersion() { return p.getString("pend_from_version", ""); }

    public void clearPending() {
        p.edit().remove("pend_target").remove("pend_version").remove("pend_from").remove("pend_from_version").apply();
    }

    /** Last result shown once in the UI after a restart: "ok:<version>" or "rollback:<version>". */
    public String lastResult() { return p.getString("result", ""); }
    public void setLastResult(String r) { p.edit().putString("result", r).apply(); changed(); }
}
