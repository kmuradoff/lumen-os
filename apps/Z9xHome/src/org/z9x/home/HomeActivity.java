package org.z9x.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.database.ContentObserver;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.z9x.home.customize.CustomizeActivity;
import org.z9x.home.data.Card;
import org.z9x.home.data.HomeModel;
import org.z9x.home.data.HomeRepository;
import org.z9x.home.data.InputSource;
import org.z9x.home.data.Row;
import org.z9x.home.data.TvpSource;
import org.z9x.home.img.ImageLoader;
import org.z9x.home.proj.ProjectorBridge;
import org.z9x.home.search.SearchActivity;
import org.z9x.home.ui.Backdrop;
import org.z9x.home.ui.CardView;
import org.z9x.home.ui.ContextPanel;
import org.z9x.home.ui.ListPanel;
import org.z9x.home.ui.Page;
import org.z9x.home.ui.PageApps;
import org.z9x.home.ui.PageForYou;
import org.z9x.home.ui.RowView;
import org.z9x.home.ui.RowsPage;
import org.z9x.home.ui.Theme;
import org.z9x.home.ui.TopBar;
import org.z9x.home.weather.Weather;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Lumen Home (HOME, priority 3, PLAN C3). Views are built in code; the first frame is drawn from the
 * snapshot (SPEC D7), then refreshed from the sources. D-pad focus is handled by hand: the top bar,
 * the current page, or the side panel gets every key. HOME is never consumed here (long-press HOME is
 * Recents in org.z9x.projector).
 */
public class HomeActivity extends Activity implements PageApps.Host, TopBar.Host, HomeRepository.Listener {
    private static final int TAB_FOR_YOU = 0, TAB_APPS = 1, TAB_INPUTS = 2;
    private static final long HERO_ADVANCE_MS = 9000;
    private static final long LONG_PRESS_MS = 500;
    private static boolean sCreatedOnce;

    private App mApp;
    private Handler mMain;
    private boolean mSafeMode;
    private Root mRoot;
    private Backdrop mBackdrop;
    private TopBar mBar;
    private PageForYou mForYou;
    private PageApps mApps;
    private RowsPage mInputs;
    private Page[] mPages;
    private ContextPanel mPanel;
    private int mTab = TAB_FOR_YOU;
    private boolean mZoneTop;
    private HomeModel mModel;
    private boolean mStarted, mResumed, mStandby;
    private long mLastKey;
    private boolean mOkDown, mOkLong;
    private long mCreateAt;
    private boolean mFullyDrawn;

    private ContentObserver mTvpObserver;
    private LauncherApps.Callback mAppsCb;
    private TvInputManager.TvInputCallback mInputCb;
    private BroadcastReceiver mStandbyRx;
    private ConnectivityManager.NetworkCallback mNetCb;
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefsCb;

    private final Runnable mLongPress = () -> {
        if (mOkDown && !mOkLong) {
            mOkLong = true;
            onLongOk();
        }
    };
    private final Runnable mHeroTick = this::heroTick;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        mCreateAt = SystemClock.uptimeMillis();
        super.onCreate(b);
        mApp = App.get();
        mMain = new Handler(Looper.getMainLooper());
        if (mApp.isCrashLooping()) {
            mSafeMode = true;
            Log.w(App.TAG, "safe-mode");
            setContentView(new SafeModeView(this, () -> {
                mApp.clearCrashes();
                recreate();
            }));
            return;
        }
        Theme.init(this);
        HomeRepository repo = mApp.repo();
        FutureTask<HomeModel> snap = repo.last() == null ? repo.loadSnapshotAsync() : null;
        build();
        HomeModel first = repo.last();
        if (snap != null) {
            try {
                first = snap.get(60, TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                Log.i(App.TAG, "snapshot not ready in 60 ms");
            }
        }
        if (first != null) bind(first);
        repo.setListener(this);
        final boolean cold = !sCreatedOnce;
        sCreatedOnce = true;
        mRoot.getViewTreeObserver().registerFrameCommitCallback(() -> {
            long now = SystemClock.uptimeMillis();
            long base = cold ? Process.getStartUptimeMillis() : mCreateAt;
            Log.i(App.TAG, "start cold=" + (cold ? 1 : 0) + " firstFrame=" + (now - base) + "ms snapshot="
                    + (mModel != null && mModel.fromSnapshot ? 1 : 0));
        });
        handleIntent(getIntent(), true);
        // a stable minute clears the crash counter
        mMain.postDelayed(() -> mApp.clearCrashes(), 60_000);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (mSafeMode) return;
        handleIntent(i, false);
    }

    private void handleIntent(Intent i, boolean create) {
        if (i != null && Intent.ACTION_ALL_APPS.equals(i.getAction())) {
            Log.i(App.TAG, "intent ALL_APPS");
            if (mPanel.isOpen()) mPanel.close();
            switchTab(TAB_APPS, false);
            enterPage();
            return;
        }
        // HOME (also while already on Home): back to the top, focus "Your apps" #1
        if (mPanel.isOpen()) mPanel.close();
        if (mTab != TAB_FOR_YOU) switchTab(TAB_FOR_YOU, false);
        mBar.focusOut();
        mZoneTop = false;
        mForYou.focusFirstApp();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mSafeMode) return;
        mStarted = true;
        mBar.refreshClockVisibility();
        for (Page p : mPages) p.reload();
        mApp.repo().refresh("start", 0);
        register();
        mApp.io().post(() -> {
            boolean sb = ProjectorBridge.standby(this);
            boolean upd = ProjectorBridge.updateReady(this);
            mMain.post(() -> {
                mStandby = sb;
                mBar.setUpdateReady(upd);
            });
        });
        refreshWeather(false, false);
        pollFullyDrawn(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mSafeMode) return;
        mResumed = true;
        mLastKey = SystemClock.uptimeMillis();
        mForYou.hero().setTrailersEnabled(mApp.prefs().trailers());
        scheduleHero();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mSafeMode) return;
        mResumed = false;
        mMain.removeCallbacks(mHeroTick);
        mForYou.hero().pauseTrailer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mSafeMode) return;
        mStarted = false;
        unregister();
        mApp.repo().saveNow();
        for (Page p : mPages) p.trim();
        mBackdrop.release();
        mApp.images().trimToBanners();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (mSafeMode) return;
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            mBackdrop.setFrozen(true);
            mForYou.hero().pauseTrailer();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSafeMode) return;
        mMain.removeCallbacksAndMessages(null);
        mApp.repo().setListener(null);
    }

    // ------------------------------------------------------------------ building

    private void build() {
        mRoot = new Root(this);
        mBackdrop = new Backdrop(this, mApp.images());
        mRoot.addView(mBackdrop);
        mForYou = new PageForYou(this, this);
        mApps = new PageApps(this, this);
        mInputs = new RowsPage(this, this, getString(R.string.tab_inputs), 150, m -> {
            ArrayList<Row> rows = new ArrayList<>();
            Row a = new Row(Row.INPUTS, Row.ID_INPUTS, getString(R.string.inputs_connected));
            a.cards.addAll(m.inputs);
            rows.add(a);
            Row c = new Row(Row.CAST, Row.ID_CAST, getString(R.string.inputs_cast));
            c.cards.addAll(m.casts);
            rows.add(c);
            Row p = new Row(Row.PROJECTOR, Row.ID_PROJECTOR, getString(R.string.row_projector));
            p.cards.addAll(m.tiles);
            rows.add(p);
            return rows;
        });
        mPages = new Page[]{mForYou, mApps, mInputs};
        for (Page p : mPages) mRoot.addView(p.view());
        mApps.setVisibility(View.GONE);
        mInputs.setVisibility(View.GONE);
        mApps.setShown(false);
        mInputs.setShown(false);
        mBar = new TopBar(this, this);
        mRoot.addView(mBar);
        mPanel = new ContextPanel(this);
        mPanel.setOnClosed(() -> mLastKey = SystemClock.uptimeMillis());
        mRoot.addView(mPanel);
        setContentView(mRoot);
        mBackdrop.show(null, null);
    }

    @Override
    public void onModel(HomeModel m) {
        if (mSafeMode) return;
        Safe.run("bind", () -> bind(m));
    }

    private void bind(HomeModel m) {
        mModel = m;
        for (Page p : mPages) p.bind(m);
        mForYou.hero().setTrailersEnabled(mApp.prefs().trailers());
        if (!m.fromSnapshot) scheduleHero();
    }

    private void pollFullyDrawn(int n) {
        if (mFullyDrawn) return;
        mMain.postDelayed(() -> {
            if (mFullyDrawn || !mStarted) return;
            if (mForYou.artReady() || n >= 15) {
                mFullyDrawn = true;
                Log.i(App.TAG, "fullyDrawn ms=" + (SystemClock.uptimeMillis() - mCreateAt) + " art=" + mForYou.artReady());
                try {
                    reportFullyDrawn();
                } catch (Throwable ignored) {
                }
            } else {
                pollFullyDrawn(n + 1);
            }
        }, 100);
    }

    // ------------------------------------------------------------------ observers (only while started)

    private void register() {
        Handler h = mMain;
        mTvpObserver = new ContentObserver(h) {
            @Override
            public void onChange(boolean selfChange) {
                mApp.repo().refresh("tvp", 1500);
            }
        };
        try {
            getContentResolver().registerContentObserver(Uri.parse("content://" + TvContract.AUTHORITY), true, mTvpObserver);
        } catch (Throwable t) {
            Log.w(App.TAG, "tvp observer: " + t);
        }
        LauncherApps la = getSystemService(LauncherApps.class);
        mAppsCb = new LauncherApps.Callback() {
            @Override
            public void onPackageRemoved(String p, UserHandle u) {
                mApp.repo().refresh("pkg", 300);
            }

            @Override
            public void onPackageAdded(String p, UserHandle u) {
                mApp.repo().refresh("pkg", 300);
            }

            @Override
            public void onPackageChanged(String p, UserHandle u) {
                mApp.repo().refresh("pkg", 300);
            }

            @Override
            public void onPackagesAvailable(String[] p, UserHandle u, boolean r) {
                mApp.repo().refresh("pkg", 300);
            }

            @Override
            public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) {
                mApp.repo().refresh("pkg", 300);
            }
        };
        try {
            la.registerCallback(mAppsCb, h);
        } catch (Throwable t) {
            Log.w(App.TAG, "launcherapps: " + t);
        }
        TvInputManager tim = getSystemService(TvInputManager.class);
        if (tim != null) {
            mInputCb = new TvInputManager.TvInputCallback() {
                @Override
                public void onInputStateChanged(String id, int state) {
                    mApp.repo().refresh("input", 300);
                }

                @Override
                public void onInputAdded(String id) {
                    mApp.repo().refresh("input", 300);
                }

                @Override
                public void onInputRemoved(String id) {
                    mApp.repo().refresh("input", 300);
                }

                @Override
                public void onInputUpdated(String id) {
                    mApp.repo().refresh("input", 300);
                }
            };
            tim.registerCallback(mInputCb, h);
        }
        mStandbyRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                mStandby = i.getBooleanExtra("standby", false);
                Log.i(App.TAG, "standby=" + mStandby);
                if (mStandby) mForYou.hero().pauseTrailer();
                scheduleHero();
            }
        };
        registerReceiver(mStandbyRx, new IntentFilter(ProjectorBridge.ACTION_STANDBY_CHANGED), ProjectorBridge.PERM_STANDBY,
                h, Context.RECEIVER_EXPORTED);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        mNetCb = new ConnectivityManager.NetworkCallback() {
            private boolean mFirst = true;

            @Override
            public void onAvailable(Network n) {
                if (mFirst) { // the registration callback itself, not a change
                    mFirst = false;
                    return;
                }
                mMain.post(() -> refreshWeather(true, false));
            }
        };
        try {
            cm.registerDefaultNetworkCallback(mNetCb, h);
        } catch (Throwable t) {
            mNetCb = null;
        }
        mPrefsCb = (sp, key) -> {
            if (key == null) return;
            if (key.equals(Prefs.K_TRAILERS)) mForYou.hero().setTrailersEnabled(mApp.prefs().trailers());
            if (key.equals(Prefs.K_WEATHER_ON) || key.equals(Prefs.K_CITY) || key.equals(Prefs.K_UNITS)) {
                refreshWeather(false, true);
                return;
            }
            if (key.equals(Prefs.K_VOICE_FAIL_UNTIL) || key.equals(Prefs.K_RECENT_SEARCH) || key.equals(Prefs.K_KNOWN_PKGS)
                    || key.equals(Prefs.K_INIT_DONE) || key.equals(Prefs.K_HERO_AUTO)) {
                if (key.equals(Prefs.K_HERO_AUTO)) scheduleHero();
                return;
            }
            mApp.repo().refresh("prefs", 100);
        };
        mApp.prefs().raw().registerOnSharedPreferenceChangeListener(mPrefsCb);
    }

    private void unregister() {
        Safe.run("unregister", () -> {
            if (mTvpObserver != null) getContentResolver().unregisterContentObserver(mTvpObserver);
            if (mAppsCb != null) getSystemService(LauncherApps.class).unregisterCallback(mAppsCb);
            TvInputManager tim = getSystemService(TvInputManager.class);
            if (tim != null && mInputCb != null) tim.unregisterCallback(mInputCb);
            if (mStandbyRx != null) unregisterReceiver(mStandbyRx);
            if (mNetCb != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(mNetCb);
            if (mPrefsCb != null) mApp.prefs().raw().unregisterOnSharedPreferenceChangeListener(mPrefsCb);
        });
        mTvpObserver = null;
        mAppsCb = null;
        mInputCb = null;
        mStandbyRx = null;
        mNetCb = null;
        mPrefsCb = null;
    }

    // ------------------------------------------------------------------ weather

    private void showWeather() {
        Weather.Now n = Weather.current(this);
        if (n == null) mBar.setWeather(0, null, null, false);
        else mBar.setWeather(n.icon, n.temp, n.city, n.stale);
    }

    private void refreshWeather(boolean networkChanged, boolean force) {
        showWeather();
        if (!mApp.prefs().weatherOn() || !mStarted) return;
        if (mStandby) return; // no fetch while the lamp is off
        mApp.io().post(() -> {
            if (force) Weather.invalidate(this);
            boolean changed = Weather.refresh(this, networkChanged, force);
            if (changed || force) mMain.post(this::showWeather);
        });
    }

    // ------------------------------------------------------------------ hero auto-advance

    private void scheduleHero() {
        mMain.removeCallbacks(mHeroTick);
        if (mResumed) mMain.postDelayed(mHeroTick, HERO_ADVANCE_MS);
    }

    private void heroTick() {
        if (!mResumed) return;
        long idle = SystemClock.uptimeMillis() - mLastKey;
        boolean ok = mTab == TAB_FOR_YOU && mForYou.inTopMode() && !mPanel.isOpen() && !mStandby
                && mApp.prefs().heroAuto() && mForYou.hero().count() > 1;
        if (ok && idle >= HERO_ADVANCE_MS) {
            Log.i(App.TAG, "hero advance");
            mForYou.hero().next(true);
            mMain.postDelayed(mHeroTick, HERO_ADVANCE_MS);
        } else {
            mMain.postDelayed(mHeroTick, Math.max(1000, HERO_ADVANCE_MS - idle));
        }
    }

    // ------------------------------------------------------------------ tabs and focus zones

    private Page page() {
        return mPages[mTab];
    }

    private void switchTab(int t, boolean animate) {
        if (t == mTab) return;
        Page old = page();
        mTab = t;
        Page nu = page();
        mBar.setSelectedTab(t);
        old.focusOut();
        old.setShown(false);
        View ov = old.view(), nv = nu.view();
        ov.animate().cancel();
        nv.animate().cancel();
        if (animate) {
            ov.animate().alpha(0).setDuration(Theme.TAB_OUT_MS).withEndAction(() -> ov.setVisibility(View.GONE)).start();
            nv.setAlpha(0);
            nv.setTranslationY(Theme.pxf(24));
            nv.setVisibility(View.VISIBLE);
            nv.animate().alpha(1).translationY(0).setStartDelay(60).setDuration(Theme.TAB_IN_MS).setInterpolator(Theme.DECEL).start();
        } else {
            ov.setVisibility(View.GONE);
            nv.setAlpha(1);
            nv.setTranslationY(0);
            nv.setVisibility(View.VISIBLE);
        }
        nu.setShown(true);
        nu.scrollToTop(false);
        mBar.animate().alpha(1).setDuration(Theme.BAR_FADE_MS).start();
        if (t == TAB_FOR_YOU) {
            Card k = mForYou.hero().current();
            mBackdrop.show(k != null ? k.image : null, k != null ? k.pkg : null);
        } else {
            mBackdrop.show(null, null);
        }
        scheduleHero();
    }

    private void enterPage() {
        mZoneTop = false;
        mBar.focusOut();
        page().focusIn();
    }

    private void enterTop() {
        mZoneTop = true;
        page().focusOut();
        mBar.focusIn();
    }

    @Override
    public void onTabFocused(int tab) {
        switchTab(tab, true);
    }

    @Override
    public void onTopAction(String a) {
        switch (a) {
            case TopBar.A_SEARCH:
                startActivity(new Intent(this, SearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case TopBar.A_UPDATE:
                ProjectorBridge.openUpdater(this);
                break;
            case TopBar.A_WEATHER:
                openWeather();
                break;
            case TopBar.A_INPUT:
                ProjectorBridge.showSource(this);
                break;
            case TopBar.A_SETTINGS:
                ProjectorBridge.showPanel(this, "");
                break;
            default:
        }
    }

    // ------------------------------------------------------------------ keys

    private static boolean isOk(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
                || k == KeyEvent.KEYCODE_BUTTON_A;
    }

    private static boolean isDpad(int k) {
        return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT
                || k == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (mSafeMode) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        mLastKey = SystemClock.uptimeMillis();
        try {
            if (mPanel.isOpen()) {
                if (mPanel.onKey(k, e)) return true;
                return super.dispatchKeyEvent(e);
            }
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) onBack();
                return true;
            }
            if (k == KeyEvent.KEYCODE_MENU) {
                if (e.getAction() == KeyEvent.ACTION_UP && !mZoneTop) page().onMenu();
                return true;
            }
            if (isOk(k)) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) {
                        mOkDown = true;
                        mOkLong = false;
                        mMain.postDelayed(mLongPress, LONG_PRESS_MS);
                    } else if (!mOkLong && (e.isLongPress() || e.getEventTime() - e.getDownTime() >= LONG_PRESS_MS)) {
                        mMain.removeCallbacks(mLongPress);
                        mOkLong = true;
                        onLongOk();
                    }
                } else if (e.getAction() == KeyEvent.ACTION_UP) {
                    mMain.removeCallbacks(mLongPress);
                    boolean wasLong = mOkLong;
                    mOkDown = false;
                    mOkLong = false;
                    if (!wasLong && !e.isCanceled()) routeKey(KeyEvent.KEYCODE_DPAD_CENTER, e);
                }
                return true;
            }
            if (isDpad(k)) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) routeKey(k, e);
                return true;
            }
        } catch (Throwable t) {
            Log.e(App.TAG, "key " + k, t);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void routeKey(int k, KeyEvent e) {
        if (mZoneTop) {
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                enterPage();
                return;
            }
            if (!mBar.onKey(k, e) && isOk(k)) enterPage();
            return;
        }
        boolean used = page().onKey(k, e);
        if (!used && k == KeyEvent.KEYCODE_DPAD_UP) enterTop();
    }

    private void onLongOk() {
        if (!mZoneTop) page().onMenu();
    }

    private void onBack() {
        if (!mZoneTop && page().onBack()) return;
        if (mTab != TAB_FOR_YOU) {
            switchTab(TAB_FOR_YOU, true);
            mBar.focusOut();
            mZoneTop = false;
            mForYou.focusFirstApp();
            return;
        }
        if (mZoneTop) {
            enterPage();
            return;
        }
        mForYou.scrollToTop(true);
    }

    // ------------------------------------------------------------------ Page.Host

    @Override
    public ImageLoader images() {
        return mApp.images();
    }

    @Override
    public void onRowFocus(RowView row, Card k) {
        if (mTab != TAB_FOR_YOU) return;
        if (mForYou.inTopMode()) {
            Card h = mForYou.hero().current();
            mBackdrop.show(h != null ? h.image : null, h != null ? h.pkg : null);
        } else if (k != null && k.kind == Card.PROGRAM && k.image != null) {
            mBackdrop.show(k.image, k.pkg);
        }
    }

    @Override
    public void onFocusArt(String uri, String pkg) {
        if (mTab == TAB_FOR_YOU || uri == null) mBackdrop.show(uri, pkg);
    }

    @Override
    public void onBrowseMode(boolean browse) {
        mBar.animate().cancel();
        mBar.animate().alpha(browse ? 0f : 1f).setDuration(Theme.BAR_FADE_MS).start();
        if (!browse) {
            Card h = mForYou.hero().current();
            mBackdrop.show(h != null ? h.image : null, h != null ? h.pkg : null);
        }
        scheduleHero();
    }

    @Override
    public void onHeroShown(Card k) {
        if (mTab == TAB_FOR_YOU && mForYou.inTopMode()) mBackdrop.show(k.image, k.pkg);
    }

    @Override
    public void onHeroAction(Card k, boolean primary) {
        mLastKey = SystemClock.uptimeMillis();
        if (k.kind == Card.FEATURE) {
            switch (k.intent) {
                case "feature:cast":
                    openCastHowTo(null);
                    break;
                case "feature:picture":
                    ProjectorBridge.showPanel(this, primary ? "action:autofocus" : "section:keystone");
                    break;
                default:
                    onCustomize();
            }
            return;
        }
        if (primary) Launch.open(this, k);
        else Launch.openApp(this, k.pkg);
    }

    @Override
    public void onCardClick(Card k, CardView v) {
        if (Launch.open(this, k)) return;
        if (k.kind == Card.CAST) openCastHowTo(k);
        else if (k.kind == Card.FEATURE) onHeroAction(k, true);
        else if ("customize".equals(k.intent)) onCustomize();
    }

    @Override
    public void onCustomize() {
        startActivity(new Intent(this, CustomizeActivity.class));
    }

    @Override
    public void onMoveDone(RowView row) {
        if (row.row() != null && Row.ID_APPS.equals(row.row().id)) saveFavorites(row.row().cards);
    }

    @Override
    public void onFavoritesReordered(List<Card> favorites) {
        saveFavorites(favorites);
    }

    private void saveFavorites(List<Card> cards) {
        ArrayList<String> ids = new ArrayList<>();
        for (Card c : cards) ids.add(c.id);
        Prefs p = mApp.prefs();
        p.putList(Prefs.K_FAVORITES, ids);
        p.putBool(Prefs.K_FAVORITES_SET, true);
        Log.i(App.TAG, "favorites saved n=" + ids.size());
    }

    private List<String> currentFavorites() {
        ArrayList<String> ids = new ArrayList<>();
        if (mModel != null) for (Card c : mModel.favorites) ids.add(c.id);
        return ids;
    }

    // ------------------------------------------------------------------ context menu

    @Override
    public void onCardMenu(Card k, RowView row) {
        ArrayList<ListPanel.Item> items = new ArrayList<>();
        switch (k.kind) {
            case Card.APP: {
                boolean fav = currentFavorites().contains(k.id);
                items.add(ListPanel.Item.action(R.drawable.ic_play, getString(R.string.menu_open), () -> {
                    mPanel.close();
                    Launch.open(this, k);
                }));
                items.add(ListPanel.Item.action(fav ? R.drawable.ic_star_off : R.drawable.ic_star,
                        getString(fav ? R.string.menu_remove_favorite : R.string.menu_add_favorite), () -> {
                            List<String> ids = currentFavorites();
                            if (fav) ids.remove(k.id);
                            else ids.add(k.id);
                            mApp.prefs().putList(Prefs.K_FAVORITES, ids);
                            mApp.prefs().putBool(Prefs.K_FAVORITES_SET, true);
                            mPanel.close();
                        }));
                if (fav && currentFavorites().size() > 1) {
                    items.add(ListPanel.Item.action(R.drawable.ic_move, getString(R.string.menu_move), () -> {
                        mPanel.close();
                        if (mTab == TAB_APPS) mApps.startMoveFavorite(k);
                        else if (row != null && Row.ID_APPS.equals(row.row().id)) row.startMove();
                        else {
                            RowView r = mForYou.appsRow();
                            if (r != null) r.startMove();
                        }
                    }));
                }
                items.add(ListPanel.Item.action(R.drawable.ic_eye_off, getString(R.string.menu_hide_app), () -> {
                    mApp.prefs().toggleInSet(Prefs.K_HIDDEN_APPS, k.id, true);
                    mApp.prefs().toggleInSet(HomeRepository.K_UNHIDDEN_APPS, k.id, false);
                    mPanel.close();
                }));
                items.add(ListPanel.Item.action(R.drawable.ic_info, getString(R.string.menu_app_info), () -> {
                    mPanel.close();
                    Launch.start(this, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", k.pkg, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "app info " + k.pkg);
                }));
                if (!k.system) {
                    items.add(ListPanel.Item.action(R.drawable.ic_trash, getString(R.string.menu_uninstall), () -> {
                        mPanel.close();
                        Launch.start(this, new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", k.pkg, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "uninstall " + k.pkg);
                    }));
                }
                mPanel.open(k.title, null, items);
                break;
            }
            case Card.PROGRAM: {
                items.add(ListPanel.Item.action(R.drawable.ic_play, getString(R.string.menu_open), () -> {
                    mPanel.close();
                    Launch.open(this, k);
                }));
                items.add(ListPanel.Item.action(R.drawable.ic_open, getString(R.string.hero_open_app, k.appLabel), () -> {
                    mPanel.close();
                    Launch.openApp(this, k.pkg);
                }));
                if (k.table == Card.T_WATCH_NEXT || k.table == Card.T_PREVIEW) {
                    boolean wn = k.table == Card.T_WATCH_NEXT;
                    items.add(ListPanel.Item.action(R.drawable.ic_trash,
                            getString(wn ? R.string.menu_remove_continue : R.string.menu_hide_item), () -> {
                                mPanel.close();
                                mApp.io().post(() -> TvpSource.hideProgram(this, k));
                                mApp.repo().refresh("hide", 600);
                            }));
                }
                if (k.channelId >= 0) {
                    items.add(ListPanel.Item.action(R.drawable.ic_eye_off, getString(R.string.menu_hide_channel), () -> {
                        mPanel.close();
                        String id = Row.channelId(k.channelId);
                        mApp.prefs().toggleInSet(Prefs.K_SHOWN_ROWS, id, false);
                        mApp.prefs().toggleInSet(Prefs.K_HIDDEN_ROWS, id, true);
                        mApp.io().post(() -> TvpSource.setChannelBrowsable(this, k.channelId, false));
                    }));
                }
                mPanel.open(k.title, k.appLabel, items);
                break;
            }
            case Card.INPUT: {
                items.add(ListPanel.Item.action(R.drawable.ic_play, getString(R.string.menu_open), () -> {
                    mPanel.close();
                    Launch.open(this, k);
                }));
                items.add(ListPanel.Item.action(R.drawable.ic_edit, getString(R.string.menu_rename), () -> renameInput(k)));
                mPanel.open(k.title, k.desc.isEmpty() ? k.meta : k.desc, items);
                break;
            }
            default:
        }
    }

    private EditText editor(CharSequence hint, CharSequence text) {
        EditText e = new EditText(this);
        Theme.text(e, 30, Theme.REGULAR, Theme.TEXT1);
        e.setHint(hint);
        e.setHintTextColor(Theme.TEXT3);
        e.setText(text);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        e.setGravity(Gravity.CENTER_VERTICAL);
        e.setPadding(Theme.px(28), 0, Theme.px(28), 0);
        e.setTextCursorDrawable(R.drawable.cursor);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.SURFACE2);
        bg.setCornerRadius(Theme.pxf(38));
        e.setBackground(bg);
        if (text != null) e.setSelection(text.length());
        return e;
    }

    private void renameInput(Card k) {
        EditText e = editor(getString(R.string.rename_hint), k.title);
        Runnable save = () -> {
            String name = e.getText().toString().trim();
            Map<String, String> m = mApp.prefs().map(Prefs.K_INPUT_LABELS);
            if (name.isEmpty()) m.remove(k.id);
            else m.put(k.id, name.length() > 40 ? name.substring(0, 40) : name);
            mApp.prefs().putMap(Prefs.K_INPUT_LABELS, m);
            mPanel.close();
        };
        e.setOnEditorActionListener((v, id, ev) -> {
            save.run();
            return true;
        });
        ArrayList<ListPanel.Item> items = new ArrayList<>();
        items.add(ListPanel.Item.action(R.drawable.ic_check, getString(R.string.save), save));
        items.add(ListPanel.Item.action(R.drawable.ic_close, getString(R.string.rename_reset), () -> {
            e.setText("");
            save.run();
        }));
        mPanel.pushEditor(getString(R.string.menu_rename), k.desc.isEmpty() ? k.title : k.desc, e, items);
    }

    // ------------------------------------------------------------------ panels

    private void openCastHowTo(Card k) {
        String name = InputSource.deviceName(this);
        boolean airplay = k != null && "cast:airplay".equals(k.id);
        ArrayList<ListPanel.Item> items = new ArrayList<>();
        if (k == null) {
            items.add(ListPanel.Item.info(R.drawable.ic_cast, getString(R.string.cast_chromecast)));
            items.add(ListPanel.Item.info(0, getString(R.string.cast_steps_chromecast, name)));
            if (InputSource.installed(this, InputSource.PKG_AIRPLAY)) {
                items.add(ListPanel.Item.info(R.drawable.ic_airplay, getString(R.string.cast_airplay)));
                items.add(ListPanel.Item.info(0, getString(R.string.cast_steps_airplay, name)));
            }
        } else {
            items.add(ListPanel.Item.info(0, getString(airplay ? R.string.cast_steps_airplay : R.string.cast_steps_chromecast, name)));
        }
        if ((k == null || airplay) && InputSource.installed(this, InputSource.PKG_AIRPLAY)) {
            items.add(ListPanel.Item.action(R.drawable.ic_tune, getString(R.string.cast_airplay_settings), () -> {
                mPanel.close();
                Launch.start(this, new Intent("org.z9x.airplay.SETTINGS").setPackage(InputSource.PKG_AIRPLAY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "airplay settings");
            }));
        }
        items.add(ListPanel.Item.action(R.drawable.ic_close, getString(R.string.close), () -> mPanel.close()));
        mPanel.open(k == null ? getString(R.string.feature_cast_title) : k.title, getString(R.string.cast_device_name, name), items);
    }

    private void openWeather() {
        Weather.Now n = Weather.current(this);
        ArrayList<ListPanel.Item> items = new ArrayList<>();
        if (n != null) {
            for (Weather.Day d : n.days) {
                items.add(ListPanel.Item.info(d.icon, d.label).value(d.max + " / " + d.min).raw());
            }
        }
        boolean manual = Weather.manualCity() != null;
        items.add(ListPanel.Item.action(R.drawable.ic_location, getString(R.string.weather_city),
                () -> startActivity(new Intent(this, CustomizeActivity.class).putExtra(CustomizeActivity.EXTRA_PAGE, "city")))
                .value(manual ? Weather.cityName(this) : getString(R.string.weather_city_auto)));
        String u = mApp.prefs().units();
        items.add(ListPanel.Item.action(R.drawable.ic_thermometer, getString(R.string.weather_units), null)
                .value(getString("c".equals(u) ? R.string.units_c : ("f".equals(u) ? R.string.units_f : R.string.units_auto))));
        final ListPanel.Item unitsItem = items.get(items.size() - 1);
        unitsItem.action = () -> {
            String cur = mApp.prefs().units();
            String next = "auto".equals(cur) ? "c" : ("c".equals(cur) ? "f" : "auto");
            mApp.prefs().putStr(Prefs.K_UNITS, next);
            mPanel.close();
            openWeather();
        };
        items.add(ListPanel.Item.action(R.drawable.ic_close, getString(R.string.weather_turn_off), () -> {
            mApp.prefs().putBool(Prefs.K_WEATHER_ON, false);
            mPanel.close();
        }));
        items.add(ListPanel.Item.info(0, getString(R.string.weather_attribution)));
        String title = n != null ? (n.city.isEmpty() ? n.temp : n.city) : getString(R.string.weather);
        String sub = n != null ? getString(R.string.weather_now, n.temp, n.feels) : null;
        mPanel.open(title, sub, items);
    }

    // ------------------------------------------------------------------ root layout

    /** Backdrop, pages and panel fill the screen; the top bar sits at y 40. */
    private final class Root extends ViewGroup {
        Root(Context c) {
            super(c);
            setClipChildren(false);
            setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int w = MeasureSpec.getSize(wms), h = MeasureSpec.getSize(hms);
            int ew = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), eh = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v == mBar) v.measure(ew, MeasureSpec.makeMeasureSpec(Theme.px(Theme.BAR_H), MeasureSpec.EXACTLY));
                else v.measure(ew, eh);
            }
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, h = b - t;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v == mBar) v.layout(0, Theme.px(Theme.TOP), w, Theme.px(Theme.TOP) + v.getMeasuredHeight());
                else v.layout(0, 0, w, h);
            }
        }
    }
}
