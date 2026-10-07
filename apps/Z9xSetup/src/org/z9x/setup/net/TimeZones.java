package org.z9x.setup.net;

import android.app.AlarmManager;
import android.content.Context;
import android.icu.text.TimeZoneNames;
import android.icu.util.ULocale;
import android.provider.Settings;

import org.z9x.setup.L;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Applying and naming time zones (SPEC 4.2, PLAN C23). */
public final class TimeZones {
    public static final String[] REGIONS = {"Europe", "Asia", "America", "Africa", "Australia", "Pacific", "Atlantic", "Indian"};

    private TimeZones() {}

    /**
     * AlarmManager.setTimeZone (SET_TIME_ZONE) as SetupWraith does. C23: the device has no telephony
     * or location time-zone source, so auto_time_zone is switched off when a zone is applied, or the
     * detector could keep the old value. auto_time (NTP) stays on.
     */
    public static boolean apply(Context c, String id) {
        try {
            Settings.Global.putInt(c.getContentResolver(), Settings.Global.AUTO_TIME_ZONE, 0);
            c.getSystemService(AlarmManager.class).setTimeZone(id);
            return true;
        } catch (Throwable t) {
            L.w("tz apply " + id, t);
            return false;
        }
    }

    /** "Moscow (GMT+03:00)". */
    public static String label(String id) {
        String city = null;
        try {
            city = TimeZoneNames.getInstance(ULocale.getDefault()).getExemplarLocationName(id);
        } catch (Throwable ignored) {
        }
        if (city == null || city.isEmpty()) {
            int i = id.lastIndexOf('/');
            city = (i >= 0 ? id.substring(i + 1) : id).replace('_', ' ');
        }
        return city + " (" + gmt(id) + ")";
    }

    public static String city(String id) {
        String l = label(id);
        int i = l.lastIndexOf(" (");
        return i > 0 ? l.substring(0, i) : l;
    }

    public static String gmt(String id) {
        int off = TimeZone.getTimeZone(id).getOffset(System.currentTimeMillis()) / 60000;
        char sign = off < 0 ? '-' : '+';
        off = Math.abs(off);
        return String.format(Locale.ROOT, "GMT%c%02d:%02d", sign, off / 60, off % 60);
    }

    /** Canonical zones of one country (ISO 3166 alpha-2), sorted by offset then name. */
    public static List<String> forCountry(String cc) {
        List<String> out = new ArrayList<>();
        if (cc == null || cc.isEmpty()) return out;
        try {
            for (String id : android.icu.util.TimeZone.getAvailableIDs(
                    android.icu.util.TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, cc, null)) {
                out.add(id);
            }
        } catch (Throwable ignored) {
        }
        sort(out);
        return out;
    }

    /** Canonical zones whose id starts with "<region>/". */
    public static List<String> forRegion(String region) {
        List<String> out = new ArrayList<>();
        try {
            for (String id : android.icu.util.TimeZone.getAvailableIDs(
                    android.icu.util.TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, null, null)) {
                if (id.startsWith(region + "/")) out.add(id);
            }
        } catch (Throwable ignored) {
        }
        sort(out);
        return out;
    }

    private static void sort(List<String> ids) {
        long now = System.currentTimeMillis();
        java.util.Map<String, Integer> off = new java.util.HashMap<>();
        java.util.Map<String, String> name = new java.util.HashMap<>();
        for (String id : ids) {
            off.put(id, TimeZone.getTimeZone(id).getOffset(now));
            name.put(id, city(id));
        }
        java.text.Collator col = java.text.Collator.getInstance();
        Collections.sort(ids, (a, b) -> {
            int c = Integer.compare(off.get(a), off.get(b));
            return c != 0 ? c : col.compare(name.get(a), name.get(b));
        });
    }

    public static List<String> regions() { return Arrays.asList(REGIONS); }
}
