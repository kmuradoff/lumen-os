package org.z9x.home.img;

/**
 * How the hero shows its art (1.0.1 follow-up; pure Java, test/HeroArtTest). The owner saw a small
 * Kinopoisk thumbnail stretched over the whole 1920x1080 backdrop (soft, pixelated) and centre-cropped
 * (the top of the picture missing). The choice is now made from the art's real pixels: the size of the
 * disk master, which is the source itself unless that is larger than the screen (up to 2560x1440,
 * {@link org.z9x.home.ui.UiScale#ART_MAX_W}; portrait sources are kept at 640x960 at most). Every size
 * here is in real pixels of the UI mode (1080p, 2K or 4K), never in design px.
 * <ul>
 *   <li>FILL: landscape art of a 16:9-ish shape (1.6:1 .. 2:1) that covers the real screen with at most
 *   1.25x enlargement fills the backdrop, cropped from the top (faces and title art at the top are
 *   never cut): a source of 1536 px or more at 1080p, 2048 at 2K; at 4K none (masters stop at 2560);</li>
 *   <li>CARD: everything else (smaller, portrait, square, 4:3, scope): a blurred, darkened copy as the
 *   backdrop and the art itself as a framed card at no more than its own size (at 2K and 4K up to
 *   {@code enlarge}, at most 1.25x): 16:9 for landscape art, a 2:3 poster for portrait art, a square for
 *   art in between (album covers).</li>
 * </ul>
 * With two candidates (poster art and thumbnail of one program) art that fills wins, else the one whose
 * card is the largest on screen; a tie keeps the first (the art the rows use).
 */
public final class HeroArt {
    private HeroArt() {}

    public static final int NONE = 0, FILL = 1, CARD = 2;
    public static final int WIDE = 0, SQUARE = 1, POSTER = 2;
    /** The most a picture shown as sharp may be enlarged. */
    public static final float MAX_UPSCALE = 1.25f;
    public static final float FILL_MIN_RATIO = 1.6f, FILL_MAX_RATIO = 2.0f;
    /** Card shape by the art's ratio: wider than 1.25:1 is landscape, narrower than 0.8:1 portrait. */
    static final float WIDE_MIN_RATIO = 1.25f, POSTER_MAX_RATIO = 0.8f;

    public static final class Plan {
        public int mode = NONE;
        /** Index of the chosen candidate. */
        public int index = -1;
        public int shape = WIDE;
        /**
         * Size on screen in real px: the screen (FILL) or the card (CARD: at most enlarge x the art's
         * pixels). The art is decoded for this box and never above its own pixels (ImageLoader.loadHero).
         */
        public int w, h;

        @Override
        public String toString() {
            return mode == NONE ? "none" : (mode == FILL ? "fill" : "card" + (shape == WIDE ? "16:9" : shape == SQUARE ? "1:1" : "2:3"))
                    + " " + w + "x" + h + " #" + index;
        }
    }

    /**
     * @param sizes      {w0, h0, w1, h1, ...} real pixels of each candidate (0 = unknown or failed)
     * @param screenW    backdrop size in px
     * @param wideMaxW   largest 16:9 card width in px
     * @param squareMax  largest square card side in px
     * @param posterMaxH largest poster card height in px
     */
    public static Plan choose(int[] sizes, int screenW, int screenH, int wideMaxW, int squareMax, int posterMaxH) {
        return choose(sizes, screenW, screenH, wideMaxW, squareMax, posterMaxH, 1f);
    }

    /**
     * @param enlarge how much a card may enlarge its art: 1 at 1080p, up to {@link #MAX_UPSCALE} at 2K
     *                and 4K (UiScale.cardEnlarge)
     */
    public static Plan choose(int[] sizes, int screenW, int screenH, int wideMaxW, int squareMax, int posterMaxH,
                              float enlarge) {
        float e = Math.max(1f, Math.min(MAX_UPSCALE, enlarge));
        Plan best = new Plan();
        long bestArea = -1;
        boolean bestFill = false;
        for (int i = 0; i + 1 < sizes.length; i += 2) {
            int w = sizes[i], h = sizes[i + 1];
            if (w <= 0 || h <= 0) continue;
            boolean fill = fills(w, h, screenW, screenH);
            Plan p = fill ? fillPlan(screenW, screenH) : card(w, h, wideMaxW, squareMax, posterMaxH, e);
            if (p.w <= 0 || p.h <= 0) continue;
            // filling art: the larger source; cards: the larger card on screen
            long area = fill ? (long) w * h : (long) p.w * p.h;
            boolean better = best.mode == NONE || (fill && !bestFill) || (fill == bestFill && area > bestArea);
            if (better) {
                p.index = i / 2;
                best = p;
                bestArea = area;
                bestFill = fill;
            }
        }
        return best;
    }

    /** 16:9-ish and big enough to cover the screen with at most {@link #MAX_UPSCALE}. */
    public static boolean fills(int w, int h, int screenW, int screenH) {
        if (w <= 0 || h <= 0 || screenW <= 0 || screenH <= 0) return false;
        float ratio = w / (float) h;
        if (ratio < FILL_MIN_RATIO || ratio > FILL_MAX_RATIO) return false;
        float cover = Math.max(screenW / (float) w, screenH / (float) h);
        return cover <= MAX_UPSCALE;
    }

    private static Plan fillPlan(int screenW, int screenH) {
        Plan p = new Plan();
        p.mode = FILL;
        p.w = screenW;
        p.h = screenH;
        return p;
    }

    /**
     * The card for art of w x h: its shape by the art's ratio, at most the box and at most {@code e}
     * times the art's pixels (e = 1: never enlarged).
     */
    static Plan card(int w, int h, int wideMaxW, int squareMax, int posterMaxH, float e) {
        Plan p = new Plan();
        p.mode = CARD;
        float ratio = w / (float) h;
        int ew = e == 1f ? w : (int) Math.floor(w * e), eh = e == 1f ? h : (int) Math.floor(h * e);
        if (ratio >= WIDE_MIN_RATIO) {
            p.shape = WIDE;
            p.w = Math.min(wideMaxW, Math.min(ew, (int) Math.floor(eh * 16f / 9f)));
            p.h = Math.min(eh, Math.round(p.w * 9f / 16f));
        } else if (ratio <= POSTER_MAX_RATIO) {
            p.shape = POSTER;
            p.h = Math.min(posterMaxH, Math.min(eh, (int) Math.floor(ew * 3f / 2f)));
            p.w = Math.min(ew, Math.round(p.h * 2f / 3f));
        } else {
            p.shape = SQUARE;
            p.w = p.h = Math.min(squareMax, Math.min(ew, eh));
        }
        return p;
    }
}
