package org.z9x.projector.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;

/**
 * Title + optional value + chevron. CENTER runs the click action (open a sub-page, start an
 * action, open another screen). {@link #setChevron(boolean)} false gives a plain button row.
 */
public class NavRow extends Row {
    private CharSequence value;
    private CharSequence shownValue;
    private boolean chevron = true;
    private Runnable onClick;

    public NavRow(Context c, CharSequence title) {
        super(c, title);
    }

    public NavRow(Context c, CharSequence title, Runnable onClick) {
        super(c, title);
        this.onClick = onClick;
    }

    public NavRow setOnClick(Runnable r) { onClick = r; return this; }

    public NavRow setValue(CharSequence v) {
        value = v;
        shownValue = null;
        invalidate();
        return this;
    }

    public CharSequence getValue() { return value; }

    public NavRow setChevron(boolean show) { chevron = show; invalidate(); return this; }

    private float maxValueWidth() { return getWidth() * 0.42f; }

    @Override
    protected float rightWidth() {
        float w = chevron ? chevronWidth(getContext()) + padH / 2 : 0;
        if (value != null && value.length() > 0) {
            w += Math.min(valuePaint.measureText(value, 0, value.length()), maxValueWidth());
        }
        return w;
    }

    @Override
    protected void drawRight(Canvas c, float right, float cy, boolean focused) {
        float r = right;
        if (chevron) r -= drawChevron(c, r, cy, focused) + padH / 2;
        if (value != null && value.length() > 0) {
            if (shownValue == null) shownValue = TextUtils.ellipsize(value, valuePaint, maxValueWidth(), TextUtils.TruncateAt.END);
            drawValueRight(c, shownValue, r, cy);
        }
    }

    @Override
    protected boolean onActivate() {
        if (onClick == null) return false;
        onClick.run();
        return true;
    }
}
