package org.z9x.setup.net;

import android.content.Context;

import org.json.JSONObject;
import org.z9x.common.GeoIp;

import java.time.ZoneId;

/**
 * Time zone from the public IP (SPEC 4.2, PLAN C13): the shared provider chain of
 * org.z9x.common.GeoIp (ipwho.is, ipapi.co, ip-api.com; byte-identical copy via apps/common/sync.sh),
 * plus setup's own last fallback, Gservices google_setup:ip_timezone. 2.5 s per provider, no
 * identifiers. Blocking: call it from a worker thread only.
 */
public final class TzLookup {
    public static final String UA = "Lumen-Setup/1.0";
    private static final int TIMEOUT_MS = 2500;

    private TzLookup() {}

    /** What the wizard keeps: the zone, where it came from, and the shared result for the cache. */
    public static final class Result {
        public String tz;
        public String src;
        public String country;
        /** org.z9x.common.GeoIp.Result JSON (setup prefs cache), or null for Gservices. */
        public String json;
    }

    public static Result lookup(Context c) {
        GeoIp.Result g = null;
        try {
            g = GeoIp.lookup(TIMEOUT_MS, UA);
        } catch (Throwable ignored) {
        }
        if (g != null && valid(g.timezone)) {
            Result r = new Result();
            r.tz = g.timezone;
            r.src = g.source;
            r.country = g.countryCode.isEmpty() ? null : g.countryCode.toUpperCase(java.util.Locale.ROOT);
            r.json = g.toJson().toString();
            return r;
        }
        String tz = Gservices.get(c, "google_setup:ip_timezone");
        if (valid(tz)) {
            Result r = new Result();
            r.tz = tz;
            r.src = "gservices";
            if (g != null && !g.countryCode.isEmpty()) r.country = g.countryCode.toUpperCase(java.util.Locale.ROOT);
            return r;
        }
        return null;
    }

    /** Country code from the cached shared result, or null. */
    public static String cachedCountry(String json) {
        if (json == null) return null;
        try {
            GeoIp.Result g = GeoIp.Result.fromJson(new JSONObject(json));
            return g == null || g.countryCode.isEmpty() ? null : g.countryCode.toUpperCase(java.util.Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean valid(String tz) {
        if (tz == null || tz.isEmpty()) return false;
        try {
            return ZoneId.getAvailableZoneIds().contains(tz);
        } catch (Throwable t) {
            return false;
        }
    }
}
