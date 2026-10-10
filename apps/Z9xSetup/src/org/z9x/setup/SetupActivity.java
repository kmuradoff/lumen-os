package org.z9x.setup;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.LocaleList;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.app.LocalePicker;

import org.z9x.setup.net.TzLookup;
import org.z9x.setup.net.NetMon;
import org.z9x.setup.net.TimeZones;
import org.z9x.setup.steps.AliceStep;
import org.z9x.setup.steps.DoneStep;
import org.z9x.setup.steps.GoogleStep;
import org.z9x.setup.steps.LauncherStep;
import org.z9x.setup.steps.NetworkStep;
import org.z9x.setup.steps.PictureStep;
import org.z9x.setup.steps.RemoteStep;
import org.z9x.setup.steps.Step;
import org.z9x.setup.steps.WelcomeStep;
import org.z9x.setup.ui.AuroraView;
import org.z9x.setup.ui.Dots;
import org.z9x.setup.ui.Icon;
import org.z9x.setup.ui.Sheet;
import org.z9x.setup.ui.Ui;
import org.z9x.setup.ui.Wordmark;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lumen OS first-run setup: the single HOME activity (priority 10, priv-app) that hosts every step
 * as a View (setup/SPEC.md section 3). Re-entry rules (4.10), crash guard (F1), the hidden
 * "hold BACK 8 s" skip (F2), language switching without a relaunch (4.1) and the finish (4.9).
 */
public class SetupActivity extends Activity implements NetMon.Listener {
    public static final String EXTRA_SAFE_FINISH = "safe_finish";
    private static final long CRASH_WINDOW_MS = 180_000;
    private static final int CRASH_LIMIT = 3;
    private static final long SKIP_HOLD_MS = 8_000;
    private static final long REMOTE_PREFETCH_MS = 3_000;

    public SetupState state;
    public NetMon net;
    public final Handler main = new Handler(Looper.getMainLooper());
    public final ExecutorService bg = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "z9x-setup-bg");
        t.setDaemon(true);
        return t;
    });

    // cached async state (main thread)
    /** null = not known yet, then the SetupBridge answered ping or not. */
    public Boolean bridgeOk;
    public boolean remoteConnected;
    public Bundle aliceInfo;
    private boolean mGeoStarted;

    // views
    private FrameLayout mRoot;
    private AuroraView mAurora;
    private Wordmark mWordmark;
    private Dots mDots;
    private LinearLayout mSplit;
    private LinearLayout mLeft;
    private TextView mTitle;
    private TextView mSub;
    private FrameLayout mLeftExtra;
    private FrameLayout mRight;
    private FrameLayout mFull;
    private View mContent;
    private View mWorking;

    private final List<Step> mSteps = new ArrayList<>();
    private int mCur = -1;
    private long mStepEnter;
    private final Deque<Integer> mHistory = new ArrayDeque<>();
    private boolean mLauncherOnly;
    private boolean mFinishing;
    private boolean mUiBuilt;
    private boolean mStarted;
    private Sheet mSheet;
    private View mFocusBeforeSheet;
    private boolean mBackFired;
    private final Runnable mBackHold = () -> {
        mBackFired = true;
        showSkipDialog();
    };
    private LocaleList mLocales;
    private Runnable mLocaleThen;
    private long mInputBlockedUntil;
    private boolean mReceiverOn;

    private final Runnable mLocaleTimeout = () -> {
        Runnable r = mLocaleThen;
        mLocaleThen = null;
        if (r != null) r.run();
    };

    private final Runnable mRemotePrefetch = new Runnable() {
        @Override
        public void run() {
            Step s = current();
            if (s == null || mFinishing || !Boolean.TRUE.equals(bridgeOk)) return;
            String id = s.id();
            if (!WelcomeStep.ID.equals(id) && !NetworkStep.ID.equals(id)) return;
            Bridge.remoteState(SetupActivity.this, r -> {
                if (Bridge.ok(r)) setRemoteConnected(r.getBoolean("connected", false));
            });
            main.postDelayed(this, REMOTE_PREFETCH_MS);
        }
    };

    private final Runnable mKill = () -> {
        L.i("finish step=8 kill");
        Process.killProcess(Process.myPid());
    };

    private final BroadcastReceiver mBridgeRcv = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Bundle ex = i.getExtras();
            if (ex == null) return;
            if ("remote".equals(ex.getString("what")) && ex.containsKey("connected")) {
                setRemoteConnected(ex.getBoolean("connected"));
            }
            Step s = current();
            if (s != null) s.onBridgeEvent(ex);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(null);
        Ui.init(this);
        state = new SetupState(this);
        mLocales = getResources().getConfiguration().getLocales();
        L.i("create t=" + L.sinceStart() + " state=" + state.state() + " step=" + state.step());

        Intent in = getIntent();
        if (in != null && in.getBooleanExtra(EXTRA_SAFE_FINISH, false)) {
            int uid = getLaunchedFromUid();
            if (uid == Process.ROOT_UID || uid == Process.SHELL_UID) {
                safeFinish("adb");
                return;
            }
            L.w("safe_finish ignored from uid " + uid);
        }

        String st = state.state();
        boolean flags = Sys.setupFlagsComplete(this);
        if (SetupState.STATE_FINISHING.equals(st)) {
            L.i("re-entry: finish was interrupted, running it again");
            runFinish(Finisher.FULL, "resume");
            return;
        }
        if (flags) {
            if (state.launcherApplied() || SetupState.STATE_DONE.equals(st)) {
                L.i("re-entry: flags set, launcher applied -> tail");
                runFinish(Finisher.TAIL, "tail");
                return;
            }
            // upgrade over existing data (or a finish interrupted between flags and launcher):
            // a one-time launcher choice, no full setup
            if (Sys.hasLumenHome(this) && Sys.hasClassic(this)) {
                L.i("re-entry: flags set, launcher not applied -> launcher step only");
                mLauncherOnly = true;
                mSteps.add(new LauncherStep(this));
                buildUi();
                next(null);
                return;
            }
            runFinish(Finisher.LAUNCHER_ONLY, "single-launcher");
            return;
        }
        if (SetupState.STATE_DONE.equals(st)) state.setState(SetupState.STATE_UI); // flags were reset

        final int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        installCrashRecorder(boot);
        int crashes = state.recentCrashes(boot, SystemClock.elapsedRealtime(), CRASH_WINDOW_MS);
        if (crashes >= CRASH_LIMIT) {
            L.w("!!! safe-finish crashloop crashes=" + crashes);
            safeFinish("crashloop");
            return;
        }
        if (Sys.debuggable() && "1".equals(Sys.prop("debug.z9x.setup.crash", "0"))) {
            throw new RuntimeException("debug.z9x.setup.crash=1 (test T13)");
        }

        // Lumen OS 1.0: the remote comes first (owner, 2026-10-08): the XGIMI remote is Bluetooth only,
        // so nothing else can be done with it before it is paired. Always shown, never skipped silently.
        mSteps.add(new RemoteStep(this));
        mSteps.add(new WelcomeStep(this));
        mSteps.add(new NetworkStep(this));
        mSteps.add(new GoogleStep(this));
        mSteps.add(new PictureStep(this));
        mSteps.add(new LauncherStep(this));
        mSteps.add(new AliceStep(this));
        mSteps.add(new DoneStep(this));
        buildUi();
        resume(state.step());
    }

    private static boolean sCrashRecorder;

    /** Records every uncaught exception of this process (crash guard F1), then lets it crash. */
    private void installCrashRecorder(int boot) {
        if (sCrashRecorder) return;
        sCrashRecorder = true;
        final SetupState st = state;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                st.recordCrash(boot, SystemClock.elapsedRealtime());
                L.e("crash recorded", e);
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_SAFE_FINISH, false)) {
            // the launching uid of a running singleTask instance is the original one: use am start -S
            L.w("safe_finish on a running instance ignored; use: am start -S -n org.z9x.setup/.SetupActivity --ez safe_finish true");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mFinishing) return;
        if (!mReceiverOn) {
            try {
                registerReceiver(mBridgeRcv, new IntentFilter(Bridge.ACTION_STATE), Bridge.PERMISSION, main,
                        Context.RECEIVER_EXPORTED);
                mReceiverOn = true;
            } catch (RuntimeException e) {
                L.w("bridge receiver", e);
            }
        }
        if (!mStarted && mUiBuilt) {
            mStarted = true;
            // nothing heavy before the first frame (SPEC 7): start background work after it
            Choreographer.getInstance().postFrameCallback(t -> main.post(this::startBackgroundWork));
        }
        if (mAurora != null) mAurora.poke();
        Step s = current();
        if (s != null) s.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mReceiverOn) {
            try {
                unregisterReceiver(mBridgeRcv);
            } catch (RuntimeException ignored) {
            }
            mReceiverOn = false;
        }
        Step s = current();
        if (s != null && !mFinishing) s.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(mRemotePrefetch);
        if (net != null) net.stop();
        Step s = current();
        if (s != null && !mFinishing) s.onExit();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mFinishing && !hasFocus) {
            // HOME is drawing over us: leave no cached process behind (SPEC 4.9 step 8)
            main.removeCallbacks(mKill);
            main.postDelayed(mKill, 1500);
        }
    }

    private void startBackgroundWork() {
        L.i("first frame t=" + L.sinceStart());
        net = new NetMon(this, this);
        net.start();
        bg.execute(() -> {
            try {
                WifiManager wm = getSystemService(WifiManager.class);
                if (wm != null) {
                    if (!wm.isWifiEnabled()) {
                        L.i("wifi off -> on");
                        wm.setWifiEnabled(true);
                    } else {
                        wm.startScan();
                    }
                }
            } catch (RuntimeException e) {
                L.w("wifi kick", e);
            }
        });
        Bridge.ping(this, r -> {
            bridgeOk = Bridge.ok(r) && r.getInt("version", 0) >= Bridge.API;
            L.i("bridge ping ok=" + bridgeOk + " projector=" + (r == null ? "?" : r.get("projectorVersion")));
            refreshDots();
            if (!bridgeOk) return;
            Bridge.remoteState(this, rs -> {
                if (Bridge.ok(rs)) setRemoteConnected(rs.getBoolean("connected", false));
            });
            Bridge.feature(this, "alice", fr -> {
                aliceInfo = Bridge.ok(fr) && fr.getBoolean("enabled", false) ? fr : null;
                refreshDots();
            });
            main.postDelayed(mRemotePrefetch, REMOTE_PREFETCH_MS);
        });
    }

    /** Bridge / remote step: the XGIMI remote is connected (its keys work) or not. Main thread. */
    public void setRemoteConnected(boolean c) {
        if (c && !remoteConnected) L.i("remote connected (bridge)");
        remoteConnected = c;
        if (c) state.setRemoteSeen(true);
    }

    /** Availability of later steps changed (bridge, network): recount the progress dots. */
    private void refreshDots() {
        if (mUiBuilt && mCur >= 0 && !mFinishing) updateDots();
    }

    @Override
    public void onNetChanged() {
        if (net != null && net.online() && !mGeoStarted) startGeo();
        refreshDots();
        Step s = current();
        if (s != null) s.onNetChanged();
    }

    /** IP time zone, once per process, in the background; never blocks the UI (SPEC 4.2, C23). */
    private void startGeo() {
        mGeoStarted = true;
        if (state.tzManual()) return;
        Thread t = new Thread(() -> {
            TzLookup.Result r = TzLookup.lookup(getApplicationContext());
            main.post(() -> {
                if (r != null) state.setGeo(r.json, r.country, System.currentTimeMillis());
                if (state.tzManual()) return;
                if (r == null || r.tz == null) {
                    L.i("tz source=none id=" + java.util.TimeZone.getDefault().getID());
                    return;
                }
                if (TimeZones.apply(this, r.tz)) {
                    state.setTzSource(r.src);
                    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(r.tz));
                    L.i("tz source=" + r.src + " id=" + r.tz);
                    Step s = current();
                    if (s != null) s.onNetChanged(); // lets the summary re-read the zone
                }
            });
        }, "z9x-setup-geo");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ UI skeleton

    private void buildUi() {
        mUiBuilt = true;
        mRoot = new FrameLayout(this);
        mAurora = new AuroraView(this);
        mRoot.addView(mAurora, match());

        mRoot.setClipChildren(false);
        mSplit = new LinearLayout(this);
        mSplit.setOrientation(LinearLayout.HORIZONTAL);
        mSplit.setClipChildren(false);   // focused cards scale slightly past the column edge
        mSplit.setClipToPadding(false);
        mSplit.setPaddingRelative(Ui.px(160), 0, Ui.px(160), 0);
        mLeft = Ui.vbox(this);
        mLeft.setGravity(Gravity.CENTER_VERTICAL);
        mLeft.setPadding(0, Ui.px(200), 0, Ui.px(140));
        mTitle = Ui.title(this);
        mSub = Ui.subtitle(this);
        mLeftExtra = new FrameLayout(this);
        mLeft.addView(mTitle, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mLeft.addView(mSub, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 24));
        mLeft.addView(mLeftExtra, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 40));
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(Ui.px(660), ViewGroup.LayoutParams.MATCH_PARENT);
        lpLeft.setMarginEnd(Ui.px(80));
        mSplit.addView(mLeft, lpLeft);
        mRight = new FrameLayout(this);
        mRight.setPadding(0, Ui.px(170), 0, Ui.px(110));
        mRight.setClipChildren(false);
        mRight.setClipToPadding(false);
        mSplit.addView(mRight, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        mRoot.addView(mSplit, match());

        mFull = new FrameLayout(this);
        mFull.setVisibility(View.GONE);
        mRoot.addView(mFull, match());

        mWordmark = new Wordmark(this);
        FrameLayout.LayoutParams lw = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lw.setMarginStart(Ui.px(160));
        lw.topMargin = Ui.px(84);
        mRoot.addView(mWordmark, lw);
        mDots = new Dots(this);
        FrameLayout.LayoutParams ld = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        ld.setMarginStart(Ui.px(160));
        ld.topMargin = Ui.px(146);
        mRoot.addView(mDots, ld);
        setContentView(mRoot);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** Spinner + "Getting things ready…" over everything (finish, headless re-runs). */
    private void showWorking() {
        if (mRoot == null) {
            mRoot = new FrameLayout(this);
            mAurora = new AuroraView(this);
            mRoot.addView(mAurora, match());
            setContentView(mRoot);
        }
        if (mWorking != null) return;
        LinearLayout w = Ui.vbox(this);
        w.setGravity(Gravity.CENTER);
        w.setBackgroundColor(0xCC0F1115);
        w.setClickable(true);
        Icon sp = new Icon(this, Icon.SPINNER).color(Ui.TEXT);
        w.addView(sp, new LinearLayout.LayoutParams(Ui.px(64), Ui.px(64)));
        TextView t = Ui.text(this, 30, Ui.TEXT_DIM, Ui.regular());
        t.setText(R.string.working);
        w.addView(t, Ui.lpTop(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 28));
        mWorking = w;
        w.setAlpha(0f);
        mRoot.addView(w, match());
        w.animate().alpha(1f).setDuration(200).start();
        w.requestFocus();
    }

    // ------------------------------------------------------------------ navigation

    public Step current() {
        return mCur >= 0 && mCur < mSteps.size() ? mSteps.get(mCur) : null;
    }

    public Step stepById(String id) {
        for (Step s : mSteps) if (s.id().equals(id)) return s;
        return null;
    }

    private void resume(String id) {
        // Power cut mid-setup (F9): resume at the stored step, or at the nearest earlier step that is
        // available right now (the Google step needs the network, which is not known before NetMon).
        int target = -1;
        if (id != null) {
            for (int i = 0; i < mSteps.size(); i++) {
                if (mSteps.get(i).id().equals(id)) target = i;
            }
            while (target > 0 && !mSteps.get(target).available()) target--;
        }
        if (target <= 0) {
            next(null);
            return;
        }
        for (int i = 0; i < target; i++) {
            if (mSteps.get(i).available() && !mSteps.get(i).autoSkip()) mHistory.push(i);
        }
        L.i("resume at step=" + id);
        show(target, true);
    }

    /** Leave the current step forward. result = next | skip | auto (null for the first show). */
    public void next(String result) {
        if (mFinishing) return;
        Step cur = current();
        if (cur != null) {
            logExit(cur, result == null ? "next" : result);
            state.clearCrashes();
        }
        int i = mCur + 1;
        while (i < mSteps.size()) {
            Step s = mSteps.get(i);
            if (!s.available()) {
                i++;
                continue;
            }
            if (s.autoSkip()) {
                L.i("step=" + s.id() + " exit result=auto dur=0");
                i++;
                continue;
            }
            break;
        }
        if (i >= mSteps.size()) {
            runFinish(mLauncherOnly ? Finisher.LAUNCHER_ONLY : Finisher.FULL, null);
            return;
        }
        if (mCur >= 0) mHistory.push(mCur);
        show(i, true);
    }

    /** Previous visible step (none on the first one); steps with nothing left to do are passed over. */
    public void back() {
        while (!mHistory.isEmpty()) {
            int p = mHistory.pop();
            if (mSteps.get(p).available() && mSteps.get(p).revisitable()) {
                logExit(current(), "back");
                show(p, false);
                return;
            }
        }
    }

    /** Jump to a step from the summary (e.g. the home-screen row on Done); BACK returns. */
    public void goTo(String id) {
        for (int i = 0; i < mSteps.size(); i++) {
            if (mSteps.get(i).id().equals(id) && mSteps.get(i).available()) {
                logExit(current(), "goto");
                mHistory.push(mCur);
                show(i, true);
                return;
            }
        }
    }

    private void logExit(Step s, String result) {
        if (s == null) return;
        L.i("step=" + s.id() + " exit result=" + result + " dur=" + (SystemClock.elapsedRealtime() - mStepEnter));
    }

    private void show(int index, boolean forward) {
        Step old = current();
        if (old != null) old.onExit();
        dismissSheet();
        Step s = mSteps.get(index);
        mCur = index;
        mStepEnter = SystemClock.elapsedRealtime();
        state.setStep(s.id());
        L.i("step=" + s.id() + " enter t=" + L.sinceStart());
        mInputBlockedUntil = SystemClock.uptimeMillis() + 220;

        View content = s.createContent();
        int mode = s.layout();
        applyMode(mode);
        setTitles(s, old != null);
        ViewGroup target = mode == Step.FULL ? mFull : mRight;
        swapContent(content, target, forward, old != null);
        updateDots();
        s.onEnter();
        focusStep(s, content);
        if (old == null && index == 0) playIntro();
    }

    private void applyMode(int mode) {
        boolean full = mode == Step.FULL;
        mFull.setVisibility(full ? View.VISIBLE : View.GONE);
        mSplit.setVisibility(full ? View.INVISIBLE : View.VISIBLE);
        mWordmark.setVisibility(full ? View.INVISIBLE : View.VISIBLE);
        mDots.setVisibility(full ? View.INVISIBLE : View.VISIBLE);
        mAurora.setAuroraEnabled(!full);
        mLeft.setVisibility(mode == Step.SPLIT ? View.VISIBLE : View.GONE);
    }

    private void setTitles(Step s, boolean animate) {
        CharSequence t = s.title(), sub = s.subtitle();
        View extra = s.leftExtra();
        Runnable apply = () -> {
            mTitle.setText(t);
            mSub.setText(sub);
            mSub.setVisibility(sub == null ? View.GONE : View.VISIBLE);
            mLeftExtra.removeAllViews();
            if (extra != null) mLeftExtra.addView(extra);
        };
        if (!animate || s.layout() != Step.SPLIT) {
            apply.run();
            mLeft.setAlpha(1f);
            return;
        }
        mLeft.animate().cancel();
        mLeft.animate().alpha(0f).setDuration(110).withEndAction(() -> {
            apply.run();
            mLeft.animate().alpha(1f).setDuration(200).setInterpolator(Ui.decel()).start();
        }).start();
    }

    private void swapContent(View content, ViewGroup target, boolean forward, boolean animate) {
        View old = mContent;
        mContent = content;
        float dir = forward ? 1f : -1f;
        if (Ui.isRtl(mRoot)) dir = -dir;
        if (old != null) {
            ViewGroup op = (ViewGroup) old.getParent();
            if (old instanceof ViewGroup) ((ViewGroup) old).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            old.animate().cancel();
            old.animate().alpha(0f).translationX(-Ui.pxf(48) * dir).setDuration(180).withLayer()
                    .setInterpolator(Ui.standard())
                    .withEndAction(() -> {
                        if (op != null) op.removeView(old);
                    }).start();
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        target.addView(content, lp);
        if (animate) {
            content.setAlpha(0f);
            content.setTranslationX(Ui.pxf(48) * dir);
            content.animate().alpha(1f).translationX(0f).setDuration(260).setStartDelay(60).withLayer()
                    .setInterpolator(Ui.decel()).start();
        }
    }

    private void focusStep(Step s, View content) {
        content.post(() -> {
            if (mContent != content) return;
            View f = s.initialFocus();
            if (f != null && f.isAttachedToWindow()) {
                f.requestFocus();
            } else {
                content.requestFocus();
            }
        });
    }

    private void updateDots() {
        if (mLauncherOnly) {
            mDots.setVisibility(View.INVISIBLE);
            return;
        }
        int count = 0, pos = 0;
        for (int i = 0; i < mSteps.size(); i++) {
            if (!mSteps.get(i).available()) continue;
            if (i == mCur) pos = count;
            count++;
        }
        mDots.set(count, pos);
    }

    /** The wordmark grows from the centre (last boot-animation frame) to its corner (SPEC 4.0). */
    private void playIntro() {
        mSplit.setAlpha(0f);
        mDots.setAlpha(0f);
        mRoot.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                mRoot.getViewTreeObserver().removeOnPreDrawListener(this);
                float sc = 2.6f;
                int[] loc = new int[2];
                mWordmark.getLocationInWindow(loc);
                float w = mWordmark.getWidth() * sc, h = mWordmark.getHeight() * sc;
                mWordmark.setPivotX(0);
                mWordmark.setPivotY(0);
                mWordmark.setScaleX(sc);
                mWordmark.setScaleY(sc);
                mWordmark.setTranslationX((mRoot.getWidth() - w) / 2f - loc[0]);
                mWordmark.setTranslationY((mRoot.getHeight() - h) / 2f - loc[1]);
                mWordmark.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                        .setStartDelay(400).setDuration(500).setInterpolator(Ui.standard()).start();
                mSplit.animate().alpha(1f).setStartDelay(700).setDuration(420).setInterpolator(Ui.decel()).start();
                mDots.animate().alpha(1f).setStartDelay(800).setDuration(300).start();
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ sheets

    public void showSheet(Sheet s, View focus) {
        dismissSheet();
        mSheet = s;
        mFocusBeforeSheet = getCurrentFocus();
        mSplit.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        mFull.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        s.setOnDismiss(() -> {
            if (mSheet == s) mSheet = null;
            mSplit.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            mFull.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            View f = mFocusBeforeSheet;
            mFocusBeforeSheet = null;
            if (f != null && f.isAttachedToWindow()) f.requestFocus();
            else if (mContent != null) mContent.requestFocus();
        });
        s.show(mRoot, focus);
    }

    public void dismissSheet() {
        if (mSheet != null) mSheet.dismiss();
        mSheet = null;
    }

    private void showSkipDialog() {
        if (mFinishing || mLauncherOnly) return;
        L.i("skip dialog (BACK held " + SKIP_HOLD_MS / 1000 + " s)");
        Sheet sh = new Sheet(this, getString(R.string.skip_setup_title), getString(R.string.skip_setup_text));
        android.widget.TextView cancel = sh.addButton(getString(R.string.action_cancel), true, v -> sh.dismiss());
        sh.addButton(getString(R.string.skip_setup_confirm), false, v -> {
            sh.dismiss();
            safeFinish("user");
        });
        showSheet(sh, cancel);
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (mAurora != null) mAurora.poke();
        if (mFinishing) return true;
        Step cur = mSheet == null ? current() : null;
        if (cur != null && cur.onKey(e)) return true;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            // hidden skip (SPEC F2): BACK held 8 s. A timer from the first DOWN works for IR repeats,
            // HID repeats and remotes that send no repeats at all.
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    mBackFired = false;
                    main.removeCallbacks(mBackHold);
                    main.postDelayed(mBackHold, SKIP_HOLD_MS);
                }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP) {
                main.removeCallbacks(mBackHold);
                if (mBackFired) {
                    mBackFired = false;
                    return true;
                }
                if (!e.isCanceled()) handleBack();
                return true;
            }
            return true;
        }
        if (SystemClock.uptimeMillis() < mInputBlockedUntil
                && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void handleBack() {
        if (mSheet != null) {
            dismissSheet();
            return;
        }
        if (SystemClock.uptimeMillis() < mInputBlockedUntil) return;
        Step s = current();
        if (s != null && s.onBack()) return;
        back();
    }

    // ------------------------------------------------------------------ language

    /** LocalePicker.updateLocales off the main thread; then the config change re-binds the strings. */
    public void setLocale(Locale l, Runnable then) {
        L.i("locale -> " + l.toLanguageTag());
        mLocaleThen = then;
        main.removeCallbacks(mLocaleTimeout);
        bg.execute(() -> {
            try {
                LocalePicker.updateLocales(new LocaleList(l));
            } catch (Throwable t) {
                L.w("updateLocales", t);
            }
            main.postDelayed(mLocaleTimeout, 1500);
        });
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (Ui.displayChanged(this)) {
            // Lumen OS 1.0.1: the UI resolution (1080p / 2K / 4K) changed while setup is shown. configChanges
            // keeps this activity, but the skeleton and every step are sized in px of the old scale: build
            // everything again at the stored step, the same path as after a power cut (F9).
            android.util.DisplayMetrics m = getResources().getDisplayMetrics();
            L.i("display changed -> " + m.widthPixels + "x" + m.heightPixels + " " + m.densityDpi + " dpi");
            Ui.init(this);
            if (mUiBuilt && !mFinishing) {
                mLocaleThen = null;                      // the new instance starts at the stored step
                main.removeCallbacksAndMessages(null);   // nothing of this instance runs after it
                recreate();
                return;
            }
        }
        LocaleList ll = c.getLocales();
        if (ll.equals(mLocales)) return;
        mLocales = ll;
        L.i("locale changed -> " + ll.toLanguageTags() + " (re-bind, no relaunch)");
        Ui.init(this);
        rebindCurrent();
        if (mLocaleThen != null) {
            main.removeCallbacks(mLocaleTimeout);
            Runnable r = mLocaleThen;
            mLocaleThen = null;
            main.postDelayed(r, 280);
        }
    }

    /** Rebuild the current step's views in the new language with a 200 ms cross-fade (SPEC 4.1). */
    private void rebindCurrent() {
        Step s = current();
        if (s == null || mContent == null) return;
        dismissSheet();
        s.onExit();
        View old = mContent;
        ViewGroup parent = (ViewGroup) old.getParent();
        View content = s.createContent();
        mContent = content;
        setTitles(s, false);
        if (parent != null) {
            parent.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            content.setAlpha(0f);
            content.animate().alpha(1f).setDuration(200).start();
            if (old instanceof ViewGroup) ((ViewGroup) old).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            old.animate().alpha(0f).setDuration(200).withEndAction(() -> parent.removeView(old)).start();
        }
        mLeft.setAlpha(0f);
        mLeft.animate().alpha(1f).setDuration(200).start();
        s.onEnter();
        focusStep(s, content);
    }

    // ------------------------------------------------------------------ finish

    /** [Start] on the Done screen. */
    public void finishSetup() {
        runFinish(Finisher.FULL, null);
    }

    /**
     * Safe finish (crash loop, hidden BACK combo, adb rescue): the same end state with defaults:
     * the current locale, no Google, the launcher chosen so far, else Lumen Home, else classic.
     */
    public void safeFinish(String reason) {
        L.w("safe-finish reason=" + reason);
        if (state.launcher() == null) {
            String d = Sys.defaultLauncher(this);
            if (d != null) state.setLauncher(d);
        }
        runFinish(Finisher.FULL, "safe-" + reason);
    }

    private void runFinish(int mode, String reason) {
        if (mFinishing) return;
        mFinishing = true;
        Step s = current();
        if (s != null) s.onExit();
        dismissSheet();
        main.removeCallbacks(mRemotePrefetch);
        showWorking();
        final Context app = getApplicationContext();
        bg.execute(() -> {
            try {
                Finisher.runBackground(app, state, mode, reason);
            } catch (Throwable t) {
                L.e("finish failed", t);
            }
            main.post(this::finishTail);
        });
    }

    /** Steps 7-8 on the main thread. */
    private void finishTail() {
        Finisher.disableSelf(this);
        try {
            startActivity(Finisher.homeIntent());
        } catch (RuntimeException e) {
            L.w("start HOME", e);
        }
        finishAndRemoveTask();
        overridePendingTransition(0, android.R.anim.fade_out);
        main.postDelayed(mKill, 5000);
    }
}
