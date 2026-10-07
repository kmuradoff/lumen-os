package org.z9x.home.img;

import android.graphics.Bitmap;

/**
 * Three box-blur passes (horizontal + vertical) approximate a Gaussian; on the 192x108 ambient
 * backdrop bitmap this costs about 2 ms. Software ARGB_8888 bitmaps only.
 */
public final class Blur {
    private Blur() {}

    public static void blur(Bitmap b, int radius) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxH(px, tmp, w, h, radius);
            boxV(tmp, px, w, h, radius);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void boxH(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[row + clamp(i, w)];
                sa += p >>> 24; sr += (p >> 16) & 0xff; sg += (p >> 8) & 0xff; sb += p & 0xff;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int pOut = in[row + clamp(x - r, w)];
                int pIn = in[row + clamp(x + r + 1, w)];
                sa += (pIn >>> 24) - (pOut >>> 24);
                sr += ((pIn >> 16) & 0xff) - ((pOut >> 16) & 0xff);
                sg += ((pIn >> 8) & 0xff) - ((pOut >> 8) & 0xff);
                sb += (pIn & 0xff) - (pOut & 0xff);
            }
        }
    }

    private static void boxV(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[clamp(i, h) * w + x];
                sa += p >>> 24; sr += (p >> 16) & 0xff; sg += (p >> 8) & 0xff; sb += p & 0xff;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int pOut = in[clamp(y - r, h) * w + x];
                int pIn = in[clamp(y + r + 1, h) * w + x];
                sa += (pIn >>> 24) - (pOut >>> 24);
                sr += ((pIn >> 16) & 0xff) - ((pOut >> 16) & 0xff);
                sg += ((pIn >> 8) & 0xff) - ((pOut >> 8) & 0xff);
                sb += (pIn & 0xff) - (pOut & 0xff);
            }
        }
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }
}
