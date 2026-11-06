// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;

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
        Ota.setBeta(beta());
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
        SharedPreferences.Editor e = p.edit().putString("state", s.name());
        // 2026-11-04: READY / INSTALLING remember their boot (Outcome.staleBootState)
        if (s == State.READY || s == State.INSTALLING) e.putString("state_boot", bootId()); else e.remove("state_boot");
        e.apply();
        changed();
    }

    /** The boot that saved READY / INSTALLING ("" = none saved). */
    public String stateBoot() { return p.getString("state_boot", ""); }

    /** /proc/sys/kernel/random/boot_id, "" when unreadable. */
    public static String bootId() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/sys/kernel/random/boot_id"))) {
            String l = r.readLine();
            return l == null ? "" : l.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Error message: string resource name + optional argument (shown in the current language). */
    public void setError(String resName, String arg) {
        p.edit().putString("state", State.ERROR.name()).putString("err", resName).putString("err_arg", arg)
                .remove("state_boot").apply();
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

    /** "Beta updates": true on, false off, null never chosen (the image's own channel, Ota.betaOn). */
    public Boolean beta() { return p.contains("beta") ? p.getBoolean("beta", false) : null; }

    public void setBeta(boolean on) {
        p.edit().putBoolean("beta", on).apply();
        Ota.setBeta(on);
        changed();
    }

    /** The last check found a verified beta manifest (Checker.probeBeta); false until one is found. */
    public boolean betaPublished() { return p.getBoolean("beta_published", false); }
    public void setBetaPublished(boolean on) {
        if (on != betaPublished()) { p.edit().putBoolean("beta_published", on).apply(); changed(); }
    }

    /** "Send anonymous statistics" (Stats); default Stats.DEFAULT_ON. */
    public boolean statsOn() { return p.getBoolean("stats", Stats.DEFAULT_ON); }

    /** Off forgets the install id and the last send: turning it on again starts as a new install. */
    public void setStatsOn(boolean on) {
        SharedPreferences.Editor e = p.edit().putBoolean("stats", on);
        if (!on) e.remove("stats_id").remove("stats_day").remove("stats_try").remove("stats_vc").remove("stats_version");
        e.apply();
        changed();
    }

    /**
     * This install saw the statistics choice: the first setup's Done page (StateProvider "stats_seen") or the
     * one-time notice after an update (Notice). Not cleared by the switch.
     */
    public boolean statsSeen() { return p.getBoolean("stats_seen", false); }
    public void setStatsSeen(boolean b) { p.edit().putBoolean("stats_seen", b).apply(); }

    /** Version code of the last boot (Notice: a change makes the notice due) and whether it is due. */
    public int noticeVc() { return p.getInt("notice_vc", 0); }
    public void setNoticeVc(int vc) { p.edit().putInt("notice_vc", vc).apply(); }
    public boolean noticeDue() { return p.getBoolean("notice_due", false); }
    public void setNoticeDue(boolean b) { p.edit().putBoolean("notice_due", b).apply(); }

    /** The random install id of Stats, made on first use. */
    public synchronized String statsId() {
        String id = p.getString("stats_id", "");
        if (id.isEmpty()) {
            id = Stats.newId();
            p.edit().putString("stats_id", id).apply();
        }
        return id;
    }

    /** UTC day (yyyy-MM-dd) of the last statistics sent. */
    public String statsDay() { return p.getString("stats_day", ""); }

    /** After a send: the day, and the version it reported (the next send after a change names it as previous). */
    public void setStatsSent(String day, int versionCode, String version, int boot) {
        p.edit().putString("stats_day", day).putInt("stats_vc", versionCode).putString("stats_version", version)
                .putInt("stats_boot", boot).apply();
    }

    /** Settings.Global.BOOT_COUNT of the last send (Stats.due: once per start). */
    public int statsBoot() { return p.getInt("stats_boot", -1); }

    /** Version code and name of the last send; 0 = never sent by this install id. */
    public int statsVc() { return p.getInt("stats_vc", 0); }
    public String statsVersion() { return p.getString("stats_version", ""); }

    /** How the update to build statsMethodBuild() was installed (ota_full, ota_delta, usb), set at its install. */
    public String statsMethod() { return p.getString("stats_method", ""); }
    public String statsMethodBuild() { return p.getString("stats_method_build", ""); }
    public long statsTry() { return p.getLong("stats_try", 0); }
    public void setStatsTry(long t) { p.edit().putLong("stats_try", t).apply(); }

    /** The user allowed, once, that an update copies XGIMI firmware unchanged into the other slot. */
    public boolean firmwareConsent() { return p.getBoolean("consent_fw", false); }
    public void setFirmwareConsent(boolean b) { p.edit().putBoolean("consent_fw", b).apply(); }

    // ---- what we expect after the restart
    public void setPending(String targetBuildId, String targetVersion, String fromBuildId, String fromVersion) {
        String method = fromUsb() ? "usb" : "ota";
        try {
            if (!fromUsb()) method = "ota_" + UpdateManifest.Pkg.parse(packageJson()).type;
        } catch (JSONException ignored) {
        }
        p.edit().putString("stats_method", method).putString("stats_method_build", targetBuildId).apply();
        p.edit().putString("pend_target", targetBuildId).putString("pend_version", targetVersion)
                .putString("pend_from", fromBuildId).putString("pend_from_version", fromVersion).apply();
    }

    public String pendingTarget() { return p.getString("pend_target", ""); }
    public String pendingVersion() { return p.getString("pend_version", ""); }
    public String pendingFrom() { return p.getString("pend_from", ""); }
    public String pendingFromVersion() { return p.getString("pend_from_version", ""); }

    public void clearPending() {
        p.edit().remove("pend_target").remove("pend_version").remove("pend_from").remove("pend_from_version").apply();
    }

    /**
     * Last result shown once in the UI after a restart: "ok:<version>", "unhealthy:<version>" (runs, but
     * the boot gate's check found a problem: {@link #lastWhy}) or "rollback:<version>". Recorded with the
     * running build id ({@link #lastResultBuild}): it is about that build only (Outcome.resultStale).
     */
    public String lastResult() { return p.getString("result", ""); }
    public void setLastResult(String r) { setLastResult(r, ""); }

    public void setLastResult(String r, String why) {
        p.edit().putString("result", r).putString("result_why", why)
                .putString("result_build", r.isEmpty() ? "" : Ota.buildId()).apply();
        changed();
    }

    /** The gate's reason (sys.z9x.ota.why) that goes with an "unhealthy:" result, else "". */
    public String lastWhy() { return p.getString("result_why", ""); }

    /** Build id that ran when the last result was recorded; "" for results of the 1.0 / 1.0.0 updater. */
    public String lastResultBuild() { return p.getString("result_build", ""); }

    /** Version whose first boot still waits for the boot gate's verdict (CheckJob JOB_GATE), else "". */
    public String gateWatch() { return p.getString("gate_watch", ""); }
    public void setGateWatch(String version) { p.edit().putString("gate_watch", version).apply(); }
}
