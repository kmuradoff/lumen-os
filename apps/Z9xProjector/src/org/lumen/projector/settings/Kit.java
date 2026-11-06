package org.lumen.projector.settings;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Lumen OS 1.0.9: the look of the Projector settings (mock design/projector_v2
 * 2026-11-05), the same warm palette and fonts as Lumen Home (Prata for titles, Onest for text).
 * Framework widgets only, built in code, nothing animated except the focus scale of a tile (cheap on weak SoCs).
 * The mock is drawn at 1920 px; one mock px = 0.5 dp for sizes, text is ~15% larger than the mock for the
 * projection distance.
 */
public final class Kit {
    private static final String TAG = "Z9xSettings";
    static final String FONT_DISPLAY = "fonts/Prata-Regular.ttf";
    static final String FONT_UI = "fonts/Onest-Variable.ttf";

    public static final int GROUND = 0xFF0A0908, S1 = 0xFF1B1815, S2 = 0xFF26221E, S3 = 0xFF332E28;
    public static final int T1 = 0xFFF4EFE6, T2 = 0xFFC9C1B4, T3 = 0xFF9D9587;
    public static final int FOCUS = 0xFFF6F1E8, ON_FOCUS = 0xFF0A0908, ON_FOCUS_DIM = 0xFF5B544B;
    public static final int ACC = 0xFFF2B26B, ACC2 = 0xFFC98642, DIVIDER = 0xFF2B2621, GLOW = 0xFF2A1D12;

    private static Typeface sDisplay, sRegular, sMedium, sSemi;

    private Kit() {}

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------------ fonts
    static synchronized void fonts(Context c) {
        if (sRegular != null) return;
        AssetManager am = c.getAssets();
        try {
            sDisplay = Typeface.createFromAsset(am, FONT_DISPLAY);
        } catch (Throwable t) {
            Log.w(TAG, "font " + FONT_DISPLAY + ": " + t);
            sDisplay = Typeface.SERIF;
        }
        sRegular = ui(am, 400);
        sMedium = ui(am, 500);
        sSemi = ui(am, 600);
    }

    private static Typeface ui(AssetManager am, int weight) {
        try {
            return new Typeface.Builder(am, FONT_UI).setFontVariationSettings("'wght' " + weight).setWeight(weight).build();
        } catch (Throwable t) {
            Log.w(TAG, "font " + FONT_UI + " " + weight + ": " + t);
            return Typeface.create(Typeface.SANS_SERIF, weight, false);
        }
    }

    public static Typeface display(Context c) { fonts(c); return sDisplay; }
    public static Typeface regular(Context c) { fonts(c); return sRegular; }
    public static Typeface medium(Context c) { fonts(c); return sMedium; }
    public static Typeface semibold(Context c) { fonts(c); return sSemi; }

    public static TextView text(Context c, CharSequence s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** The screen background: the ground colour with the warm glow top right (as Lumen Home). */
    public static void background(View v) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{GROUND, GROUND});
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{GLOW, GROUND});
        g.setGradientCenter(0.85f, 0.1f);
        g.setGradientRadius(dp(v.getContext(), 600));
        v.setBackground(g);
    }

    // ------------------------------------------------------------------ tile ("now" actions)
    /** A large action tile: icon top, title and one line below. Focused: light, 1.04 scale. */
    public static View tile(Context c, int icon, CharSequence title, CharSequence sub, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 14);
        t.setPadding(pad, pad, pad, pad);
        t.setFocusable(true);
        t.setClickable(true);
        final float r = dp(c, 14);
        t.setBackground(round(S1, r));
        final ImageView iv = new ImageView(c);
        iv.setImageResource(icon);
        iv.setColorFilter(ACC);
        t.addView(iv, new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)));
        View gap = new View(c);
        t.addView(gap, new LinearLayout.LayoutParams(1, 0, 1f));
        final TextView tt = text(c, title, 15, T1, medium(c));
        tt.setSingleLine(true);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(tt);
        final TextView st = text(c, sub, 11, T3, regular(c));
        st.setPadding(0, dp(c, 4), 0, 0);
        st.setMaxLines(2);
        st.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(st);
        t.setOnFocusChangeListener((v, f) -> {
            v.setBackground(round(f ? FOCUS : S1, r));
            tt.setTextColor(f ? ON_FOCUS : T1);
            st.setTextColor(f ? ON_FOCUS_DIM : T3);
            iv.setColorFilter(f ? ACC2 : ACC);
            v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(120).start();
            v.setElevation(f ? dp(c, 10) : 0);
        });
        t.setOnClickListener(safe(l));
        return t;
    }

    /** Tiles in one row of equal width, 12 dp apart. */
    public static LinearLayout tileRow(Context c, View... tiles) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        for (int i = 0; i < tiles.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 100), 1f);
            if (i > 0) lp.leftMargin = dp(c, 12);
            row.addView(tiles[i], lp);
        }
        return row;
    }

    // ------------------------------------------------------------------ cards and rows
    /** A rounded card; add rows with {@link #add}. */
    public static LinearLayout card(Context c) {
        LinearLayout k = new LinearLayout(c);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setBackground(round(S1, dp(c, 14)));
        k.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        return k;
    }

    /** Adds a row to a card, with a divider above every row but the first visible one. */
    public static void add(LinearLayout card, Row row) {
        Context c = card.getContext();
        if (card.getChildCount() > 0) {
            View d = new View(c);
            d.setBackground(new ColorDrawable(DIVIDER));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
            lp.leftMargin = dp(c, 14);
            lp.rightMargin = dp(c, 14);
            card.addView(d, lp);
            row.divider = d;
        }
        card.addView(row.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Two cards side by side (12 dp apart), or one when the second is null / empty. */
    public static LinearLayout columns(Context c, View left, View right) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(c, 12);
            row.addView(right, lp);
        }
        return row;
    }

    /** A settings row: title, an explanation line, and on the right a switch, a value with ›, or nothing. */
    public static final class Row {
        public final LinearLayout view;
        public final TextView title, sub, value;
        public final SwitchView sw;
        View divider;
        private boolean enabled = true;

        Row(Context c, CharSequence t, CharSequence s, int kind) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setFocusable(true);
            view.setClickable(true);
            view.setPadding(dp(c, 14), dp(c, 9), dp(c, 14), dp(c, 9));
            view.setMinimumHeight(dp(c, 44));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = text(c, t, 14, T1, medium(c));
            texts.addView(title);
            sub = text(c, s == null ? "" : s, 11, T3, regular(c));
            sub.setPadding(0, dp(c, 3), 0, 0);
            if (s == null || s.length() == 0) sub.setVisibility(View.GONE);
            texts.addView(sub);
            view.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (kind == 1) {
                sw = new SwitchView(c);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 36), dp(c, 20));
                lp.leftMargin = dp(c, 16);
                view.addView(sw, lp);
                value = null;
            } else if (kind == 2) {
                sw = null;
                value = text(c, "…", 13, T2, regular(c));
                value.setPadding(dp(c, 16), 0, 0, 0);
                value.setMaxWidth(dp(c, 220));
                value.setSingleLine(true);
                value.setEllipsize(TextUtils.TruncateAt.END);
                view.addView(value);
                TextView chev = text(c, "›", 17, T3, regular(c));
                chev.setPadding(dp(c, 6), 0, 0, dp(c, 2));
                view.addView(chev);
            } else {
                sw = null;
                value = null;
            }
            final float r = dp(c, 11);
            view.setBackground(round(0x00000000, r));
            view.setOnFocusChangeListener((v, f) -> {
                v.setBackground(round(f ? FOCUS : 0x00000000, r));
                title.setTextColor(f ? ON_FOCUS : T1);
                sub.setTextColor(f ? ON_FOCUS_DIM : T3);
                if (value != null) value.setTextColor(f ? ON_FOCUS_DIM : T2);
                if (sw != null) sw.setFocusedLook(f);
            });
        }

        public Row onClick(View.OnClickListener l) {
            view.setOnClickListener(safe(l));
            return this;
        }

        /** Lumen rule (2026-10-12): what cannot be used is not shown; this hides the row and its divider. */
        public void setShown(boolean shown) {
            int vis = shown ? View.VISIBLE : View.GONE;
            view.setVisibility(vis);
            if (divider != null) divider.setVisibility(vis);
        }

        /** Z9X: a row that needs the HAL while features are blocked stays visible but cannot be used. */
        public void setUsable(boolean usable) {
            enabled = usable;
            view.setEnabled(usable);
            view.setFocusable(usable);
            view.setAlpha(usable ? 1f : 0.4f);
        }

        public boolean usable() { return enabled; }
    }

    public static Row switchRow(Context c, CharSequence t, CharSequence s) { return new Row(c, t, s, 1); }
    public static Row valueRow(Context c, CharSequence t, CharSequence s) { return new Row(c, t, s, 2); }
    public static Row actionRow(Context c, CharSequence t, CharSequence s) { return new Row(c, t, s, 0); }

    /** Overline (amber, spaced capitals) and the section title in Prata. */
    public static void heading(LinearLayout into, CharSequence overline, CharSequence title) {
        Context c = into.getContext();
        if (overline != null) {
            TextView o = text(c, overline.toString().toUpperCase(), 8.5f, ACC, semibold(c));
            o.setLetterSpacing(0.22f);
            into.addView(o);
        }
        TextView h = text(c, title, 24, T1, display(c));
        h.setPadding(0, dp(c, overline != null ? 6 : 0), 0, dp(c, 18));
        into.addView(h);
    }

    public static View space(Context c, int dpH) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, dpH)));
        return v;
    }

    static View.OnClickListener safe(View.OnClickListener l) {
        return v -> {
            try {
                if (l != null) l.onClick(v);
            } catch (Throwable e) {
                Log.w(TAG, "click: " + e);
            }
        };
    }

    // ------------------------------------------------------------------ the switch
    /** 36x20 dp track, 15 dp knob. on = amber track; null = state not read yet (dim, knob in the middle). */
    public static final class SwitchView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private Boolean on;
        private boolean focused;
        private float pos = 0f;     // 0 off .. 1 on
        private ValueAnimator anim;

        public SwitchView(Context c) { super(c); }

        public void setOn(Boolean v) {
            boolean same = v == null ? on == null : v.equals(on);
            on = v;
            float target = v == null ? 0.5f : (v ? 1f : 0f);
            if (anim != null) anim.cancel();
            if (same || !isAttachedToWindow()) {
                pos = target;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(pos, target).setDuration(140);
            anim.addUpdateListener(a -> { pos = (float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        }

        public Boolean isOn() { return on; }

        void setFocusedLook(boolean f) { focused = f; invalidate(); }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            boolean isOn = Boolean.TRUE.equals(on);
            p.setColor(isOn ? ACC2 : (focused ? 0xFFD9D2C6 : S3));
            p.setAlpha(on == null ? 110 : 255);
            rf.set(0, 0, w, h);
            cv.drawRoundRect(rf, r, r, p);
            float inset = h * 0.125f, kr = r - inset;
            float cx = inset + kr + pos * (w - 2 * (inset + kr));
            p.setColor(isOn ? T1 : (focused ? 0xFF8A8276 : T3));
            p.setAlpha(on == null ? 110 : 255);
            cv.drawCircle(cx, r, kr, p);
        }
    }
}
