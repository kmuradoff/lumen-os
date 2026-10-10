package org.z9x.home.ui;

/**
 * Header contrast whatever the art (1.0.1 follow-up, owner report: clock, date, search and settings
 * vanished over a white flag in the hero art). Pure Java: test/HeaderContrastTest composes these over a
 * pure white backdrop and checks every header colour for 4.5:1 (WCAG relative luminance) at 1080p, 2K
 * and 4K.
 * <ul>
 *   <li>the whole header (mark, tabs, update badge, search, settings, clock: y 54-138) lies over the
 *   header scrim: full width, ground at {@link #HEAD_ALPHA} from the top edge down to y {@link #HEAD_HOLD},
 *   then an eased fade to nothing at y {@link #HEAD_END}. It replaces the follow-up's plate behind the
 *   end-side cluster (owner: it looked like a dark box) and its header band over the stage. Drawn under
 *   the pages by the sky (every Home state, under the veil of the other tabs) and by the hero's stage
 *   (under its framed card, which must not be dimmed);</li>
 *   <li>the mark and the tabs over hero art also get the stage's start-side scrim (D_Home).</li>
 * </ul>
 */
public final class Scrims {
    private Scrims() {}

    /** Header scrim: ground at this alpha behind the header (TEXT2 tabs 5.0:1 over white, 0.72 would be 4.65). */
    public static final float HEAD_ALPHA = 0.74f;
    /** Header scrim, design px: the hold (every header item ends above it) and the end of the fade. */
    public static final int HEAD_HOLD = 140, HEAD_END = 340;
    /** Fade samples of the header scrim's gradient (a smoothstep drawn as 10 linear pieces). */
    private static final int HEAD_STEPS = 10;
    /**
     * The header scrim as gradient stops over y 0 .. {@link #HEAD_END}: positions (fractions of HEAD_END)
     * and alphas. The fade is a smoothstep, flat where it meets the hold and where it ends, so the scrim
     * has no visible edge (a linear fade shows a line where its slope changes).
     */
    public static final float[] HEAD_POS = new float[HEAD_STEPS + 2], HEAD_A = new float[HEAD_STEPS + 2];

    static {
        HEAD_POS[0] = 0f;
        HEAD_A[0] = HEAD_ALPHA;
        for (int i = 0; i <= HEAD_STEPS; i++) {
            float y = HEAD_HOLD + (HEAD_END - HEAD_HOLD) * i / (float) HEAD_STEPS;
            HEAD_POS[i + 1] = y / HEAD_END;
            HEAD_A[i + 1] = headAlpha(y);
        }
    }

    /** Header scrim alpha at y (design px), the smooth curve. */
    public static float headAlpha(float y) {
        if (y <= HEAD_HOLD) return HEAD_ALPHA;
        if (y >= HEAD_END) return 0f;
        float t = (y - HEAD_HOLD) / (HEAD_END - HEAD_HOLD);
        return HEAD_ALPHA * (1f - t * t * (3f - 2f * t));
    }

    /** Header scrim alpha at y (design px) as the gradient draws it (linear between the stops). */
    public static float headDrawn(float y) {
        float f = y / HEAD_END;
        if (f <= 0f) return HEAD_A[0];
        for (int i = 1; i < HEAD_POS.length; i++) {
            if (f <= HEAD_POS[i]) {
                float t = (f - HEAD_POS[i - 1]) / Math.max(1e-6f, HEAD_POS[i] - HEAD_POS[i - 1]);
                return HEAD_A[i - 1] + (HEAD_A[i] - HEAD_A[i - 1]) * t;
            }
        }
        return 0f;
    }

    /**
     * Alpha (times {@link #HEAD_ALPHA}'s gradient) of the stage's header scrim while its art covers the
     * sky by {@code art} (0..1: a crossfade to or from no art; 1 otherwise). The sky under the art has
     * the same scrim already, so the art's share needs all of it and the sky's none: with this alpha a
     * white picture over a white sky is exactly as dark as under the scrim alone at every step of the fade
     * (art 0.5: 0.79; the art's own 0.5 left the tabs near 3:1 mid-fade), smoothly from 0 to 1.
     */
    public static float headOverArt(float art) {
        if (art >= 1f) return 1f;
        float a = Math.max(0f, art);
        return a / (1f - HEAD_ALPHA + HEAD_ALPHA * a);
    }

    /** D_Home start-side scrim: linear-gradient(90deg, .94 0%, .8 27%, .22 57%, 0 76%). */
    public static final float[] SIDE_ALPHA = {0.94f, 0.8f, 0.22f, 0f};
    public static final float[] SIDE_POS = {0f, 0.27f, 0.57f, 0.76f};

    /** Start-side scrim alpha at a fraction of the width from the start edge. */
    public static float sideAlpha(float frac) {
        if (frac <= SIDE_POS[0]) return SIDE_ALPHA[0];
        for (int i = 1; i < SIDE_POS.length; i++) {
            if (frac <= SIDE_POS[i]) {
                float t = (frac - SIDE_POS[i - 1]) / (SIDE_POS[i] - SIDE_POS[i - 1]);
                return SIDE_ALPHA[i - 1] + (SIDE_ALPHA[i] - SIDE_ALPHA[i - 1]) * t;
            }
        }
        return SIDE_ALPHA[SIDE_ALPHA.length - 1];
    }

    /** Opaque colour of {@code color} at {@code alpha} over opaque {@code under}. */
    public static int over(int color, float alpha, int under) {
        int r = mix((color >> 16) & 0xFF, (under >> 16) & 0xFF, alpha);
        int g = mix((color >> 8) & 0xFF, (under >> 8) & 0xFF, alpha);
        int b = mix(color & 0xFF, under & 0xFF, alpha);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int mix(int a, int b, float t) {
        return Math.round(a * t + b * (1f - t));
    }

    /** WCAG 2 relative luminance of an opaque sRGB colour. */
    public static double luminance(int c) {
        return 0.2126 * lin((c >> 16) & 0xFF) + 0.7152 * lin((c >> 8) & 0xFF) + 0.0722 * lin(c & 0xFF);
    }

    private static double lin(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** WCAG contrast ratio of two opaque colours (1 .. 21). */
    public static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }
}
