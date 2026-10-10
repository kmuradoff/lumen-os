package org.z9x.projector.ak;

/**
 * Host check of the unit of the vendor's auto-keystone 109 corners ({@link AkPatternSpec#cornerScale}):
 * stock 1920 x 1080 units (measured at the 1080p UI) scaled to the view, or UI pixels when the quad
 * cannot be in the stock units (a vendor that reports the 3840 x 2160 OSD region at the 4K UI).
 * Run by sh test/uires/run.sh.
 */
public final class AkUnitsTest {
    private static int failed, passed;

    private static void eq(String what, String want, float[] got) {
        String g = got == null ? "null" : String.format(java.util.Locale.US, "%.3f,%.3f", got[0], got[1]);
        if (want.equals(g)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + g);
        }
    }

    private static float[] q(float... v) {
        return v;
    }

    public static void main(String[] a) {
        // the friend's 1080p log (2026-10-08): 109 = the 116 actual_result panel corners / 2
        float[] stock = q(86, 180, 1800, 52, 11, 1016, 1899, 1034);
        float[] panel = q(172, 360, 3600, 104, 22, 2032, 3798, 2068);
        eq("1080p UI, stock units", "1.000,1.000", AkPatternSpec.cornerScale(stock, 1920, 1080));
        eq("4K UI, stock units: x2", "2.000,2.000", AkPatternSpec.cornerScale(stock, 3840, 2160));
        eq("4K UI, UI pixels: 1:1", "1.000,1.000", AkPatternSpec.cornerScale(panel, 3840, 2160));
        eq("2K UI, stock units", "1.333,1.333", AkPatternSpec.cornerScale(stock, 2560, 1440));
        eq("2K UI, UI pixels", "1.000,1.000",
                AkPatternSpec.cornerScale(q(115, 240, 2400, 69, 15, 1355, 2532, 1379), 2560, 1440));
        eq("full stock frame at 4K", "2.000,2.000", AkPatternSpec.cornerScale(q(0, 0, 1920, 0, 0, 1080, 1920, 1080), 3840, 2160));
        eq("just past the stock frame (10 % slack)", "2.000,2.000",
                AkPatternSpec.cornerScale(q(0, 0, 2100, 0, 0, 1180, 2100, 1180), 3840, 2160));
        eq("strong keystone in 4K pixels", "1.000,1.000",
                AkPatternSpec.cornerScale(q(900, 500, 2950, 520, 1000, 1700, 2850, 1650), 3840, 2160));
        eq("only y past the stock frame", "1.000,1.000",
                AkPatternSpec.cornerScale(q(300, 200, 1800, 150, 250, 1900, 1850, 1950), 3840, 2160));
        eq("no corners: view scale", "2.000,2.000", AkPatternSpec.cornerScale(null, 3840, 2160));
        eq("wrong length: view scale", "2.000,2.000", AkPatternSpec.cornerScale(q(1, 2, 3), 3840, 2160));
        eq("1080p UI never switches", "1.000,1.000", AkPatternSpec.cornerScale(panel, 1920, 1080));

        System.out.println((failed == 0 ? "OK" : "FAILED") + ": " + passed + " passed, " + failed + " failed (ak 109 units)");
        if (failed != 0) System.exit(1);
    }
}
