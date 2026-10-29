package org.lumen.projector.guide;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.lumen.projector.R;

import java.util.Locale;

/**
 * Lumen OS 1.0.4, Projector settings > User guide (in place of XGIMI's manual app): the online guide of the
 * repository (docs/guide, Russian for Russian, Ukrainian, Belarusian and Kazakh, English otherwise) as a QR
 * code to open on a phone, and its address. Nothing is downloaded on the projector.
 */
public final class GuideDialog {
    private static final String BASE = "https://github.com/kmuradoff/lumen-os/blob/main/docs/guide/";

    private GuideDialog() {}

    static String url() {
        String lang = Locale.getDefault().getLanguage();
        boolean ru = "ru".equals(lang) || "uk".equals(lang) || "be".equals(lang) || "kk".equals(lang);
        return BASE + (ru ? "ru.md" : "en.md");
    }

    public static void show(Activity a) {
        String url = url();
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(24 * d);
        box.setPadding(pad, pad, pad, pad);
        ImageView qr = new ImageView(a);
        qr.setImageBitmap(bitmap(Qr.encode(url), Math.round(220 * d)));
        box.addView(qr, new LinearLayout.LayoutParams(Math.round(220 * d), Math.round(220 * d)));
        TextView t = new TextView(a);
        t.setText(a.getString(R.string.guide_scan) + "\n" + url);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Math.round(16 * d), 0, 0);
        box.addView(t);
        new AlertDialog.Builder(a).setTitle(R.string.guide_title).setView(box)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    /** The code on white with the 4-module quiet zone, scaled to whole pixels per module. */
    private static Bitmap bitmap(Qr q, int px) {
        int n = q.size + 8;
        int m = Math.max(1, px / n);
        int side = m * n;
        Bitmap b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        for (int y = 0; y < q.size; y++) {
            for (int x = 0; x < q.size; x++) {
                if (!q.get(x, y)) continue;
                for (int dy = 0; dy < m; dy++) {
                    for (int dx = 0; dx < m; dx++) b.setPixel((x + 4) * m + dx, (y + 4) * m + dy, Color.BLACK);
                }
            }
        }
        return b;
    }
}
