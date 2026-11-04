package org.lumen.projector.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;

/**
 * Title + "<  value  >". LEFT/RIGHT step through the options WITHOUT wrapping (stock analysis:
 * "enums cycle and do not wrap"). CENTER runs {@link #setOnOpen} (e.g. push a sub-page with the full
 * list) and otherwise does nothing: the arrows shown are the only way to change the value (
 * 2026-11-04; OK changes a value only on a toggle row). The listener gets the final index after
 * {@link #setCommitDelay} ms without a key (default 0 = immediately), so quick presses send one
 * HAL call. Index -1 = unknown value ("—", or the text of {@link #setPlaceholder}).
 */
public class ChoiceRow extends Row {
    public interface OnChoice { void onChoice(ChoiceRow row, int index); }

    private CharSequence[] options = new CharSequence[0];
    private int index = -1;
    private OnChoice listener;
    private Runnable onOpen;
    private long commitDelay;
    /** Shown instead of "—" while no option is selected (null = "—"). */
    private CharSequence placeholder;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable commit = () -> { if (listener != null && index >= 0) listener.onChoice(this, index); };
    private final float arrowGap;

    public ChoiceRow(Context c, CharSequence title, CharSequence[] options, OnChoice l) {
        super(c, title);
        if (options != null) this.options = options;
        listener = l;
        arrowGap = Theme.pxf(c, 16);
    }

    public ChoiceRow setOptions(CharSequence[] o) {
        options = o == null ? new CharSequence[0] : o;
        if (index >= options.length) index = -1;
        invalidate();
        return this;
    }

    /** Sets the shown index without calling the listener (read-back). */
    public ChoiceRow setSelected(int i) {
        index = (i >= 0 && i < options.length) ? i : -1;
        invalidate();
        return this;
    }

    public int getSelected() { return index; }

    public ChoiceRow setOnChoice(OnChoice l) { listener = l; return this; }

    public ChoiceRow setOnOpen(Runnable r) { onOpen = r; return this; }

    public ChoiceRow setCommitDelay(long ms) { commitDelay = Math.max(0, ms); return this; }

    /**
     * Lumen OS 1.0.8: the text shown while no option is selected, for a device value that is not one of the options
     * (stock colour temperature "Colour temp 3" in the Office mode); null = "—".
     */
    public ChoiceRow setPlaceholder(CharSequence p) {
        if (!TextUtils.equals(placeholder, p)) {
            placeholder = p;
            invalidate();
        }
        return this;
    }

    /** Running line for a value that does not fit between the arrows (Marquee). */
    private final Marquee valueMarquee = new Marquee();

    private CharSequence label() { return index >= 0 ? options[index] : placeholder(); }

    private CharSequence placeholder() { return placeholder != null ? placeholder : "—"; }

    /**
     * The value takes what the title does not need (at least 45 % of the row), so a short title leaves room for a long
     * value ("Game speed  < Standard, high frame rate >", which used to run over its arrows); a value that still does
     * not fit is ellipsized in drawRight.
     */
    @Override
    protected float rightWidth() {
        float arrows = 2 * (chevronWidth(getContext()) + arrowGap);
        CharSequence ph = placeholder();
        float max = valuePaint.measureText(ph, 0, ph.length());
        for (CharSequence o : options) max = Math.max(max, valuePaint.measureText(o, 0, o.length()));
        float free = getWidth() - 2.5f * padH - titleWidth() - arrows;
        return Math.min(max, Math.max(getWidth() * 0.45f, free)) + arrows;
    }

    @Override
    protected void drawRight(Canvas c, float right, float cy, boolean focused) {
        float cw = chevronWidth(getContext());
        float rw = rightWidth();
        float left = right - rw;
        drawChevronLeft(c, left, cy, focused, index <= 0);
        drawChevronRight(c, right, cy, focused, index < 0 || index >= options.length - 1);
        float room = Math.max(0, rw - 2 * (cw + arrowGap));
        CharSequence full = label();
        float base = cy - (valuePaint.descent() + valuePaint.ascent()) / 2f;
        if (focused && !isMirroredNow() && valuePaint.measureText(full, 0, full.length()) > room) {
            valueMarquee.draw(this, c, full, left + cw + arrowGap, room, base, valuePaint);
            return;
        }
        valueMarquee.reset();
        CharSequence t = TextUtils.ellipsize(full, valuePaint, room, TextUtils.TruncateAt.END);
        float w = valuePaint.measureText(t, 0, t.length());
        float mid = left + rw / 2f;
        drawTextAt(c, t, mid - w / 2f, base, valuePaint);
    }

    private void changeTo(int ni) {
        if (ni == index) return;
        index = ni;
        invalidate();
        h.removeCallbacks(commit);
        if (commitDelay == 0) commit.run(); else h.postDelayed(commit, commitDelay);
    }

    @Override
    protected boolean onStep(int dir, int repeatCount) {
        if (options.length == 0) return true;
        int ni = index < 0 ? 0 : index + dir;
        if (ni < 0 || ni >= options.length) return true;          // no wrap; consume
        changeTo(ni);
        return true;
    }

    /** Runs a pending (delayed) commit now; the row is about to be detached or covered. */
    public void flushPending() {
        if (h.hasCallbacks(commit)) {
            h.removeCallbacks(commit);
            commit.run();
        }
    }

    @Override
    protected boolean onActivate() {
        if (onOpen != null) { flushPending(); onOpen.run(); return true; }
        return options.length > 0;                        // consumed, value unchanged
    }

    @Override
    protected void onDetachedFromWindow() {
        flushPending();                                   // panel closed / page pushed mid-choice: still apply it
        super.onDetachedFromWindow();
    }
}
