package org.z9x.home.sky;

/**
 * Host self-check of the live sky's astronomy (no JUnit, no Android): sh test/sky/run.sh
 * Moscow 55.75 N 37.62 E, UTC+3: solstice sunrise / sunset / highest sun within 3 min / 0.3 deg of the
 * reference values (SolarCalc's own result, within a minute of the published tables), the moon's phase
 * at known new / full / quarter moons, and the daily scene
 * rotation of SkyLook (deterministic, no two equal days in a row).
 */
public final class SolarCalcTest {
    private static int failed, passed;

    private static final double LAT = 55.75, LON = 37.62;
    private static final long TZ_MS = 3 * 3_600_000L;

    public static void main(String[] a) {
        solstice(2025, 12, 21, 8 * 60 + 57, 15 * 60 + 58, 10.8);
        solstice(2025, 6, 21, 3 * 60 + 44, 21 * 60 + 18, 57.7);
        solstice(2026, 12, 21, 8 * 60 + 57, 15 * 60 + 58, 10.8);
        solstice(2026, 6, 21, 3 * 60 + 44, 21 * 60 + 18, 57.7);
        winterAfternoonDark();
        moonPhases();
        polar();
        dailyScene();
        System.out.println("sky tests passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void solstice(int y, int m, int d, double riseMin, double setMin, double maxEl) {
        long midnight = SolarCalc.daysFromCivil(y, m, d) * 86_400_000L - TZ_MS;
        double[] o = new double[4];
        SolarCalc.daylight(midnight, LAT, LON, o);
        String day = y + "-" + m + "-" + d;
        near(o[0], riseMin, 3, day + " sunrise " + hm(o[0]));
        near(o[1], setMin, 3, day + " sunset " + hm(o[1]));
        near(o[2], maxEl, 0.3, day + " max elevation " + String.format("%.2f", o[2]));
        System.out.println(day + "  rise " + hm(o[0]) + "  set " + hm(o[1]) + "  max " + String.format("%.2f", o[2])
                + " at " + hm(o[3]));
    }

    /** 21 Dec, 16:30 local: the sun is down (the sky must be dusk, not day). */
    private static void winterAfternoonDark() {
        long t = SolarCalc.daysFromCivil(2025, 12, 21) * 86_400_000L - TZ_MS + 16 * 3_600_000L + 30 * 60_000L;
        double[] o = new double[2];
        SolarCalc.sun(t, LAT, LON, o);
        ok(o[0] < -2 && o[0] > -8, "Dec 21 16:30 elevation " + o[0]);
        // azimuth at solar noon (12:27 local at 37.62 E) close to south
        long noon = SolarCalc.daysFromCivil(2025, 12, 21) * 86_400_000L - TZ_MS + 12 * 3_600_000L + 27 * 60_000L;
        SolarCalc.sun(noon, LAT, LON, o);
        near(o[1], 180, 3, "Dec 21 12:27 azimuth " + o[1]);
        // summer night stays light: around local midnight on 21 Jun the sun is only ~11 degrees down
        long mid = SolarCalc.daysFromCivil(2025, 6, 22) * 86_400_000L - TZ_MS + 30 * 60_000L;
        SolarCalc.sun(mid, LAT, LON, o);
        ok(o[0] > -12 && o[0] < -8, "Jun 22 00:30 elevation " + o[0]);
    }

    private static void moonPhases() {
        double[] o = new double[4];
        SolarCalc.moon(utc(2025, 10, 7, 3, 48), LAT, LON, o);
        ok(o[2] > 0.985, "full moon 2025-10-07 k=" + o[2]);
        SolarCalc.moon(utc(2025, 10, 21, 12, 25), LAT, LON, o);
        ok(o[2] < 0.015, "new moon 2025-10-21 k=" + o[2]);
        SolarCalc.moon(utc(2025, 10, 29, 16, 21), LAT, LON, o);
        near(o[2], 0.5, 0.05, "first quarter 2025-10-29 k=" + o[2]);
        ok(o[3] > 0, "first quarter is waxing");
        SolarCalc.moon(utc(2025, 11, 12, 5, 28), LAT, LON, o);
        near(o[2], 0.5, 0.05, "last quarter 2025-11-12 k=" + o[2]);
        ok(o[3] < 0, "last quarter is waning");
        // full moon of 7 Oct 2025 seen from Moscow: low in the western sky before dawn
        SolarCalc.moon(utc(2025, 10, 7, 3, 0), LAT, LON, o);
        ok(o[0] > 0 && o[0] < 30 && o[1] > 200 && o[1] < 280, "full moon 06:00 local el=" + o[0] + " az=" + o[1]);
    }

    private static void polar() {
        double[] o = new double[4];
        long midnight = SolarCalc.daysFromCivil(2025, 6, 21) * 86_400_000L - TZ_MS;
        SolarCalc.daylight(midnight, 70.0, 30.0, o);
        ok(Double.isNaN(o[0]) && Double.isNaN(o[1]) && o[2] > 40, "polar day at 70N: no rise/set");
    }

    private static void dailyScene() {
        int n = SkyLook.SCENE_COUNT;
        int[] seen = new int[n];
        int prev = -1;
        boolean repeats = false;
        long d0 = SolarCalc.daysFromCivil(2026, 1, 1);
        for (long d = d0; d < d0 + 730; d++) {
            int s = SkyLook.autoScene(d);
            ok(s >= 0 && s < n, "auto scene in range");
            if (s == prev) repeats = true;
            if (SkyLook.autoScene(d) != s) repeats = true;
            seen[s]++;
            prev = s;
        }
        ok(!repeats, "auto scene: deterministic, never the same two days in a row");
        for (int i = 0; i < n; i++) ok(seen[i] > 100, "auto scene " + i + " used " + seen[i] + " days of 730");
    }

    // ------------------------------------------------------------------ helpers

    private static long utc(int y, int m, int d, int hh, int mm) {
        return SolarCalc.daysFromCivil(y, m, d) * 86_400_000L + hh * 3_600_000L + mm * 60_000L;
    }

    private static String hm(double min) {
        if (Double.isNaN(min)) return "--:--";
        int t = (int) Math.round(min);
        return String.format("%02d:%02d", t / 60, t % 60);
    }

    private static void near(double got, double want, double tol, String what) {
        ok(Math.abs(got - want) <= tol, what + " (want " + want + " +- " + tol + ")");
    }

    private static void ok(boolean c, String what) {
        if (c) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }
}
