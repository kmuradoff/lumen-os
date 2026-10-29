package org.lumen.projector.panel;

import android.content.Context;
import android.os.SystemClock;

import org.lumen.projector.ui.ChoiceRow;

/**
 * Play 6 only (Lumen 1.0.6, audit 2026-10-24 B1): a choice row whose read-backs wait while the user's change is
 * still waiting for its delayed commit. ChoiceRow (a synced copy shared with the Z9X, apps/common/projector) let a
 * read-back of another row or a page refresh in that window put the old vendor value back, and the commit then sent
 * that old value: the user's change was lost. The Z9X stays as it is (device separation); its rows are unchanged.
 */
final class P6ChoiceRow extends ChoiceRow {
    /** A read-back right after the commit may still carry the old value: this much longer. */
    private static final long SETTLE_MS = 150;
    private long delay;
    private long pendingUntil;

    P6ChoiceRow(Context c, CharSequence title, CharSequence[] options, OnChoice l) {
        super(c, title, options, l);
    }

    @Override
    public ChoiceRow setCommitDelay(long ms) {
        delay = Math.max(0, ms);
        return super.setCommitDelay(ms);
    }

    /** A change the user made is waiting for its delayed commit. */
    boolean isPending() { return SystemClock.uptimeMillis() < pendingUntil; }

    @Override
    public ChoiceRow setSelected(int i) {
        if (isPending()) return this;
        return super.setSelected(i);
    }

    @Override
    protected boolean onStep(int dir, int repeatCount) {
        int before = getSelected();
        boolean r = super.onStep(dir, repeatCount);
        if (getSelected() != before) pendingUntil = SystemClock.uptimeMillis() + delay + SETTLE_MS;
        return r;
    }

    @Override
    protected boolean onActivate() {
        int before = getSelected();
        boolean r = super.onActivate();
        if (getSelected() != before) pendingUntil = SystemClock.uptimeMillis() + delay + SETTLE_MS;
        return r;
    }
}
