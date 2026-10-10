package org.z9x.home.ui;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The warm focus glow of D (box-shadow 0 0 50-70 px of the accent around a focused tile or button) and
 * the soft shadow under the header's icons (1.0.1: the header has no plate any more). A blurred shape is
 * rendered ONCE per size into a small ALPHA_8 bitmap (software blur, off the draw path) and then drawn
 * tinted with the paint colour, so focusing never blurs in onDraw. Soft by nature, the bitmaps are
 * rendered at most at the 1080p scale ({@link Theme#softFactor}) and enlarged when drawn at 2K and 4K:
 * the same memory and blur time as at 1080p. Main thread only.
 */
public final class Glow {
    private Glow() {}

    private static final int MAX = 10;
    private static final Map<String, Bitmap> CACHE = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            return size() > MAX;
        }
    };
    private static final Map<String, Shadow> SHADOWS = new LinkedHashMap<>();

    /**
     * Mask for a w x h shape with corner radius r and blur radius b (all real px); the bitmap covers
     * (w + 2b) x (h + 2b) real px from (-b, -b) relative to the shape: draw it into that box with
     * {@link #box} (it may have fewer pixels than the box, see {@link Theme#softFactor}).
     */
    public static Bitmap mask(int w, int h, float r, int b) {
        if (w <= 0 || h <= 0) return null;
        float k = Theme.softFactor();
        int bw = Math.max(1, Math.round((w + 2 * b) * k)), bh = Math.max(1, Math.round((h + 2 * b) * k));
        String key = w + "x" + h + "r" + Math.round(r) + "b" + b + "@" + bw + "x" + bh;
        Bitmap bmp = CACHE.get(key);
        if (bmp != null) return bmp;
        try {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8);
            Canvas c = new Canvas(bmp);
            c.scale(bw / (float) (w + 2 * b), bh / (float) (h + 2 * b)); // the blur follows the canvas scale
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF000000);
            p.setMaskFilter(new BlurMaskFilter(Math.max(1f, b * 0.8f), BlurMaskFilter.Blur.NORMAL));
            c.drawRoundRect(new RectF(b, b, b + w, b + h), r, r, p);
            bmp.prepareToDraw();
            CACHE.put(key, bmp);
        } catch (Throwable t) {
            bmp = null;
        }
        return bmp;
    }

    /** The box a {@link #mask} of a w x h shape with blur b is drawn into (real px, relative to the shape). */
    public static void box(RectF out, int w, int h, int b) {
        out.set(-b, -b, w + b, h + b);
    }

    /** A soft icon shadow: an ALPHA_8 bitmap and where it goes, relative to the icon (real px). */
    public static final class Shadow {
        public final Bitmap bmp;
        public final float x, y, w, h;

        Shadow(Bitmap bmp, float x, float y, float w, float h) {
            this.bmp = bmp;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /**
     * Soft shadow of an icon drawable ({@code res} names it in the cache) drawn at size x size real px,
     * blurred by {@code blur} real px; null if it cannot be made. Kept for the process (a few header icons).
     */
    public static Shadow iconShadow(Drawable d, int res, int size, float blur) {
        if (d == null || res == 0 || size <= 0) return null;
        float k = Theme.softFactor();
        int n = Math.max(1, Math.round(size * k));
        String key = res + "s" + size + "b" + Math.round(blur) + "@" + n;
        Shadow s = SHADOWS.get(key);
        if (s != null) return s;
        Bitmap src = null;
        try {
            src = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(src);
            Drawable dd = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            dd.setBounds(0, 0, n, n);
            dd.draw(c);
            Paint p = new Paint();
            p.setMaskFilter(new BlurMaskFilter(Math.max(1f, blur * k), BlurMaskFilter.Blur.NORMAL));
            int[] off = new int[2];
            Bitmap a = src.extractAlpha(p, off); // grows by the blur; off tells where it starts
            a.prepareToDraw();
            float inv = size / (float) n; // back to real px
            s = new Shadow(a, off[0] * inv, off[1] * inv, a.getWidth() * inv, a.getHeight() * inv);
            SHADOWS.put(key, s);
        } catch (Throwable t) {
            s = null;
        } finally {
            if (src != null) src.recycle();
        }
        return s;
    }

    public static void trim() {
        CACHE.clear();
    }
}
