package org.z9x.home.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextClock;

import org.z9x.home.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

/**
 * Header of direction D (D_Home), y 60-132 (1.0.1 follow-up: 14 px lower than D_Home's 46, so the
 * two-line clock stays inside the TV safe area, 54 px from the top): the 2A mark + "lumen", the tabs
 * Home / Apps / Projector (1.0.1: the Inputs tab is gone, the quick panel and the remote's input key
 * list the inputs); right-aligned: the update badge (only when an update is ready), search, settings,
 * and the clock block (a Prata time over "date · temperature"). No plate behind them (owner, after the
 * 1.0.1 follow-up: the dark capsule looked like a box): the backgrounds under the header carry a soft
 * full-width top scrim ({@link Scrims#HEAD_ALPHA}, the sky and the hero's stage, under the pages; the
 * Apps grid fades out what would scroll under the header), which keeps every header item at 4.5:1 or
 * more even over pure white, and the clock, the tabs and the icons have soft shadows. The clock block is
 * a focus stop while weather is shown (OK opens the forecast). It is on screen in every state but one:
 * the calm Home's top, where the big clock replaces it. In browse mode everything but the clock fades
 * out. A tab switches the page when it keeps focus for 120 ms.
 */
public class TopBar extends ViewGroup {
    public static final String A_SEARCH = "search", A_UPDATE = "update", A_WEATHER = "weather", A_INPUT = "input",
            A_SETTINGS = "settings";
    public static final int TABS = 3;
    /** Height of the clock block (its focus pill, y 54-138) and the pill's corner radius (design px). */
    private static final int CLOCK_H = 84, PILL_R = 28;

    public interface Host {
        void onTabFocused(int tab);

        void onTopAction(String action);
    }

    private Host mHost;
    private final Mark mMark;
    private final PillButton[] mTabs = new PillButton[TABS];
    private final PillButton mUpdate, mSearch, mGear;
    private final ClockBlock mClock;
    private final ArrayList<View> mOrder = new ArrayList<>();
    private int mFocus = -1;     // index into mOrder, -1 = not focused
    private int mSelectedTab;
    private boolean mCalm, mWeather, mBrowse;
    private boolean mClockShown = true;  // target state of the clock block (it fades in and out)
    // search / settings sit beside the clock or at the edge; kept while they are faded out (browse), so
    // they never jump while visible: the clock crossfades over them instead
    private boolean mButtonsBesideClock = true;
    private final Runnable mTabSwitch = () -> {
        View v = mFocus >= 0 ? mOrder.get(mFocus) : null;
        for (int i = 0; i < TABS; i++) if (v == mTabs[i]) mHost.onTabFocused(i);
    };

    public TopBar(Context c, Host host) {
        super(c);
        mHost = host;
        setClipChildren(false);
        mMark = new Mark(c, 34, true);
        addView(mMark);
        int[] labels = {R.string.tab_home, R.string.tab_apps, R.string.row_projector};
        for (int i = 0; i < TABS; i++) {
            mTabs[i] = new PillButton(c, 52, 24, PillButton.STYLE_PLAIN).label(c.getString(labels[i])).focusScale(1.0f)
                    .shadow(true);
            addView(mTabs[i]);
        }
        mUpdate = new PillButton(c, 52, 22, PillButton.STYLE_FILLED).icon(R.drawable.ic_update)
                .label(c.getString(R.string.update_ready));
        mUpdate.setVisibility(GONE);
        addView(mUpdate);
        mSearch = new PillButton(c, 52, 26, PillButton.STYLE_PLAIN).icon(R.drawable.ic_search).shadow(true);
        mSearch.setContentDescription(c.getString(R.string.search));
        addView(mSearch);
        mGear = new PillButton(c, 52, 26, PillButton.STYLE_PLAIN).icon(R.drawable.ic_settings).shadow(true);
        mGear.setContentDescription(c.getString(R.string.settings_button));
        addView(mGear);
        mClock = new ClockBlock(c);
        addView(mClock);
        rebuildOrder();
        setSelectedTab(0);
        applyClock(false);
    }

    private void rebuildOrder() {
        View cur = mFocus >= 0 && mFocus < mOrder.size() ? mOrder.get(mFocus) : null;
        mOrder.clear();
        for (PillButton t : mTabs) mOrder.add(t);
        if (mUpdate.getVisibility() == VISIBLE) mOrder.add(mUpdate);
        mOrder.add(mSearch);
        mOrder.add(mGear);
        if (mClock.getVisibility() == VISIBLE && mWeather) mOrder.add(mClock);
        mFocus = cur != null ? mOrder.indexOf(cur) : -1;
        if (cur != null && mFocus < 0) { // the focused item went away (clock hidden): back to the tab
            focus(cur, false);
            mFocus = mOrder.indexOf(mTabs[mSelectedTab]);
            focus(mOrder.get(mFocus), true);
        }
    }

    public void setSelectedTab(int t) {
        mSelectedTab = t;
        for (int i = 0; i < TABS; i++) mTabs[i].setSelectedState(i == t);
    }

    public boolean hasFocus2() {
        return mFocus >= 0;
    }

    /** Focuses the selected tab (UP from a page). */
    public void focusIn() {
        setFocusIndex(mOrder.indexOf(mTabs[mSelectedTab]), false);
    }

    public void focusSearch() {
        setFocusIndex(mOrder.indexOf(mSearch), false);
    }

    public void focusOut() {
        setFocusIndex(-1, false);
    }

    private void setFocusIndex(int i, boolean switchTab) {
        if (mFocus >= 0 && mFocus < mOrder.size()) focus(mOrder.get(mFocus), false);
        mFocus = i;
        if (i >= 0) focus(mOrder.get(i), true);
        removeCallbacks(mTabSwitch);
        if (switchTab) postDelayed(mTabSwitch, 120);
    }

    private void focus(View v, boolean f) {
        if (v instanceof PillButton) ((PillButton) v).setFocusState(f, true);
        else if (v instanceof ClockBlock) ((ClockBlock) v).setFocusState(f);
    }

    public boolean onKey(int keyCode, KeyEvent e) {
        if (mFocus < 0) return false;
        boolean rtl = Theme.rtl(this);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int dir = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) != rtl ? 1 : -1;
                int to = mFocus + dir;
                if (to >= 0 && to < mOrder.size()) setFocusIndex(to, true);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: {
                View v = mOrder.get(mFocus);
                if (v == mSearch) mHost.onTopAction(A_SEARCH);
                else if (v == mUpdate) mHost.onTopAction(A_UPDATE);
                else if (v == mClock) mHost.onTopAction(A_WEATHER);
                else if (v == mGear) mHost.onTopAction(A_SETTINGS);
                else for (int i = 0; i < TABS; i++) if (v == mTabs[i]) {
                        removeCallbacks(mTabSwitch);
                        mHost.onTabFocused(i);
                        return false; // let the page take focus (DOWN behaviour)
                    }
                return true;
            }
            default:
                return false;
        }
    }

    /** Weather for the clock block's second line ({@code temp} null = no weather). */
    public void setWeather(int icon, String temp, String city, boolean stale) {
        mWeather = temp != null;
        mClock.setWeather(temp, stale);
        rebuildOrder();
        requestLayout();
    }

    /** Localized short date for the clock block ("ср, 8 октября"); the minute tick sends it unchanged. */
    public void setDate(String date) {
        if (mClock.setDate(date)) requestLayout();
    }

    /** True while the calm Home's big clock is on screen: the header then shows no clock of its own. */
    public void setCalm(boolean calm) {
        if (mCalm == calm) return;
        mCalm = calm;
        applyClock(isLaidOut()); // the first state (before the first frame) is set at once
    }

    /** Browse mode (rows scrolled up): the mark, tabs and buttons fade out, the clock stays. */
    public void setBrowse(boolean browse) {
        if (browse == mBrowse) return;
        mBrowse = browse;
        requestLayout(); // leaving browse: the buttons take their place while still faded out
        float a = browse ? 0f : 1f;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v == mClock) continue;
            // a new alpha animation replaces a running one; a pill's focus scale keeps running
            if (Theme.animations()) v.animate().alpha(a).setDuration(Theme.BAR_FADE_MS).start();
            else v.setAlpha(a);
        }
    }

    public void setUpdateReady(boolean ready) {
        if ((mUpdate.getVisibility() == VISIBLE) == ready) return;
        mUpdate.setVisibility(ready ? VISIBLE : GONE);
        rebuildOrder();
        requestLayout();
    }

    /** The clock is hidden until the time is set (first boot without NTP, SPEC 12) and under the big calm clock. */
    public void refreshClockVisibility() {
        applyClock(true);
    }

    private void applyClock(boolean animate) {
        boolean show = timeKnown() && !mCalm;
        if (show == mClockShown) return;
        mClockShown = show;
        requestLayout(); // the buttons' place follows the target at once (see onLayout)
        mClock.animate().cancel();
        boolean fade = animate && Theme.animations();
        if (show) {
            if (mClock.getVisibility() != VISIBLE) {
                mClock.setAlpha(fade ? 0f : 1f);
                mClock.setVisibility(VISIBLE);
                rebuildOrder();
                requestLayout();
            }
            if (fade) mClock.animate().alpha(1f).setDuration(Theme.BAR_FADE_MS).start();
            else mClock.setAlpha(1f);
        } else if (fade) {
            mClock.animate().alpha(0f).setDuration(Theme.BAR_FADE_MS).withEndAction(this::hideClock).start();
        } else {
            hideClock();
        }
    }

    private void hideClock() {
        if (mClockShown || mClock.getVisibility() == GONE) return;
        mClock.setVisibility(GONE);
        rebuildOrder();
        requestLayout();
    }

    public static boolean timeKnown() {
        return Calendar.getInstance().get(Calendar.YEAR) >= 2026;
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        int un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(un, un);
        setMeasuredDimension(MeasureSpec.getSize(wms), Theme.px(Theme.BAR_H));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        boolean rtl = Theme.rtl(this);
        int x = Theme.px(Theme.MARGIN);
        x = place(mMark, x, h, w, rtl) + Theme.px(44);
        for (PillButton tab : mTabs) x = place(tab, x, h, w, rtl) + Theme.px(6);
        // right side, laid out from the right edge; the clock is always at the edge
        int xr = w - Theme.px(Theme.MARGIN);
        if (mClock.getVisibility() == VISIBLE) placeR(mClock, xr, h, w, rtl);
        if (!mBrowse) mButtonsBesideClock = mClockShown;
        if (mButtonsBesideClock) xr -= mClock.getMeasuredWidth() + Theme.px(16);
        xr = placeR(mGear, xr, h, w, rtl) - Theme.px(20);
        xr = placeR(mSearch, xr, h, w, rtl) - Theme.px(20);
        if (mUpdate.getVisibility() == VISIBLE) placeR(mUpdate, xr, h, w, rtl);
    }

    private static int place(View v, int x, int h, int w, boolean rtl) {
        int vw = v.getMeasuredWidth(), vh = v.getMeasuredHeight();
        int y = (h - vh) / 2;
        int lx = rtl ? w - x - vw : x;
        v.layout(lx, y, lx + vw, y + vh);
        return x + vw;
    }

    private static int placeR(View v, int xr, int h, int w, boolean rtl) {
        int vw = v.getMeasuredWidth(), vh = v.getMeasuredHeight();
        int y = (h - vh) / 2;
        int lx = rtl ? w - xr : xr - vw;
        v.layout(lx, y, lx + vw, y + vh);
        return xr - vw;
    }

    // ------------------------------------------------------------------ clock block

    /**
     * The time in Prata (56 px, the calm clock's face) over "ср, 8 октября · 12°" (21 px), end-aligned,
     * centred on the header line. 1.0.0 had 34 px Onest, which disappeared over bright hero art. The
     * block is 84 px high (y 54-138: inside the safe area, also its focus pill) and lies wholly in the
     * hold of the header scrim under it ({@link Scrims#HEAD_HOLD}), which keeps it readable over any art
     * (the 1.0.1 ellipse was not enough over white, the follow-up's plate looked like a box); the soft
     * text shadows add a little more over busy art. The time is a
     * TextClock (24 h / 12 h from the system setting, ticks on the minute by itself) with a fixed width,
     * the widest time of its format: Prata has no tabular figures, and a width that changed with the
     * minute would move the buttons and re-layout the whole window once a minute. The date comes from the
     * activity's minute tick.
     */
    static final class ClockBlock extends ViewGroup {
        private static final float TIME_PX = 56, LINE_PX = 21;
        private static final int LINE_COLOR = 0xFFE6DFD3; // calm date colour: brighter than TEXT2 over art
        private static final int SHADOW = 0x8C000000;
        private final TextClock mTime;
        private final TextPaint mLine = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mR = new RectF();
        private String mDate = "", mTemp;
        private CharSequence mText = "", mShown = "";
        private boolean mFocused, mStale;
        private final int mPad, mTimeW, mDigitH, mLineCap, mGap;
        private int mLineBase;

        ClockBlock(Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
            mPad = Theme.px(18);
            mGap = Theme.px(12);
            mTime = new TextClock(c);
            Theme.text(mTime, TIME_PX, Theme.DISPLAY, Theme.TEXT1);
            Locale loc = Locale.getDefault();
            String f24 = DateFormat.getBestDateTimePattern(loc, "Hm");
            String f12 = DateFormat.getBestDateTimePattern(loc, "hm").replace("a", "").trim(); // as the calm clock
            mTime.setFormat24Hour(f24);
            mTime.setFormat12Hour(f12);
            mTime.setFontFeatureSettings("tnum, lnum");
            mTime.setSingleLine(true);
            mTime.setTextAlignment(TEXT_ALIGNMENT_VIEW_END);
            mTime.setShadowLayer(Theme.pxf(18), 0, Theme.pxf(1), SHADOW);
            mTimeW = (int) Math.ceil(Math.max(widest(mTime.getPaint(), f24), widest(mTime.getPaint(), f12)));
            Rect b = new Rect();
            mTime.getPaint().getTextBounds("0", 0, 1, b);
            mDigitH = b.height();
            // a fixed width: a new minute only swaps the text layout, it never requests a layout pass
            addView(mTime, new LayoutParams(mTimeW, LayoutParams.WRAP_CONTENT));
            mLine.setTypeface(Theme.REGULAR);
            mLine.setTextSize(Theme.pxf(LINE_PX) * Theme.fontScale(c));
            mLine.getTextBounds("8", 0, 1, b);
            mLineCap = b.height();
            mLine.setShadowLayer(Theme.pxf(14), 0, Theme.pxf(1), SHADOW);
        }

        /** Width of the widest time this format can show (every digit a '0', two-digit hours). */
        private static float widest(Paint p, String fmt) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 10);
            cal.set(Calendar.MINUTE, 0);
            String s = DateFormat.format(fmt, cal).toString().replaceAll("[0-9]", "0");
            return p.measureText(s);
        }

        /** Returns true if the date changed (only then a layout pass). */
        boolean setDate(String d) {
            String n = d == null ? "" : d;
            if (n.equals(mDate)) return false;
            mDate = n;
            rebuild();
            return true;
        }

        void setWeather(String temp, boolean stale) {
            mTemp = temp;
            mStale = stale;
            rebuild();
        }

        private void rebuild() {
            mText = mTemp == null || mTemp.isEmpty() ? mDate : (mDate.isEmpty() ? mTemp : mDate + " · " + mTemp);
            setContentDescription(mText);
            requestLayout();
            invalidate();
        }

        /**
         * The block fades in and out (calm Home, browse): the alpha goes to every draw (time, date, focus
         * pill) instead of an offscreen layer cut to the view's bounds, which would also cut the text
         * shadows into a hard-edged box for the whole fade.
         */
        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        void setFocusState(boolean f) {
            mFocused = f;
            mTime.setTextColor(f ? Theme.ON_FOCUS : Theme.TEXT1);
            // dark text on the light pill: no shadow
            if (f) {
                mTime.setShadowLayer(0, 0, 0, 0);
                mLine.clearShadowLayer();
            } else {
                mTime.setShadowLayer(Theme.pxf(18), 0, Theme.pxf(1), SHADOW);
                mLine.setShadowLayer(Theme.pxf(14), 0, Theme.pxf(1), SHADOW);
            }
            invalidate();
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            mTime.measure(MeasureSpec.makeMeasureSpec(mTimeW, MeasureSpec.EXACTLY), un);
            int lw = (int) Math.ceil(mLine.measureText(mText, 0, mText.length()));
            int w = Math.max(mTimeW, Math.min(lw, Theme.px(420)));
            // 84 px; a large font scale (up to 1.3) may need a little more
            setMeasuredDimension(w + 2 * mPad, Math.max(Theme.px(CLOCK_H), mDigitH + mGap + mLineCap + Theme.px(12)));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, h = b - t;
            boolean rtl = Theme.rtl(this);
            // the visible block (top of the digits .. the date's baseline) is centred on the header line
            int vis = mDigitH + mGap + mLineCap;
            int timeBase = (h - vis) / 2 + mDigitH;
            mLineBase = timeBase + mGap + mLineCap;
            int tw = mTime.getMeasuredWidth(), th = mTime.getMeasuredHeight();
            int x = rtl ? mPad : w - mPad - tw;
            int y = timeBase - mTime.getBaseline();
            mTime.layout(x, y, x + tw, y + th);
            mShown = TextUtils.ellipsize(mText, mLine, w - 2 * mPad, TextUtils.TruncateAt.START);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (mFocused) {
                mR.set(0, 0, w, h);
                mFill.setColor(Theme.FOCUS);
                c.drawRoundRect(mR, Theme.pxf(PILL_R), Theme.pxf(PILL_R), mFill);
            }
            mLine.setColor(mFocused ? Theme.alpha(Theme.ON_FOCUS, 0.8f) : (mStale ? Theme.TEXT2 : LINE_COLOR));
            CharSequence s = mShown;
            float sw = mLine.measureText(s, 0, s.length());
            boolean rtl = Theme.rtl(this);
            float x = rtl ? mPad : w - mPad - sw;
            c.drawText(s, 0, s.length(), x, mLineBase, mLine);
        }
    }
}
