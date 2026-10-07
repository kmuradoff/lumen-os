package org.z9x.projector.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** Title + check mark when selected: one option of a sub-page list (radio style). CENTER = click. */
public class CheckRow extends Row {
    private boolean checked;
    private Runnable onClick;
    private final Paint mark;
    private final Path path = new Path();
    private final float size;

    public CheckRow(Context c, CharSequence title, boolean checked, Runnable onClick) {
        super(c, title);
        this.checked = checked;
        this.onClick = onClick;
        mark = Theme.stroke(c, Theme.ACCENT, 4);
        mark.setStrokeCap(Paint.Cap.ROUND);
        mark.setStrokeJoin(Paint.Join.ROUND);
        size = Theme.pxf(c, 28);
    }

    public CheckRow setChecked(boolean v) { checked = v; invalidate(); return this; }

    public boolean isChecked() { return checked; }

    public CheckRow setOnClick(Runnable r) { onClick = r; return this; }

    @Override
    protected float rightWidth() { return size; }

    @Override
    protected void drawRight(Canvas c, float right, float cy, boolean focused) {
        if (!checked) return;
        float l = right - size;
        path.rewind();
        path.moveTo(l, cy);
        path.lineTo(l + size * 0.38f, cy + size * 0.36f);
        path.lineTo(right, cy - size * 0.4f);
        c.drawPath(path, mark);
    }

    @Override
    protected boolean onActivate() {
        if (onClick == null) return false;
        onClick.run();
        return true;
    }
}
