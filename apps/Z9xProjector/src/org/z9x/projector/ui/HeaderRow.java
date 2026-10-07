package org.z9x.projector.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** Section header inside a page. Not focusable. */
public class HeaderRow extends View {
    private final TextPaint paint;
    private final float padH;
    private CharSequence text;

    public HeaderRow(Context c, CharSequence text) {
        super(c);
        this.text = text == null ? "" : text;
        paint = Theme.text(c, Theme.HEADER_SIZE, Theme.TEXT_FAINT, true);
        padH = Theme.pxf(c, Theme.ROW_PAD_H);
        setFocusable(false);
        setWillNotDraw(false);
    }

    public void setText(CharSequence t) { text = t == null ? "" : t; invalidate(); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), Math.round(Theme.pxf(getContext(), Theme.HEADER_HEIGHT)));
    }

    @Override
    protected void onDraw(Canvas c) {
        CharSequence t = TextUtils.ellipsize(text, paint, getWidth() - 2 * padH, TextUtils.TruncateAt.END);
        float base = getHeight() - Theme.pxf(getContext(), 14);
        float x = padH;
        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {            // right-aligned in RTL
            x = getWidth() - padH - paint.measureText(t, 0, t.length());
        }
        c.drawText(t, 0, t.length(), x, base, paint);
    }
}
