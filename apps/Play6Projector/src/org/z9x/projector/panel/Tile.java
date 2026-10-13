package org.z9x.projector.panel;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.animation.PathInterpolator;

import org.z9x.projector.ui.Theme;

/**
 * One icon tile of the quick panel's top level (v6.2). ONE View drawn on Canvas: rounded
 * background, a line icon (res/drawable/ic_tile_*.xml, tinted here), a label and an optional
 * value line (current mode / level). A "wide" tile spans the whole grid row (icon, label, chevron
 * in one line) and is used for "All settings".
 *
 * Look (FEATURE_SPEC 1.3 stock tiles): radius 24, unfocused background #E5545454 with icon/label
 * #B2FFFFFF; focused white with #E5000000. Motion (v6.2 requirement 7): focus cross-fades the
 * colours and scales the tile up a little in 160 ms; OK presses it in (70 ms) and runs the action
 * at the bottom of the press, then springs back (the action usually replaces the page anyway).
 * All animation state is two floats driven by small ValueAnimators; nothing is allocated per frame.
 *
 * Main thread only. The action is wrapped in try/catch (persistent process must never crash).
 */
final class Tile extends View {
    private static final String TAG = "Z9xPanel";

    /** Design px (1920-wide). */
    static final float SQUARE_H = 176, WIDE_H = 96;
    private static final float ICON = 48, ICON_WIDE = 44, RADIUS = 24;
    private static final float ICON_TOP = 34, LABEL_GAP = 16, VALUE_GAP = 6, PAD_H = 14, WIDE_PAD_H = 32;
    private static final float LABEL_MAX = 26, LABEL_MIN = 18, VALUE_SIZE = 21, VALUE_MIN = 16;
    private static final float FOCUS_SCALE = 1.06f, FOCUS_SCALE_WIDE = 1.02f, PRESS_SCALE = 0.94f;
    static final long FOCUS_MS = 160;
    private static final long PRESS_DOWN_MS = 70, PRESS_UP_MS = 150;

    private static final int BG = Theme.TILE_BG, BG_F = Theme.ROW_BG_FOCUSED;
    private static final int FG = Theme.TEXT_DIM, FG_F = Theme.TEXT_FOCUSED;
    private static final int VAL = Theme.TEXT_FAINT, VAL_F = Theme.TEXT_FOCUSED_DIM;

    final String section;
    final boolean wide;
    private final Drawable icon;
    private final Runnable action;
    private CharSequence label;
    private CharSequence value;

    private final Paint bgPaint = Theme.fill(BG);
    private final TextPaint labelPaint;
    private final TextPaint valuePaint;
    private final Paint chevronPaint;
    private final Path chevron = new Path();
    private final RectF rect = new RectF();
    private final float radius;
    /** displayWidth / 1920, read once (Theme.scale). */
    private final float k;

    // label / value fitted to the current width (recomputed only when text or width change)
    private CharSequence shownLabel, shownValue;
    private float shownLabelSize;
    private float shownValueSize;
    private int fitWidth = -1;

    // animation state
    private float focusF;                 // 0 unfocused .. 1 focused (colours + scale)
    private float press = 1f;             // multiplies the scale while OK is pressed
    private int lastTint;
    private final ValueAnimator focusAnim = ValueAnimator.ofFloat(0f, 1f);
    private ValueAnimator pressAnim;
    private boolean pressing;

    Tile(Context c, String section, int iconRes, CharSequence label, boolean wide, Runnable action) {
        super(c);
        this.section = section;
        this.wide = wide;
        this.action = action;
        Drawable d = null;
        try {
            d = c.getDrawable(iconRes);
            if (d != null) d = d.mutate();
        } catch (Throwable t) {
            Log.w(TAG, "tile icon " + section + ": " + t);
        }
        icon = d;
        labelPaint = Theme.text(c, LABEL_MAX, FG, false);
        valuePaint = Theme.text(c, VALUE_SIZE, VAL, false);
        chevronPaint = Theme.stroke(c, FG, 3);
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        k = Theme.scale(c);
        radius = px(RADIUS);
        setLabel(label);
        setFocusable(true);
        setClickable(true);
        setDefaultFocusHighlightEnabled(false);
        setWillNotDraw(false);
        focusAnim.setDuration(FOCUS_MS);
        focusAnim.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        focusAnim.addUpdateListener(a -> {
            focusF = (float) a.getAnimatedValue();
            applyScale();
            invalidate();
        });
    }

    void setLabel(CharSequence l) {
        label = l == null ? "" : l;
        setContentDescription(label);
        fitWidth = -1;
        invalidate();
    }

    /** Current value under the label (null/empty = none). Cheap when unchanged. */
    void setValue(CharSequence v) {
        if (TextUtils.equals(v, value)) return;
        value = v;
        fitWidth = -1;
        invalidate();
    }

    CharSequence getValue() { return value; }

    // ------------------------------------------------------------------ focus / press motion
    @Override
    protected void onFocusChanged(boolean gain, int direction, Rect prev) {
        super.onFocusChanged(gain, direction, prev);
        float target = gain ? 1f : 0f;
        focusAnim.cancel();
        if (!isAttachedToWindow() || Math.abs(focusF - target) < 0.001f) {
            focusF = target;
            applyScale();
            invalidate();
            return;
        }
        focusAnim.setFloatValues(focusF, target);
        focusAnim.start();
    }

    private void applyScale() {
        float max = wide ? FOCUS_SCALE_WIDE : FOCUS_SCALE;
        float s = (1f + (max - 1f) * focusF) * press;
        setScaleX(s);
        setScaleY(s);
    }

    @Override
    protected void onDetachedFromWindow() {
        // Page switches remove the tile: end every animation in a clean resting state.
        focusAnim.cancel();
        if (pressAnim != null) pressAnim.cancel();
        pressing = false;
        press = 1f;
        focusF = isFocused() ? 1f : 0f;
        applyScale();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (isOk(keyCode)) {
            if (ev.getRepeatCount() == 0 && !pressing) pressAndRun();
            return true;
        }
        return super.onKeyDown(keyCode, ev);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent ev) {
        if (isOk(keyCode)) return true;                    // never a second (click) action
        return super.onKeyUp(keyCode, ev);
    }

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void pressAndRun() {
        pressing = true;
        playSoundEffect(SoundEffectConstants.CLICK);
        ValueAnimator down = ValueAnimator.ofFloat(press, PRESS_SCALE);
        down.setDuration(PRESS_DOWN_MS);
        down.setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f));
        down.addUpdateListener(a -> { press = (float) a.getAnimatedValue(); applyScale(); });
        down.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled) return;
                try {
                    if (action != null) action.run();
                } catch (Throwable t) {
                    Log.e(TAG, "tile " + section + " failed", t);
                }
                springBack();
            }
        });
        pressAnim = down;
        down.start();
    }

    private void springBack() {
        if (!isAttachedToWindow()) {                       // the action replaced the page
            pressing = false;
            press = 1f;
            applyScale();
            return;
        }
        ValueAnimator up = ValueAnimator.ofFloat(press, 1f);
        up.setDuration(PRESS_UP_MS);
        up.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        up.addUpdateListener(a -> { press = (float) a.getAnimatedValue(); applyScale(); });
        up.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { pressing = false; }
        });
        pressAnim = up;
        up.start();
    }

    // ------------------------------------------------------------------ drawing
    private float px(float design) { return design * k; }

    private static int lerp(int a, int b, float f) {
        if (f <= 0f) return a;
        if (f >= 1f) return b;
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * f) << 24) | ((int) (ar + (br - ar) * f) << 16)
                | ((int) (ag + (bg - ag) * f) << 8) | (int) (ab + (bb - ab) * f);
    }

    /**
     * Fits the label to {@code avail} px (shrink from 26 to 18 design px, then ellipsize) and the value
     * to {@code valueAvail} px the same way (21 to 16 design px, then ellipsize).
     */
    private void fit(int avail, float valueAvail) {
        if (avail == fitWidth) return;
        fitWidth = avail;
        float size = px(LABEL_MAX);
        float min = px(LABEL_MIN);
        labelPaint.setTextSize(size);
        while (size > min && labelPaint.measureText(label, 0, label.length()) > avail) {
            size -= px(1);
            labelPaint.setTextSize(size);
        }
        shownLabelSize = size;
        shownLabel = TextUtils.ellipsize(label, labelPaint, avail, TextUtils.TruncateAt.END);
        float vs = px(VALUE_SIZE);
        float vmin = px(VALUE_MIN);
        valuePaint.setTextSize(vs);
        if (value != null) {
            while (vs > vmin && valuePaint.measureText(value, 0, value.length()) > valueAvail) {
                vs -= px(1);
                valuePaint.setTextSize(vs);
            }
        }
        shownValueSize = vs;
        shownValue = value == null || value.length() == 0 ? null
                : TextUtils.ellipsize(value, valuePaint, Math.max(0f, valueAvail), TextUtils.TruncateAt.END);
    }

    private void tint(int color) {
        if (icon == null || color == lastTint) return;
        lastTint = color;
        icon.setTint(color);
    }

    private static float centerBaseline(Paint p, float cy) {
        return cy - (p.descent() + p.ascent()) / 2f;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float f = focusF;
        int fg = lerp(FG, FG_F, f);
        bgPaint.setColor(lerp(BG, BG_F, f));
        rect.set(0, 0, w, h);
        c.drawRoundRect(rect, radius, radius, bgPaint);
        labelPaint.setColor(fg);
        valuePaint.setColor(lerp(VAL, VAL_F, f));
        tint(fg);
        if (wide) drawWide(c, w, h, fg); else drawSquare(c, w);
    }

    private void drawSquare(Canvas c, int w) {
        float pad = px(PAD_H);
        fit((int) (w - 2 * pad), w - 2 * pad);
        labelPaint.setTextSize(shownLabelSize);
        valuePaint.setTextSize(shownValueSize);
        int is = Math.round(px(ICON));
        int top = Math.round(px(ICON_TOP));
        if (icon != null) {
            int l = (w - is) / 2;
            icon.setBounds(l, top, l + is, top + is);
            icon.draw(c);
        }
        // Fixed positions (icons and labels line up across a row, with or without a value).
        float labelCy = top + is + px(LABEL_GAP) + px(LABEL_MAX) * 0.6f;
        if (shownLabel != null && shownLabel.length() > 0) {
            float lw = labelPaint.measureText(shownLabel, 0, shownLabel.length());
            c.drawText(shownLabel, 0, shownLabel.length(), (w - lw) / 2f, centerBaseline(labelPaint, labelCy), labelPaint);
        }
        if (shownValue != null && shownValue.length() > 0) {
            float valueCy = labelCy + px(LABEL_MAX) * 0.6f + px(VALUE_GAP)
                    + px(VALUE_SIZE) * 0.6f;
            float vw = valuePaint.measureText(shownValue, 0, shownValue.length());
            c.drawText(shownValue, 0, shownValue.length(), (w - vw) / 2f, centerBaseline(valuePaint, valueCy), valuePaint);
        }
    }

    /** Icon, label and value in one line with a chevron; mirrored in RTL. */
    private void drawWide(Canvas c, int w, int h, int fg) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float pad = px(WIDE_PAD_H);
        int is = Math.round(px(ICON_WIDE));
        float gap = px(20);
        float chevW = px(18) * 0.55f;
        float textStart = pad + is + gap;
        float textEnd = w - pad - chevW - gap;
        float valueW = 0f;
        if (value != null && value.length() > 0) {
            valuePaint.setTextSize(px(VALUE_SIZE));                 // measured at full size
            valueW = Math.min(valuePaint.measureText(value, 0, value.length()), (textEnd - textStart) * 0.45f);
        }
        fit((int) Math.max(0, textEnd - textStart - (valueW > 0 ? valueW + gap : 0)), valueW);
        labelPaint.setTextSize(shownLabelSize);
        valuePaint.setTextSize(shownValueSize);
        float cy = h / 2f;
        if (icon != null) {
            int l = rtl ? Math.round(w - pad - is) : Math.round(pad);
            int t = Math.round(cy - is / 2f);
            icon.setBounds(l, t, l + is, t + is);
            icon.draw(c);
        }
        if (shownLabel != null && shownLabel.length() > 0) {
            float lw = labelPaint.measureText(shownLabel, 0, shownLabel.length());
            float x = rtl ? w - textStart - lw : textStart;
            c.drawText(shownLabel, 0, shownLabel.length(), x, centerBaseline(labelPaint, cy), labelPaint);
        }
        if (shownValue != null && shownValue.length() > 0) {
            float vw = valuePaint.measureText(shownValue, 0, shownValue.length());
            float x = rtl ? w - textEnd : textEnd - vw;
            c.drawText(shownValue, 0, shownValue.length(), x, centerBaseline(valuePaint, cy), valuePaint);
        }
        // chevron pointing to the reading direction's end
        float ch = px(18);
        float tip = rtl ? pad : w - pad;
        float back = rtl ? tip + chevW : tip - chevW;
        chevron.rewind();
        chevron.moveTo(back, cy - ch / 2);
        chevron.lineTo(tip, cy);
        chevron.lineTo(back, cy + ch / 2);
        chevronPaint.setColor(fg);
        c.drawPath(chevron, chevronPaint);
    }
}
