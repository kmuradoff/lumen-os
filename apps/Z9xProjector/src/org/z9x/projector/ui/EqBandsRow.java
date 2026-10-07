package org.z9x.projector.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.view.KeyEvent;

/**
 * v6.4: the equalizer bands as vertical sliders in one row (D-pad driven).
 *
 * Keys: LEFT / RIGHT select a band (also while adjusting); OK starts / ends adjusting the selected
 * band; while adjusting UP / DOWN move it by one step (half a dB: values are in steps, labels come
 * from {@link #setFormatter}), held keys repeat. UP / DOWN outside adjusting move the focus to the
 * next row as usual; BACK while adjusting only ends adjusting (the owner calls {@link #stopEditing}
 * from its key hook). Losing focus ends adjusting.
 *
 * {@link Listener#onChange} decides each step (it may refuse a step that breaks a limit) and runs at
 * once (UI); {@link Listener#onCommit} runs 200 ms after the last accepted step with the whole
 * curve (and at once when the row is detached mid-change).
 *
 * Look: the title line (key hint) on top; below it one track per band with a centre (0 dB) mark,
 * the fill from 0 to the value, a knob; the selected band in the accent colour, its value above it
 * and its frequency label in bold. RTL: Row mirrors the canvas, so the bands run right to left.
 */
public class EqBandsRow extends Row {
    public interface Listener {
        /** The user wants {@code band} at {@code wanted}; return the value to show (old = refused). */
        int onChange(EqBandsRow row, int band, int wanted);
        /** 200 ms after the last accepted change: persist and apply {@link #getValues()}. */
        void onCommit(EqBandsRow row);
        /** UP / DOWN pressed with {@code band} already at its own limit (nothing changes). */
        default void onLimit(EqBandsRow row, int band, boolean up) {}
    }

    public interface Formatter { String format(int value); }

    private final int n, span;
    private final int[] values, min, max;
    private final String[] labels;
    private int selected;
    private boolean editing, known;
    private CharSequence hintIdle = "", hintEdit = "";
    private Listener listener;
    private Formatter formatter = Integer::toString;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable commit = () -> { if (listener != null) listener.onCommit(this); };
    private final Paint track, fill, fillSel, knob, zero;
    private final TextPaint small, smallBold;
    private final RectF r = new RectF();
    private final Path arrow = new Path();

    public EqBandsRow(Context c, String[] bandLabels, int[] min, int[] max, Listener l) {
        super(c, "");
        n = bandLabels.length;
        labels = bandLabels.clone();
        this.min = min.clone();
        this.max = max.clone();
        values = new int[n];
        int sp = 1;
        for (int i = 0; i < n; i++) sp = Math.max(sp, Math.max(Math.abs(min[i]), Math.abs(max[i])));
        span = sp;
        listener = l;
        track = Theme.fill(Theme.TRACK);
        fill = Theme.fill(Theme.TEXT_DIM);
        fillSel = Theme.fill(Theme.ACCENT);
        knob = Theme.fill(Theme.TEXT);
        zero = Theme.fill(Theme.TEXT_DIM);
        small = Theme.text(c, Theme.NOTIFY_DESC_SIZE, Theme.TEXT_DIM, false);
        smallBold = Theme.text(c, Theme.NOTIFY_DESC_SIZE, Theme.TEXT, true);
    }

    // ------------------------------------------------------------------ API
    /** Hints shown as the title: idle (select / OK to adjust) and while adjusting. */
    public EqBandsRow setHints(CharSequence idle, CharSequence edit) {
        hintIdle = idle == null ? "" : idle;
        hintEdit = edit == null ? "" : edit;
        setTitle(editing ? hintEdit : hintIdle);
        return this;
    }

    public EqBandsRow setFormatter(Formatter f) { if (f != null) formatter = f; invalidate(); return this; }

    /** Read-back values (no listener call); null = unknown. Ignored while the user is adjusting. */
    public void setValues(int[] v) {
        if (editing || h.hasCallbacks(commit)) return;
        known = v != null;
        if (v != null) for (int i = 0; i < n && i < v.length; i++) values[i] = clamp(i, v[i]);
        invalidate();
    }

    public int[] getValues() { return values.clone(); }

    public boolean isEditing() { return editing; }

    /** True while adjusting or while a change still waits for its commit. */
    public boolean isBusy() { return editing || h.hasCallbacks(commit); }

    public void stopEditing() {
        if (!editing) return;
        editing = false;
        setTitle(hintIdle);
        flush();
        invalidate();
    }

    /** Runs a pending commit now. */
    public void flush() {
        if (h.hasCallbacks(commit)) {
            h.removeCallbacks(commit);
            commit.run();
        }
    }

    private int clamp(int band, int v) { return Math.max(min[band], Math.min(max[band], v)); }

    // ------------------------------------------------------------------ Row
    @Override
    protected float designHeight() { return 300; }

    @Override
    protected float titleCenterY() { return Theme.pxf(getContext(), 34); }

    @Override
    protected float rightWidth() { return 0; }

    @Override
    protected void drawRight(Canvas c, float right, float cy, boolean focused) {
        Context ctx = getContext();
        float left = padH;
        float colW = (right - left) / n;
        float top = Theme.pxf(ctx, 104), bottom = getHeight() - Theme.pxf(ctx, 52);
        float valueBase = top - Theme.pxf(ctx, 14);
        float labelBase = getHeight() - Theme.pxf(ctx, 18);
        float tw = Theme.pxf(ctx, 8), kr = Theme.pxf(ctx, 11);
        int dimText = focused ? Theme.TEXT_FOCUSED_DIM : Theme.TEXT_DIM;
        int text = focused ? Theme.TEXT_FOCUSED : Theme.TEXT;
        track.setColor(focused ? Theme.TRACK_FOCUSED : Theme.TRACK);
        fill.setColor(dimText);
        knob.setColor(text);
        zero.setColor(dimText);
        small.setColor(dimText);
        smallBold.setColor(text);
        for (int i = 0; i < n; i++) {
            float cx = left + colW * (i + 0.5f);
            boolean sel = focused && i == selected;
            r.set(cx - tw / 2, top, cx + tw / 2, bottom);
            c.drawRoundRect(r, tw / 2, tw / 2, track);
            float y0 = yOf(0, i, top, bottom);
            c.drawRect(cx - tw * 1.3f, y0 - 1, cx + tw * 1.3f, y0 + 1, zero);
            if (known) {
                float y = yOf(values[i], i, top, bottom);
                r.set(cx - tw / 2, Math.min(y, y0), cx + tw / 2, Math.max(y, y0));
                c.drawRect(r, sel ? fillSel : fill);
                c.drawCircle(cx, y, sel ? kr * 1.25f : kr, sel ? fillSel : knob);
                if (sel) {
                    String v = formatter.format(values[i]);
                    drawTextAt(c, v, cx - smallBold.measureText(v) / 2f, valueBase, smallBold);
                    if (editing) {
                        float a = Theme.pxf(ctx, 9);
                        arrow(c, cx + kr * 2.6f, y - a * 1.4f, -1, a);
                        arrow(c, cx + kr * 2.6f, y + a * 1.4f, 1, a);
                    }
                }
            }
            TextPaint lp = sel ? smallBold : small;
            drawTextAt(c, labels[i], cx - lp.measureText(labels[i]) / 2f, labelBase, lp);
        }
    }

    /** Value -> y on one symmetric scale for all bands (0 dB is the same line; band ranges may differ). */
    private float yOf(int v, int band, float top, float bottom) {
        float f = (v + span) / (2f * span);
        return bottom - (bottom - top) * f;
    }

    private void arrow(Canvas c, float x, float y, int dir, float a) {
        arrow.rewind();
        arrow.moveTo(x, y + dir * a * 0.6f);            // tip (dir -1 = up)
        arrow.lineTo(x - a * 0.7f, y - dir * a * 0.4f);
        arrow.lineTo(x + a * 0.7f, y - dir * a * 0.4f);
        arrow.close();
        c.drawPath(arrow, fillSel);
    }

    @Override
    protected boolean onActivate() {
        if (!known) return false;
        editing = !editing;
        setTitle(editing ? hintEdit : hintIdle);
        if (!editing) flush();
        invalidate();
        return true;
    }

    @Override
    protected boolean onStep(int dir, int repeatCount) {
        int ns = Math.max(0, Math.min(n - 1, selected + dir));
        if (ns != selected) {
            selected = ns;
            invalidate();
        }
        return true;                                    // never leaves the row sideways
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (editing && known && isRowEnabled()
                && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            int step = keyCode == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1;
            int wanted = clamp(selected, values[selected] + step);
            if (wanted == values[selected]) {
                if (listener != null) listener.onLimit(this, selected, step > 0);
            } else {
                int got = listener != null ? listener.onChange(this, selected, wanted) : wanted;
                got = clamp(selected, got);
                if (got != values[selected]) {
                    values[selected] = got;
                    invalidate();
                    h.removeCallbacks(commit);
                    h.postDelayed(commit, 200);
                }
            }
            return true;
        }
        return super.onKeyDown(keyCode, ev);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        if (!gainFocus) stopEditing();
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    }

    @Override
    protected void onDetachedFromWindow() {
        editing = false;
        setTitle(hintIdle);
        flush();
        super.onDetachedFromWindow();
    }
}
