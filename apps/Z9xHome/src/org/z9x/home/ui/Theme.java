package org.z9x.home.ui;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import org.z9x.home.R;
import org.z9x.home.data.Card;

import java.util.Locale;

/**
 * Lumen Home look, direction D (SPEC 7, mockups D_Home / D_Calm). Every size is in px of the 1920x1080
 * UI ("design px", half a dp), scaled by the real display width with {@link #px}: the 1.0.1 UI modes
 * 1080p / 2K (the default) / 4K show the same 960x540 dp layout at 1x / 1.33x / 2x the pixels
 * ({@link UiScale} holds the rules that do not simply scale: soft layers, art, memory). Colours
 * come from res/values/colors_lumen.xml (warm ground, paper text, one warm light accent); LIVE/OK keep
 * the brand tokens' meaning. Fonts: "Prata" for titles and the big clock, "Onest" for the UI, loaded
 * from assets/fonts when the image ships them, else the system serif / sans-serif.
 */
public final class Theme {
    private Theme() {}

    // ------------------------------------------------------------------ colours (filled by init)
    public static int BG, SURFACE, SURFACE2, SURFACE3, TEXT1, TEXT2, TEXT3, FOCUS, ON_FOCUS, ACCENT, ACCENT_STRONG,
            LIVE, OK, GLOW_A, GLOW_B, TEXT_DIM, FACET_A, FACET_B;

    // ------------------------------------------------------------------ type
    public static Typeface REGULAR, MEDIUM, SEMIBOLD, BOLD, LIGHT, DISPLAY;
    /** True when Prata came from the assets (the serif fallback runs wider: titles get less tracking). */
    public static boolean sDisplayFromAssets;

    // ------------------------------------------------------------------ geometry (design px)
    public static final int MARGIN = 96;
    /** Header line y 60-132 (D_Home: 46-118): the two-line clock block spans y 54-138, inside SAFE. */
    public static final int TOP = 60;
    public static final int BAR_H = 72;
    public static final int GAP = 24;
    public static final int GAP_APPS = 28;
    public static final int RADIUS = 20;
    public static final int RADIUS_APP = 18;
    public static final int ROW_TITLE_H = 30;
    public static final int ROW_TITLE_GAP = 22;
    public static final int ROW_CAPTION_H = 104;
    public static final int FOCUS_LINE_Y = 132;
    /**
     * TV safe area (1.0.1 follow-up): nothing on Home, the focused card's scale, ring and caption
     * included, comes closer than 5 % of the height (54 px at 1080p, 72 at 2K, 108 at 4K) to any screen
     * edge: keystone crops
     * the edges on a projector. Rows that would cross the line are faded out until they scroll in
     * (PageForYou); the side panel keeps its items off the screen edge (ListPanel).
     */
    public static final int SAFE = 54;
    /**
     * Top of the first row under the hero (D_Home) and under the calm clock (D_Calm), at most: D_Home's
     * 790 put the "Continue watching" cards at y 842-1058 and the focused ring at 1073 (owner: cut by
     * keystone). 740 keeps the focused 16:9 card (216 * 1.06 + the ring) above y 1026 (in real px with
     * the rounding of each mode: 2K 1368, 4K 2052; test/ResolutionTest); a taller first row (the user's
     * row order) moves up further (PageForYou.firstRowY), the hero's buttons with it.
     */
    public static final int FIRST_ROW_Y = 740;
    public static final int FIRST_ROW_Y_CALM = 752;
    public static final float FOCUS_SCALE = 1.06f;

    // ------------------------------------------------------------------ motion
    public static final Interpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    public static final Interpolator DECEL = new PathInterpolator(0f, 0f, 0f, 1f);
    public static final Interpolator LINEAR = new LinearInterpolator();
    public static final long FOCUS_IN_MS = 160, FOCUS_OUT_MS = 120, ROW_SCROLL_MS = 200, ROW_REPEAT_MS = 90,
            PAGE_SCROLL_MS = 280, BAR_FADE_MS = 180, TAB_OUT_MS = 120, TAB_IN_MS = 220, PANEL_MS = 280,
            BACKDROP_MS = 400, BACKDROP_DWELL_MS = 250, HERO_FADE_MS = 600, STAGE_MS = 700;

    private static final String FONT_DISPLAY = "fonts/Prata-Regular.ttf";
    private static final String FONT_UI = "fonts/Onest-Variable.ttf";

    private static float sScale = 1f;
    private static float sDensity = 2f;
    private static boolean sInit;

    public static void init(Context c) {
        Resources r = c.getResources();
        DisplayMetrics dm = r.getDisplayMetrics();
        sScale = UiScale.scale(dm.widthPixels, dm.heightPixels);
        sDensity = dm.density;
        if (sInit) return;
        sInit = true;
        BG = c.getColor(R.color.lumen_ground);
        SURFACE = c.getColor(R.color.lumen_surface);
        SURFACE2 = c.getColor(R.color.lumen_surface2);
        SURFACE3 = c.getColor(R.color.lumen_surface3);
        TEXT1 = c.getColor(R.color.lumen_text);
        TEXT2 = c.getColor(R.color.lumen_text2);
        TEXT3 = c.getColor(R.color.lumen_text3);
        TEXT_DIM = TEXT3;
        FOCUS = c.getColor(R.color.lumen_focus);
        ON_FOCUS = c.getColor(R.color.lumen_on_focus);
        ACCENT = c.getColor(R.color.lumen_accent);
        ACCENT_STRONG = c.getColor(R.color.lumen_accent_strong);
        LIVE = c.getColor(R.color.lumen_live);
        OK = c.getColor(R.color.z9x_ok);
        GLOW_A = c.getColor(R.color.lumen_glow_a);
        GLOW_B = c.getColor(R.color.lumen_glow_b);
        FACET_A = c.getColor(R.color.lumen_facet_a);
        FACET_B = c.getColor(R.color.lumen_facet_b);
        loadFonts(c);
    }

    /**
     * Prata / Onest from the assets if the image ships them (owner's choice), else the system families.
     * Onest is a variable font: one Typeface per weight through the 'wght' axis.
     */
    private static void loadFonts(Context c) {
        AssetManager am = c.getAssets();
        Typeface display = null;
        if (hasAsset(am, FONT_DISPLAY)) {
            try {
                display = Typeface.createFromAsset(am, FONT_DISPLAY);
            } catch (Throwable t) {
                Log.w("Z9xHome", "font " + FONT_DISPLAY + ": " + t);
            }
        }
        sDisplayFromAssets = display != null;
        DISPLAY = display != null ? display : Typeface.SERIF;
        boolean onest = hasAsset(am, FONT_UI);
        LIGHT = ui(am, onest, 300);
        REGULAR = ui(am, onest, 400);
        MEDIUM = ui(am, onest, 500);
        SEMIBOLD = ui(am, onest, 600);
        BOLD = ui(am, onest, 700);
    }

    private static Typeface ui(AssetManager am, boolean fromAssets, int weight) {
        if (fromAssets) {
            try {
                return new Typeface.Builder(am, FONT_UI).setFontVariationSettings("'wght' " + weight).setWeight(weight).build();
            } catch (Throwable t) {
                Log.w("Z9xHome", "font " + FONT_UI + " " + weight + ": " + t);
            }
        }
        if (weight >= 500) return Typeface.create(Typeface.create("sans-serif-medium", Typeface.NORMAL), weight, false);
        return Typeface.create(Typeface.SANS_SERIF, weight, false);
    }

    private static boolean hasAsset(AssetManager am, String path) {
        try {
            am.open(path).close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Design px (1920 wide) to real px. */
    public static int px(float design) {
        return UiScale.px(design, sScale);
    }

    /** Real px per design px: 1 at 1080p, 1.33 at 2K, 2 at 4K. */
    public static float scale() {
        return sScale;
    }

    /**
     * Pixels of a soft bitmap (blurred glows, shadows) per real pixel it covers: 1 at 1080p, 0.75 at 2K,
     * 0.5 at 4K ({@link UiScale#SOFT_MAX}); the GPU enlarges it, which a blur does not show.
     */
    public static float softFactor() {
        return UiScale.soft(sScale) / sScale;
    }

    public static float pxf(float design) {
        return design * sScale;
    }

    /** Focus ring width: 4 dp. */
    public static float ring() {
        return 4f * sDensity;
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

    /** Small caps-style heading (D: 22 px, semibold, tracked, upper case; scripts without case are unchanged). */
    public static void heading(TextView t, float designPx, int color) {
        text(t, designPx, SEMIBOLD, color);
        t.setLetterSpacing(0.1f);
        t.setAllCaps(true);
    }

    public static String upper(CharSequence s) {
        return s == null ? "" : s.toString().toUpperCase(Locale.getDefault());
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

    public static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(255 * Math.max(0f, Math.min(1f, a))) << 24);
    }

    // ------------------------------------------------------------------ card sizes (design px)

    public static int cardW(int kind, int aspect) {
        switch (kind) {
            case Card.APP:
            case Card.TILE:
            case Card.MORE_APPS:
                return 272;
            case Card.INPUT:
            case Card.CAST:
                return 384;
            default:
                switch (aspect) {
                    case Card.A_3_2:
                        return 324;
                    case Card.A_4_3:
                        return 288;
                    case Card.A_1_1:
                        return 248;
                    case Card.A_2_3:
                        return 208;
                    default:
                        return 384;
                }
        }
    }

    public static int cardH(int kind, int aspect) {
        switch (kind) {
            case Card.APP:
            case Card.TILE:
            case Card.MORE_APPS:
                return 153;
            case Card.INPUT:
            case Card.CAST:
                return 216;
            default:
                switch (aspect) {
                    case Card.A_1_1:
                        return 248;
                    case Card.A_2_3:
                        return 312;
                    default:
                        return 216;
                }
        }
    }

    public static int cardGap(int kind) {
        return kind == Card.APP || kind == Card.TILE || kind == Card.MORE_APPS ? GAP_APPS : GAP;
    }

    public static float cardRadius(int kind) {
        return pxf(kind == Card.APP || kind == Card.TILE || kind == Card.MORE_APPS ? RADIUS_APP : RADIUS);
    }

    /**
     * Pixel size of the art bitmap of a card: the card's own size, so the art is shown 1:1 at rest
     * (1.0.1; Android draws a scaled bitmap with one bilinear tap and no mipmaps, so the 1.0.0 choice, the
     * focused size, softened every card that was not focused). Real pixels of the UI mode: a 16:9 row
     * card is 384x216 at 1080p, 512x288 at 2K and 768x432 at 4K.
     */
    public static int artW(int kind, int aspect) {
        return px(cardW(kind, aspect));
    }

    public static int artH(int kind, int aspect) {
        return px(cardH(kind, aspect));
    }

    /** False when the user set "Remove animations" (animator duration scale 0): time-based draws snap. */
    public static boolean animations() {
        return android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    public static float density() {
        return sDensity;
    }
}
