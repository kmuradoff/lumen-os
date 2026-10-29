package org.lumen.projector.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.AnimationUtils;

/**
 * Lumen OS 1.0.5: a running line for text that does not fit (owner: "the settings items must be a running line, so
 * you can read what is written"). Only while the row or tile has the focus and the text is wider than its room:
 * 1.2 s still at the start, then it scrolls at 40 dp/s until the end is visible, 1.2 s still, back to the start.
 * Unfocused or fitting text is drawn as before (ellipsized by the caller). Drawing only: the view asks for the next
 * frame with postInvalidateOnAnimation while it scrolls, nothing runs when it does not. LTR only; in RTL the callers
 * keep their ellipsis.
 */
public final class Marquee {
    private static final long PAUSE_MS = 1200;
    private static final float SPEED_DP = 40f;
    private long start = -1;

    /** Forget the position: the next scroll starts at the beginning again (focus lost, text changed). */
    public void reset() { start = -1; }

    /**
     * Draws {@code text} clipped to [{@code left}, {@code left + room}] at baseline {@code base}, scrolled to the
     * current position, and asks {@code v} for the next frame. Call only when the text is wider than {@code room}.
     */
    public void draw(View v, Canvas c, CharSequence text, float left, float room, float base, Paint p) {
        float textW = p.measureText(text, 0, text.length());
        float dist = Math.max(0f, textW - room);
        float speed = SPEED_DP * v.getResources().getDisplayMetrics().density;
        long now = AnimationUtils.currentAnimationTimeMillis();
        if (start < 0) start = now;
        long run = (long) (dist / speed * 1000f);
        long t = (now - start) % (PAUSE_MS + run + PAUSE_MS);
        float off = t < PAUSE_MS ? 0f : t < PAUSE_MS + run ? (t - PAUSE_MS) * speed / 1000f : dist;
        float top = base + p.ascent() - 4, bottom = base + p.descent() + 4;
        c.save();
        c.clipRect(left, top, left + room, bottom);
        c.drawText(text, 0, text.length(), left - off, base, p);
        c.restore();
        v.postInvalidateOnAnimation();
    }
}
