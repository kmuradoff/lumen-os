package org.z9x.home.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import org.z9x.home.R;
import org.z9x.home.data.Card;

/**
 * Lumen Home look (SPEC 7). Every size is in px of the 1920x1080 UI ("design px", 1 dp = 2 px on the
 * Z9X), scaled by the real display width with {@link #px}. Colours come from the brand tokens
 * (res/values/z9x_tokens.xml, a copy of apps/common/tokens.xml, PLAN C7).
 */
public final class Theme {
    private Theme() {}

    // ------------------------------------------------------------------ colours (filled by init)
    public static int BG, SURFACE, SURFACE2, SURFACE3, TEXT1, TEXT2, TEXT3, FOCUS, ON_FOCUS, ACCENT, ACCENT_STRONG,
            LIVE, OK, GLOW_A, GLOW_B, TEXT_DIM;

    // ------------------------------------------------------------------ type
    public static Typeface REGULAR, MEDIUM, BOLD, LIGHT;

    // ------------------------------------------------------------------ geometry (design px)
    public static final int MARGIN = 96;
    public static final int TOP = 40;
    public static final int BAR_H = 64;
    public static final int GAP = 28;
    public static final int RADIUS = 16;
    public static final int ROW_TITLE_H = 36;
    public static final int ROW_TITLE_GAP = 12;
    public static final int ROW_CAPTION_H = 104;
    public static final int FOCUS_LINE_Y = 120;
    public static final int FIRST_ROW_Y = 664;
    public static final int RING = 4;
    public static final float FOCUS_SCALE = 1.08f;

    // ------------------------------------------------------------------ motion
    public static final Interpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    public static final Interpolator DECEL = new PathInterpolator(0f, 0f, 0f, 1f);
    public static final Interpolator LINEAR = new LinearInterpolator();
    public static final long FOCUS_IN_MS = 160, FOCUS_OUT_MS = 120, ROW_SCROLL_MS = 200, ROW_REPEAT_MS = 90,
            PAGE_SCROLL_MS = 280, BAR_FADE_MS = 180, TAB_OUT_MS = 120, TAB_IN_MS = 220, PANEL_MS = 280,
            BACKDROP_MS = 400, BACKDROP_DWELL_MS = 250, HERO_FADE_MS = 600;

    private static float sScale = 1f;
    private static float sDensity = 2f;
    private static boolean sInit;

    public static void init(Context c) {
        Resources r = c.getResources();
        DisplayMetrics dm = r.getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        sScale = w / 1920f;
        sDensity = dm.density;
        if (sInit) return;
        sInit = true;
        BG = c.getColor(R.color.z9x_bg);
        SURFACE = c.getColor(R.color.z9x_surface);
        SURFACE2 = c.getColor(R.color.z9x_surface2);
        SURFACE3 = c.getColor(R.color.z9x_surface3);
        TEXT1 = c.getColor(R.color.z9x_text);
        TEXT2 = (TEXT1 & 0x00FFFFFF) | 0xB8000000; // 72 %
        TEXT3 = (TEXT1 & 0x00FFFFFF) | 0x7A000000; // 48 %
        TEXT_DIM = c.getColor(R.color.z9x_text_dim);
        FOCUS = TEXT1;
        ON_FOCUS = c.getColor(R.color.z9x_focus_text);
        ACCENT = c.getColor(R.color.z9x_accent);
        ACCENT_STRONG = c.getColor(R.color.z9x_accent_strong);
        LIVE = c.getColor(R.color.z9x_live);
        OK = c.getColor(R.color.z9x_ok);
        GLOW_A = c.getColor(R.color.z9x_glow_a);
        GLOW_B = c.getColor(R.color.z9x_glow_b);
        REGULAR = Typeface.create(Typeface.SANS_SERIF, 400, false);
        MEDIUM = Typeface.create(Typeface.SANS_SERIF, 500, false);
        BOLD = Typeface.create(Typeface.SANS_SERIF, 700, false);
        LIGHT = Typeface.create(Typeface.SANS_SERIF, 300, false);
    }

    /** Design px (1920 wide) to real px. */
    public static int px(float design) {
        return Math.round(design * sScale);
    }

    public static float pxf(float design) {
        return design * sScale;
    }

    /**
     * Text in design px; set as COMPLEX_UNIT_PX so a font scale above 1.3 cannot grow layouts (SPEC 7.3:
     * respected up to 1.3x, above that text ellipsizes and cards keep their size).
     */
    public static void text(TextView t, float designPx, Typeface tf, int color) {
        float fs = Math.min(1.3f, Math.max(0.85f, t.getResources().getConfiguration().fontScale));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, pxf(designPx) * fs);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        // RTL UI with Latin app/channel names: align to the layout's start edge, not the text's
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
    }

    public static float fontScale(Context c) {
        return Math.min(1.3f, Math.max(0.85f, c.getResources().getConfiguration().fontScale));
    }

    public static Drawable icon(Context c, int res, int tint) {
        Drawable d = c.getDrawable(res);
        if (d == null) return null;
        d = d.mutate();
        if (tint != 0) d.setTint(tint);
        return d;
    }

    public static boolean rtl(View v) {
        return v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    // ------------------------------------------------------------------ card sizes (design px)

    public static int cardW(int kind, int aspect) {
        switch (kind) {
            case Card.APP:
            case Card.TILE:
            case Card.MORE_APPS:
                return 256;
            case Card.INPUT:
            case Card.CAST:
                return 392;
            default:
                switch (aspect) {
                    case Card.A_3_2:
                        return 330;
                    case Card.A_4_3:
                        return 292;
                    case Card.A_1_1:
                        return 256;
                    case Card.A_2_3:
                        return 208;
                    default:
                        return 392;
                }
        }
    }

    public static int cardH(int kind, int aspect) {
        switch (kind) {
            case Card.APP:
            case Card.TILE:
            case Card.MORE_APPS:
                return 144;
            case Card.INPUT:
            case Card.CAST:
                return 220;
            default:
                switch (aspect) {
                    case Card.A_1_1:
                        return 256;
                    case Card.A_2_3:
                        return 312;
                    default:
                        return 220;
                }
        }
    }

    /** Pixel size of the art bitmap of a card: the focused (scaled) size, so focus never upsamples. */
    public static int artW(int kind, int aspect) {
        return px(cardW(kind, aspect) * FOCUS_SCALE);
    }

    public static int artH(int kind, int aspect) {
        return px(cardH(kind, aspect) * FOCUS_SCALE);
    }

    /** False when the user set "Remove animations" (animator duration scale 0): time-based draws snap. */
    public static boolean animations() {
        return android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    public static float density() {
        return sDensity;
    }
}
