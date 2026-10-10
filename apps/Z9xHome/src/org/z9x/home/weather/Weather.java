package org.z9x.home.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.z9x.common.GeoIp;
import org.z9x.home.App;
import org.z9x.home.Prefs;
import org.z9x.home.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Weather by IP (SPEC 5.7, user decision): IP geolocation (shared GeoIp chain, PLAN C13) and
 * Open-Meteo, keyless. Fetched only while Home is visible: geo at most every 6 h, forecast at most every
 * 30 min. Readings are shown up to 3 h old (dimmed after 1 h). No identifiers are sent.
 * Attribution: "Weather: Open-Meteo.com (CC BY 4.0)" in Customize > About.
 */
public final class Weather {
    private Weather() {}

    public static final String FILE = "weather";
    private static final String UA = "Lumen-Home/1.0";
    private static final int TIMEOUT = 4000;
    private static final long GEO_MAX_AGE = 6 * 3600_000L;
    private static final long GEO_NET_AGE = 3600_000L;
    private static final long FC_MAX_AGE = 30 * 60_000L;
    private static final long SHOW_MAX_AGE = 3 * 3600_000L;
    private static final long DIM_AGE = 3600_000L;
    private static final long CITY_NAME_AGE = 30L * 86_400_000L;

    public static final class Day {
        public String label;
        public int icon;
        public String max, min;
    }

    public static final class Now {
        public String temp;
        public String feels;
        public String city;
        public int icon;
        public int code;          // WMO weather code (condition text, living sky)
        public boolean day;
        public boolean stale;
        public long time;
        public final ArrayList<Day> days = new ArrayList<>();
    }

    public static final class City {
        public String name, detail;
        public double lat, lon;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** The reading to show, or null (weather off, nothing cached, or older than 3 h). Any thread. */
    public static Now current(Context c) {
        Prefs p = App.get().prefs();
        if (!p.weatherOn()) return null;
        SharedPreferences s = sp(c);
        long t = s.getLong("fc_time", 0);
        long age = System.currentTimeMillis() - t;
        if (t == 0 || age > SHOW_MAX_AGE || age < -3600_000L) return null;
        try {
            JSONObject fc = new JSONObject(s.getString("fc", "{}"));
            JSONObject cur = fc.getJSONObject("current");
            Now n = new Now();
            n.time = t;
            n.stale = age > DIM_AGE;
            boolean day = cur.optInt("is_day", 1) == 1;
            n.code = cur.optInt("weather_code", 0);
            n.day = day;
            n.icon = Wmo.icon(n.code, day);
            n.temp = deg(cur.optDouble("temperature_2m"));
            n.feels = deg(cur.optDouble("apparent_temperature"));
            n.city = cityName(c);
            JSONObject d = fc.optJSONObject("daily");
            if (d != null) {
                JSONArray times = d.optJSONArray("time");
                JSONArray codes = d.optJSONArray("weather_code");
                JSONArray max = d.optJSONArray("temperature_2m_max");
                JSONArray min = d.optJSONArray("temperature_2m_min");
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
                SimpleDateFormat out = new SimpleDateFormat("EEEE", Locale.getDefault());
                for (int i = 0; times != null && i < Math.min(3, times.length()); i++) {
                    Day dd = new Day();
                    if (i == 0) dd.label = c.getString(R.string.weather_today);
                    else {
                        Date dt = in.parse(times.getString(i));
                        String l = dt != null ? out.format(dt) : times.getString(i);
                        dd.label = l.isEmpty() ? l : l.substring(0, 1).toUpperCase(Locale.getDefault()) + l.substring(1);
                    }
                    dd.icon = Wmo.icon(codes != null ? codes.optInt(i) : 0, true);
                    dd.max = max != null ? deg(max.optDouble(i)) : "";
                    dd.min = min != null ? deg(min.optDouble(i)) : "";
                    n.days.add(dd);
                }
            }
            return n;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String deg(double v) {
        if (Double.isNaN(v)) return "";
        return String.format(Locale.getDefault(), "%d°", Math.round(v));
    }

    /** Display name of the city: the manual one, else the localized IP city. */
    public static String cityName(Context c) {
        JSONObject manual = manualCity();
        if (manual != null) return manual.optString("name");
        SharedPreferences s = sp(c);
        String loc = s.getString("city_loc", "");
        return !loc.isEmpty() ? loc : s.getString("city_raw", "");
    }

    public static JSONObject manualCity() {
        String m = App.get().prefs().str(Prefs.K_CITY, "");
        if (m.isEmpty()) return null;
        try {
            return new JSONObject(m);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean fahrenheit(Context c) {
        String u = App.get().prefs().units();
        if ("f".equals(u)) return true;
        if ("c".equals(u)) return false;
        String cc = sp(c).getString("cc", "");
        if (cc.isEmpty()) cc = Locale.getDefault().getCountry();
        return cc.equals("US") || cc.equals("LR") || cc.equals("MM");
    }

    /**
     * Refreshes what is due (io thread only). {@code networkChanged}: a new network may mean a new IP
     * location (geo older than 1 h is redone). Returns true if the shown reading changed.
     */
    public static boolean refresh(Context c, boolean networkChanged, boolean force) {
        Prefs p = App.get().prefs();
        if (!p.weatherOn()) return false;
        SharedPreferences s = sp(c);
        long now = System.currentTimeMillis();
        double lat, lon;
        JSONObject manual = manualCity();
        boolean changedPlace = false;
        if (manual != null) {
            lat = manual.optDouble("lat");
            lon = manual.optDouble("lon");
            String key = manual.optString("name") + "|" + lat + "|" + lon;
            changedPlace = !key.equals(s.getString("place_key", ""));
            if (changedPlace) s.edit().putString("place_key", key).apply();
        } else {
            long geoT = s.getLong("geo_time", 0);
            long geoAge = now - geoT;
            boolean due = geoT == 0 || geoAge > GEO_MAX_AGE || (networkChanged && geoAge > GEO_NET_AGE) || geoAge < 0
                    || !s.getString("place_key", "").startsWith("ip|");
            if (due) {
                GeoIp.Result r = GeoIp.lookup(TIMEOUT, UA);
                if (r != null && r.hasLocation()) {
                    String key = "ip|" + Math.round(r.lat * 10) + "|" + Math.round(r.lon * 10);
                    changedPlace = !key.equals(s.getString("place_key", ""));
                    s.edit().putString("geo", r.toJson().toString()).putLong("geo_time", now)
                            .putString("city_raw", r.city).putString("cc", r.countryCode)
                            .putString("place_key", key).apply();
                    Log.i(App.TAG, "weather geo src=" + r.source + " city=" + r.city + " ok");
                } else {
                    Log.w(App.TAG, "weather geo failed");
                    if (geoT == 0) return false;
                }
            }
            try {
                GeoIp.Result g = GeoIp.Result.fromJson(new JSONObject(s.getString("geo", "{}")));
                lat = g.lat;
                lon = g.lon;
                localizeCity(c, g.city);
            } catch (Exception e) {
                return false;
            }
        }
        boolean f = fahrenheit(c);
        String unitKey = f ? "f" : "c";
        long fcT = s.getLong("fc_time", 0);
        if (!force && !changedPlace && fcT > 0 && now - fcT < FC_MAX_AGE && now >= fcT
                && unitKey.equals(s.getString("fc_units", ""))) {
            return false;
        }
        long t0 = SystemClock.uptimeMillis();
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + fmt(lat) + "&longitude=" + fmt(lon)
                    + "&current=temperature_2m,apparent_temperature,weather_code,is_day"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=3&timezone=auto"
                    + (f ? "&temperature_unit=fahrenheit" : "");
            JSONObject fc = GeoIp.getJson(url, TIMEOUT, UA);
            if (fc == null || !fc.has("current")) {
                Log.w(App.TAG, "weather fc failed");
                return false;
            }
            s.edit().putString("fc", fc.toString()).putLong("fc_time", now).putString("fc_units", unitKey).apply();
            Log.i(App.TAG, "weather fc 200 ms=" + (SystemClock.uptimeMillis() - t0));
            return true;
        } catch (Throwable t) {
            Log.w(App.TAG, "weather fc: " + t);
            return false;
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /** City name in the UI language via Open-Meteo geocoding, cached 30 days per city + language. */
    private static void localizeCity(Context c, String raw) {
        if (raw == null || raw.isEmpty()) return;
        SharedPreferences s = sp(c);
        String lang = Locale.getDefault().getLanguage();
        String key = raw + "|" + lang;
        if (key.equals(s.getString("city_loc_key", "")) && System.currentTimeMillis() - s.getLong("city_loc_time", 0) < CITY_NAME_AGE) {
            return;
        }
        String name = raw;
        try {
            List<City> l = search(raw, lang, 1);
            if (!l.isEmpty()) name = l.get(0).name;
        } catch (Throwable ignored) {
        }
        s.edit().putString("city_loc", name).putString("city_loc_key", key).putLong("city_loc_time", System.currentTimeMillis()).apply();
    }

    /** City search for the manual city (io thread). */
    public static List<City> search(String q, String lang, int count) throws Exception {
        ArrayList<City> out = new ArrayList<>();
        if (q == null || q.trim().length() < 2) return out;
        String url = "https://geocoding-api.open-meteo.com/v1/search?name=" + Uri.encode(q.trim()) + "&count=" + count
                + "&language=" + Uri.encode(lang) + "&format=json";
        JSONObject o = GeoIp.getJson(url, TIMEOUT, UA);
        JSONArray a = o != null ? o.optJSONArray("results") : null;
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i);
            City ct = new City();
            ct.name = r.optString("name");
            String admin = r.optString("admin1");
            String country = r.optString("country");
            ct.detail = (admin.isEmpty() ? "" : admin + ", ") + country;
            ct.lat = r.optDouble("latitude");
            ct.lon = r.optDouble("longitude");
            out.add(ct);
        }
        return out;
    }

    public static void setManualCity(City ct) {
        Prefs p = App.get().prefs();
        if (ct == null) {
            p.putStr(Prefs.K_CITY, "");
            return;
        }
        try {
            p.putStr(Prefs.K_CITY, new JSONObject().put("name", ct.name).put("lat", ct.lat).put("lon", ct.lon).toString());
        } catch (Exception ignored) {
        }
    }

    /** Forget cached readings (units or place changed). */
    public static void invalidate(Context c) {
        sp(c).edit().putLong("fc_time", 0).apply();
    }
}
