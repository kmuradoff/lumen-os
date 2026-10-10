package org.z9x.home.sky;

import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.z9x.home.weather.Weather;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Lumen screensaver (prototype D_Saver): the live sky full screen with the scene of the wallpaper, a big
 * clock (Prata) and the date with the temperature bottom-left, the scene name bottom-right. Place and
 * weather come from what Home already has ({@link SkyEnv}, no network). After 5 minutes everything
 * dims to 55 %. Not interactive: any key wakes the projector (DreamService default).
 */
public class SkyDreamService extends DreamService {
    private static final int BG = 0xFF0A0908;
    private static final int TEXT = 0xFFF4EFE6;
    private static final int DATE = 0xFFEADFCF;     // D_Saver date line
    private static final int CAPTION = 0xC7F4EFE6;  // 78 % text over the bottom scrim
    private static final int SHADOW = 0x8C000000;
    private static final long DIM_AFTER_MS = 5 * 60_000L, DIM_MS = 4000;
    private static final float DIM_ALPHA = 0.45f;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT, MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private final Handler mH = new Handler(Looper.getMainLooper());
    private final double[] mLoc = new double[2];
    private SkyView mSky;
    private TextClock mClock;
    private TextView mDate, mCaption;
    private View mDim;
    private float mScale = 1f;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        Resources res = getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        mScale = Math.max(dm.widthPixels, dm.heightPixels) / 1920f;
        float fs = Math.min(1.3f, Math.max(0.85f, res.getConfiguration().fontScale));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        mSky = new SkyView(this);
        mSky.setScrim(0f, 0.7f); // D_Saver: 0.7 at the bottom
        mSky.setScene(SkySettings.scene(this));
        env(reading()); // before the first render, so it is already the right place and weather
        root.addView(mSky, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        mClock = new TextClock(this);
        Locale loc = Locale.getDefault();
        mClock.setFormat24Hour(DateFormat.getBestDateTimePattern(loc, "Hm"));
        mClock.setFormat12Hour(noAmPm(DateFormat.getBestDateTimePattern(loc, "hm")));
        style(mClock, 132, SkyFonts.display(this), TEXT);
        mClock.setLetterSpacing(-0.015f);
        col.addView(mClock, new LinearLayout.LayoutParams(WRAP, WRAP));
        mDate = new TextView(this);
        style(mDate, 28 * fs, SkyFonts.ui(this), DATE);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(WRAP, WRAP);
        dl.topMargin = px(16);
        col.addView(mDate, dl);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.BOTTOM | Gravity.START);
        cl.setMarginStart(px(110));
        cl.bottomMargin = px(96);
        root.addView(col, cl);

        mCaption = new TextView(this);
        style(mCaption, 22 * fs, SkyFonts.ui(this), CAPTION);
        FrameLayout.LayoutParams capl = new FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.BOTTOM | Gravity.END);
        capl.setMarginEnd(px(110));
        capl.bottomMargin = px(104);
        root.addView(mCaption, capl);

        mDim = new View(this);
        mDim.setBackgroundColor(BG);
        mDim.setAlpha(0f);
        root.addView(mDim, new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (mSky == null) return;
        mSky.setPaused(false);
        mDim.animate().cancel();
        mDim.setAlpha(0f);
        mMinute.run();
        mH.postDelayed(mDimRun, DIM_AFTER_MS);
    }

    @Override
    public void onDreamingStopped() {
        mH.removeCallbacksAndMessages(null);
        if (mSky != null) mSky.setPaused(true);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        mH.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private final Runnable mDimRun = () -> {
        if (mDim != null) mDim.animate().alpha(DIM_ALPHA).setDuration(DIM_MS).start();
    };

    /** Every minute, on the minute: place, weather, date line and scene name. */
    private final Runnable mMinute = new Runnable() {
        @Override
        public void run() {
            update();
            long now = System.currentTimeMillis();
            mH.postDelayed(this, 60_000L - now % 60_000L + 200);
        }
    };

    /** Place and weather (the reading read once per minute, also for the temperature). */
    private void env(Weather.Now n) {
        if (SkyEnv.location(this, mLoc)) mSky.setLocation(mLoc[0], mLoc[1]);
        mSky.setWeather(SkyEnv.weather(n));
    }

    private Weather.Now reading() {
        try {
            return Weather.current(this);
        } catch (Throwable t) {
            return null;
        }
    }

    private void update() {
        if (mSky == null) return;
        Weather.Now n = reading();
        env(n);
        Locale loc = Locale.getDefault();
        String d = new SimpleDateFormat(DateFormat.getBestDateTimePattern(loc, "EEEEdMMMM"), loc).format(new Date());
        if (!d.isEmpty()) d = d.substring(0, 1).toUpperCase(loc) + d.substring(1);
        if (n != null && n.temp != null && !n.temp.isEmpty()) d = d + " · " + n.temp;
        mDate.setText(d);
        mCaption.setText(SkyView.sceneTitle(this, mSky.sceneNow()));
    }

    /**
     * The locale's 12-hour pattern without the day period, kept out of the big clock: 'a' outside quoted
     * text goes, and the spaces around it (ICU puts a narrow no-break space before it, which trim()
     * keeps); 'K' (0..11, Japanese) becomes 'h' so 12:30 is not shown as 0:30.
     */
    static String noAmPm(String p) {
        StringBuilder b = new StringBuilder(p.length());
        boolean quoted = false;
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            if (ch == '\'') quoted = !quoted;
            if (!quoted && ch == 'a') continue;
            b.append(!quoted && ch == 'K' ? 'h' : ch);
        }
        int s = 0, e = b.length();
        while (s < e && Character.isSpaceChar(b.charAt(s))) s++;
        while (e > s && Character.isSpaceChar(b.charAt(e - 1))) e--;
        return s < e ? b.substring(s, e) : p;
    }

    private void style(TextView t, float designPx, android.graphics.Typeface tf, int color) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, designPx * mScale);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setShadowLayer(12 * mScale, 0, 2 * mScale, SHADOW);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
    }

    private int px(float design) {
        return Math.round(design * mScale);
    }
}
