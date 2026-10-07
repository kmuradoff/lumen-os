package org.z9x.home.img;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Renders app art on an image worker thread (SPEC 5.2):
 *  - the app's own 16:9 banner (activity banner, then application banner), or
 *  - a generated tile: the icon centred on a card in a colour derived from the icon, label below.
 * Also renders plain app icons (provider badges on program cards, hero provider line).
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
        Drawable banner = ai.loadBanner(pm);
        if (banner == null && ai.applicationInfo != null) banner = ai.applicationInfo.loadBanner(pm);
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
                banner.setBounds(x, y, x + dw, y + dh);
            } else {
                banner.setBounds(0, 0, w, h);
            }
            banner.draw(cv);
            return b;
        }
        Drawable icon = ai.loadIcon(pm);
        int bg = tileColor(icon);
        cv.drawColor(bg);
        int is = Math.round(h * 0.40f);
        int ix = (w - is) / 2, iy = Math.round(h * 0.15f);
        if (icon != null) {
            icon.setBounds(ix, iy, ix + is, iy + is);
            icon.draw(cv);
        }
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setTypeface(Typeface.create(Typeface.SANS_SERIF, 500, false));
        tp.setTextSize(h * 0.15f);
        tp.setColor(0xFFFFFFFF);
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
        d.setBounds(0, 0, size, size);
        d.draw(cv);
        return b;
    }

    /** Most saturated colour of the icon, darkened for a tile background. */
    private static int tileColor(Drawable icon) {
        if (icon == null) return 0xFF263041;
        Bitmap s = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(s);
        icon.setBounds(0, 0, 24, 24);
        icon.draw(cv);
        int c = DominantColor.of(s);
        s.recycle();
        if (c == 0) return 0xFF263041;
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        // deep, calm tones: yellow/olive hues get less saturation so they never turn muddy
        boolean yellow = hsv[0] >= 38f && hsv[0] <= 80f;
        hsv[1] = Math.min(yellow ? 0.32f : 0.52f, Math.max(hsv[1], 0.28f));
        hsv[2] = yellow ? 0.30f : 0.34f;
        return Color.HSVToColor(hsv);
    }
}
