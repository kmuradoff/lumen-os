package org.z9x.projector.recents;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import org.z9x.projector.KeyReceiver;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.Ui;
import org.z9x.projector.home.HomeSearch;
import org.z9x.projector.mem.MemoryGuard;

import java.util.ArrayList;
import java.util.List;

/**
 * Recent apps on LONG-PRESS HOME (speed spec 6, PLAN C21, Lumen OS 1.0 decision).
 *
 * <b>Route</b> (no framework patch): Lineage's long-press HOME action SEARCH = launchAssistAction with
 * invocation_type 5 -> SearchManager.launchAssist -> SystemUI AssistManager.startAssistActivity, which
 * starts Secure.assistant = this activity (we hold the ASSISTANT role: config_defaultAssistant +
 * MemoryGuard.ensureAssistant) with the extras. invocation_type 5 shows Recents; any other value (a
 * KEYCODE_ASSIST of another remote, an app's ACTION_ASSIST) forwards to Lumen Home search (keyboard;
 * the classic launcher's voice orb, search_type 1, opens it listening, tap-to-talk) and finishes. A second long-press while Recents is up dismisses it. Short HOME goes home as
 * always (the framework's double-tap delay is removed by the Lineage platform RRO). Long-press HOME does
 * nothing before the setup completed (framework rule; checked here too).
 *
 * <b>Process</b>: the persistent org.z9x.projector, so no process start: first frame target &lt; 300 ms
 * after the long-press threshold (log {@code open type=5 tasks=N firstFrameMs=...}). The views and
 * drawables are released in onDestroy; only the thumbnail LruCache (&lt;= 2 MB) stays (PLAN C21:
 * projector PSS &lt;= v6.5 + 5 MB after Recents was opened and closed).
 *
 * <b>Behaviour</b>: initial focus on the second card when the first is the app just left (still
 * visible under this translucent activity), so "long-press HOME, OK" flips back like Alt-Tab; from the
 * home screen focus starts on the first card. OK switches (startActivityFromRecents resumes the task as
 * it was left), Close / long-press OK closes (MemoryGuard P1: removeTask + force-stop unless
 * protected), Close all (P2), then "No open apps" for 600 ms and HOME.
 */
public final class RecentsActivity extends Activity implements RecentsView.Listener {
    private static final String TAG = "Z9xRecents";
    /** AssistUtils.INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS. */
    static final int TYPE_HOME_LONG_PRESS = 5;
    private static final String EXTRA_TYPE = "invocation_type";
    /**
     * Classic launcher (TVLauncher fpq.g(int)): its search orbs start a plain ASSIST with this int extra,
     * 1 = the voice orb, 2 = the keyboard orb (checked in the image's TVLauncher, hda / gkx cases 6 / 7).
     */
    private static final String EXTRA_SEARCH_TYPE = "search_type";
    private static final int SEARCH_TYPE_VOICE = 1;
    private static final long EMPTY_HOLD_MS = 600;

    private static SafeHandler sWorker;

    private RecentsView view;
    private List<RecentsModel.Card> cards = new ArrayList<>();
    private long t0;
    private boolean leaving;

    /** Quick panel / Lumen Home tile: the same screen as a long-press HOME. */
    public static void open(Context ctx) {
        try {
            Ui.wakeFromDream(ctx);
            ctx.startActivity(new Intent(ctx, RecentsActivity.class).putExtra(EXTRA_TYPE, TYPE_HOME_LONG_PRESS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Throwable t) {
            Log.w(TAG, "open: " + t);
        }
    }

    private static synchronized SafeHandler worker() {
        if (sWorker == null) sWorker = SafeHandler.newThread("z9x-recents");
        return sWorker;
    }

    @Override
    protected void onCreate(Bundle b) {
        t0 = SystemClock.uptimeMillis();
        super.onCreate(b);
        overridePendingTransition(0, 0);
        try {
            if (!dispatch(getIntent(), true)) return;
            show();
        } catch (Throwable t) {
            Log.e(TAG, "onCreate", t);
            finishQuietly();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        try {
            // a second long-press HOME while Recents is up: dismiss (toggle)
            if (intent != null && intent.getIntExtra(EXTRA_TYPE, 0) == TYPE_HOME_LONG_PRESS) {
                Log.i(TAG, "long-press HOME again: dismiss");
                finishQuietly();
                return;
            }
            dispatch(intent, false);
        } catch (Throwable t) {
            Log.w(TAG, "onNewIntent: " + t);
        }
    }

    /** true = show Recents; otherwise the intent was handled (search forward) and we finish. */
    private boolean dispatch(Intent i, boolean creating) {
        int type = i == null ? 0 : i.getIntExtra(EXTRA_TYPE, 0);
        if (type != TYPE_HOME_LONG_PRESS) {
            int searchType = i == null ? -1 : i.getIntExtra(EXTRA_SEARCH_TYPE, -1);
            boolean voice = searchType == SEARCH_TYPE_VOICE;
            Log.i(TAG, "assist type=" + type + " search_type=" + searchType + ": Lumen Home search" + (voice ? " (voice, tap-to-talk)" : ""));
            HomeSearch.open(this, voice, voice, "assist type " + type + ", search_type " + searchType);
            finishQuietly();
            return false;
        }
        if (!KeyReceiver.isSetupComplete(this)) {
            Log.i(TAG, "setup not complete: no Recents");
            finishQuietly();
            return false;
        }
        if (org.z9x.projector.power.StandbyController.isActive()) {
            finishQuietly();
            return false;
        }
        return true;
    }

    private void show() {
        cards = RecentsModel.load(this);
        int focus = 0;
        if (cards.size() > 1 && cards.get(0).visible) focus = 1;     // the app just left: Alt-Tab
        view = new RecentsView(this, this);
        view.bind(cards, focus);
        setContentView(view);
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(120).start();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        final int n = cards.size();
        view.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                view.getViewTreeObserver().removeOnPreDrawListener(this);
                Log.i(TAG, "open type=" + TYPE_HOME_LONG_PRESS + " tasks=" + n + " firstFrameMs="
                        + (SystemClock.uptimeMillis() - t0));
                return true;
            }
        });
        // art off the main thread: the snapshot of the app just left first, then banners
        final List<RecentsModel.Card> list = new ArrayList<>(cards);
        final Context app = getApplicationContext();
        worker().post(() -> {
            if (!list.isEmpty() && list.get(0).visible) {
                RecentsModel.Card c = list.get(0);
                android.graphics.Bitmap bm = RecentsModel.capture(c.taskId());
                if (bm != null) {
                    Ui.main().post(() -> {
                        if (leaving || view == null) return;
                        c.thumb = bm;
                        view.refresh(c);
                    });
                }
            }
            for (RecentsModel.Card c : list) {
                if (c.thumb != null) continue;
                RecentsModel.loadArt(app, c);
                Ui.main().post(() -> {
                    if (!leaving && view != null) view.refresh(c);
                });
            }
        });
    }

    // ------------------------------------------------------------------ RecentsView.Listener

    @Override
    public void onOpen(RecentsModel.Card c) {
        if (leaving) return;
        int task = c.taskId();
        Log.i(TAG, "switch to " + c.pkg + " task=" + task);
        leaving = true;
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            ActivityTaskManager.getService().startActivityFromRecents(task, o.toBundle());   // START_TASKS_FROM_RECENTS
        } catch (Throwable t) {
            Log.w(TAG, "startActivityFromRecents " + task + ": " + t + "; launching the app instead");
            try {
                Intent li = getPackageManager().getLeanbackLaunchIntentForPackage(c.pkg);
                if (li == null) li = getPackageManager().getLaunchIntentForPackage(c.pkg);
                if (li != null) startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t2) {
                Log.w(TAG, "launch " + c.pkg + ": " + t2);
            }
        }
        finishQuietly();
    }

    @Override
    public void onClose(RecentsModel.Card c) {
        if (leaving || view == null) return;
        cards.remove(c);
        RecentsModel.forget(c.taskIds);
        view.remove(c);
        MemoryGuard.close(this, c.pkg, c.taskIds);
        if (cards.isEmpty()) Ui.main().postDelayed(this::goHome, EMPTY_HOLD_MS);
    }

    @Override
    public void onCloseAll() {
        if (leaving || view == null) return;
        List<String> pkgs = new ArrayList<>();
        List<List<Integer>> ids = new ArrayList<>();
        for (RecentsModel.Card c : cards) {
            pkgs.add(c.pkg);
            ids.add(new ArrayList<>(c.taskIds));
            RecentsModel.forget(c.taskIds);
        }
        cards.clear();
        view.clear();
        MemoryGuard.closeAll(this, pkgs, ids, null);
        Ui.main().postDelayed(this::goHome, EMPTY_HOLD_MS);
    }

    private void goHome() {
        if (isFinishing() || isDestroyed()) return;
        leaving = true;
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Log.w(TAG, "home: " + t);
        }
        finishQuietly();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onBackPressed() {
        finishQuietly();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) finishQuietly();                 // HOME / another app on top: never linger
    }

    @Override
    protected void onDestroy() {
        leaving = true;
        if (view != null) view.release();
        view = null;
        cards = new ArrayList<>();
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) RecentsModel.trim();
    }

    private void finishQuietly() {
        leaving = true;
        finish();
        overridePendingTransition(0, 0);
    }
}
