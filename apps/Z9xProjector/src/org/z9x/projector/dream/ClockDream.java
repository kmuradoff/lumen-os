package org.z9x.projector.dream;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.Log;

import org.z9x.projector.SafeHandler;

/**
 * MODULE "screensaver" (v6.2, requirement 6). Our own screensaver: a minimal clock + date on pure
 * black, with the lamp dimmed while it runs (DreamLamp) and restored on wake / sleep.
 * research/v62/dream/DREAM_SPEC.md section 3.
 *
 * Selected in TvSettings > Device preferences > Screen saver as "Clock" next to Google's Backdrop
 * (Ambient) and Colors; DreamSettings.applyDefaultsOnce makes it the active one only while no dream
 * was ever chosen (screensaver_components empty), so the user's later choice always wins.
 *
 * Light on CPU / GPU / power: no wakelock of our own (the dream keeps the screen on itself), no
 * per-second work, no TIME_TICK receiver, no network, no bitmaps; one minute-aligned handler tick.
 *
 * Lifecycle (every callback wrapped: this runs in the persistent process):
 *  - onAttachedToWindow: non-interactive, fullscreen, black window, ClockView at alpha 0;
 *  - onDreamingStarted: text + first tick; after 300 ms (the 250 ms open animation is over and the
 *    screen is black) dim the lamp, then fade the clock in over 1.4 s;
 *  - onWakeUp (a key woke it): restore the lamp FIRST, fade the clock out (200 ms), finish();
 *  - onDreamingStopped / onDetachedFromWindow (also the sleep path, which skips onWakeUp):
 *    cleanup + restore (idempotent).
 */
public final class ClockDream extends DreamService {
    private static final String TAG = "Z9xDream";

    private static final long DIM_DELAY_MS = 300;
    private static final long FADE_IN_MS = 1400;
    private static final long WAKE_FADE_MS = 200;
    private static final long WAKE_FINISH_FALLBACK_MS = 400;
    private static final long TICK_SLACK_MS = 40;

    /** Main thread: our dream is between onDreamingStarted and onDreamingStopped. */
    private static volatile boolean sRunning;

    private final SafeHandler mH = new SafeHandler(Looper.getMainLooper(), "dream");
    private ClockView mView;
    private BroadcastReceiver mReceiver;
    private boolean mStopped;
    private boolean mFinished;

    /** Any thread. */
    static boolean isRunning() {
        return sRunning;
    }

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            if (mStopped || mView == null) return;
            mView.advance();
            scheduleTick();
        }
    };

    private final Runnable mStartLamp = () -> {
        if (mStopped) return;
        int lvl = DreamSettings.lampLevel();
        if (lvl == DreamSettings.LAMP_UNCHANGED) {
            fadeIn();
        } else {
            DreamLamp.dim(this, lvl, this::fadeIn);
        }
    };

    private final Runnable mFinishOnce = () -> {
        if (mFinished) return;
        mFinished = true;
        try { finish(); } catch (Throwable t) { Log.w(TAG, "finish: " + t); }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            setInteractive(false);
            setFullscreen(true);
            setScreenBright(true);    // Android backlight is not the DLP LED (unverified): dim via 176
            if (getWindow() != null) getWindow().setBackgroundDrawable(new ColorDrawable(0xFF000000));
            mView = new ClockView(this);
            mView.setAlpha(0f);
            setContentView(mView);
        } catch (Throwable t) {
            Log.e(TAG, "attach", t);
        }
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        try {
            mStopped = false;
            mFinished = false;
            sRunning = true;
            DreamLamp.install(this);       // no-op when App already installed it
            registerTimeReceiver();
            if (mView != null) {
                mView.refreshFormats();
                mView.updateNow();
            }
            scheduleTick();
            mH.postDelayed(mStartLamp, DIM_DELAY_MS);
            Log.i(TAG, "started");
        } catch (Throwable t) {
            Log.e(TAG, "start", t);
            fadeIn();
        }
    }

    private void fadeIn() {
        try {
            if (mStopped || mView == null) return;
            mView.fadeIn(FADE_IN_MS);
        } catch (Throwable t) {
            Log.w(TAG, "fadeIn: " + t);
            if (mView != null) mView.setAlpha(1f);
        }
    }

    private void scheduleTick() {
        mH.removeCallbacks(mTick);
        long now = System.currentTimeMillis();
        // Next minute after the time the view shows (or is fading to); advance() starts its 450 ms
        // fade-out that much early, so the new time appears on the minute (+ slack), not 0.5 s late.
        long shown = mView != null ? Math.max(mView.shownFor(), 0L) : now;
        if (shown == 0L) shown = now;
        long next = (shown / 60_000L + 1) * 60_000L + TICK_SLACK_MS - ClockView.ADVANCE_OUT_MS;
        mH.postDelayed(mTick, Math.max(0L, next - now));
    }

    private void registerTimeReceiver() {
        if (mReceiver != null) return;
        mReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    if (mStopped || mView == null) return;
                    mView.refreshFormats();
                    mView.updateNow();
                    scheduleTick();
                } catch (Throwable t) {
                    Log.w(TAG, "time change: " + t);
                }
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_LOCALE_CHANGED);
        registerReceiver(mReceiver, f, Context.RECEIVER_EXPORTED);
    }

    /** A key / stopDream woke us: lamp back first, then a short fade, then finish(). */
    @Override
    public void onWakeUp() {
        try {
            DreamLamp.restore(this);
            cleanup();
            if (mView != null) {
                mView.fadeOut(WAKE_FADE_MS, mFinishOnce);
                mH.postDelayed(mFinishOnce, WAKE_FINISH_FALLBACK_MS);
            } else {
                mFinishOnce.run();
            }
        } catch (Throwable t) {
            Log.w(TAG, "wake: " + t);
            mFinishOnce.run();
        }
    }

    @Override
    public void onDreamingStopped() {
        try {
            cleanup();
            DreamLamp.restore(this);
            Log.i(TAG, "stopped");
        } catch (Throwable t) {
            Log.w(TAG, "stop: " + t);
        }
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        try {
            cleanup();
            DreamLamp.restore(this);
        } catch (Throwable t) {
            Log.w(TAG, "detach: " + t);
        }
        super.onDetachedFromWindow();
    }

    private void cleanup() {
        mStopped = true;
        sRunning = false;
        mH.removeCallbacks(mTick);
        mH.removeCallbacks(mStartLamp);
        if (mReceiver != null) {
            try { unregisterReceiver(mReceiver); } catch (Throwable ignored) { }
            mReceiver = null;
        }
    }
}
