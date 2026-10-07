package org.z9x.home.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextClock;

import org.z9x.home.R;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * Top bar (SPEC 7.4), y 40-104: Lumen mark, search, the three tabs; right-aligned: update badge,
 * weather chip, clock, input and settings buttons. A tab switches the page on focus after 120 ms.
 */
public class TopBar extends ViewGroup {
    public static final String A_SEARCH = "search", A_UPDATE = "update", A_WEATHER = "weather", A_INPUT = "input",
            A_SETTINGS = "settings";

    public interface Host {
        void onTabFocused(int tab);

        void onTopAction(String action);
    }

    private Host mHost;
    private final Mark mMark;
    private final PillButton mSearch;
    private final PillButton[] mTabs = new PillButton[3];
    private final PillButton mUpdate;
    private final WeatherChip mWeather;
    private final TextClock mClock;
    private final PillButton mInput, mGear;
    private final ArrayList<View> mOrder = new ArrayList<>();
    private int mFocus = -1;     // index into mOrder, -1 = not focused
    private int mSelectedTab;
    private final Runnable mTabSwitch = () -> {
        View v = mFocus >= 0 ? mOrder.get(mFocus) : null;
        for (int i = 0; i < 3; i++) if (v == mTabs[i]) mHost.onTabFocused(i);
    };

    public TopBar(Context c, Host host) {
        super(c);
        mHost = host;
        setClipChildren(false);
        mMark = new Mark(c);
        addView(mMark);
        mSearch = new PillButton(c, 64, 28, PillButton.STYLE_PLAIN).icon(R.drawable.ic_search);
        mSearch.setContentDescription(c.getString(R.string.search));
        addView(mSearch);
        int[] labels = {R.string.tab_for_you, R.string.tab_apps, R.string.tab_inputs};
        for (int i = 0; i < 3; i++) {
            mTabs[i] = new PillButton(c, 56, 30, PillButton.STYLE_PLAIN).label(c.getString(labels[i]));
            addView(mTabs[i]);
        }
        mUpdate = new PillButton(c, 56, 24, PillButton.STYLE_FILLED).icon(R.drawable.ic_update)
                .label(c.getString(R.string.update_ready));
        mUpdate.setVisibility(GONE);
        addView(mUpdate);
        mWeather = new WeatherChip(c);
        mWeather.setVisibility(GONE);
        addView(mWeather);
        mClock = new TextClock(c);
        Theme.text(mClock, 36, Theme.MEDIUM, Theme.TEXT1);
        mClock.setSingleLine(true);
        addView(mClock);
        mInput = new PillButton(c, 56, 28, PillButton.STYLE_PLAIN).icon(R.drawable.ic_input);
        mInput.setContentDescription(c.getString(R.string.inputs_button));
        addView(mInput);
        mGear = new PillButton(c, 56, 28, PillButton.STYLE_PLAIN).icon(R.drawable.ic_settings);
        mGear.setContentDescription(c.getString(R.string.settings_button));
        addView(mGear);
        rebuildOrder();
        setSelectedTab(0);
        refreshClockVisibility();
    }

    private void rebuildOrder() {
        View cur = mFocus >= 0 && mFocus < mOrder.size() ? mOrder.get(mFocus) : null;
        mOrder.clear();
        mOrder.add(mSearch);
        for (PillButton t : mTabs) mOrder.add(t);
        if (mUpdate.getVisibility() == VISIBLE) mOrder.add(mUpdate);
        if (mWeather.getVisibility() == VISIBLE) mOrder.add(mWeather);
        mOrder.add(mInput);
        mOrder.add(mGear);
        mFocus = cur != null ? mOrder.indexOf(cur) : -1;
    }

    public void setSelectedTab(int t) {
        mSelectedTab = t;
        for (int i = 0; i < 3; i++) mTabs[i].setSelectedState(i == t);
    }

    public boolean hasFocus2() {
        return mFocus >= 0;
    }

    /** Focuses the selected tab (DOWN->UP from a page). */
    public void focusIn() {
        setFocusIndex(mOrder.indexOf(mTabs[mSelectedTab]), false);
    }

    public void focusSearch() {
        setFocusIndex(0, false);
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
        if (v instanceof PillButton) {
            PillButton p = (PillButton) v;
            p.setFocusState(f, true);
            if (p == mSearch) {
                p.label(f ? getContext().getString(R.string.search) : "");
            }
        } else if (v instanceof WeatherChip) {
            ((WeatherChip) v).setFocusState(f);
        }
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
                else if (v == mWeather) mHost.onTopAction(A_WEATHER);
                else if (v == mInput) mHost.onTopAction(A_INPUT);
                else if (v == mGear) mHost.onTopAction(A_SETTINGS);
                else for (int i = 0; i < 3; i++) if (v == mTabs[i]) {
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

    public void setWeather(int icon, String temp, String city, boolean stale) {
        boolean vis = temp != null;
        if (vis) mWeather.set(icon, temp, city, stale);
        if ((mWeather.getVisibility() == VISIBLE) != vis) {
            mWeather.setVisibility(vis ? VISIBLE : GONE);
            rebuildOrder();
            requestLayout();
        }
    }

    public void setUpdateReady(boolean ready) {
        if ((mUpdate.getVisibility() == VISIBLE) == ready) return;
        mUpdate.setVisibility(ready ? VISIBLE : GONE);
        rebuildOrder();
        requestLayout();
    }

    /** The clock is hidden until the time is set (first boot without NTP, SPEC 12). */
    public void refreshClockVisibility() {
        boolean ok = Calendar.getInstance().get(Calendar.YEAR) >= 2026;
        mClock.setVisibility(ok ? VISIBLE : INVISIBLE);
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
        x = place(mMark, x, h, w, rtl) + Theme.px(20);
        x = place(mSearch, x, h, w, rtl) + Theme.px(16);
        for (PillButton tab : mTabs) x = place(tab, x, h, w, rtl) + Theme.px(8);
        // right side, laid out from the right edge
        int xr = w - Theme.px(Theme.MARGIN);
        xr = placeR(mGear, xr, h, w, rtl) - Theme.px(12);
        xr = placeR(mInput, xr, h, w, rtl) - Theme.px(28);
        xr = placeR(mClock, xr, h, w, rtl) - Theme.px(32);
        if (mWeather.getVisibility() == VISIBLE) xr = placeR(mWeather, xr, h, w, rtl) - Theme.px(20);
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

    // ------------------------------------------------------------------ the Lumen mark

    /** 40x40 mark: a rounded tile in the brand gradient with a light "beam" line (the boot motif). */
    static final class Mark extends View {
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mT = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mR = new RectF();

        Mark(Context c) {
            super(c);
            mT.setTypeface(Theme.LIGHT);
            mT.setColor(0xFFFFFFFF);
            setContentDescription("Lumen OS");
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(Theme.px(40), Theme.px(40));
        }

        @Override
        protected void onDraw(Canvas c) {
            int s = getWidth();
            mR.set(0, 0, s, s);
            mP.setShader(new LinearGradient(0, s, s, 0, Theme.ACCENT_STRONG, Theme.ACCENT, Shader.TileMode.CLAMP));
            c.drawRoundRect(mR, s * 0.3f, s * 0.3f, mP);
            mP.setShader(null);
            mT.setTextSize(s * 0.62f);
            float tw = mT.measureText("L");
            Paint.FontMetrics fm = mT.getFontMetrics();
            c.drawText("L", (s - tw) / 2f, s / 2f - (fm.ascent + fm.descent) / 2f - s * 0.04f, mT);
            mP.setColor(0xE6FFFFFF);
            float y = s * 0.80f;
            mP.setStrokeWidth(Math.max(1f, s * 0.045f));
            mP.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(s * 0.24f, y, s * 0.76f, y, mP);
        }
    }

    // ------------------------------------------------------------------ weather chip

    static final class WeatherChip extends View {
        private final TextPaint mTemp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mCity = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mR = new RectF();
        private Drawable mIcon;
        private String mT = "", mC = "";
        private boolean mFocused, mStale;

        WeatherChip(Context c) {
            super(c);
            float fs = Theme.fontScale(c);
            mTemp.setTypeface(Theme.MEDIUM);
            mTemp.setTextSize(Theme.pxf(30) * fs);
            mCity.setTypeface(Theme.REGULAR);
            mCity.setTextSize(Theme.pxf(20) * fs);
        }

        void set(int icon, String temp, String city, boolean stale) {
            mIcon = icon != 0 ? getContext().getDrawable(icon) : null;
            mT = temp;
            mC = city == null ? "" : city;
            mStale = stale;
            setContentDescription(temp + " " + mC);
            setAlpha(stale ? 0.6f : 1f);
            requestLayout();
            invalidate();
        }

        void setFocusState(boolean f) {
            mFocused = f;
            animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(Theme.FOCUS_IN_MS).start();
            invalidate();
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            float tw = Math.max(mTemp.measureText(mT), Math.min(mCity.measureText(mC), Theme.pxf(220)));
            setMeasuredDimension(Theme.px(20 + 44 + 12 + 20) + (int) tw, Theme.px(64));
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (mFocused) {
                mR.set(0, 0, w, h);
                mFill.setColor(Theme.FOCUS);
                c.drawRoundRect(mR, h / 2f, h / 2f, mFill);
            }
            int is = Theme.px(44);
            int ix = Theme.px(20), iy = (h - is) / 2;
            if (mIcon != null) {
                mIcon.setBounds(ix, iy, ix + is, iy + is);
                mIcon.draw(c);
            }
            float x = ix + is + Theme.px(12);
            mTemp.setColor(mFocused ? Theme.ON_FOCUS : Theme.TEXT1);
            mCity.setColor(mFocused ? (Theme.ON_FOCUS & 0x00FFFFFF) | 0xA0000000 : Theme.TEXT3);
            if (mC.isEmpty()) {
                Paint.FontMetrics fm = mTemp.getFontMetrics();
                c.drawText(mT, x, h / 2f - (fm.ascent + fm.descent) / 2f, mTemp);
            } else {
                c.drawText(mT, x, h * 0.50f, mTemp);
                CharSequence city = TextUtils.ellipsize(mC, mCity, Theme.pxf(220), TextUtils.TruncateAt.END);
                c.drawText(city, 0, city.length(), x, h * 0.86f, mCity);
            }
        }
    }
}
