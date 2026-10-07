/*
 * Lumen OS (author kmuradoff). SINGLE SOURCE: gsi/apps/common/GeoIp.java (PLAN.md C13).
 * Copied unchanged into each app as src/org/z9x/common/GeoIp.java by gsi/apps/common/sync.sh;
 * there is no shared runtime library. Edit only the source file, then run sync.sh for every app.
 */
package org.z9x.common;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Keyless IP geolocation with one provider chain, shared by Lumen Home (weather) and Lumen Setup
 * (time zone). Blocking: call it from a worker thread only.
 *
 * Providers, in order (each one is skipped for 6 h after an HTTP 429 or 403 in this process):
 *  1. https://ipwho.is/          (HTTPS)
 *  2. https://ipapi.co/json/     (HTTPS)
 *  3. http://ip-api.com/json/    (cleartext: the app's network_security_config must allow ip-api.com only)
 *
 * Only the device IP (implicitly) is sent: no identifiers, no cookies. Setup adds its own Gservices
 * ip_timezone fallback on top of this; that is not part of the shared chain.
 */
public final class GeoIp {
    private GeoIp() {}

    private static final String TAG = "Z9xGeoIp";

    public static final String SRC_IPWHO = "ipwho";
    public static final String SRC_IPAPI = "ipapi";
    public static final String SRC_IPAPICOM = "ip-api";

    private static final String[] ORDER = {SRC_IPWHO, SRC_IPAPI, SRC_IPAPICOM};
    private static final long BACKOFF_MS = 6L * 3600_000L;
    private static final int MAX_BYTES = 64 * 1024;
    private static final long[] sBackoffUntil = new long[ORDER.length];

    /** One lookup result. Strings are never null (empty when unknown). */
    public static final class Result {
        public final String source;
        public final String city;
        public final String region;
        public final String countryCode;
        public final String timezone;
        public final double lat;
        public final double lon;
        public final long time;

        public Result(String source, String city, String region, String countryCode, String timezone,
                      double lat, double lon, long time) {
            this.source = nn(source);
            this.city = nn(city);
            this.region = nn(region);
            this.countryCode = nn(countryCode);
            this.timezone = nn(timezone);
            this.lat = lat;
            this.lon = lon;
            this.time = time;
        }

        public boolean hasLocation() {
            return !(lat == 0 && lon == 0) && !Double.isNaN(lat) && !Double.isNaN(lon);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("src", source).put("city", city).put("region", region).put("cc", countryCode)
                        .put("tz", timezone).put("lat", lat).put("lon", lon).put("t", time);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Result fromJson(JSONObject o) {
            if (o == null) return null;
            return new Result(o.optString("src"), o.optString("city"), o.optString("region"),
                    o.optString("cc"), o.optString("tz"), o.optDouble("lat", 0), o.optDouble("lon", 0),
                    o.optLong("t", 0));
        }

        @Override
        public String toString() {
            return "src=" + source + " city=" + city + " cc=" + countryCode + " tz=" + timezone;
        }
    }

    /** Tries the providers in order; null if every one failed. */
    public static Result lookup(int timeoutMs, String userAgent) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < ORDER.length; i++) {
            synchronized (sBackoffUntil) {
                if (sBackoffUntil[i] > now) continue;
            }
            try {
                Result r = query(ORDER[i], timeoutMs, userAgent, i);
                if (r != null && (r.hasLocation() || !r.timezone.isEmpty())) {
                    Log.i(TAG, "geo ok " + r);
                    return r;
                }
            } catch (Throwable t) {
                Log.w(TAG, "geo " + ORDER[i] + " failed: " + t);
            }
        }
        return null;
    }

    private static Result query(String src, int timeoutMs, String ua, int idx) throws Exception {
        long now = System.currentTimeMillis();
        switch (src) {
            case SRC_IPWHO: {
                JSONObject o = get("https://ipwho.is/?fields=success,city,region,country_code,latitude,longitude,timezone",
                        timeoutMs, ua, idx);
                if (o == null || !o.optBoolean("success", false)) return null;
                Object tz = o.opt("timezone");
                String tzId = tz instanceof JSONObject ? ((JSONObject) tz).optString("id") : o.optString("timezone");
                return new Result(src, o.optString("city"), o.optString("region"), o.optString("country_code"),
                        tzId, o.optDouble("latitude", 0), o.optDouble("longitude", 0), now);
            }
            case SRC_IPAPI: {
                JSONObject o = get("https://ipapi.co/json/", timeoutMs, ua, idx);
                if (o == null || o.optBoolean("error", false)) return null;
                return new Result(src, o.optString("city"), o.optString("region"), o.optString("country_code"),
                        o.optString("timezone"), o.optDouble("latitude", 0), o.optDouble("longitude", 0), now);
            }
            default: {
                JSONObject o = get("http://ip-api.com/json/?fields=status,city,regionName,countryCode,lat,lon,timezone",
                        timeoutMs, ua, idx);
                if (o == null || !"success".equals(o.optString("status"))) return null;
                return new Result(src, o.optString("city"), o.optString("regionName"), o.optString("countryCode"),
                        o.optString("timezone"), o.optDouble("lat", 0), o.optDouble("lon", 0), now);
            }
        }
    }

    /** GET a small JSON document. Also used by the apps for other keyless JSON APIs. */
    public static JSONObject getJson(String url, int timeoutMs, String userAgent) throws Exception {
        return get(url, timeoutMs, userAgent, -1);
    }

    private static JSONObject get(String url, int timeoutMs, String ua, int idx) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json");
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            int code = c.getResponseCode();
            if (code == 429 || code == 403) {
                if (idx >= 0) {
                    synchronized (sBackoffUntil) {
                        sBackoffUntil[idx] = System.currentTimeMillis() + BACKOFF_MS;
                    }
                }
                Log.w(TAG, "http " + code + " " + host(url) + ", backing off");
                return null;
            }
            if (code != 200) {
                Log.w(TAG, "http " + code + " " + host(url));
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream(4096);
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > MAX_BYTES) throw new IllegalStateException("response too large");
                }
                return new JSONObject(bo.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }

    private static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    private static String nn(String s) {
        return s == null || "null".equals(s) ? "" : s;
    }
}
