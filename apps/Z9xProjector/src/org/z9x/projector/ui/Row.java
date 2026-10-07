package org.z9x.projector.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

/**
 * Base of every list row of the XGIMI-style panels: ONE View per row, drawn with Canvas (no nested
 * layouts, no XML), hardware accelerated, invalidate() on change only.
 *
 * Look (Theme / FEATURE_SPEC 1.3): unfocused = transparent background, title #E5FFFFFF, value
 * #B2FFFFFF; focused = white rounded background (radius 24), title #E5000000, value #99000000; no
 * scale. Disabled rows are drawn at 40 % and are not focusable.
 *
 * Keys: DPAD_CENTER / ENTER -> {@link #onActivate()}; DPAD_LEFT / RIGHT -> {@link #onStep(int, int)}
 * (repeat count passed). UP / DOWN are left to the framework focus search (ScrollView keeps the
 * focused row visible). Subclasses draw their right-hand part in {@link #drawRight}.
 *
 * RTL (Arabic; the app declares supportsRtl): subclasses always draw in LTR terms. When the row's
 * layout direction is RTL, onDraw mirrors the canvas horizontally, so the title ends up on the
 * right and the value / switch / chevrons / slider on the left; text is drawn through
 * {@link #drawTextAt}, which un-mirrors each string in place so glyphs stay readable. LEFT / RIGHT
 * are swapped in RTL ({@link #onStep} keeps its logical meaning: -1 = towards the start).
 */
public abstract class Row extends View {
    protected final TextPaint titlePaint;
    protected final TextPaint valuePaint;
    protected final Paint bgPaint;
    protected final float padH;
    protected final float radius;
    private final RectF rect = new RectF();
    private final Path chevron = new Path();
    private final Paint chevronPaint;
    private CharSequence title;
    private CharSequence shownTitle;
    private int shownTitleWidth = -1;
    private boolean rowEnabled = true;
    /** True while onDraw runs with the mirrored canvas (RTL). */
    private boolean mirrored;

    protected Row(Context c, CharSequence title) {
        super(c);
        this.title = title == null ? "" : title;
        titlePaint = Theme.text(c, Theme.ROW_TITLE_SIZE, Theme.TEXT, false);
        valuePaint = Theme.text(c, Theme.ROW_VALUE_SIZE, Theme.TEXT_DIM, false);
        bgPaint = Theme.fill(Theme.ROW_BG_FOCUSED);
        chevronPaint = Theme.stroke(c, Theme.TEXT_DIM, 3);
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        padH = Theme.pxf(c, Theme.ROW_PAD_H);
        radius = Theme.pxf(c, Theme.ROW_RADIUS);
        setFocusable(true);
        setClickable(true);
        setDefaultFocusHighlightEnabled(false);
        setWillNotDraw(false);
    }

    // ------------------------------------------------------------------ API
    public void setTitle(CharSequence t) {
        title = t == null ? "" : t;
        shownTitleWidth = -1;
        invalidate();
    }

    public CharSequence getTitle() { return title; }

    /** Disabled rows are dimmed and skipped by D-pad focus. */
    public void setRowEnabled(boolean enabled) {
        if (rowEnabled == enabled) return;
        rowEnabled = enabled;
        setFocusable(enabled);
        setAlpha(enabled ? 1f : 0.4f);
        invalidate();
    }

    public boolean isRowEnabled() { return rowEnabled; }

    // ------------------------------------------------------------------ subclass hooks
    /** Design-px height of this row. */
    protected float designHeight() { return Theme.ROW_HEIGHT; }

    /** Width (real px) reserved at the right for the value part. */
    protected abstract float rightWidth();

    /** Draws the value part; {@code right} is the inner right edge, {@code cy} the title baseline centre. */
    protected abstract void drawRight(Canvas c, float right, float cy, boolean focused);

    /** CENTER / ENTER. Return true when handled. */
    protected boolean onActivate() { return false; }

    /** LEFT (-1) / RIGHT (+1). Return true when handled (consumes the key). */
    protected boolean onStep(int dir, int repeatCount) { return false; }

    /** Vertical centre of the title line (sliders use the upper half). */
    protected float titleCenterY() { return getHeight() / 2f; }

    // ------------------------------------------------------------------ View
    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.round(Theme.pxf(getContext(), designHeight())));
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    /** True when this row is laid out right-to-left (RTL locale, supportsRtl). */
    public boolean isRtl() {
        return getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    @Override
    protected void onDraw(Canvas c) {
        mirrored = isRtl();
        if (mirrored) {
            c.save();
            c.scale(-1f, 1f, getWidth() / 2f, 0f);
        }
        try {
            drawRow(c);
        } finally {
            if (mirrored) c.restore();
            mirrored = false;
        }
    }

    private void drawRow(Canvas c) {
        boolean focused = isFocused();
        if (focused) {
            rect.set(0, 0, getWidth(), getHeight());
            c.drawRoundRect(rect, radius, radius, bgPaint);
        }
        titlePaint.setColor(focused ? Theme.TEXT_FOCUSED : Theme.TEXT);
        valuePaint.setColor(focused ? Theme.TEXT_FOCUSED_DIM : Theme.TEXT_DIM);
        float right = getWidth() - padH;
        float rw = rightWidth();
        int avail = (int) Math.max(0, right - rw - padH - (rw > 0 ? padH / 2 : 0));
        if (avail != shownTitleWidth) {
            shownTitle = TextUtils.ellipsize(title, titlePaint, avail, TextUtils.TruncateAt.END);
            shownTitleWidth = avail;
        }
        float cy = titleCenterY();
        float base = cy - (titlePaint.descent() + titlePaint.ascent()) / 2f;
        drawTextAt(c, shownTitle, padH, base, titlePaint);
        drawRight(c, right, cy, focused);
    }

    /**
     * Draws {@code text} with its left edge at {@code x} (LTR coordinates). Inside the mirrored RTL
     * canvas the string is flipped back around its own centre, so it reads normally at the mirrored
     * position. Subclasses must draw text only through this method.
     */
    protected void drawTextAt(Canvas c, CharSequence text, float x, float base, Paint p) {
        if (text == null || text.length() == 0) return;
        if (!mirrored) {
            c.drawText(text, 0, text.length(), x, base, p);
            return;
        }
        float w = p.measureText(text, 0, text.length());
        c.save();
        c.scale(-1f, 1f, x + w / 2f, 0f);
        c.drawText(text, 0, text.length(), x, base, p);
        c.restore();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (!rowEnabled) return super.onKeyDown(keyCode, ev);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (ev.getRepeatCount() == 0 && onActivate()) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    return true;
                }
                return ev.getRepeatCount() > 0 || super.onKeyDown(keyCode, ev);
            case KeyEvent.KEYCODE_DPAD_LEFT:                   // mirrored in RTL
                return onStep(isRtl() ? 1 : -1, ev.getRepeatCount()) || super.onKeyDown(keyCode, ev);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return onStep(isRtl() ? -1 : 1, ev.getRepeatCount()) || super.onKeyDown(keyCode, ev);
            default:
                return super.onKeyDown(keyCode, ev);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent ev) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return true;                                  // never a second (click) action
            default:
                return super.onKeyUp(keyCode, ev);
        }
    }

    // ------------------------------------------------------------------ drawing helpers
    /** Draws a right-pointing chevron whose right edge is {@code right}. Returns its width. */
    protected float drawChevron(Canvas c, float right, float cy, boolean focused) {
        float h = Theme.pxf(getContext(), 18);
        float w = h * 0.55f;
        chevron.rewind();
        chevron.moveTo(right - w, cy - h / 2);
        chevron.lineTo(right, cy);
        chevron.lineTo(right - w, cy + h / 2);
        chevronPaint.setColor(focused ? Theme.TEXT_FOCUSED_DIM : Theme.TEXT_DIM);
        c.drawPath(chevron, chevronPaint);
        return w;
    }

    /** Draws a left-pointing chevron whose left edge is {@code left}. */
    protected void drawChevronLeft(Canvas c, float left, float cy, boolean focused, boolean faint) {
        float h = Theme.pxf(getContext(), 18);
        float w = h * 0.55f;
        chevron.rewind();
        chevron.moveTo(left + w, cy - h / 2);
        chevron.lineTo(left, cy);
        chevron.lineTo(left + w, cy + h / 2);
        int col = focused ? Theme.TEXT_FOCUSED_DIM : Theme.TEXT_DIM;
        chevronPaint.setColor(faint ? (col & 0x00FFFFFF) | 0x33000000 : col);
        c.drawPath(chevron, chevronPaint);
    }

    /** Right chevron variant that can be faint (end of a choice list). */
    protected void drawChevronRight(Canvas c, float right, float cy, boolean focused, boolean faint) {
        float h = Theme.pxf(getContext(), 18);
        float w = h * 0.55f;
        chevron.rewind();
        chevron.moveTo(right - w, cy - h / 2);
        chevron.lineTo(right, cy);
        chevron.lineTo(right - w, cy + h / 2);
        int col = focused ? Theme.TEXT_FOCUSED_DIM : Theme.TEXT_DIM;
        chevronPaint.setColor(faint ? (col & 0x00FFFFFF) | 0x33000000 : col);
        c.drawPath(chevron, chevronPaint);
    }

    protected static float chevronWidth(Context c) { return Theme.pxf(c, 18) * 0.55f; }

    /** Draws {@code text} right-aligned ending at {@code right}, vertically centred on cy. */
    protected float drawValueRight(Canvas c, CharSequence text, float right, float cy) {
        if (text == null || text.length() == 0) return 0;
        float w = valuePaint.measureText(text, 0, text.length());
        float base = cy - (valuePaint.descent() + valuePaint.ascent()) / 2f;
        drawTextAt(c, text, right - w, base, valuePaint);
        return w;
    }
}
