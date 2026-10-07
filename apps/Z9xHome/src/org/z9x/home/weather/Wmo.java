package org.z9x.home.weather;

import org.z9x.home.R;

/** WMO weather code to our two-tone icons, day and night variants (SPEC 5.7). */
public final class Wmo {
    private Wmo() {}

    public static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.wx_clear_day : R.drawable.wx_clear_night;
        if (code == 1 || code == 2) return day ? R.drawable.wx_partly_day : R.drawable.wx_partly_night;
        if (code == 3) return R.drawable.wx_cloudy;
        if (code == 45 || code == 48) return R.drawable.wx_fog;
        if (code >= 51 && code <= 57) return R.drawable.wx_drizzle;
        if (code >= 61 && code <= 67) return R.drawable.wx_rain;
        if (code >= 71 && code <= 77) return R.drawable.wx_snow;
        if (code >= 80 && code <= 82) return day ? R.drawable.wx_showers : R.drawable.wx_rain;
        if (code == 85 || code == 86) return R.drawable.wx_snow;
        if (code >= 95 && code <= 99) return R.drawable.wx_thunder;
        return R.drawable.wx_cloudy;
    }
}
