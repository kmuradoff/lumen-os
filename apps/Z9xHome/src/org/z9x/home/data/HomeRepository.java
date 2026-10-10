package org.z9x.home.data;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.z9x.home.App;
import org.z9x.home.Prefs;
import org.z9x.home.R;
import org.z9x.home.tvp.ProgramsInitializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;

/**
 * Loads apps, TvProvider content and inputs on the "z9x-home-io" thread, ranks them and posts an
 * immutable {@link HomeModel} to the UI (SPEC 5.1). Refreshes are coalesced; the snapshot is written
 * 5 s after the last change and when Home stops.
 */
public final class HomeRepository {
    public interface Listener {
        void onModel(HomeModel m);
    }

    public static final String SECURE_LAUNCHER = "z9x_launcher";
    public static final String PKG_TVLAUNCHER = "com.google.android.tvlauncher";
    private static final long NEW_DOT_MS = 24L * 3600_000L;
    private static final long SAVE_DELAY_MS = 5000;

    private final App mApp;
    private final Handler mIo;
    private final SnapshotStore mStore;
    private volatile HomeModel mLast;
    private Listener mListener;
    private String mReason = "";
    private final Runnable mRefresh = () -> doRefresh();
    private final Runnable mSave;

    public HomeRepository(App app) {
        mApp = app;
        mIo = app.io();
        mStore = new SnapshotStore(app);
        mSave = () -> {
            HomeModel m = mLast;
            if (m != null && !m.fromSnapshot) mStore.save(m);
        };
    }

    public HomeModel last() {
        return mLast;
    }

    /** Main thread. */
    public void setListener(Listener l) {
        mListener = l;
    }

    /** Starts reading the snapshot on io; the caller joins it for at most ~60 ms. */
    public FutureTask<HomeModel> loadSnapshotAsync() {
        FutureTask<HomeModel> f = new FutureTask<>(() -> {
            HomeModel m = mStore.load();
            // "Continue watching on Home" hidden since this snapshot was saved: not even its first frame
            if (m != null && !mApp.prefs().continueOnHome()) Ranker.dropTvp(m);
            if (m != null && mLast == null) mLast = m;
            return m;
        });
        mIo.postAtFrontOfQueue(f);
        return f;
    }

    public void refresh(String reason, long delayMs) {
        synchronized (this) {
            mReason = mReason.isEmpty() ? reason : (mReason.contains(reason) ? mReason : mReason + "+" + reason);
        }
        mIo.removeCallbacks(mRefresh);
        mIo.postDelayed(mRefresh, delayMs);
    }

    public void saveSoon() {
        mIo.removeCallbacks(mSave);
        mIo.postDelayed(mSave, SAVE_DELAY_MS);
    }

    public void saveNow() {
        mIo.removeCallbacks(mSave);
        mIo.post(mSave);
    }

    public void post(Runnable r) {
        mIo.post(r);
    }

    private void doRefresh() {
        String reason;
        synchronized (this) {
            reason = mReason;
            mReason = "";
        }
        long t0 = SystemClock.uptimeMillis();
        try {
            HomeModel m = build(mApp);
            mLast = m;
            long ms = SystemClock.uptimeMillis() - t0;
            Log.i(App.TAG, "refresh reason=" + reason + " rows=" + m.rows.size() + " apps=" + m.apps.size()
                    + " hero=" + m.hero.size() + " tvp=" + m.tvpMode + " ms=" + ms);
            mApp.main().post(() -> {
                Listener l = mListener;
                if (l != null) l.onModel(m);
            });
            saveSoon();
        } catch (Throwable t) {
            Log.e(App.TAG, "refresh failed reason=" + reason, t);
        }
    }

    // ------------------------------------------------------------------ building

    private HomeModel build(Context c) {
        Prefs p = mApp.prefs();
        long now = System.currentTimeMillis();
        List<AppSource.Entry> raw = AppSource.load(c);
        Map<String, Long> used = AppSource.lastUsed(c);
        Map<String, Long> firstSeen = trackPackages(c, p, raw, now);

        Ranker.Input in = new Ranker.Input();
        in.now = now;
        in.locale = Locale.getDefault();
        in.lastUsed = used;
        for (AppSource.Entry a : raw) {
            Card k = new Card(Card.APP, a.cn.flattenToString(), a.label);
            k.pkg = a.cn.getPackageName();
            k.appLabel = a.label;
            k.system = a.system;
            k.image = AppSource.imageKey(a);
            k.intent = a.cn.flattenToString();
            k.color = TvpSource.placeholder(k.pkg);
            Long fs = firstSeen.get(k.pkg);
            k.isNew = fs != null && fs > 0 && now - fs < NEW_DOT_MS;
            k.meta = "";
            in.apps.add(k);
            if (!in.labels.containsKey(k.pkg)) in.labels.put(k.pkg, a.label);
        }
        in.favorites = p.list(Prefs.K_FAVORITES);
        in.favoritesSet = p.bool(Prefs.K_FAVORITES_SET, false);
        in.hiddenApps = p.set(Prefs.K_HIDDEN_APPS);
        in.unhiddenApps = p.set(K_UNHIDDEN_APPS);
        in.defaultHidden = AppSource.HIDDEN_BY_DEFAULT;
        in.rowOrder = p.list(Prefs.K_ROW_ORDER);
        in.hiddenRows = p.set(Prefs.K_HIDDEN_ROWS);
        in.shownRows = p.set(Prefs.K_SHOWN_ROWS);
        // "Continue watching on Home" hidden: no TvProvider query at all (no hero, no Watch Next, no channels)
        in.tvp = p.continueOnHome() ? TvpSource.load(c, in.labels) : TvpSource.off();
        in.inputs = InputSource.inputs(c, p.map(Prefs.K_INPUT_LABELS));
        // no cast cards since 1.0.1: their only place was the Inputs tab (gone); the how-to panel in
        // HomeActivity stays without an entry point (in.casts stays empty, nothing probes the packages)
        in.tiles = InputSource.tiles(c);
        in.tYourApps = c.getString(R.string.tab_apps); // D_Calm: the shelf is simply "Apps"
        in.tContinue = c.getString(R.string.row_continue);
        in.tInputs = c.getString(R.string.row_inputs);
        in.tProjector = c.getString(R.string.row_projector);
        in.tCustomize = c.getString(R.string.customize_home);
        in.defaultFavoritePkgs.add(AppSource.PKG_YOUTUBE);
        in.defaultFavoritePkgs.add(AppSource.PKG_KINOPOISK);
        in.defaultFavoritePkgs.add(AppSource.PKG_PLAY);
        HomeModel m = Ranker.build(in);
        m.locale = SnapshotStore.localeTag();
        m.classicAvailable = classicAvailable(c);
        return m;
    }

    /**
     * Visible app cards without touching package tracking or TvProvider: the search screen uses this
     * in classic-launcher mode, where Lumen Home itself never runs (io thread).
     */
    public static List<Card> appCardsForSearch(Context c, Prefs p) {
        Ranker.Input in = new Ranker.Input();
        for (AppSource.Entry a : AppSource.load(c)) {
            Card k = new Card(Card.APP, a.cn.flattenToString(), a.label);
            k.pkg = a.cn.getPackageName();
            k.appLabel = a.label;
            k.system = a.system;
            k.image = AppSource.imageKey(a);
            k.intent = a.cn.flattenToString();
            k.color = TvpSource.placeholder(k.pkg);
            in.apps.add(k);
        }
        in.hiddenApps = p.set(Prefs.K_HIDDEN_APPS);
        in.unhiddenApps = p.set(K_UNHIDDEN_APPS);
        in.defaultHidden = AppSource.HIDDEN_BY_DEFAULT;
        in.tvp = new TvpSource.Result();
        return Ranker.build(in).apps;
    }

    public static final String K_UNHIDDEN_APPS = "unhidden_apps";
    private static final String K_LAST_MODE = "last_launcher_mode";

    /**
     * New-package tracking (the "new" dot for 24 h) and INITIALIZE_PROGRAMS: once for every package on
     * the first run, then for each package that is new since the last refresh, and for all packages
     * again after a switch from the classic launcher back to Lumen Home.
     */
    private Map<String, Long> trackPackages(Context c, Prefs p, List<AppSource.Entry> raw, long now) {
        HashSet<String> current = new HashSet<>();
        for (AppSource.Entry a : raw) current.add(a.cn.getPackageName());
        Map<String, String> known = p.map(Prefs.K_KNOWN_PKGS);
        HashMap<String, Long> out = new HashMap<>();
        boolean first = !p.bool(Prefs.K_INIT_DONE, false);
        String mode = Settings.Secure.getString(c.getContentResolver(), SECURE_LAUNCHER);
        String lastMode = p.str(K_LAST_MODE, "");
        boolean backFromClassic = "classic".equals(lastMode) && !"classic".equals(mode);
        if (mode != null && !mode.equals(lastMode)) p.putStr(K_LAST_MODE, mode);
        ArrayList<String> fresh = new ArrayList<>();
        boolean changed = false;
        for (String pkg : current) {
            String v = known.get(pkg);
            long fs;
            if (v == null) {
                fs = first ? 0 : now;
                if (!first) fresh.add(pkg);
                known.put(pkg, Long.toString(fs));
                changed = true;
            } else {
                try {
                    fs = Long.parseLong(v);
                } catch (NumberFormatException e) {
                    fs = 0;
                }
            }
            out.put(pkg, fs);
        }
        Set<String> gone = new HashSet<>(known.keySet());
        gone.removeAll(current);
        if (!gone.isEmpty()) {
            for (String g : gone) known.remove(g);
            changed = true;
        }
        if (changed) p.putMap(Prefs.K_KNOWN_PKGS, known);
        if (first || backFromClassic) {
            int n = ProgramsInitializer.send(c, current);
            p.putBool(Prefs.K_INIT_DONE, true);
            Log.i(App.TAG, "init-programs all reason=" + (first ? "first-run" : "from-classic") + " receivers=" + n);
        } else if (!fresh.isEmpty()) {
            ProgramsInitializer.send(c, fresh);
        }
        return out;
    }

    /** The classic Android TV launcher can be chosen only if it is installed (possibly disabled). */
    public static boolean classicAvailable(Context c) {
        try {
            c.getPackageManager().getApplicationInfo(PKG_TVLAUNCHER, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
