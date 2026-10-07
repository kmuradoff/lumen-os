package org.z9x.home.img;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Placeholder colour of a piece of art: the most saturated mid-luminance pixel of a 16x16 downscale,
 * darkened into a range where white text stays readable (SPEC 5.2, 7.5).
 */
public final class DominantColor {
    private DominantColor() {}

    public static int of(Bitmap sw) {
        if (sw == null || sw.getConfig() == Bitmap.Config.HARDWARE) return 0;
        Bitmap s = Bitmap.createScaledBitmap(sw, 16, 16, true);
        int[] px = new int[256];
        s.getPixels(px, 0, 16, 0, 0, 16, 16);
        if (s != sw) s.recycle();
        float[] hsv = new float[3];
        float bestScore = -1;
        int best = 0;
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            r += Color.red(p);
            g += Color.green(p);
            b += Color.blue(p);
            n++;
            Color.colorToHSV(p, hsv);
            float v = hsv[2];
            if (v < 0.15f || v > 0.95f) continue;
            float score = hsv[1] * (1f - Math.abs(v - 0.55f));
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (n == 0) return 0;
        int c = bestScore > 0.12f ? best : Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
        return tame(c);
    }

    /** Clamp value to 0.22-0.42 and saturation to <= 0.65 so it works as a dark card background. */
    public static int tame(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(hsv[1], 0.65f);
        hsv[2] = Math.max(0.22f, Math.min(0.42f, hsv[2]));
        return Color.HSVToColor(hsv);
    }
}
