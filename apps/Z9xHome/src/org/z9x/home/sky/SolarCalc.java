package org.z9x.home.sky;

/**
 * Sun and moon for the live sky, plain Java (no android.*), so it runs on the host:
 * test/sky/SolarCalcTest.java.
 *
 * Sun: NOAA "General Solar Position Calculations" (fractional year series for the equation of time
 * and the declination), the model of the approved prototype D_Live; within about 1 minute / 0.1
 * degree of the full NOAA spreadsheet at mid latitudes. Elevations are geometric; sunrise and sunset
 * are the crossings of -0.833 degrees (refraction plus the solar radius), as in the prototype.
 *
 * Moon: low precision series (P. Schlyter, "How to compute planetary positions"): about 0.3 degree,
 * with the topocentric parallax, plus the phase (illuminated fraction, waxing or waning).
 *
 * Nothing here allocates: the sky calls it once per minute and the sunrise scan 1441 times per day.
 */
public final class SolarCalc {
    private SolarCalc() {}

    /** Sunrise / sunset elevation (degrees). */
    public static final double RISE_SET_DEG = -0.833;

    private static final long DAY_MS = 86_400_000L;
    private static final double RAD = Math.PI / 180;

    // ------------------------------------------------------------------ sun

    /** out[0] = elevation (degrees), out[1] = azimuth (degrees from north, clockwise). */
    public static void sun(long utcMillis, double latDeg, double lonDeg, double[] out) {
        long day = Math.floorDiv(utcMillis, DAY_MS);
        double hour = (utcMillis - day * DAY_MS) / 3_600_000.0;
        int year = yearOf(day);
        int doy = (int) (day - daysFromCivil(year, 1, 1)) + 1;
        double yearLen = leap(year) ? 366 : 365;

        double g = 2 * Math.PI / yearLen * (doy - 1 + (hour - 12) / 24);
        double eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double d = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        // hour angle from the true solar time (UTC based, so no time zone term), normalized to -180..180
        double haDeg = (hour * 60 + eq + 4 * lonDeg) / 4 - 180;
        haDeg = haDeg - 360 * Math.floor((haDeg + 180) / 360);
        horizontal(haDeg * RAD, d, latDeg * RAD, out);
    }

    /** Elevation of the sun (degrees). */
    public static double sunElevation(long utcMillis, double latDeg, double lonDeg, double[] tmp) {
        sun(utcMillis, latDeg, lonDeg, tmp);
        return tmp[0];
    }

    /**
     * Daylight of one local day: scans its 1441 minutes from {@code localMidnightUtc} (the UTC instant
     * of 00:00 local). out[0] = sunrise, out[1] = sunset (minutes after local midnight, NaN when the sun
     * does not cross -0.833 that day: polar day or night), out[2] = highest elevation (degrees),
     * out[3] = its minute. {@code out} needs 4 slots.
     */
    public static void daylight(long localMidnightUtc, double latDeg, double lonDeg, double[] out) {
        double rise = Double.NaN, set = Double.NaN, max = -90, maxAt = 0;
        double prev = 0;
        for (int m = 0; m <= 1440; m++) {
            sun(localMidnightUtc + m * 60_000L, latDeg, lonDeg, out); // out is scratch until the end
            double e = out[0];
            if (m > 0) {
                if (prev < RISE_SET_DEG && e >= RISE_SET_DEG && Double.isNaN(rise)) {
                    rise = m - 1 + (RISE_SET_DEG - prev) / (e - prev);
                } else if (prev >= RISE_SET_DEG && e < RISE_SET_DEG) {
                    set = m - 1 + (prev - RISE_SET_DEG) / (prev - e);
                }
            }
            if (e > max) {
                max = e;
                maxAt = m;
            }
            prev = e;
        }
        out[0] = rise;
        out[1] = set;
        out[2] = max;
        out[3] = maxAt;
    }

    // ------------------------------------------------------------------ moon

    /**
     * out[0] = topocentric elevation (degrees), out[1] = azimuth (degrees from north), out[2] =
     * illuminated fraction 0..1, out[3] = +1 waxing / -1 waning.
     */
    public static void moon(long utcMillis, double latDeg, double lonDeg, double[] out) {
        double d = utcMillis / 86_400_000.0 + 2440587.5 - 2451543.5; // days since 1999-12-31 0h UT
        double ut = (utcMillis - Math.floorDiv(utcMillis, DAY_MS) * DAY_MS) / 3_600_000.0;

        // sun (for the sidereal time and the phase)
        double ws = 282.9404 + 4.70935e-5 * d;
        double ms = rev(356.0470 + 0.9856002585 * d);
        double es = 0.016709 - 1.151e-9 * d;
        double eS = ms + es / RAD * Math.sin(ms * RAD) * (1 + es * Math.cos(ms * RAD));
        double xs = Math.cos(eS * RAD) - es;
        double ys = Math.sqrt(1 - es * es) * Math.sin(eS * RAD);
        double sunLon = rev(Math.atan2(ys, xs) / RAD + ws);
        double ls = rev(ms + ws);

        // moon orbital elements
        double n = rev(125.1228 - 0.0529538083 * d);
        double inc = 5.1454;
        double w = rev(318.0634 + 0.1643573223 * d);
        double a = 60.2666;
        double e = 0.054900;
        double mm = rev(115.3654 + 13.0649929509 * d);
        double ecc = mm + e / RAD * Math.sin(mm * RAD) * (1 + e * Math.cos(mm * RAD));
        for (int i = 0; i < 4; i++) {
            ecc = ecc - (ecc - e / RAD * Math.sin(ecc * RAD) - mm) / (1 - e * Math.cos(ecc * RAD));
        }
        double xv = a * (Math.cos(ecc * RAD) - e);
        double yv = a * Math.sqrt(1 - e * e) * Math.sin(ecc * RAD);
        double v = Math.atan2(yv, xv) / RAD;
        double r = Math.sqrt(xv * xv + yv * yv);
        double vw = (v + w) * RAD;
        double xh = r * (Math.cos(n * RAD) * Math.cos(vw) - Math.sin(n * RAD) * Math.sin(vw) * Math.cos(inc * RAD));
        double yh = r * (Math.sin(n * RAD) * Math.cos(vw) + Math.cos(n * RAD) * Math.sin(vw) * Math.cos(inc * RAD));
        double zh = r * Math.sin(vw) * Math.sin(inc * RAD);
        double lon = Math.atan2(yh, xh) / RAD;
        double lat = Math.atan2(zh, Math.sqrt(xh * xh + yh * yh)) / RAD;

        // main perturbations (degrees, earth radii)
        double lm = rev(n + w + mm);
        double dd = lm - ls;
        double f = lm - n;
        lon += -1.274 * sinD(mm - 2 * dd) + 0.658 * sinD(2 * dd) - 0.186 * sinD(ms) - 0.059 * sinD(2 * mm - 2 * dd)
                - 0.057 * sinD(mm - 2 * dd + ms) + 0.053 * sinD(mm + 2 * dd) + 0.046 * sinD(2 * dd - ms)
                + 0.041 * sinD(mm - ms) - 0.035 * sinD(dd) - 0.031 * sinD(mm + ms) - 0.015 * sinD(2 * f - 2 * dd)
                + 0.011 * sinD(mm - 4 * dd);
        lat += -0.173 * sinD(f - 2 * dd) - 0.055 * sinD(mm - f - 2 * dd) - 0.046 * sinD(mm + f - 2 * dd)
                + 0.033 * sinD(f + 2 * dd) + 0.017 * sinD(2 * mm + f);
        r += -0.58 * Math.cos((mm - 2 * dd) * RAD) - 0.46 * Math.cos(2 * dd * RAD);

        // ecliptic -> equatorial
        double ecl = (23.4393 - 3.563e-7 * d) * RAD;
        double xg = Math.cos(lon * RAD) * Math.cos(lat * RAD);
        double yg = Math.sin(lon * RAD) * Math.cos(lat * RAD);
        double zg = Math.sin(lat * RAD);
        double ye = yg * Math.cos(ecl) - zg * Math.sin(ecl);
        double ze = yg * Math.sin(ecl) + zg * Math.cos(ecl);
        double ra = Math.atan2(ye, xg) / RAD;
        double dec = Math.atan2(ze, Math.sqrt(xg * xg + ye * ye));

        // local sidereal time -> hour angle -> horizontal
        double lst = ls + 180 + ut * 15 + lonDeg; // degrees
        double haDeg = lst - ra;
        haDeg = haDeg - 360 * Math.floor((haDeg + 180) / 360);
        horizontal(haDeg * RAD, dec, latDeg * RAD, out);
        double alt = out[0] * RAD;
        out[0] = (alt - Math.asin(1 / r) * Math.cos(alt)) / RAD; // topocentric (parallax ~1 degree)

        double elong = Math.acos(clamp(Math.cos((sunLon - lon) * RAD) * Math.cos(lat * RAD)));
        out[2] = (1 - Math.cos(elong)) / 2; // = (1 + cos(phase angle)) / 2
        out[3] = rev(lon - sunLon) < 180 ? 1 : -1;
    }

    // ------------------------------------------------------------------ helpers

    /** Hour angle and declination (radians) to out[0] elevation, out[1] azimuth (degrees). */
    private static void horizontal(double ha, double dec, double la, double[] out) {
        double cz = clamp(Math.sin(la) * Math.sin(dec) + Math.cos(la) * Math.cos(dec) * Math.cos(ha));
        double z = Math.acos(cz);
        double sz = Math.sin(z);
        double az;
        double den = Math.cos(la) * sz;
        if (Math.abs(den) < 1e-9) {
            az = 180;
        } else {
            az = Math.acos(clamp((Math.sin(la) * Math.cos(z) - Math.sin(dec)) / den)) / RAD;
            az = ha > 0 ? (az + 180) % 360 : (540 - az) % 360;
        }
        out[0] = 90 - z / RAD;
        out[1] = az;
    }

    private static double sinD(double deg) {
        return Math.sin(deg * RAD);
    }

    private static double clamp(double v) {
        return v < -1 ? -1 : v > 1 ? 1 : v;
    }

    static double rev(double deg) {
        return deg - 360 * Math.floor(deg / 360);
    }

    static boolean leap(int y) {
        return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
    }

    /** Epoch day of a proleptic Gregorian date (H. Hinnant's days_from_civil). */
    public static long daysFromCivil(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = Math.floorDiv(y, 400);
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** Year of an epoch day (civil_from_days). */
    public static int yearOf(long epochDay) {
        long z = epochDay + 719468;
        long era = Math.floorDiv(z, 146097);
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long m = mp < 10 ? mp + 3 : mp - 9;
        return (int) (m <= 2 ? y + 1 : y);
    }

    /** Day of the year 1..366 of an epoch day. */
    public static int dayOfYear(long epochDay) {
        return (int) (epochDay - daysFromCivil(yearOf(epochDay), 1, 1)) + 1;
    }
}
