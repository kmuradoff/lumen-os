package org.z9x.home.data;

import org.z9x.home.img.HeroArt;
import org.z9x.home.ui.Scrims;
import org.z9x.home.ui.Theme;
import org.z9x.home.ui.UiScale;

/**
 * The three UI modes of 1.0.1 (1080p @ 320, 2K @ 427 = the default, 4K @ 640; the same 960x540 dp):
 * Home's design px land on the same places in real px (with each mode's rounding), the TV safe area
 * holds, art is decoded for real pixels, and the memory budgets stay bounded. Theme's constants are
 * compile-time ints (read without loading Theme, whose interpolators need Android).
 */
public final class ResolutionTest {
    private static final long MB = 1024L * 1024;

    public static void run() {
        int[][] modes = UiScale.MODES;
        T.eq(modes.length, 3, "three UI modes");
        T.eq(modes[0][0] + "x" + modes[0][1], "1920x1080", "1080p");
        T.eq(modes[1][0] + "x" + modes[1][1], "2560x1440", "2K");
        T.eq(modes[2][0] + "x" + modes[2][1], "3840x2160", "4K");
        String[] safe = {"54", "72", "108"};
        String[] rowY = {"740", "987", "1480"};
        String[] card = {"384x216", "512x288", "768x432"};
        String[] heroCard = {"640x360", "853x480", "1280x720"};
        for (int i = 0; i < modes.length; i++) {
            int w = modes[i][0], h = modes[i][1], dpi = modes[i][2];
            float s = UiScale.scale(w, h);
            float density = dpi / 160f;
            String m = w + "x" + h + " ";
            // the same layout in dp: a design px is half a dp in every mode (427 dpi: 0.1 % off)
            T.ok(Math.abs(s * 2f / density - 1f) < 0.002f, m + "design px = half a dp (scale " + s + ", density " + density + ")");
            // 960x540 dp (427 dpi: 959.3 x 539.6, the nearest whole dpi to 426.67)
            float dpW = UiScale.DESIGN_W * s / density, dpH = UiScale.DESIGN_H * s / density;
            T.ok(Math.abs(dpW - 960f) < 1f && Math.abs(dpH - 540f) < 1f, m + "960x540 dp (got " + dpW + "x" + dpH + ")");
            // TV safe area: 5 % of the height
            int sa = UiScale.px(Theme.SAFE, s);
            T.eq(String.valueOf(sa), safe[i], m + "safe area px");
            T.ok(sa >= h * 0.05f - 0.5f, m + "safe area >= 5 % of the height");
            // "Continue watching" at y 740: the focused 16:9 card with its scale and ring ends inside the
            // safe area (RowView.focusExtent for a Watch Next row; the ring is 4 dp)
            int y = UiScale.px(Theme.FIRST_ROW_Y, s);
            T.eq(String.valueOf(y), rowY[i], m + "first row y");
            int ch = UiScale.px(216, s);
            int top = y + UiScale.px(Theme.ROW_TITLE_H + Theme.ROW_TITLE_GAP, s);
            int bottom = top + ch + Math.round(ch * (Theme.FOCUS_SCALE - 1f) / 2f) + (int) Math.ceil(4f * density * Theme.FOCUS_SCALE);
            T.ok(bottom <= h - sa, m + "focused card ends at y " + bottom + " <= " + (h - sa));
            // the header (y 54-138) inside the safe area and in the scrim's hold
            T.ok(UiScale.px(54, s) >= sa && UiScale.px(138, s) <= UiScale.px(Scrims.HEAD_HOLD, s), m + "header in the safe area and the scrim's hold");
            // art at real pixels
            T.eq(UiScale.px(384, s) + "x" + UiScale.px(216, s), card[i], m + "16:9 row card art px (Theme.artW/artH)");
            T.eq(UiScale.px(640, s) + "x" + UiScale.px(360, s), heroCard[i], m + "hero 16:9 card box px");
            int mw = UiScale.masterW(w, h), mh = UiScale.masterH(w, h);
            T.ok(mw <= UiScale.ART_MAX_W && mh <= UiScale.ART_MAX_H && mw <= w && mh <= h, m + "masters within the screen and 2560x1440");
            // soft layers stay at 1080p's pixels, crisp ones follow the screen
            T.ok(UiScale.soft(s) == 1f && UiScale.crisp(s) == s, m + "soft layers at 1080p scale, crisp at " + s);
            // memory: the decoded-art budget holds the hero plus 16 posters and 4 banners
            long budget = UiScale.imageMemBytes(w, h);
            // the biggest hero bitmap: full-bleed from a screen-covering master, else (4K) the 16:9 card
            boolean fills = w <= mw * HeroArt.MAX_UPSCALE;
            long hero = fills ? (long) mw * mh * 4 : UiScale.px(640, s) * (long) UiScale.px(360, s) * 4;
            long need = hero + 16L * UiScale.px(208, s) * UiScale.px(312, s) * 4 + 4L * UiScale.px(272, s) * UiScale.px(153, s) * 4;
            T.ok(need <= budget, m + "image budget " + mb(budget) + " MiB holds hero + 16 posters + 4 banners (" + mb(need) + " MiB)");
            T.ok(budget <= 64 * MB, m + "image budget at most 64 MB");
            long disk = UiScale.diskBytes(w, h);
            T.ok(disk >= 64 * MB && disk <= 128 * MB, m + "disk cache " + mb(disk) + " MiB");
            System.out.println("mode " + m + "scale " + s + ": safe " + sa + " px, row card " + card[i] + ", hero card "
                    + heroCard[i] + ", masters " + mw + "x" + mh + ", hero bitmap <= " + mb(hero) + " MiB, image budget "
                    + mb(budget) + " MiB, disk " + mb(disk) + " MiB");
        }
        // 1080p keeps 1.0.1's numbers
        T.eq(UiScale.imageMemBytes(1920, 1080), 30 * MB, "1080p image budget 30 MB (1.0.1)");
        T.eq(UiScale.diskBytes(1920, 1080), 64 * MB, "1080p disk cache 64 MB (1.0.1)");
        T.eq(UiScale.imageMemBytes(3840, 2160), 64 * MB, "4K image budget capped at 64 MB");
    }

    private static String mb(long b) {
        return String.format(java.util.Locale.ROOT, "%.1f", b / (double) MB);
    }
}
