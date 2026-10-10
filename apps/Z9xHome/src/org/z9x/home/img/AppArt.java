package org.z9x.home.img;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Renders app art on an image worker thread (SPEC 5.2, direction D):
 *  - the app's own 16:9 Android TV banner (PackageManager.getActivityBanner, then getApplicationBanner;
 *    320x180 dp assets), or
 *  - the fallback tile: the app icon centred on a 16:9 card tinted from the icon's own colour, the name
 *    below it.
 * Also renders plain app icons (provider badges on program cards, hero provider line).
 * Every bitmap is made at the exact pixels it is shown at, and big sources are reduced properly
 * ({@link #drawFitted}): a 640x360 banner in a 272x153 card, or a 216 px icon in a 36 px badge.
 */
final class AppArt {
    private AppArt() {}

    static Bitmap banner(Context c, String flatComponent, String label, int w, int h, int surface) {
        PackageManager pm = c.getPackageManager();
        ComponentName cn = ComponentName.unflattenFromString(flatComponent);
        if (cn == null) return null;
        ActivityInfo ai;
        try {
            ai = pm.getActivityInfo(cn, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        Drawable banner = null;
        try {
            banner = pm.getActivityBanner(cn);
            if (banner == null) banner = pm.getApplicationBanner(cn.getPackageName());
        } catch (Throwable ignored) {
        }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        if (banner != null) {
            cv.drawColor(surface);
            int iw = banner.getIntrinsicWidth(), ih = banner.getIntrinsicHeight();
            if (iw > 0 && ih > 0) {
                // cover the card, keep the banner's aspect (most are 16:9 = card aspect)
                float s = Math.max(w / (float) iw, h / (float) ih);
                int dw = Math.round(iw * s), dh = Math.round(ih * s);
                int x = (w - dw) / 2, y = (h - dh) / 2;
                drawFitted(cv, banner, x, y, dw, dh);
            } else {
                banner.setBounds(0, 0, w, h);
                banner.draw(cv);
            }
            return b;
        }
        Drawable icon = ai.loadIcon(pm);
        int bg = tileColor(icon);
        cv.drawColor(bg);
        int is = Math.round(h * 0.40f);
        int ix = (w - is) / 2, iy = Math.round(h * 0.16f);
        if (icon != null) drawFitted(cv, icon, ix, iy, is, is);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        Typeface tf = org.z9x.home.ui.Theme.SEMIBOLD;
        tp.setTypeface(tf != null ? tf : Typeface.create(Typeface.SANS_SERIF, 600, false));
        tp.setTextSize(h * 0.14f);
        tp.setColor(0xFFF4EFE6);
        CharSequence t = TextUtils.ellipsize(label == null ? "" : label, tp, w * 0.86f, TextUtils.TruncateAt.END);
        float tw = tp.measureText(t, 0, t.length());
        cv.drawText(t, 0, t.length(), (w - tw) / 2f, h * 0.86f, tp);
        return b;
    }

    static Bitmap icon(Context c, String pkg, int size) {
        PackageManager pm = c.getPackageManager();
        Drawable d;
        try {
            d = pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        drawFitted(cv, d, 0, 0, size, size);
        return b;
    }

    /**
     * Draws {@code d} into the w x h box at (x, y). Android draws a scaled bitmap with a single bilinear
     * tap and no mipmaps, so a source more than twice the box aliases (banner logos and icon edges turn
     * ragged). Such a drawable is taken at its own size (a bitmap as it is, anything else rendered once),
     * halved while still at least twice the box (each halving is an exact 2x2 average) and then drawn
     * once, filtered. Smaller sources and vectors without a size are drawn directly. Worker thread.
     */
    static void drawFitted(Canvas cv, Drawable d, int x, int y, int w, int h) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) return;
        if (iw < 2 * w || ih < 2 * h || iw > 4096 || ih > 4096) {
            d.setBounds(x, y, x + w, y + h);
            d.draw(cv);
            return;
        }
        Bitmap src = null, b = null;
        boolean own = false;
        try {
            if (d instanceof BitmapDrawable && d.getColorFilter() == null) {
                Bitmap bb = ((BitmapDrawable) d).getBitmap();
                if (bb != null && bb.getConfig() != Bitmap.Config.HARDWARE && bb.getWidth() == iw && bb.getHeight() == ih) {
                    src = bb;
                }
            }
            if (src == null) {
                src = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
                own = true;
                Canvas c = new Canvas(src);
                d.setBounds(0, 0, iw, ih);
                d.draw(c);
            }
            b = src;
            while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
                Bitmap n = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
                if (b != src || own) b.recycle();
                b = n;
            }
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            if (!own) p.setAlpha(d.getAlpha()); // a rendered copy already carries it
            cv.drawBitmap(b, null, new Rect(x, y, x + w, y + h), p);
        } catch (Throwable t) {
            d.setBounds(x, y, x + w, y + h);
            d.draw(cv);
        } finally {
            if (b != null && (b != src || own)) b.recycle();
            if (own && src != null && src != b) src.recycle();
        }
    }

    /** Most saturated colour of the icon, darkened for a tile background (warm neutral without one). */
    private static int tileColor(Drawable icon) {
        if (icon == null) return 0xFF2A2520;
        Bitmap s = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(s);
        icon.setBounds(0, 0, 24, 24);
        icon.draw(cv);
        int c = DominantColor.of(s);
        s.recycle();
        if (c == 0) return 0xFF2A2520;
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        // deep, calm tones: yellow/olive hues get less saturation so they never turn muddy
        boolean yellow = hsv[0] >= 38f && hsv[0] <= 80f;
        hsv[1] = Math.min(yellow ? 0.32f : 0.52f, Math.max(hsv[1], 0.28f));
        hsv[2] = yellow ? 0.30f : 0.34f;
        return Color.HSVToColor(hsv);
    }
}
