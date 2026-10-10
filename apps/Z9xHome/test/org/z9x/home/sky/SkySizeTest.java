package org.z9x.home.sky;

import org.z9x.home.ui.UiScale;

/**
 * The living sky's bitmaps in the three UI modes (1.0.1): 1080p exactly as before, the soft layers (sky
 * gradient, clouds, fog) never above their 1080p size, the crisp ones (land silhouettes, moon) at the
 * real pixels, and the memory per rendered minute. Run by tools/run_tests.sh.
 */
public final class SkySizeTest {
    private static int failed, passed;
    private static final double MB = 1024.0 * 1024;

    public static void main(String[] a) {
        // 1080p: 1.0.1's layers
        int[] z = SkyRenderer.sizes(1f);
        eq(z[SkyRenderer.SKY_W] + "x" + z[SkyRenderer.SKY_H], "960x540", "1080p sky half resolution");
        eq(z[SkyRenderer.LAND_W] + "x" + z[SkyRenderer.LAND_H], "1920x620", "1080p land full resolution");
        eq(z[SkyRenderer.CLOUD_PX_W] + "x" + z[SkyRenderer.CLOUD_PX_H], "410x160", "1080p cloud half resolution");
        eq(String.valueOf(z[SkyRenderer.MOON]), "80", "1080p moon 1:1");
        eq(z[SkyRenderer.FOG_PX_W] + "x" + z[SkyRenderer.FOG_PX_H], "625x80", "1080p fog quarter resolution");
        String[] land = {"1920x620", "2560x827", "3840x1240"};
        String[] moon = {"80", "107", "160"};
        double[] maxMb = {8.5, 12.5, 23};
        for (int i = 0; i < UiScale.MODES.length; i++) {
            int w = UiScale.MODES[i][0], h = UiScale.MODES[i][1];
            float s = UiScale.scale(w, h);
            int[] y = SkyRenderer.sizes(s);
            String m = w + "x" + h + " ";
            // soft: the 1080p sizes at every mode
            ok(y[SkyRenderer.SKY_W] == z[SkyRenderer.SKY_W] && y[SkyRenderer.SKY_H] == z[SkyRenderer.SKY_H]
                    && y[SkyRenderer.CLOUD_PX_W] == z[SkyRenderer.CLOUD_PX_W] && y[SkyRenderer.FOG_PX_W] == z[SkyRenderer.FOG_PX_W],
                    m + "soft layers keep 1080p's size");
            // crisp: the land and the moon at the real pixels
            eq(y[SkyRenderer.LAND_W] + "x" + y[SkyRenderer.LAND_H], land[i], m + "land at the real resolution");
            // the land layer starts on a whole real pixel and ends at the bottom edge: drawn 1:1, never resampled
            float top = SkyRenderer.landTop(s) * s;
            ok(Math.abs(top - Math.round(top)) < 1e-3f && Math.round(top) + y[SkyRenderer.LAND_H] == h
                    && y[SkyRenderer.LAND_W] == w && SkyRenderer.landTop(s) <= Scenes.LAND_TOP,
                    m + "land 1:1 on whole pixels (top " + top + ")");
            eq(String.valueOf(y[SkyRenderer.MOON]), moon[i], m + "moon at the real resolution");
            double mb = SkyRenderer.frameBytes(s) / MB;
            ok(mb <= maxMb[i], m + String.format(java.util.Locale.ROOT, "frame %.1f MiB <= %.1f", mb, maxMb[i]));
            System.out.println(String.format(java.util.Locale.ROOT,
                    "sky %s: frame %.1f MiB (one set + its GPU copy while shown), %.1f MiB of bitmaps while the next minute renders",
                    m.trim(), mb, 2 * mb));
        }
        System.out.println("sky size tests passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void ok(boolean c, String what) {
        if (c) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    private static void eq(Object a, Object b, String what) {
        ok(a == null ? b == null : a.equals(b), what + " (got " + a + ", want " + b + ")");
    }
}
