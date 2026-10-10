package org.z9x.projector.report;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * Report preferences: the per-device random install id that stands in for the serial number (made on the
 * first report, never derived from any hardware id), and the code of the last report sent.
 */
final class ReportStore {
    private static final String PREFS = "z9x_report";
    private static final String K_INSTALL_ID = "install_id";
    private static final String K_LAST_CODE = "last_code";
    private static final String K_LAST_TIME = "last_time";

    private ReportStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 24 lowercase hex characters (96 random bits); the Worker accepts [0-9a-f]{16,64}. */
    static synchronized String installId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString(K_INSTALL_ID, null);
        if (id != null && id.matches("[0-9a-f]{24}")) return id;
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(24);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        id = sb.toString();
        p.edit().putString(K_INSTALL_ID, id).apply();
        return id;
    }

    static void setLast(Context c, String code, long timeMs) {
        prefs(c).edit().putString(K_LAST_CODE, code).putLong(K_LAST_TIME, timeMs).apply();
    }

    static String lastCode(Context c) {
        return prefs(c).getString(K_LAST_CODE, null);
    }

    static long lastTime(Context c) {
        return prefs(c).getLong(K_LAST_TIME, 0);
    }
}
