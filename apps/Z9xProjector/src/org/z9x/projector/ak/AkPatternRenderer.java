package org.z9x.projector.ak;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;

/**
 * Draws the auto-keystone test pattern of {@link AkPatternSpec} with android.graphics, generated at
 * runtime (no XGIMI image is shipped). The same drawing order as the Mac harness that was compared
 * with the stock image: white background; ONE black path (checker cells + corner brackets + marker
 * squares, non-zero winding so adjacent squares have no seams); then per marker ONE anti-aliased
 * white path of its data cells (their edges are fractional, 160/6 px, like the stock image).
 *
 * The caption under the board (stock: blue icon + "自动梯形校正中", or orange icon + "光学变焦中"
 * for event 115) is drawn separately by {@link #drawCaption} so it can be localized and switched
 * without a second bitmap. The caption band lies below the checkerboard (y 999..1035).
 *
 * Lumen OS 1.0.1 (interface resolution 1080p / 2K / 4K): the bitmap is an ALPHA_8 coverage mask
 * ("how black", 0 = white) at the real UI size, drawn by AkWarpView in black over a white rect: the
 * same picture as the opaque ARGB pattern of 1.0, sharp at every resolution, at a quarter of the
 * memory (1080p 2.1 MB, 2K 3.7 MB, 4K 8.3 MB = what the 1080p ARGB bitmap took before).
 */
final class AkPatternRenderer {
    static final int CAPTION_KEYSTONE = 0, CAPTION_ZOOM = 1;
    private static final int ICON_BLUE = 0xFF4670FF;    // stock icon colour (measured)
    private static final int ICON_ORANGE = 0xFFFA8328;  // stock zoom icon colour (measured)

    private AkPatternRenderer() {}

    /**
     * Renders the pattern without caption into a new w x h ALPHA_8 mask (any thread): alpha = the black
     * of the pattern, 0 = its white. Same drawing order as the opaque one: the black path on the
     * (cleared = white) background, then the white data cells taking the black away again (DST_OUT is
     * exactly "white painted over black" for an anti-aliased edge).
     */
    static Bitmap render(AkPatternSpec s, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        b.eraseColor(Color.TRANSPARENT);
        Canvas c = new Canvas(b);
        c.scale(w / (float) AkPatternSpec.W, h / (float) AkPatternSpec.H);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);

        Path black = new Path();
        black.setFillType(Path.FillType.WINDING);
        AkPatternSpec.RectSink sb = (l, t, r, bt) -> black.addRect(l, t, r, bt, Path.Direction.CW);
        s.boardRects(sb);
        AkPatternSpec.bracketRects(sb);
        for (int id = 0; id < 4; id++) s.markerSquare(id, sb);
        p.setColor(Color.BLACK);
        c.drawPath(black, p);

        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        for (int id = 0; id < 4; id++) {
            Path white = new Path();
            white.setFillType(Path.FillType.WINDING);
            s.markerWhiteCells(id, (l, t, r, bt) -> white.addRect(l, t, r, bt, Path.Direction.CW));
            c.drawPath(white, p);
        }
        return b;
    }

    /** Paints reused by {@link #drawCaption}. */
    static final class CaptionPaints {
        final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final Path path = new Path();

        CaptionPaints() {
            text.setColor(Color.BLACK);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            glyph.setColor(Color.WHITE);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
        }
    }

    /**
     * Draws icon + caption centred under the board, in DESIGN coordinates (the caller's canvas is
     * already scaled from 1920 x 1080 to the view at any UI resolution and warped with the pattern). Our own simple glyphs: a keystone
     * trapezoid (keystone) or a magnifier (optical zoom).
     */
    static void drawCaption(Canvas c, CaptionPaints cp, String caption, int kind) {
        if (caption == null) caption = "";
        float icon = AkPatternSpec.CAPTION_ICON, gap = AkPatternSpec.CAPTION_GAP;
        float size = AkPatternSpec.CAPTION_TEXT_SIZE;
        cp.text.setTextSize(size);
        float tw = cp.text.measureText(caption);
        float maxText = AkPatternSpec.CAPTION_MAX_W - icon - gap;
        if (tw > maxText && tw > 0) {                         // long translations: shrink to fit
            size = Math.max(18f, size * maxText / tw);
            cp.text.setTextSize(size);
            tw = cp.text.measureText(caption);
        }
        float total = icon + (tw > 0 ? gap + tw : 0);
        float left = AkPatternSpec.CAPTION_CENTER_X - total / 2f;
        float top = AkPatternSpec.CAPTION_TOP;

        cp.icon.setColor(kind == CAPTION_ZOOM ? ICON_ORANGE : ICON_BLUE);
        cp.r.set(left, top, left + icon, top + icon);
        c.drawRoundRect(cp.r, 8, 8, cp.icon);
        cp.glyph.setStrokeWidth(3f);
        cp.path.reset();
        if (kind == CAPTION_ZOOM) {                           // magnifier with "+"
            float cx = left + 15, cy = top + 15, rr = 8;
            c.drawCircle(cx, cy, rr, cp.glyph);
            c.drawLine(cx + rr * 0.72f, cy + rr * 0.72f, left + 28, top + 28, cp.glyph);
            c.drawLine(cx - 4, cy, cx + 4, cy, cp.glyph);
            c.drawLine(cx, cy - 4, cx, cy + 4, cp.glyph);
        } else {                                              // keystone trapezoid
            cp.path.moveTo(left + 12, top + 10);
            cp.path.lineTo(left + 24, top + 10);
            cp.path.lineTo(left + 29, top + 26);
            cp.path.lineTo(left + 7, top + 26);
            cp.path.close();
            c.drawPath(cp.path, cp.glyph);
        }
        if (tw > 0) {
            Paint.FontMetrics fm = cp.text.getFontMetrics();
            float baseline = top + icon / 2f - (fm.ascent + fm.descent) / 2f;
            c.drawText(caption, left + icon + gap, baseline, cp.text);
        }
    }
}
