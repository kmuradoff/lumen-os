package org.z9x.projector.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Title + switch. CENTER asks the listener for the opposite state; the row then shows "pending"
 * (dimmed switch) until the owner calls {@link #setChecked(Boolean)} with the value read back from
 * the HAL (or the old value on failure). A null state = unknown (switch hidden, "—" shown).
 * LEFT/RIGHT do nothing (stock: switches toggle on OK only).
 */
public class ToggleRow extends Row {
    public interface OnToggle { void onToggle(ToggleRow row, boolean wanted); }

    private Boolean checked;
    private boolean pending;
    private OnToggle listener;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob;
    private final RectF r = new RectF();
    private final float tw, th;

    public ToggleRow(Context c, CharSequence title, OnToggle l) {
        super(c, title);
        listener = l;
        knob = Theme.fill(0xFFFFFFFF);
        tw = Theme.pxf(c, 64);
        th = Theme.pxf(c, 36);
    }

    public ToggleRow setOnToggle(OnToggle l) { listener = l; return this; }

    /** Main thread. Clears the pending state. */
    public ToggleRow setChecked(Boolean v) {
        checked = v;
        pending = false;
        invalidate();
        return this;
    }

    public Boolean isChecked() { return checked; }

    public boolean isPending() { return pending; }

    public ToggleRow setPending(boolean p) { pending = p; invalidate(); return this; }

    @Override
    protected float rightWidth() { return tw; }

    @Override
    protected void drawRight(Canvas c, float right, float cy, boolean focused) {
        if (checked == null) {
            drawValueRight(c, "—", right, cy);
            return;
        }
        boolean on = checked;
        r.set(right - tw, cy - th / 2, right, cy + th / 2);
        int col = on ? Theme.ACCENT : (focused ? Theme.SWITCH_OFF_FOCUSED : Theme.SWITCH_OFF);
        track.setColor(col);
        track.setAlpha(pending ? 0x60 : 0xFF);
        c.drawRoundRect(r, th / 2, th / 2, track);
        float kr = th / 2 - Theme.pxf(getContext(), 4);
        float kx = on ? right - th / 2 : right - tw + th / 2;
        knob.setAlpha(pending ? 0x99 : 0xFF);
        c.drawCircle(kx, cy, kr, knob);
    }

    @Override
    protected boolean onActivate() {
        if (checked == null || pending || listener == null) return checked != null;
        pending = true;
        invalidate();
        listener.onToggle(this, !checked);
        return true;
    }
}
