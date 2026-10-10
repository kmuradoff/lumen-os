package org.z9x.home.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * The calm Home of direction D (D_Calm), shown when nothing can be continued: over the living sky at
 * x 96 / y 250 a big Prata clock (200 px), the date (30 px) and the weather line "12°, cloudy ·
 * city" (26 px, with its icon). No hero card, no filler. The weather line is the page's first focus
 * stop while weather is shown (OK opens the forecast). Soft text shadows keep it readable over a
 * bright day sky.
 */
public class CalmView extends ViewGroup {
    private static final int DATE_COLOR = 0xFFE6DFD3;
    private final TextClock mClock;
    private final TextView mDate;
    private final WeatherLine mWeather;
    private boolean mTimeKnown = true;
    private int mRowY = Theme.px(Theme.FIRST_ROW_Y_CALM);

    public CalmView(Context c) {
        super(c);
        setClipChildren(false);
        mClock = new TextClock(c);
        Theme.text(mClock, 200, Theme.DISPLAY, Theme.TEXT1);
        java.util.Locale loc = java.util.Locale.getDefault();
        mClock.setFormat24Hour(android.text.format.DateFormat.getBestDateTimePattern(loc, "Hm"));
        mClock.setFormat12Hour(android.text.format.DateFormat.getBestDateTimePattern(loc, "hm").replace("a", "").trim());
        mClock.setLetterSpacing(-0.02f);
        mClock.setFontFeatureSettings("tnum, lnum");
        mClock.setSingleLine(true);
        mClock.setShadowLayer(Theme.pxf(28), 0, Theme.pxf(2), 0x59000000);
        addView(mClock);
        mDate = new TextView(c);
        Theme.text(mDate, 30, Theme.REGULAR, DATE_COLOR);
        mDate.setSingleLine(true);
        mDate.setEllipsize(TextUtils.TruncateAt.END);
        mDate.setShadowLayer(Theme.pxf(16), 0, Theme.pxf(1), 0x66000000);
        addView(mDate);
        mWeather = new WeatherLine(c);
        mWeather.setVisibility(GONE);
        addView(mWeather);
    }

    public void setDate(String d) {
        if (!TextUtils.equals(d, mDate.getText())) mDate.setText(d); // the minute tick: no relayout
    }

    /** Hides the clock and date until the time is set (first boot without NTP, SPEC 12). */
    public void setTimeKnown(boolean known) {
        if (known == mTimeKnown) return;
        mTimeKnown = known;
        mClock.setVisibility(known ? VISIBLE : INVISIBLE);
        mDate.setVisibility(known ? VISIBLE : INVISIBLE);
    }

    /** {@code text} null = no weather line. */
    public void setWeather(int icon, String text, boolean stale) {
        boolean vis = text != null && !text.isEmpty();
        if (vis) mWeather.set(icon, text, stale);
        mWeather.setVisibility(vis ? VISIBLE : GONE);
        requestLayout();
    }

    /** Top of the first row below (real px); measured before this view by the page. */
    public void setRowY(int px) {
        mRowY = px;
    }

    public boolean hasWeather() {
        return mWeather.getVisibility() == VISIBLE;
    }

    public void setWeatherFocused(boolean f) {
        mWeather.setFocusState(f);
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = MeasureSpec.getSize(wms);
        int un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        int tw = MeasureSpec.makeMeasureSpec(w - 2 * Theme.px(Theme.MARGIN), MeasureSpec.AT_MOST);
        mClock.measure(un, un);
        mDate.measure(tw, un);
        mWeather.measure(un, un);
        setMeasuredDimension(w, mRowY - Theme.px(20));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        boolean rtl = Theme.rtl(this);
        int x = Theme.px(Theme.MARGIN);
        int y = Theme.px(214); // glyph tops near y 265 like D_Calm (CSS line-height 0.9)
        lay(mClock, x, y, w, rtl);
        y += mClock.getMeasuredHeight() + Theme.px(14);
        lay(mDate, x, y, w, rtl);
        y += mDate.getMeasuredHeight() + Theme.px(18);
        // the weather pill has its own padding: keep its text on the content edge
        lay(mWeather, x - mWeather.pad(), y - Theme.px(10), w, rtl);
    }

    private static void lay(View v, int x, int y, int w, boolean rtl) {
        int vw = v.getMeasuredWidth(), vh = v.getMeasuredHeight();
        int lx = rtl ? w - x - vw : x;
        v.layout(lx, y, lx + vw, y + vh);
    }

    /** Icon + "12°, cloudy · Moscow"; a light pill when focused. */
    static final class WeatherLine extends View {
        private final android.text.TextPaint mText = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mR = new RectF();
        private final Paint.FontMetrics mFm = new Paint.FontMetrics();
        private Drawable mIcon, mIconFocused;
        private String mS = "";
        private CharSequence mShown = "";
        private boolean mFocused, mStale;
        private final int mPad, mIconSize, mH;

        WeatherLine(Context c) {
            super(c);
            mText.setTypeface(Theme.REGULAR);
            mText.setTextSize(Theme.pxf(26) * Theme.fontScale(c));
            mPad = Theme.px(20);
            mIconSize = Theme.px(34);
            mH = Theme.px(56);
            mText.setShadowLayer(Theme.pxf(14), 0, Theme.pxf(1), 0x66000000);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        int pad() {
            return mPad;
        }

        void set(int icon, String s, boolean stale) {
            mIcon = icon != 0 ? getContext().getDrawable(icon) : null;
            if (mIcon != null) mIcon = mIcon.mutate();
            // the light weather glyphs would vanish on the light focus pill: a dark copy for that state
            mIconFocused = icon != 0 ? Theme.icon(getContext(), icon, Theme.ON_FOCUS) : null;
            mS = s;
            mShown = s;
            mStale = stale;
            setContentDescription(s);
            requestLayout();
            invalidate();
        }

        void setFocusState(boolean f) {
            if (mFocused == f) return;
            mFocused = f;
            animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(Theme.FOCUS_IN_MS).start();
            invalidate();
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int tw = (int) Math.ceil(mText.measureText(mS));
            int w = 2 * mPad + (mIcon != null ? mIconSize + Theme.px(14) : 0) + Math.min(tw, Theme.px(1100));
            setMeasuredDimension(w, mH);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float avail = w - 2 * mPad - (mIcon != null ? mIconSize + Theme.px(14) : 0);
            mShown = TextUtils.ellipsize(mS, mText, Math.max(0, avail), TextUtils.TruncateAt.END);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (mFocused) {
                mR.set(0, 0, w, h);
                mFill.setColor(Theme.FOCUS);
                c.drawRoundRect(mR, h / 2f, h / 2f, mFill);
            }
            boolean rtl = Theme.rtl(this);
            int x = rtl ? w - mPad : mPad;
            if (mIcon != null) {
                int iy = (h - mIconSize) / 2;
                int ix = rtl ? x - mIconSize : x;
                Drawable ic = mFocused && mIconFocused != null ? mIconFocused : mIcon;
                ic.setAlpha(mStale && !mFocused ? 150 : 255);
                ic.setBounds(ix, iy, ix + mIconSize, iy + mIconSize);
                ic.draw(c);
                x += (rtl ? -1 : 1) * (mIconSize + Theme.px(14));
            }
            mText.setColor(mFocused ? Theme.ON_FOCUS : (mStale ? Theme.TEXT3 : Theme.TEXT2));
            if (mFocused) mText.clearShadowLayer();
            else mText.setShadowLayer(Theme.pxf(14), 0, Theme.pxf(1), 0x66000000);
            CharSequence s = mShown;
            float sw = mText.measureText(s, 0, s.length());
            mText.getFontMetrics(mFm);
            float y = h / 2f - (mFm.ascent + mFm.descent) / 2f;
            c.drawText(s, 0, s.length(), rtl ? x - sw : x, y, mText);
        }
    }
}
