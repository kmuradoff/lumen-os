package org.lumen.home.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextClock;

import org.lumen.home.R;

import java.text.NumberFormat;
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
 * out. A tab switches the page when it keeps focus for 120 ms. Projectors with an internal battery (only
 * when org.lumen.projector reports one, {@link #setBattery}) also get a battery mark before search; without
 * it the header is exactly as before.
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
    private BatteryMark mBattery;   // made at the first battery reading only (never on a projector without one)
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

    /**
     * The battery mark: {@code level} 0..100, or negative for none (no battery reported, or the reading
     * failed). It is created at the first level, so a projector that reports none never has the view; it
     * is not a focus stop (a status, not a button) and fades with the buttons in browse mode.
     */
    public void setBattery(int level, boolean adapter, boolean charging) {
        if (level < 0) {
            if (mBattery == null || mBattery.getVisibility() == GONE) return;
            mBattery.setVisibility(GONE);
            requestLayout();
            return;
        }
        if (mBattery == null) {
            mBattery = new BatteryMark(getContext());
            mBattery.setAlpha(mBrowse ? 0f : 1f);
            addView(mBattery);   // lays the header out again
        } else if (mBattery.getVisibility() != VISIBLE) {
            mBattery.setVisibility(VISIBLE);
            requestLayout();
        }
        mBattery.set(level, adapter, charging);
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
        if (mBattery != null && mBattery.getVisibility() == VISIBLE) xr = placeR(mBattery, xr, h, w, rtl) - Theme.px(20);
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

    // ------------------------------------------------------------------ battery mark

    /**
     * "85 %" in the header font, then a battery glyph with the fill level on the end side, centred on the
     * header line like the 52 px icon pills (the glyph as tall as the search and settings glyphs, its
     * outline as thick as their strokes, its cap towards the end in every layout direction, as the icons
     * are not mirrored either). The header's soft shadows, as PillButton's: the outline's blurred copy
     * ({@link Glow#iconShadow}, made once, never in onDraw) and the tabs' text shadow. While the power
     * adapter is in, a bolt: cut out of the fill and solid over its empty part, so it reads at every level.
     * On the battery alone at 20 % or less the fill and the figure turn to the warm accent
     * (test/HeaderContrastTest: readable over white). The width is fixed (the widest level in the locale's
     * percent format, tabular figures), so a new level never moves the buttons nor asks for a layout pass.
     */
    static final class BatteryMark extends View {
        // design px; the search / settings glyphs are 29 px icons drawn with 1.9 of 24 (2.3 px) strokes
        private static final float BODY_W = 36, BODY_H = 19, STROKE = 2.3f, BODY_R = 5.5f, CAP_W = 3, CAP_H = 8,
                CAP_R = 1.5f, CAP_GAP = 1.5f, INSET = 2f, FILL_R = 2f, MIN_FILL = 1.5f, TEXT_PX = 22, BOLT_H = 15;
        private static final int H = 52, PAD = 12, GAP = 10, LOW = 20;
        private static final int SHADOW = 0x8C000000;   // as the icons' and the tabs' (PillButton)
        // Glow caches icon shadows by drawable resource id; the outline is drawn in code: a key no id can be
        private static final int SHADOW_KEY = -1;
        // the bolt in a unit box (x, y pairs): top, left middle, inner corner, bottom tip, right middle, inner corner
        private static final float[] BOLT = {0.70f, 0f, 0.05f, 0.58f, 0.47f, 0.58f, 0.30f, 1f, 0.95f, 0.42f, 0.53f, 0.42f};
        private final TextPaint mText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final NumberFormat mPct = NumberFormat.getPercentInstance(Locale.getDefault());
        private final RectF mR = new RectF(), mBox = new RectF();
        private final Path mFill = new Path(), mBolt = new Path();
        // the glyph lies centred in a square box of its own width (real px), the shape Glow's icon shadows want
        private final int mH, mPad, mGap, mBoxW, mTextW;
        private final float mBaseline;   // from the line's centre, as PillButton centres its label
        private final Glow.Shadow mShadow;
        private int mLevel = -1;
        private boolean mAdapter, mCharging, mLow;
        private String mFigure = "";
        private float mFigureW;

        BatteryMark(Context c) {
            super(c);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            mH = Theme.px(H);
            mPad = Theme.px(PAD);
            mGap = Theme.px(GAP);
            mBoxW = (int) Math.ceil(Theme.pxf(BODY_W + CAP_GAP + CAP_W));
            mText.setTypeface(Theme.MEDIUM);
            mText.setTextSize(Theme.pxf(TEXT_PX) * Theme.fontScale(c));
            mText.setFontFeatureSettings("tnum, lnum");
            mText.setShadowLayer(Theme.pxf(12), 0, Theme.pxf(1), SHADOW);
            float widest = 0;
            for (int l = 0; l <= 100; l++) widest = Math.max(widest, mText.measureText(mPct.format(l / 100.0)));
            mTextW = (int) Math.ceil(widest);
            Paint.FontMetrics fm = mText.getFontMetrics();
            mBaseline = -(fm.ascent + fm.descent) / 2f;
            mShadow = Glow.iconShadow(new OutlineDrawable(), SHADOW_KEY, mBoxW, Theme.pxf(10));
            mShadowPaint.setColor(SHADOW);
        }

        void set(int level, boolean adapter, boolean charging) {
            level = Math.max(0, Math.min(100, level));
            if (level == mLevel && adapter == mAdapter && charging == mCharging) return;
            mLevel = level;
            mAdapter = adapter;
            mCharging = charging;
            mLow = level <= LOW && !adapter;
            mFigure = mPct.format(level / 100.0);
            mFigureW = mText.measureText(mFigure);
            int desc = !adapter ? R.string.battery_level : (charging ? R.string.battery_charging : R.string.battery_plugged);
            setContentDescription(getContext().getString(desc, mFigure));
            shape();
            invalidate();
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            setMeasuredDimension(mPad + mTextW + mGap + mBoxW + mPad, mH);
        }

        /** Shadows reach past the bounds: a fade (browse mode) without an offscreen layer cut to them. */
        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        /** The body outline and the cap in the glyph box (0..mBoxW square, real px). */
        private void drawOutline(Canvas c, Paint p) {
            float st = Theme.pxf(STROKE), top = (mBoxW - Theme.pxf(BODY_H)) / 2f, mid = mBoxW / 2f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(st);
            mR.set(st / 2, top + st / 2, Theme.pxf(BODY_W) - st / 2, top + Theme.pxf(BODY_H) - st / 2);
            c.drawRoundRect(mR, Theme.pxf(BODY_R) - st / 2, Theme.pxf(BODY_R) - st / 2, p);
            p.setStyle(Paint.Style.FILL);
            float cx = Theme.pxf(BODY_W + CAP_GAP);
            mR.set(cx, mid - Theme.pxf(CAP_H) / 2, cx + Theme.pxf(CAP_W), mid + Theme.pxf(CAP_H) / 2);
            c.drawRoundRect(mR, Theme.pxf(CAP_R), Theme.pxf(CAP_R), p);
        }

        /** The fill (with the bolt cut out) and the bolt over the empty part, in the glyph box. */
        private void shape() {
            mFill.rewind();
            mBolt.rewind();
            float st = Theme.pxf(STROKE), in = st + Theme.pxf(INSET), top = (mBoxW - Theme.pxf(BODY_H)) / 2f;
            float l = in, t = top + in, r = Theme.pxf(BODY_W) - in, b = top + Theme.pxf(BODY_H) - in;
            float fw = (r - l) * mLevel / 100f;
            if (mLevel > 0) fw = Math.max(fw, Theme.pxf(MIN_FILL));
            if (fw > 0) mFill.addRoundRect(l, t, l + fw, b, Theme.pxf(FILL_R), Theme.pxf(FILL_R), Path.Direction.CW);
            if (!mAdapter) return;
            float bh = Theme.pxf(BOLT_H), bw = bh * 0.62f, bx = Theme.pxf(BODY_W) / 2 - bw / 2, by = mBoxW / 2f - bh / 2;
            mBolt.moveTo(bx + BOLT[0] * bw, by + BOLT[1] * bh);
            for (int i = 2; i < BOLT.length; i += 2) mBolt.lineTo(bx + BOLT[i] * bw, by + BOLT[i + 1] * bh);
            mBolt.close();
            Path cut = new Path(mFill);   // not in onDraw: only when the reading changes
            mFill.op(mBolt, Path.Op.DIFFERENCE);
            mBolt.op(cut, Path.Op.DIFFERENCE);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (mLevel < 0) return;
            boolean rtl = Theme.rtl(this);
            int gx = rtl ? mPad : getWidth() - mPad - mBoxW, gy = (getHeight() - mBoxW) / 2; // as PillButton's icons
            Glow.Shadow sh = mShadow;
            if (sh != null) {
                float dy = Theme.pxf(1); // as the text's
                mBox.set(gx + sh.x, gy + sh.y + dy, gx + sh.x + sh.w, gy + sh.y + dy + sh.h);
                c.drawBitmap(sh.bmp, null, mBox, mShadowPaint);
            }
            int ink = mLow ? Theme.ACCENT : Theme.TEXT1;
            c.save();
            c.translate(gx, gy);
            mPaint.setColor(Theme.TEXT1);
            drawOutline(c, mPaint);
            mPaint.setColor(ink);
            c.drawPath(mFill, mPaint);
            if (mAdapter) {
                mPaint.setColor(Theme.TEXT1);
                c.drawPath(mBolt, mPaint);
            }
            c.restore();
            mText.setColor(ink);
            float x = rtl ? gx + mBoxW + mGap : gx - mGap - mFigureW;
            c.drawText(mFigure, x, getHeight() / 2f + mBaseline, mText);
        }

        /** The outline as a drawable of any square size, for its shadow (Glow renders it once, smaller). */
        private final class OutlineDrawable extends Drawable {
            private final Paint mInk = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas c) {
                Rect b = getBounds();
                c.save();
                c.translate(b.left, b.top);
                c.scale(b.width() / (float) mBoxW, b.height() / (float) mBoxW);
                drawOutline(c, mInk);
                c.restore();
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        }
    }
}
