package org.z9x.projector.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.view.View;

/** Multi-line, non-focusable text (dialog message, hints). */
public class TextRow extends View {
    private final TextPaint paint;
    private final float padH, padV;
    private CharSequence text;
    private StaticLayout layout;
    private int layoutWidth = -1;

    public TextRow(Context c, CharSequence text) {
        this(c, text, Theme.ROW_VALUE_SIZE, Theme.TEXT_DIM);
    }

    public TextRow(Context c, CharSequence text, float designSize, int color) {
        super(c);
        this.text = text == null ? "" : text;
        paint = Theme.text(c, designSize, color, false);
        padH = Theme.pxf(c, Theme.ROW_PAD_H);
        padV = Theme.pxf(c, 12);
        setFocusable(false);
        setWillNotDraw(false);
    }

    public void setText(CharSequence t) {
        text = t == null ? "" : t;
        layoutWidth = -1;
        requestLayout();
        invalidate();
    }

    private void build(int w) {
        int inner = Math.max(1, (int) (w - 2 * padH));
        if (inner == layoutWidth && layout != null) return;
        // Paragraph direction follows the row (RTL locale: ALIGN_NORMAL = right-aligned, also for a
        // line that starts with Latin text such as "HDMI 1 · HDR10").
        layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, inner)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setTextDirection(getLayoutDirection() == LAYOUT_DIRECTION_RTL
                        ? TextDirectionHeuristics.FIRSTSTRONG_RTL : TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .build();
        layoutWidth = inner;
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        layoutWidth = -1;                                 // paragraph direction changed: rebuild
        requestLayout();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        build(w);
        setMeasuredDimension(w, Math.round(layout.getHeight() + 2 * padV));
    }

    @Override
    protected void onDraw(Canvas c) {
        build(getWidth());
        c.save();
        c.translate(padH, padV);
        layout.draw(c);
        c.restore();
    }
}
