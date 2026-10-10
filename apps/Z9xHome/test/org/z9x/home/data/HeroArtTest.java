package org.z9x.home.data;

import org.z9x.home.img.HeroArt;
import org.z9x.home.ui.UiScale;

/**
 * Hero art layout by real pixels (1.0.1 follow-up, owner report: a small Kinopoisk thumbnail stretched
 * full-bleed and centre-cropped). Screen 1920x1080; card boxes as Stage: 16:9 640 wide, square 420,
 * poster 510 high. Then the same rules in real pixels of the 2K (the default) and 4K UI modes: boxes
 * 853 / 560 / 680 and 1280 / 840 / 1020, art masters at most 2560x1440, cards enlarged at most 1.25x.
 */
public final class HeroArtTest {
    private static final int SW = 1920, SH = 1080, WIDE = 640, SQ = 420, POSTER = 510;

    private static HeroArt.Plan plan(int... sizes) {
        return HeroArt.choose(sizes, SW, SH, WIDE, SQ, POSTER);
    }

    public static void run() {
        // full-size 16:9 art fills the backdrop
        HeroArt.Plan p = plan(1920, 1080);
        T.eq(p.mode, HeroArt.FILL, "1920x1080 fills");
        T.eq(p.w + "x" + p.h, "1920x1080", "fill decodes at the screen");
        T.eq(plan(1600, 900).mode, HeroArt.FILL, "1600x900 fills (1.2x)");
        T.eq(plan(1920, 1200).mode, HeroArt.FILL, "16:10 at 1920 fills (cropped from the top)");
        T.eq(plan(1536, 864).mode, HeroArt.FILL, "1.25x is the most");
        // smaller than ~1600 px: never stretched full-bleed
        p = plan(1280, 720);
        T.eq(p.mode, HeroArt.CARD, "1280x720 is a card");
        T.eq(p.shape, HeroArt.WIDE, "landscape: a 16:9 card");
        T.eq(p.w + "x" + p.h, "640x360", "card box, downscaled");
        p = plan(480, 270);
        T.eq(p.w + "x" + p.h, "480x270", "a small thumbnail is a card at its own size, never enlarged");
        T.eq(plan(1500, 844).mode, HeroArt.CARD, "1.28x enlargement is too much");
        // other shapes never fill, even when large
        p = plan(2000, 1500);
        T.eq(p.mode, HeroArt.CARD, "4:3 does not fill");
        T.eq(p.shape, HeroArt.WIDE, "4:3 is landscape: 16:9 card (top crop)");
        T.ok(p.w <= 2000 && p.h <= 1500 && p.w * 9 == p.h * 16, "16:9 card within the art");
        T.eq(plan(1920, 803).mode, HeroArt.CARD, "scope 2.39:1 does not fill");
        p = plan(640, 960);
        T.eq(p.mode, HeroArt.CARD, "portrait: card");
        T.eq(p.shape, HeroArt.POSTER, "portrait: poster card");
        T.eq(p.w + "x" + p.h, "340x510", "poster box");
        p = plan(200, 300);
        T.eq(p.w + "x" + p.h, "200x300", "small poster at its own size");
        p = plan(600, 600);
        T.eq(p.shape, HeroArt.SQUARE, "square art: square card");
        T.eq(p.w + "x" + p.h, "420x420", "square box");
        // the larger of poster art and thumbnail
        p = plan(640, 960, 1920, 1080);
        T.eq(p.mode, HeroArt.FILL, "a fill-size thumbnail beats the poster");
        T.eq(p.index, 1, "the thumbnail");
        p = plan(600, 900, 480, 270);
        T.eq(p.index, 0, "a poster card beats a smaller 16:9 card");
        p = plan(300, 450, 960, 540);
        T.eq(p.index, 1, "a bigger 16:9 card beats a small poster");
        T.eq(p.w + "x" + p.h, "640x360", "16:9 card");
        p = plan(1920, 1080, 2560, 1440);
        T.eq(p.index, 1, "two that fill: the larger source");
        p = plan(1280, 720, 1280, 720);
        T.eq(p.index, 0, "a tie keeps the row's own art");
        // failures
        T.eq(plan(0, 0).mode, HeroArt.NONE, "nothing fetched: no art (the sky)");
        p = plan(0, 0, 1280, 720);
        T.eq(p.index, 1, "a failed candidate is skipped");
        // never more than the art's own pixels for a card
        for (int w = 100; w <= 2000; w += 37) {
            for (int h = 100; h <= 2000; h += 41) {
                HeroArt.Plan q = plan(w, h);
                if (q.mode != HeroArt.CARD) continue;
                if (q.w > w || q.h > h) {
                    T.ok(false, "card " + q + " larger than its art " + w + "x" + h);
                    return;
                }
            }
        }
        T.ok(true, "cards never enlarge their art");
        modes();
    }

    /** Stage's boxes for a mode, real px (no row pushing the band up). */
    private static HeroArt.Plan planAt(int sw, int sh, int... sizes) {
        float s = UiScale.scale(sw, sh);
        return HeroArt.choose(sizes, sw, sh, UiScale.px(640, s), UiScale.px(420, s), UiScale.px(510, s), UiScale.cardEnlarge(s));
    }

    private static void modes() {
        // 2K: the default
        T.eq(UiScale.cardEnlarge(1f), 1f, "1080p cards never enlarge (as 1.0.1)");
        T.eq(UiScale.cardEnlarge(UiScale.scale(2560, 1440)), 1.25f, "2K cards: at most 1.25x");
        HeroArt.Plan p = planAt(2560, 1440, 1920, 1080);
        T.eq(p.mode, HeroArt.CARD, "2K: 1920x1080 art would be enlarged 1.33x full-bleed: a card");
        T.eq(p.w + "x" + p.h, "853x480", "2K: 16:9 card box in real px (640x360 design)");
        p = planAt(2560, 1440, 2560, 1440);
        T.eq(p.mode, HeroArt.FILL, "2K: 2560x1440 art fills");
        T.eq(p.w + "x" + p.h, "2560x1440", "2K: fill at the real screen");
        T.eq(planAt(2560, 1440, 2048, 1152).mode, HeroArt.FILL, "2K: 2048 px fills (1.25x)");
        T.eq(planAt(2560, 1440, 2000, 1125).mode, HeroArt.CARD, "2K: 2000 px is a card (1.28x)");
        p = planAt(2560, 1440, 640, 360);
        T.eq(p.w + "x" + p.h, "800x450", "2K: a 640x360 thumbnail at 1.25x (94 % of its 1080p size)");
        p = planAt(2560, 1440, 640, 960);
        T.eq(p.shape, HeroArt.POSTER, "2K: portrait master: poster");
        T.eq(p.w + "x" + p.h, "453x680", "2K: poster box (340x510 design)");
        // 4K: masters stop at 2560x1440, so nothing fills; cards up to 1280x720
        int mw = UiScale.masterW(3840, 2160), mh = UiScale.masterH(3840, 2160);
        T.eq(mw + "x" + mh, "2560x1440", "4K: masters at most 2560x1440");
        p = planAt(3840, 2160, mw, mh);
        T.eq(p.mode, HeroArt.CARD, "4K: a 2560x1440 master would be 1.5x full-bleed: a card");
        T.eq(p.w + "x" + p.h, "1280x720", "4K: 16:9 card 1280x720 real px, decoded 1:1");
        T.eq(planAt(3840, 2160, 3072, 1728).mode, HeroArt.FILL, "4K: the rule itself: 3072 px would fill (1.25x)");
        p = planAt(3840, 2160, 640, 360);
        T.eq(p.w + "x" + p.h, "800x450", "4K: a 640x360 thumbnail at 1.25x, no more");
        p = planAt(3840, 2160, 600, 600);
        T.eq(p.w + "x" + p.h, "750x750", "4K: square art at 1.25x (box 840)");
        p = planAt(3840, 2160, 640, 960);
        T.eq(p.w + "x" + p.h, "680x1020", "4K: poster box (1.06x of a 640x960 master)");
        // 1080p: the masters and boxes of 1.0.1
        T.eq(UiScale.masterW(1920, 1080) + "x" + UiScale.masterH(1920, 1080), "1920x1080", "1080p masters as 1.0.1");
        T.eq(planAt(1920, 1080, 640, 360).w + "x" + planAt(1920, 1080, 640, 360).h, "640x360", "1080p: own pixels, as 1.0.1");
        // every mode: a card at most 1.25x its art and within its box; fill only within 1.25x of the screen
        for (int[] m : UiScale.MODES) {
            float s = UiScale.scale(m[0], m[1]);
            float e = UiScale.cardEnlarge(s);
            int wide = UiScale.px(640, s), sq = UiScale.px(420, s), poster = UiScale.px(510, s);
            for (int w = 100; w <= 2560; w += 53) {
                for (int h = 100; h <= 2560; h += 47) {
                    HeroArt.Plan q = planAt(m[0], m[1], w, h);
                    boolean bad = q.mode == HeroArt.CARD && (q.w > w * e + 0.5f || q.h > h * e + 0.5f
                            || q.w > Math.max(wide, sq) || q.h > Math.max(poster, Math.max(sq, wide * 9 / 16)));
                    bad |= q.mode == HeroArt.FILL && Math.max(m[0] / (float) w, m[1] / (float) h) > HeroArt.MAX_UPSCALE + 1e-4f;
                    bad |= q.mode == HeroArt.CARD && e == 1f && (q.w > w || q.h > h);
                    if (bad) {
                        T.ok(false, m[0] + "x" + m[1] + ": " + q + " for art " + w + "x" + h);
                        return;
                    }
                }
            }
            T.ok(true, m[0] + "x" + m[1] + ": nothing on the stage enlarged over 1.25x (real px), cards within their boxes");
        }
    }
}
