package org.z9x.projector.dream;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.icu.text.SimpleDateFormat;
import android.icu.util.TimeZone;
import android.text.TextPaint;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.view.animation.PathInterpolator;

import org.z9x.projector.ui.Theme;

import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * MODULE "screensaver" (v6.2). The clock + date of {@link ClockDream}, drawn on Canvas (no XML,
 * no AndroidX, no bitmaps, no bundled fonts).
 *
 * Cost budget (DREAM_SPEC section 5): the strings are rebuilt once a minute (and on a time /
 * time-zone / locale change); onDraw allocates nothing. Motion is a once-a-minute fade out (450 ms)
 * -> jump to the next small drift offset -> fade in (900 ms), animated only through RenderNode
 * alpha / translation, and {@link #hasOverlappingRendering()} is false so no offscreen layer is
 * created. About 80 composited frames per minute; nothing between the minute changes.
 *
 * Sizes are XGIMI design px of a 1920-wide screen (Theme.px). Typography: the system sans-serif
 * (Roboto variable font on this GSI) at weight 200, tabular digits, so the time never jitters.
 * Formats come from ICU per locale (24/12 h follows the system setting; AM/PM drawn small and apart).
 */
final class ClockView extends View {
    private static final String TAG = "Z9xDream";

    private static final float TIME_SIZE = 200f;
    private static final float AMPM_SIZE = 44f;
    private static final float DATE_SIZE = 34f;
    private static final float AMPM_GAP = 18f;
    private static final float DATE_GAP = 44f;          // time baseline -> date cap top
    private static final float DRIFT_X = 96f, DRIFT_Y = 64f;
    private static final float STEP_X = 40f, STEP_Y = 28f, STEP_MIN = 16f;

    private final TextPaint mTimePaint;
    private final TextPaint mAmPmPaint;
    private final TextPaint mDatePaint;
    private final Rect mBounds = new Rect();
    private final Random mRandom = new Random();
    private final float mScale;

    private SimpleDateFormat mTimeFmt;
    private SimpleDateFormat mAmPmFmt;      // null on a 24 h clock
    private SimpleDateFormat mDateFmt;
    private boolean mAmPmBefore;
    private Locale mLocale = Locale.getDefault();

    private String mTime = "";
    private String mAmPm = null;
    private String mDate = "";
    private float mTimeW, mAmPmW, mDateW;
    private float mTimeCap, mAmPmCap, mDateCap;

    private float mOffX, mOffY;

    ClockView(Context c) {
        super(c);
        mScale = Theme.scale(c);
        Typeface base = Typeface.create("sans-serif", Typeface.NORMAL);
        mTimePaint = paint(Typeface.create(base, 200, false), TIME_SIZE, 0xEBFFFFFF);
        mTimePaint.setFontFeatureSettings("tnum");
        mTimePaint.setLetterSpacing(-0.01f);
        mAmPmPaint = paint(Typeface.create(base, 300, false), AMPM_SIZE, 0x8CFFFFFF);
        mDatePaint = paint(Typeface.create(base, 400, false), DATE_SIZE, 0x8CFFFFFF);
        mDatePaint.setLetterSpacing(0.04f);
        setFocusable(false);
        setWillNotDraw(false);
        refreshFormats();
    }

    private TextPaint paint(Typeface tf, float size, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size * mScale);
        p.setColor(color);
        p.setTextAlign(Paint.Align.LEFT);
        return p;
    }

    /** Alpha without an offscreen layer (single draw of non-overlapping text). */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    // =================================================================== formats / text

    /** Rebuild the ICU formats (start, locale, time zone, 12/24 h change). Main thread. */
    void refreshFormats() {
        try {
            Context c = getContext();
            Locale loc = c.getResources().getConfiguration().getLocales().get(0);
            if (loc == null) loc = Locale.getDefault();
            mLocale = loc;
            TimeZone tz = TimeZone.getDefault();
            boolean is24 = DateFormat.is24HourFormat(c);
            String pattern = DateFormat.getBestDateTimePattern(loc, is24 ? "Hm" : "hm");
            if (is24) {
                mTimeFmt = new SimpleDateFormat(pattern, loc);
                mAmPmFmt = null;
            } else {
                int a = indexOutsideQuotes(pattern, 'a');
                int h = Math.min(idx(pattern, 'h'), Math.min(idx(pattern, 'K'), idx(pattern, 'H')));
                mAmPmBefore = a >= 0 && a < h;
                mTimeFmt = new SimpleDateFormat(stripAmPm(pattern), loc);
                mAmPmFmt = a >= 0 ? new SimpleDateFormat("a", loc) : null;
            }
            mDateFmt = new SimpleDateFormat(DateFormat.getBestDateTimePattern(loc, "EEEEdMMMM"), loc);
            mTimeFmt.setTimeZone(tz);
            mDateFmt.setTimeZone(tz);
            if (mAmPmFmt != null) mAmPmFmt.setTimeZone(tz);
            // Cap heights for the layout (digits / letters of the current locale).
            mTimePaint.getTextBounds("0123456789", 0, 10, mBounds);
            mTimeCap = -mBounds.top;
        } catch (Throwable t) {
            Log.w(TAG, "formats: " + t);
            if (mTimeFmt == null) mTimeFmt = new SimpleDateFormat("HH:mm", Locale.ROOT);
            if (mDateFmt == null) mDateFmt = new SimpleDateFormat("EEEE d MMMM", Locale.ROOT);
        }
    }

    /** Rebuild the strings for the current time and redraw once. Main thread. */
    void updateNow() {
        updateAt(System.currentTimeMillis());
    }

    /** Wall time the text shows (or will show once a running advance() ends). */
    private long mShownFor;

    long shownFor() {
        return mShownFor;
    }

    /** Formats the clock for wall time {@code atMillis}. */
    private void updateAt(long atMillis) {
        mShownFor = atMillis;
        try {
            Date now = new Date(atMillis);
            mTime = mTimeFmt.format(now).trim();
            mAmPm = mAmPmFmt != null ? mAmPmFmt.format(now) : null;
            String d = mDateFmt.format(now);
            mDate = d.isEmpty() ? d : d.substring(0, d.offsetByCodePoints(0, 1)).toUpperCase(mLocale)
                    + d.substring(d.offsetByCodePoints(0, 1));
            mTimeW = mTimePaint.measureText(mTime);
            mAmPmW = mAmPm != null ? mAmPmPaint.measureText(mAmPm) : 0f;
            mDateW = mDatePaint.measureText(mDate);
            // Cap heights of the actual text (script-dependent: Latin, Cyrillic, CJK, Arabic).
            if (mAmPm != null && !mAmPm.isEmpty()) {
                mAmPmPaint.getTextBounds(mAmPm, 0, mAmPm.length(), mBounds);
                mAmPmCap = -mBounds.top;
            }
            if (!mDate.isEmpty()) {
                mDatePaint.getTextBounds(mDate, 0, mDate.length(), mBounds);
                mDateCap = -mBounds.top;
            }
            invalidate();
        } catch (Throwable t) {
            Log.w(TAG, "update: " + t);
        }
    }

    private static int idx(String p, char c) {
        int i = indexOutsideQuotes(p, c);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private static int indexOutsideQuotes(String p, char c) {
        boolean q = false;
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            if (ch == '\'') q = !q;
            else if (!q && ch == c) return i;
        }
        return -1;
    }

    /** Removes the 'a' field (and the spaces next to it) outside quoted literals. */
    private static String stripAmPm(String p) {
        StringBuilder sb = new StringBuilder(p.length());
        boolean q = false;
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            if (ch == '\'') q = !q;
            if (!q && ch == 'a') continue;
            sb.append(ch);
        }
        String s = sb.toString().replace(' ', ' ').replace(' ', ' ').trim();
        return s.replaceAll("\\s{2,}", " ");
    }

    // =================================================================== motion

    /** First appearance: fade in from slightly below. Main thread. */
    void fadeIn(long ms) {
        animate().cancel();
        setTranslationX(mOffX);
        setTranslationY(mOffY + 12f * mScale);
        animate().alpha(1f).translationY(mOffY).setDuration(ms)
                .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
    }

    /** Minute change: fade out, new text + next drift offset, fade in. Main thread. */
    /** Fade-out of {@link #advance}; ClockDream starts the tick this much before the minute. */
    static final long ADVANCE_OUT_MS = 450;

    /**
     * Minute change: fade out, new text, fade in. Started ADVANCE_OUT_MS before the minute, so the new
     * text is formatted for the end of the fade-out (never earlier than that, even if the animation
     * ends a frame early) and appears on the minute.
     */
    void advance() {
        final long due = System.currentTimeMillis() + ADVANCE_OUT_MS;
        mShownFor = due;
        animate().cancel();
        animate().alpha(0f).setDuration(ADVANCE_OUT_MS).setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction(() -> {
                    try {
                        updateAt(Math.max(System.currentTimeMillis(), due));
                        nextOffset();
                        setTranslationX(mOffX);
                        setTranslationY(mOffY);
                        animate().alpha(1f).setDuration(900)
                                .setInterpolator(new PathInterpolator(0f, 0f, 0.2f, 1f)).start();
                    } catch (Throwable t) {
                        Log.w(TAG, "advance: " + t);
                        setAlpha(1f);
                    }
                }).start();
    }

    /** Fade out on wake; {@code end} runs after it (main). */
    void fadeOut(long ms, Runnable end) {
        animate().cancel();
        animate().alpha(0f).setDuration(ms).setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction(end).start();
    }

    private void nextOffset() {
        float bx = DRIFT_X * mScale, by = DRIFT_Y * mScale, min = STEP_MIN * mScale;
        for (int i = 0; i < 6; i++) {
            float dx = (mRandom.nextFloat() * 2f - 1f) * STEP_X * mScale;
            float dy = (mRandom.nextFloat() * 2f - 1f) * STEP_Y * mScale;
            float nx = Math.max(-bx, Math.min(bx, mOffX + dx));
            float ny = Math.max(-by, Math.min(by, mOffY + dy));
            if (Math.hypot(nx - mOffX, ny - mOffY) >= min || i == 5) {
                mOffX = nx;
                mOffY = ny;
                return;
            }
        }
    }

    // =================================================================== draw

    @Override
    protected void onDraw(Canvas c) {
        float s = mScale;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float gap = DATE_GAP * s;
        float blockH = mTimeCap + gap + mDateCap;
        float top = cy - blockH / 2f;
        float timeBase = top + mTimeCap;

        if (mAmPm != null) {
            float g = AMPM_GAP * s;
            float total = mTimeW + g + mAmPmW;
            float x = cx - total / 2f;
            float ampmBase = top + mAmPmCap;
            if (mAmPmBefore) {
                c.drawText(mAmPm, x, ampmBase, mAmPmPaint);
                c.drawText(mTime, x + mAmPmW + g, timeBase, mTimePaint);
            } else {
                c.drawText(mTime, x, timeBase, mTimePaint);
                c.drawText(mAmPm, x + mTimeW + g, ampmBase, mAmPmPaint);
            }
        } else {
            c.drawText(mTime, cx - mTimeW / 2f, timeBase, mTimePaint);
        }
        c.drawText(mDate, cx - mDateW / 2f, timeBase + gap + mDateCap, mDatePaint);
    }
}
