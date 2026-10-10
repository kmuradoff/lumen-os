package org.z9x.home.ui;

import org.z9x.home.img.HeroArt;

/**
 * The UI resolution of Lumen OS 1.0.1 (owner: 1080p, 2K = the default, or 4K; always 960x540 dp, at
 * 320 / 427 / 640 dpi) and the size rules that depend on real pixels. Pure Java (test/ResolutionTest).
 * Layout sizes are design px of the 1920x1080 frame ({@link Theme#px}: times the real width / 1920, half
 * a dp), so every mode shows the same layout, only sharper. What must not simply scale lives here:
 * <ul>
 *   <li>soft layers (blurred art, focus glows and icon shadows, the sky's gradient, clouds and fog) are
 *   rendered at most at the 1080p scale and enlarged by the GPU: more pixels would show nothing new and
 *   cost 1.8x (2K) or 4x (4K) the memory and the CPU;</li>
 *   <li>crisp layers (the sky's land silhouettes, the moon) are rendered at the real scale;</li>
 *   <li>art is decoded for its real pixels on screen and never shown enlarged more than 1.25x; the disk
 *   masters cover the screen up to 2560x1440 (a full-bleed 4K hero bitmap would be 33 MB);</li>
 *   <li>the memory budgets grow with the pixel count, capped.</li>
 * </ul>
 */
public final class UiScale {
    private UiScale() {}

    public static final int DESIGN_W = 1920, DESIGN_H = 1080;
    /** {width, height, dpi} of the three UI modes (same 960x540 dp); 2K is the default. */
    public static final int[][] MODES = {{1920, 1080, 320}, {2560, 1440, 427}, {3840, 2160, 640}};
    /** Soft layers are rendered at most at this scale (1080p). */
    public static final float SOFT_MAX = 1f;
    /** Crisp layers are rendered at the real scale, up to 4K. */
    public static final float CRISP_MAX = 2f;
    /** Landscape art masters (and so the full-bleed hero) cover the screen, at most this. */
    public static final int ART_MAX_W = 2560, ART_MAX_H = 1440;
    private static final long MB = 1024L * 1024;
    /** Image memory (LRU of decoded art): 1.0.1's 30 MB at 1080p, growing with the pixel count, at most 64 MB. */
    public static final long IMAGE_MEM_1080 = 30 * MB, IMAGE_MEM_MAX = 64 * MB;
    /** Disk cache of masters and app art: 1.0.1's 64 MB at 1080p, growing as the masters do (up to 2560x1440). */
    public static final long DISK_1080 = 64 * MB;

    /** Real px per design px of a display. */
    public static float scale(int w, int h) {
        return Math.max(w, h) / (float) DESIGN_W;
    }

    public static int px(float design, float scale) {
        return Math.round(design * scale);
    }

    /** Render scale of a soft layer (real px per design px). */
    public static float soft(float scale) {
        return Math.min(SOFT_MAX, scale);
    }

    /** Render scale of a crisp layer (real px per design px). */
    public static float crisp(float scale) {
        return Math.min(CRISP_MAX, scale);
    }

    /** Pixel count of a display relative to 1080p. */
    public static float area(int w, int h) {
        return (float) ((double) w * h / ((double) DESIGN_W * DESIGN_H));
    }

    /**
     * How much the hero's framed card may enlarge its art (real pixels): 1 at 1080p (1.0.1: never), at
     * 2K and 4K up to the hero's {@link HeroArt#MAX_UPSCALE}, so a small picture keeps about its 1080p
     * size on screen (2K: 94 %) instead of shrinking to its own pixels.
     */
    public static float cardEnlarge(float scale) {
        return Math.max(1f, Math.min(HeroArt.MAX_UPSCALE, scale));
    }

    /** Width of a landscape master for this display: covers it, at most {@link #ART_MAX_W}. */
    public static int masterW(int w, int h) {
        return Math.min(ART_MAX_W, Math.max(w, h));
    }

    public static int masterH(int w, int h) {
        return Math.min(ART_MAX_H, Math.min(w, h));
    }

    /** Budget of the decoded-art LRU: 30 MB at 1080p, 53 MB at 2K, 64 MB at 4K. */
    public static long imageMemBytes(int w, int h) {
        long b = Math.round(IMAGE_MEM_1080 * (double) area(w, h));
        return Math.max(IMAGE_MEM_1080, Math.min(IMAGE_MEM_MAX, b));
    }

    /** Budget of the disk cache: 64 MB at 1080p, 114 MB at 2K and 4K (the masters stop at 2560x1440). */
    public static long diskBytes(int w, int h) {
        double a = Math.min(area(w, h), area(ART_MAX_W, ART_MAX_H));
        return Math.max(DISK_1080, Math.round(DISK_1080 * a));
    }
}
