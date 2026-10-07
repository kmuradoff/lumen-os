package org.z9x.projector.source;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import org.z9x.projector.ui.Row;
import org.z9x.projector.ui.Theme;

/**
 * One entry of the Source overlay: title on the left; on the right an optional state dot + state
 * text ("Connected" / "No signal") and an optional check mark for the input that is on screen.
 * Drawn on Canvas like every other row of the UI kit. Main thread only.
 */
final class SourceRow extends Row {
    /** Dot colour for a connected input (signal present). */
    private static final int DOT_ON = 0xFF34C759;
    private static final float DOT_R = 7;          // design px
    private static final float CHECK_W = 26;       // design px

    private final Runnable onClick;
    private final Paint dotPaint = Theme.fill(DOT_ON);
    private final Paint checkPaint;
    private final Path checkPath = new Path();
    private CharSequence state;
    private boolean showDot;
    private boolean dotOn;
    private boolean current;

    SourceRow(Context c, CharSequence title, Runnable onClick) {
        super(c, title);
        this.onClick = onClick;
        checkPaint = Theme.stroke(c, Theme.TEXT, 4);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** state text (null = none); dot: null = no dot, true = signal, false = no signal. */
    void setState(CharSequence text, Boolean dot) {
        state = text;
        showDot = dot != null;
        dotOn = dot != null && dot;
        invalidate();
    }

    void setCurrent(boolean cur) {
        if (current == cur) return;
        current = cur;
        invalidate();
    }

    boolean isCurrent() { return current; }

    private float gap() { return padH / 2; }

    @Override
    protected float rightWidth() {
        Context c = getContext();
        float w = 0;
        if (current) w += Theme.pxf(c, CHECK_W) + gap();
        if (state != null && state.length() > 0) w += valuePaint.measureText(state, 0, state.length());
        if (showDot) w += Theme.pxf(c, DOT_R) * 2 + gap();
        return w;
    }

    @Override
    protected void drawRight(Canvas c, float right, float cy, boolean focused) {
        Context ctx = getContext();
        float r = right;
        if (current) {
            float w = Theme.pxf(ctx, CHECK_W);
            float h = w * 0.72f;
            checkPath.rewind();
            checkPath.moveTo(r - w, cy);
            checkPath.lineTo(r - w * 0.62f, cy + h / 2);
            checkPath.lineTo(r, cy - h / 2);
            checkPaint.setColor(focused ? Theme.TEXT_FOCUSED : Theme.TEXT);
            c.drawPath(checkPath, checkPaint);
            r -= w + gap();
        }
        if (state != null && state.length() > 0) {
            r -= drawValueRight(c, state, r, cy);
        }
        if (showDot) {
            float rad = Theme.pxf(ctx, DOT_R);
            r -= gap();
            int off = focused ? Theme.TEXT_FOCUSED_DIM : Theme.TEXT_FAINT;
            dotPaint.setColor(dotOn ? DOT_ON : off);
            c.drawCircle(r - rad, cy, rad, dotPaint);
        }
    }

    @Override
    protected boolean onActivate() {
        if (onClick == null) return false;
        onClick.run();
        return true;
    }
}
