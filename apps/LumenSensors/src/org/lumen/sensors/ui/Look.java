package org.lumen.sensors.ui;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.DisplayMetrics;

import org.lumen.sensors.core.Levels;

/**
 * The Lumen look of the HUD and the settings screen, after Projector's ui.Theme (XGIMI quick panel):
 * sizes in design px of a 1920-wide screen, scaled by display width / 1920 with {@link #scale} (the
 * Lumen UI is 4K by default: 1 design px = 2 real px). Colours: Projector's panel and text values,
 * the Lumen accent token z9x_accent_strong, the Lumen warn / error tokens for warm / hot values.
 */
public final class Look {
    private Look() {}

    public static final float DESIGN_WIDTH = 1920f;
    /** TV safe area: nothing within 5 % of the height of any edge (keystone crops the edges). */
    public static final float SAFE = 54;

    // ------------------------------------------------------------------ colours
    public static final int PANEL_BG = 0xEB1C1C1C;
    public static final int PANEL_STROKE = 0x1AFFFFFF;
    public static final int TEXT = 0xE5FFFFFF;
    public static final int LABEL = 0xB2FFFFFF;
    public static final int FAINT = 0x80FFFFFF;
    public static final int FOCUS_BG = 0xFFFFFFFF;
    public static final int TEXT_FOCUSED = 0xE5000000;
    public static final int TEXT_FOCUSED_DIM = 0x99000000;
    public static final int ACCENT = 0xFF3B78E7;           // Lumen token z9x_accent_strong
    public static final int SWITCH_OFF = 0x4DFFFFFF;
    public static final int SWITCH_OFF_FOCUSED = 0x4D000000;
    public static final int WARM = 0xFFF6AE2D;             // Lumen lu_warn
    public static final int HOT = 0xFFF28B82;              // Lumen lu_error
    public static final int GRAPH = 0xFF8AB4F8;            // Lumen token z9x_accent (light blue)
    public static final int GRAPH_GUIDE = 0x26FFFFFF;

    // ------------------------------------------------------------------ settings panel (design px)
    public static final float PANEL_WIDTH_FRACTION = 0.34f;
    public static final float PANEL_RADIUS = 36;
    public static final float PANEL_PAD_H = 28;
    public static final float PANEL_PAD_TOP = 40;
    public static final float PANEL_TITLE_SIZE = 40;
    public static final float ROW_HEIGHT = 92;
    public static final float ROW_GAP = 6;
    public static final float ROW_RADIUS = 24;
    public static final float ROW_PAD_H = 28;
    public static final float ROW_TITLE_SIZE = 30;
    public static final float ROW_VALUE_SIZE = 26;
    public static final float HEADER_SIZE = 24;
    public static final float HEADER_HEIGHT = 64;
    public static final float HINT_SIZE = 22;
    public static final float STROKE = 3;
    /** Gap between the settings panel and a HUD moved aside while the settings are open. */
    public static final float HUD_ASIDE_GAP = 24;

    // ------------------------------------------------------------------ scale
    public static float scale(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, m.heightPixels);
        return w <= 0 ? 1f : w / DESIGN_WIDTH;
    }

    public static float pxf(Context c, float design) {
        return design * scale(c);
    }

    public static int px(Context c, float design) {
        return Math.round(pxf(c, design));
    }

    /** Right edge (real px) of the settings panel, where a HUD on the left moves while settings are open. */
    public static int settingsPanelRight(Context c) {
        return px(c, SAFE + DESIGN_WIDTH * PANEL_WIDTH_FRACTION + HUD_ASIDE_GAP);
    }

    // ------------------------------------------------------------------ paints
    public static TextPaint text(float sizePx, int color, Typeface tf, boolean tabular) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTextSize(sizePx);
        p.setColor(color);
        if (tf != null) p.setTypeface(tf);
        if (tabular) p.setFontFeatureSettings("tnum");      // digits do not jitter as values change
        return p;
    }

    public static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    public static Paint stroke(int color, float widthPx) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(widthPx);
        return p;
    }

    public static final Typeface BOLD = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    public static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** Value colour of a level. */
    public static int levelColor(int level) {
        return level == Levels.HOT ? HOT : (level == Levels.WARM ? WARM : TEXT);
    }
}
