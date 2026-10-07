// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view kit in the Lumen look (brand tokens, D-pad first: white pill = focus). */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF0F1115, SURFACE = 0xFF1E232C, SURFACE2 = 0xFF263041, TEXT = 0xFFE8EAED,
            DIM = 0xFF9AA0A6, FOCUS_TEXT = 0xFF0E0E0F, ACCENT = 0xFF8AB4F8, ACCENT_STRONG = 0xFF3B78E7,
            WARN = 0xFFF6AE2D, ERROR = 0xFFF28B82, OK = 0xFF81C995;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, CharSequence s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false));
        t.setLineSpacing(0, 1.18f);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Pill button: surface2 at rest, white pill with dark text when focused. */
    static TextView button(Context c, CharSequence label, boolean primary, View.OnClickListener l) {
        TextView b = text(c, label, 17, TEXT, 500);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        int padH = dp(c, 26), padV = dp(c, 12);
        b.setPadding(padH, padV, padH, padV);
        b.setMinWidth(dp(c, 150));
        StateListDrawable sl = new StateListDrawable();
        float r = dp(c, 24);
        sl.addState(new int[] {android.R.attr.state_focused}, round(TEXT, r));
        sl.addState(new int[] {android.R.attr.state_pressed}, round(TEXT, r));
        sl.addState(new int[0], round(primary ? ACCENT_STRONG : SURFACE2, r));
        b.setBackground(sl);
        b.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[] {FOCUS_TEXT, FOCUS_TEXT, TEXT}));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(l);
        b.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(120).start());
        return b;
    }

    /** Settings-style row: title + value, white pill when focused. */
    static LinearLayout row(Context c, CharSequence title, CharSequence value, View.OnClickListener l) {
        return row(c, title, value, l, false);
    }

    /** stacked = the value goes under the title (long values in the narrow side column). */
    static LinearLayout row(Context c, CharSequence title, CharSequence value, View.OnClickListener l, boolean stacked) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        r.setGravity(stacked ? Gravity.START : Gravity.CENTER_VERTICAL);
        int ph = dp(c, 20), pv = dp(c, 13);
        r.setPadding(ph, pv, ph, pv);
        TextView t = text(c, title, 17, TEXT, 400);
        TextView v = text(c, value, 16, DIM, 400);
        t.setSingleLine(true);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        r.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (stacked) {
            v.setGravity(Gravity.START);
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            r.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            vp.setMarginStart(dp(c, 16));
            r.addView(v, vp);
        }
        if (value == null || value.length() == 0) v.setVisibility(View.GONE);
        StateListDrawable sl = new StateListDrawable();
        float rad = dp(c, 14);
        sl.addState(new int[] {android.R.attr.state_focused}, round(TEXT, rad));
        sl.addState(new int[0], round(Color.TRANSPARENT, rad));
        r.setBackground(sl);
        if (l != null) {
            r.setFocusable(true);
            r.setClickable(true);
            r.setOnClickListener(l);
            r.setOnFocusChangeListener((view, f) -> {
                t.setTextColor(f ? FOCUS_TEXT : TEXT);
                v.setTextColor(f ? FOCUS_TEXT : DIM);
            });
        }
        r.setTag(v);
        return r;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 8);
        l.setPadding(p, p, p, p);
        l.setBackground(round(SURFACE, dp(c, 18)));
        return l;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams lp, int l, int t, int r, int b) {
        lp.setMargins(l, t, r, b);
        return lp;
    }

    /** Thin progress line: accent on surface2; indeterminate = a gliding segment. */
    static final class ProgressLine extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float value = -1f;
        private final RectF rect = new RectF();

        ProgressLine(Context c) {
            super(c);
            bg.setColor(SURFACE2);
            fg.setColor(ACCENT);
        }

        void set(float v) {
            value = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float h = getHeight(), w = getWidth(), r = h / 2f;
            rect.set(0, 0, w, h);
            cv.drawRoundRect(rect, r, r, bg);
            if (value >= 0) {
                rect.set(0, 0, Math.max(h, w * Math.min(1f, value)), h);
                cv.drawRoundRect(rect, r, r, fg);
            } else {
                float t = (System.currentTimeMillis() % 1600) / 1600f;
                float seg = w * 0.25f, x = -seg + (w + seg) * t;
                rect.set(Math.max(0, x), 0, Math.min(w, x + seg), h);
                cv.drawRoundRect(rect, r, r, fg);
                postInvalidateOnAnimation();
            }
        }
    }

    /** A QR code on a white card with the 4-module quiet zone, crisp at any size. */
    static final class QrView extends View {
        private final QrCode qr;
        private final Paint dark = new Paint();
        private final Drawable card;

        QrView(Context c, String text) {
            super(c);
            qr = QrCode.encode(text);
            dark.setColor(0xFF000000);
            card = round(0xFFFFFFFF, dp(c, 10));
            setContentDescription(text);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int s = Math.min(getWidth(), getHeight());
            card.setBounds(0, 0, s, s);
            card.draw(cv);
            int n = qr.size + 8;
            float m = s / (float) n;
            for (int y = 0; y < qr.size; y++)
                for (int x = 0; x < qr.size; x++)
                    if (qr.dark(x, y)) cv.drawRect((x + 4) * m, (y + 4) * m, (x + 5) * m + 0.5f, (y + 5) * m + 0.5f, dark);
        }
    }
}
