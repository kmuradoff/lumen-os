// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Anonymous usage statistics (README "Privacy"): at most once a day, when the updater checks for an update and the
 * first-run setup is finished, one HTTPS POST with: a random install id (Store, the app's own storage: a new one
 * after "Clear data" or after the switch was off), the projector serial (the server keeps only a keyed hash of it),
 * the device codename and the XGIMI model, the Lumen OS version, version code and build id, the update channel, and
 * on the first send after a version change the previous version and how it changed (ota_full, ota_delta, usb, or
 * installer when no update of this app did it). The server derives the country from the address (no city) and does
 * not store the address. One shared switch (Store.statsOn): this app's settings, the setup wizard and the projector
 * settings (StateProvider "stats" / "stats_set"). Runs on its own thread, never delays the check, failures are
 * only logged.
 */
public final class Stats {
    private Stats() {}

    /** Default of the "Send anonymous statistics" switch (one place: the owner's choice). */
    public static final boolean DEFAULT_ON = true;
    /**
     * Tried in order until one answers 2xx (a fallback server goes after the first). Empty = no statistics server in
     * this build: nothing is sent and every statistics switch is hidden (Setup, projector settings, this app), the
     * rule "never show what does not work". 2026-11-05: the owner approved <domain> for the public HB 1.0.8.
     */
    public static final String[] URLS = {"https://<domain>/lumen/v1/ping"};

    /** A statistics server is configured in this build (else nothing is sent and no switch is shown). */
    public static boolean configured() { return URLS.length > 0; }
    /** A failed send is tried again at the next check, but not sooner than this. */
    private static final long RETRY_MS = 3 * 3600 * 1000L;
    private static final int TIMEOUT_MS = 10000;

    /** Called at the start of every online check (Checker.check): never blocks, never throws. */
    static void maybeSend(Context c) {
        try {
            Store st = Store.get(c);
            if (!configured() || !st.statsOn() || !setupDone(c)) return;
            String today = day(System.currentTimeMillis());
            long now = System.currentTimeMillis();
            if (today.equals(st.statsDay()) || Math.abs(now - st.statsTry()) < RETRY_MS) return;
            st.setStatsTry(now);
            int vc = Ota.currentVersionCode();
            String version = Ota.currentVersion(), build = Ota.buildId();
            int prevVc = st.statsVc();
            String prev = "", method = "";
            if (prevVc == 0) {
                method = "first";
            } else if (prevVc != vc) {
                prev = st.statsVersion();
                method = build.equals(st.statsMethodBuild()) ? st.statsMethod() : "installer";
            } else {
                prevVc = 0;
            }
            byte[] body = body(st.statsId(), serial(), model(), Ota.prop("ro.boot.xgimi.modelname").trim(), version,
                    vc, build, Ota.betaOn() ? "beta" : "stable", prev, prevVc, method).getBytes(StandardCharsets.UTF_8);
            Thread t = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                for (String url : URLS) {
                    try {
                        Http.post(url, body, TIMEOUT_MS);
                        st.setStatsSent(today, vc, version);
                        Log.i(Ota.TAG, "stats sent");
                        return;
                    } catch (Throwable e) {
                        Log.i(Ota.TAG, "stats not sent: " + e);
                    }
                }
            }, "update-stats");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            Log.i(Ota.TAG, "stats: " + t);
        }
    }

    /** Nothing is sent before the first-run setup ends (it shows the switch; user_setup_complete is set at its end). */
    static boolean setupDone(Context c) {
        return Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 0) == 1;
    }

    /**
     * The projector serial (the XGIMI serial on the HB and the Z9X): Build.getSerial() (READ_PRIVILEGED_PHONE_STATE,
     * granted by the platform signature), else ro.serialno (serialno_prop: an app domain may not read it under an
     * enforcing policy), else "".
     */
    @SuppressWarnings("MissingPermission")
    static String serial() {
        String s;
        try {
            s = Build.getSerial();
        } catch (Throwable t) {
            s = "";
        }
        if (s == null || s.trim().isEmpty() || Build.UNKNOWN.equals(s)) s = Ota.prop("ro.serialno");
        s = s == null ? "" : s.trim();
        return Build.UNKNOWN.equals(s) ? "" : s;
    }

    /**
     * The device codename of the image (ro.lumen.device, e.g. goat_elegant); an image without it (the Z9X up to
     * 1.0.8) sends the vendor device name, which the server maps to the codename.
     */
    static String model() {
        String m = Ota.prop("ro.lumen.device").trim();
        return m.isEmpty() ? Ota.prop("ro.product.vendor.device").trim() : m;
    }

    /** The JSON body, written by hand (no org.json: the host test runs it on a plain JVM). */
    static String body(String id, String serial, String model, String xgimiModel, String version, int versionCode,
            String build, String channel, String prevVersion, int prevVersionCode, String method) {
        return "{\"v\":2,\"id\":" + str(id) + ",\"serial\":" + str(serial) + ",\"model\":" + str(model)
                + ",\"xgimi_model\":" + str(xgimiModel) + ",\"version\":" + str(version)
                + ",\"version_code\":" + versionCode + ",\"build\":" + str(build) + ",\"channel\":" + str(channel)
                + ",\"prev_version\":" + str(prevVersion) + ",\"prev_version_code\":" + prevVersionCode
                + ",\"method\":" + str(method) + "}";
    }

    static String str(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : (s == null ? "" : s).toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }

    static String day(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t));
    }

    static String newId() { return UUID.randomUUID().toString(); }
}
