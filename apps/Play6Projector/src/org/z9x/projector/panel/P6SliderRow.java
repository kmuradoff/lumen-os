package org.z9x.projector.panel;

import android.content.Context;
import android.os.SystemClock;

import org.z9x.projector.ui.SliderRow;

/**
 * Play 6 only (Lumen 1.0.6, audit 2026-10-24 B1): a slider whose read-backs wait while the user's move is still
 * waiting for its delayed commit (default 200 ms in SliderRow, a synced copy shared with the Z9X). See P6ChoiceRow.
 */
final class P6SliderRow extends SliderRow {
    private static final long SETTLE_MS = 150;
    private long delay = 200;
    private long pendingUntil;

    P6SliderRow(Context c, CharSequence title, int min, int max, OnSlide l) {
        super(c, title, min, max, l);
    }

    @Override
    public SliderRow setCommitDelay(long ms) {
        delay = Math.max(0, ms);
        return super.setCommitDelay(ms);
    }

    @Override
    public SliderRow setValue(Integer v) {
        if (SystemClock.uptimeMillis() < pendingUntil) return this;
        return super.setValue(v);
    }

    @Override
    protected boolean onStep(int dir, int repeatCount) {
        int before = getValue();
        boolean r = super.onStep(dir, repeatCount);
        if (getValue() != before) pendingUntil = SystemClock.uptimeMillis() + delay + SETTLE_MS;
        return r;
    }
}
