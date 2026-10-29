package org.lumen.home.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * The look of a program without art (Lumen OS 1.0.5; the Kino player's Continue watching cards have none
 * yet): never a flat block. The card is a diagonal wash of its placeholder colour, lighter at the top
 * start corner and deeper at the bottom end (its title on it, CardView); the backdrop is the same colour
 * as a soft glow towards the top end corner over the ground, a 192x108 bitmap the GPU enlarges (smooth,
 * no banding to speak of under the scrims' dither).
 */
final class Tint {
    private Tint() {}

    private static final int BACK_W = 192, BACK_H = 108;
    private static int sBackColor;
    private static Bitmap sBack;

    /** {@code c} lightened (f > 1) or deepened (f < 1) per channel, opaque. */
    static int scale(int c, float f) {
        int r = Math.min(255, Math.round(((c >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((c >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((c & 0xFF) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The card's wash for a w x h card (real px). */
    static Shader card(int color, int w, int h, boolean rtl) {
        int a = scale(color, 1.55f), b = scale(color, 0.7f);
        return rtl ? new LinearGradient(w, 0, 0, h, a, b, Shader.TileMode.CLAMP)
                : new LinearGradient(0, 0, w, h, a, b, Shader.TileMode.CLAMP);
    }

    /** The backdrop for a program without art (the last one is kept: the same colour costs nothing). */
    static Bitmap backdrop(int color, int ground) {
        if (sBack != null && sBackColor == color) return sBack;
        Bitmap bm = Bitmap.createBitmap(BACK_W, BACK_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawColor(ground);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        int glow = scale(color, 1.9f);
        p.setShader(new RadialGradient(BACK_W * 0.72f, BACK_H * 0.3f, BACK_W * 0.62f,
                new int[]{glow, Theme.alpha(glow, 0.55f), Theme.alpha(glow, 0f)}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, BACK_W, BACK_H, p);
        p.setShader(new LinearGradient(0, 0, 0, BACK_H, Theme.alpha(color, 0.35f), Theme.alpha(ground, 0f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, BACK_W, BACK_H, p);
        bm.setHasAlpha(false);
        sBackColor = color;
        sBack = bm;
        return bm;
    }
}
