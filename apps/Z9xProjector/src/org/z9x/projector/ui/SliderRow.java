package org.z9x.projector.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

/**
 * Title + value on the first line, a bar below. LEFT/RIGHT change the value by the step (held keys
 * auto-repeat; after 10 repeats the step is x2, after 30 x5). {@link OnSlide#onChanging} runs at
 * once (UI only); {@link OnSlide#onCommit} runs once {@link #setCommitDelay} ms (default 200,
 * stock BrightnessRepository delay(200) + DROP_OLDEST) after the last change, with the final value
 * only. A step beyond min/max calls the {@link EdgeListener} (e.g. Boost above lamp level 10).
 */
public class SliderRow extends Row {
    public interface OnSlide {
        default void onChanging(SliderRow row, int value) {}
        void onCommit(SliderRow row, int value);
    }

    public interface EdgeListener {
        /** dir -1 = below min, +1 = above max. Return true when handled. */
        boolean onEdge(SliderRow row, int dir);
    }

    public interface Formatter { CharSequence format(int value); }

    private int min, max, step = 1, value;
    private boolean known;
    private OnSlide listener;
    private EdgeListener edge;
    private Formatter formatter;
    private long commitDelay = 200;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable commit = () -> { if (listener != null) listener.onCommit(this, value); };
    private final Paint trackPaint, fillPaint;
    private final RectF r = new RectF();

    public SliderRow(Context c, CharSequence title, int min, int max, OnSlide l) {
        super(c, title);
        this.min = min;
        this.max = Math.max(min, max);
        this.value = min;
        listener = l;
        trackPaint = Theme.fill(Theme.TRACK);
        fillPaint = Theme.fill(Theme.ACCENT);
    }

    public SliderRow setRange(int mn, int mx) {
        min = mn;
        max = Math.max(mn, mx);
        value = Math.max(min, Math.min(max, value));
        invalidate();
        return this;
    }

    public SliderRow setStep(int s) { step = Math.max(1, s); return this; }

    public SliderRow setFormatter(Formatter f) { formatter = f; invalidate(); return this; }

    public SliderRow setEdgeListener(EdgeListener e) { edge = e; return this; }

    public SliderRow setOnSlide(OnSlide l) { listener = l; return this; }

    public SliderRow setCommitDelay(long ms) { commitDelay = Math.max(0, ms); return this; }

    /** Read-back value (no listener call). Pass null for unknown. */
    public SliderRow setValue(Integer v) {
        known = v != null;
        if (v != null) value = Math.max(min, Math.min(max, v));
        invalidate();
        return this;
    }

    public int getValue() { return value; }

    public boolean isKnown() { return known; }

    public int getMin() { return min; }

    public int getMax() { return max; }

    @Override
    protected float designHeight() { return Theme.ROW_SLIDER_HEIGHT; }

    @Override
    protected float titleCenterY() { return getHeight() * 0.38f; }

    private CharSequence label() {
        if (!known) return "—";
        return formatter != null ? formatter.format(value) : Integer.toString(value);
    }

    @Override
    protected float rightWidth() {
        CharSequence t = label();
        return valuePaint.measureText(t, 0, t.length());
    }

    @Override
    protected void drawRight(Canvas c, float right, float cy, boolean focused) {
        drawValueRight(c, label(), right, cy);
        float barH = Theme.pxf(getContext(), 8);
        float top = getHeight() * 0.72f - barH / 2;
        r.set(padH, top, right, top + barH);
        trackPaint.setColor(focused ? Theme.TRACK_FOCUSED : Theme.TRACK);
        c.drawRoundRect(r, barH / 2, barH / 2, trackPaint);
        if (known && max > min) {
            float f = (value - min) / (float) (max - min);
            r.right = padH + (right - padH) * f;
            c.drawRoundRect(r, barH / 2, barH / 2, fillPaint);
        }
    }

    @Override
    protected boolean onStep(int dir, int repeatCount) {
        if (!known) return true;
        int mult = repeatCount >= 30 ? 5 : repeatCount >= 10 ? 2 : 1;
        int nv = value + dir * step * mult;
        if (nv < min || nv > max) {
            if (value == (dir < 0 ? min : max)) {
                if (repeatCount == 0 && edge != null) edge.onEdge(this, dir);
                return true;
            }
            nv = Math.max(min, Math.min(max, nv));
        }
        value = nv;
        invalidate();
        if (listener != null) listener.onChanging(this, value);
        h.removeCallbacks(commit);
        if (commitDelay == 0) commit.run(); else h.postDelayed(commit, commitDelay);
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (h.hasCallbacks(commit)) {                      // panel closed mid-slide: still apply it
            h.removeCallbacks(commit);
            commit.run();
        }
        super.onDetachedFromWindow();
    }
}
