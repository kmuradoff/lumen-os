package org.z9x.setup.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.z9x.setup.R;

/**
 * Lumen look for the wizard. Sizes are design px of a 1920-wide screen (setup/SPEC.md 4.0), scaled by
 * displayWidth / 1920 (on the Z9X 1 design px = 1 real px; 1 dp = 2 px). Colours are the brand tokens
 * (res/values/tokens.xml, PLAN C7): focus = light fill (z9x_text) with dark text, like TvSettings and
 * the quick panel.
 */
public final class Ui {
    private Ui() {}

    public static final float DESIGN_WIDTH = 1920f;

    public static final int[] FOCUSED = {android.R.attr.state_focused};
    public static final int[] PRESSED = {android.R.attr.state_pressed};
    public static final int[] SELECTED = {android.R.attr.state_selected};
    public static final int[] EMPTY = {};

    // colour tokens, cached once per process (the theme never changes at runtime)
    public static int BG, SURFACE, SURFACE2, TEXT, TEXT_DIM, FOCUS_TEXT, ACCENT, ACCENT_STRONG, BLACK,
            BG_BOTTOM, ERROR, OK;
    public static final int FOCUS_TEXT_DIM = 0x990E0E0F;
    public static final int HAIRLINE = 0x1FFFFFFF;

    private static float sScale = 1f;
    private static Typeface sMedium, sRegular, sLight, sThin;

    public static void init(Context c) {
        BG = c.getColor(R.color.z9x_bg);
        SURFACE = c.getColor(R.color.z9x_surface);
        SURFACE2 = c.getColor(R.color.z9x_surface2);
        TEXT = c.getColor(R.color.z9x_text);
        TEXT_DIM = c.getColor(R.color.z9x_text_dim);
        FOCUS_TEXT = c.getColor(R.color.z9x_focus_text);
        ACCENT = c.getColor(R.color.z9x_accent);
        ACCENT_STRONG = c.getColor(R.color.z9x_accent_strong);
        BLACK = c.getColor(R.color.z9x_black);
        BG_BOTTOM = c.getColor(R.color.setup_bg_bottom);
        ERROR = c.getColor(R.color.setup_error);
        OK = c.getColor(R.color.setup_ok);
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, m.heightPixels);
        sScale = w <= 0 ? 1f : w / DESIGN_WIDTH;
        sRegular = Typeface.create("sans-serif", Typeface.NORMAL);
        sMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        sLight = Typeface.create(Typeface.DEFAULT, 300, false);
        sThin = Typeface.create(Typeface.DEFAULT, 200, false);
    }

    public static int px(float design) { return Math.round(design * sScale); }

    public static float pxf(float design) { return design * sScale; }

    public static Typeface medium() { return sMedium; }

    public static Typeface regular() { return sRegular; }

    public static Typeface light() { return sLight; }

    public static Typeface thin() { return sThin; }

    /** Stock PathInterpolator(0,0,0,1) used for every enter motion (matches the quick panel). */
    public static PathInterpolator decel() { return new PathInterpolator(0f, 0f, 0f, 1f); }

    public static PathInterpolator standard() { return new PathInterpolator(0.2f, 0f, 0f, 1f); }

    public static boolean isRtl(View v) { return v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL; }

    /** True when the view (or its parent, for duplicateParentState children) is drawn focused. */
    public static boolean focusedState(View v) {
        for (int s : v.getDrawableState()) if (s == android.R.attr.state_focused) return true;
        return false;
    }

    // ------------------------------------------------------------------ text

    public static ColorStateList focusText() {
        return new ColorStateList(new int[][]{FOCUSED, EMPTY}, new int[]{FOCUS_TEXT, TEXT});
    }

    public static ColorStateList focusTextDim() {
        return new ColorStateList(new int[][]{FOCUSED, EMPTY}, new int[]{FOCUS_TEXT_DIM, TEXT_DIM});
    }

    public static TextView text(Context c, float size, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, pxf(size));
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }

    /** Title 64 px medium, 90 % white, max 3 lines (SPEC 4.0, F11). */
    public static TextView title(Context c) {
        TextView t = text(c, 60, TEXT, sRegular);
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLineSpacing(pxf(8), 1f);
        return t;
    }

    /** Subtitle 30 px, dim, max 3 lines. */
    public static TextView subtitle(Context c) {
        TextView t = text(c, 30, TEXT_DIM, sRegular);
        t.setMaxLines(4);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLineSpacing(pxf(8), 1f);
        return t;
    }

    public static TextView body(Context c, CharSequence s) {
        TextView t = text(c, 26, TEXT_DIM, sRegular);
        t.setText(s);
        t.setLineSpacing(pxf(6), 1f);
        return t;
    }

    // ------------------------------------------------------------------ backgrounds

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(pxf(radius));
        return d;
    }

    /** Focus fill (light) when focused, {@code idle} otherwise; 120 ms cross-fade (SPEC 4.0). */
    public static Drawable focusBg(int idle, float radius, int strokeIdle) {
        StateListDrawable s = new StateListDrawable();
        s.addState(FOCUSED, round(TEXT, radius));
        GradientDrawable i = round(idle, radius);
        if (strokeIdle != 0) i.setStroke(px(2), strokeIdle);
        s.addState(EMPTY, i);
        s.setEnterFadeDuration(120);
        s.setExitFadeDuration(120);
        return s;
    }

    public static Drawable roundRect(int color, float radius) { return round(color, radius); }

    /** Opaque dialog card (sheets): nothing of the screen behind may bleed through the text. */
    public static Drawable cardOpaque(float radius) {
        GradientDrawable d = round(SURFACE, radius);
        d.setStroke(px(2), HAIRLINE);
        return d;
    }

    /** Translucent card behind a group of controls. */
    public static Drawable card(float radius) {
        GradientDrawable d = round(0xE61E232C, radius);
        d.setStroke(px(2), HAIRLINE);
        return d;
    }

    // ------------------------------------------------------------------ layout helpers

    public static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    public static LinearLayout.LayoutParams lpTop(int w, int h, float topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = px(topMargin);
        return p;
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static View space(Context c) { return new View(c); }
}
