package org.z9x.projector.ui;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.view.animation.PathInterpolator;

/**
 * XGIMI look for every overlay of the app. All sizes are XGIMI "design px" of a 1920-wide screen
 * (stock SystemUI / newsettings layouts), scaled by displayWidth / 1920 with {@link #px}. They are
 * NOT Android dp: on the stock 1080p UI (1920x1080, density 320) 1 design px = 1 real px, while 1 dp = 2 px.
 * Lumen OS 1.0.1 interface resolution: at 4K (3840x2160, 640 dpi, the default) and 2K (2560x1440,
 * 427 dpi, testing only) both scale together (1 design px = 2 / 1.33 real px, 1 dp = 2 design px still),
 * so every overlay keeps its size and layout on the screen; only sharper. Nothing here may assume
 * 1 design px = 1 px (it is 2 px at the 4K default).
 *
 * Sources (research/v61/inventory/FEATURE_SPEC.md):
 *  - 1.2 capsule notifier: 468x156, #FA292929, radius 36, stroke 3 #1AFFFFFF, icon 72, title bold
 *    #B2FFFFFF, description #99FFFFFF, margin 48, slide-in 500 ms, hold 5500 ms (VERIFIED SystemUI).
 *  - 1.3 quick panel: tiles #E5545454 / text #B2FFFFFF; focused #FFFFFFFF / text #E5000000, no
 *    scale; radius 24; enter 360 ms PathInterpolator(0,0,0,1) over 608 px, alpha 250 ms, row i
 *    delayed i*16 ms; exit 260 ms (VERIFIED newsettings misckey). Stock draws that column on the
 *    left; our panel is the right-side SettingService variant (RESULT_quicksettings), so the slide
 *    is mirrored.
 * Panel width 0.34 x W (~650 px, RESULT_quicksettings decision "Panel window").
 */
public final class Theme {
    private Theme() {}

    public static final float DESIGN_WIDTH = 1920f;

    // ------------------------------------------------------------------ colours
    public static final int PANEL_BG = 0xEB1C1C1C;
    public static final int PANEL_STROKE = 0x1AFFFFFF;
    public static final int ROW_BG = 0x00000000;
    public static final int ROW_BG_FOCUSED = 0xFFFFFFFF;
    public static final int TILE_BG = 0xE5545454;
    public static final int TEXT = 0xE5FFFFFF;
    public static final int TEXT_DIM = 0xB2FFFFFF;
    public static final int TEXT_FAINT = 0x80FFFFFF;
    public static final int TEXT_FOCUSED = 0xE5000000;
    public static final int TEXT_FOCUSED_DIM = 0x99000000;
    public static final int ACCENT = 0xFF3B78E7;            // Lumen token z9x_accent_strong (was the XGIMI blue 2F8CFF)
    public static final int SWITCH_OFF = 0x4DFFFFFF;
    public static final int SWITCH_OFF_FOCUSED = 0x4D000000;
    public static final int TRACK = 0x33FFFFFF;
    public static final int TRACK_FOCUSED = 0x26000000;
    public static final int NOTIFY_BG = 0xFA292929;
    public static final int NOTIFY_STROKE = 0x1AFFFFFF;
    public static final int NOTIFY_TITLE = 0xB2FFFFFF;
    public static final int NOTIFY_DESC = 0x99FFFFFF;

    // ------------------------------------------------------------------ sizes (design px)
    public static final float PANEL_WIDTH_FRACTION = 0.34f;
    public static final float PANEL_MARGIN = 24;
    public static final float PANEL_RADIUS = 36;
    public static final float PANEL_PAD_H = 28;
    public static final float PANEL_PAD_TOP = 40;
    public static final float PANEL_TITLE_SIZE = 40;
    public static final float ROW_HEIGHT = 92;
    public static final float ROW_SLIDER_HEIGHT = 120;
    public static final float ROW_RADIUS = 24;
    public static final float ROW_PAD_H = 28;
    public static final float ROW_GAP = 6;
    public static final float ROW_TITLE_SIZE = 30;
    public static final float ROW_VALUE_SIZE = 26;
    public static final float HEADER_SIZE = 24;
    public static final float HEADER_HEIGHT = 64;
    public static final float STROKE = 3;
    public static final float NOTIFY_W = 468;
    public static final float NOTIFY_H = 156;
    public static final float NOTIFY_RADIUS = 36;
    public static final float NOTIFY_MARGIN = 48;
    public static final float NOTIFY_ICON = 72;
    public static final float NOTIFY_TITLE_SIZE = 30;
    public static final float NOTIFY_DESC_SIZE = 24;
    public static final float DIALOG_WIDTH = 860;

    // ------------------------------------------------------------------ motion (ms)
    public static final long PANEL_ENTER_MS = 360;
    public static final long PANEL_FADE_MS = 250;
    public static final long PANEL_EXIT_MS = 260;
    public static final long ROW_STAGGER_MS = 16;
    public static final float PANEL_SLIDE = 608;
    public static final long NOTIFY_SLIDE_MS = 500;
    public static final long NOTIFY_HOLD_MS = 5_500;
    public static final long PAGE_SWITCH_MS = 140;

    /** Stock PathInterpolator(0,0,0,1) used by the quick panel. New instance per animation is fine. */
    public static PathInterpolator panelInterpolator() {
        return new PathInterpolator(0f, 0f, 0f, 1f);
    }

    // ------------------------------------------------------------------ scale
    /** displayWidth / 1920. */
    public static float scale(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, m.heightPixels);
        return w <= 0 ? 1f : w / DESIGN_WIDTH;
    }

    /** Design px -> real px. */
    public static int px(Context c, float design) {
        return Math.round(design * scale(c));
    }

    public static float pxf(Context c, float design) {
        return design * scale(c);
    }

    public static TextPaint text(Context c, float designSize, int color, boolean bold) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTextSize(pxf(c, designSize));
        p.setColor(color);
        if (bold) p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return p;
    }

    public static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    public static Paint stroke(Context c, int color, float designWidth) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(pxf(c, designWidth));
        return p;
    }
}
