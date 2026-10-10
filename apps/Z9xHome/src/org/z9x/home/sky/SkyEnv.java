package org.z9x.home.sky;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.z9x.common.GeoIp;
import org.z9x.home.weather.Weather;

/**
 * Place and weather for the sky from what Lumen Home already knows, without any network: the manual
 * weather city, else the cached IP location (weather prefs "geo"); the current Open-Meteo reading
 * while {@link Weather#current} would show it (weather on, at most 3 h old), else clear.
 */
public final class SkyEnv {
    private SkyEnv() {}

    /** out[0] = latitude, out[1] = longitude; false when nothing is known yet. */
    public static boolean location(Context c, double[] out) {
        try {
            JSONObject m = Weather.manualCity();
            if (m != null && m.has("lat") && m.has("lon")) {
                double la = m.optDouble("lat"), lo = m.optDouble("lon");
                if (valid(la, lo)) {
                    out[0] = la;
                    out[1] = lo;
                    return true;
                }
            }
            String geo = prefs(c).getString("geo", null);
            if (geo != null) {
                GeoIp.Result g = GeoIp.Result.fromJson(new JSONObject(geo));
                if (g != null && g.hasLocation() && valid(g.lat, g.lon)) {
                    out[0] = g.lat;
                    out[1] = g.lon;
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** SkyView.WEATHER_* of the reading Home shows, WEATHER_CLEAR without one. */
    public static int weather(Context c) {
        try {
            return weather(Weather.current(c));
        } catch (Throwable t) {
            return SkyView.WEATHER_CLEAR;
        }
    }

    /** SkyView.WEATHER_* of a reading from {@link Weather#current}; WEATHER_CLEAR for null. */
    public static int weather(Weather.Now n) {
        return n == null ? SkyView.WEATHER_CLEAR : weatherFromWmo(n.code);
    }

    /** WMO weather code (Open-Meteo) to SkyView.WEATHER_*. */
    public static int weatherFromWmo(int code) {
        if (code <= 1) return SkyView.WEATHER_CLEAR;
        if (code == 2 || code == 3) return SkyView.WEATHER_CLOUDS;
        if (code == 45 || code == 48) return SkyView.WEATHER_FOG;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SkyView.WEATHER_SNOW;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95) return SkyView.WEATHER_RAIN;
        return SkyView.WEATHER_CLOUDS;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Weather.FILE, Context.MODE_PRIVATE);
    }

    private static boolean valid(double la, double lo) {
        return !Double.isNaN(la) && !Double.isNaN(lo) && Math.abs(la) <= 90 && Math.abs(lo) <= 180 && !(la == 0 && lo == 0);
    }
}
