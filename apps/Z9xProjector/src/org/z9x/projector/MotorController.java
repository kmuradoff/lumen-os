package org.z9x.projector;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.z9x.projector.ak.AkOverlay;
import org.z9x.projector.hal.GmpfClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Focus motor, on its own thread "z9x-motor". The vendor moves the motor continuously after
 * manualFocus(0/1) with no timeout of its own, so:
 *  - a move starts only from a key DOWN with repeatCount 0 (start), and only after the
 *    persisted flag manual_moving=true was committed (a failed commit refuses the move);
 *  - key repeats feed a 700 ms watchdog; no feed -> stop;
 *  - ANY key UP (also canceled) -> stop = manualFocus(2), then the flag is cleared;
 *  - a failed stop is retried every 200 ms while the flag is set and the HAL is connected;
 *    after a process restart or HAL reconnect the flag makes HalController send the stop.
 * Allowed manualFocus values: 17 (overlay open), 0 (stock RIGHT), 1 (stock LEFT), 2 (stop).
 * A stop is sent only when something may be moving (or the overlay entered 17): any
 * manualFocus call interrupts a running autofocus in the vendor.
 */
final class MotorController {
    private static final String TAG = "Z9xMotor";
    static final String KEY_MOVING = "manual_moving";
    private static final long WATCHDOG_MS = 700;
    private static final long STOP_RETRY_MS = 200;
    private static final long STOP_RETRY_SLOW_MS = 2_000;

    private final Context app;
    private final GmpfClient client;
    private final SharedPreferences state;
    private final AtomicBoolean kstInFlight;
    private final SafeHandler h = SafeHandler.newThread("z9x-motor");

    // motor-thread state
    private volatile boolean moving;
    private int dir = -1;
    private boolean entered;          // manualFocus(17) sent by the overlay, not yet followed by 2
    private int stopRetries;
    private volatile long lastActivity;

    private final Runnable watchdog = () -> {
        if (moving) {
            Log.w(TAG, "watchdog: no key repeat for " + WATCHDOG_MS + " ms, stopping");
            stopNow();
        }
    };
    private final Runnable stopRetry = this::stopNow;

    MotorController(Context app, GmpfClient client, SharedPreferences state, AtomicBoolean kstInFlight) {
        this.app = app;
        this.client = client;
        this.state = state;
        this.kstInFlight = kstInFlight;
    }

    boolean isMoving() { return moving; }

    /** Not moving AND the persisted manual_moving flag is clear (a failed stop leaves both set). */
    boolean isIdleConfirmed() { return !moving && !flagged(); }

    /** elapsedRealtime of the last motor key (the overlay uses it for its auto-close timer). */
    long lastActivity() { return lastActivity; }

    private boolean flagged() { return state.getBoolean(KEY_MOVING, false); }

    /** Returns true when the flag is on disk with this value. */
    private boolean setFlag(boolean on) {
        if (flagged() == on) return true;
        if (state.edit().putBoolean(KEY_MOVING, on).commit()) return true;
        Log.e(TAG, "could not persist " + KEY_MOVING + "=" + on);
        return false;
    }

    private boolean halUsable() {
        return HalController.featureEnabled() && client.isConnected();
    }

    // ------------------------------------------------------------------ public (any thread)
    /** Overlay opened: manualFocus(17). */
    void enter() {
        lastActivity = SystemClock.elapsedRealtime();
        h.post(() -> {
            if (!halUsable() || kstInFlight.get() || moving) return;
            if (AkOverlay.isActive()) { Log.i(TAG, "manualFocus(17) refused: auto keystone overlay active"); return; }
            try {
                client.manualFocus(GmpfClient.ManualFocus.ENTER);
                entered = true;
                Log.i(TAG, "manualFocus(17)");
            } catch (GmpfClient.ReplyException e) {
                entered = true;
                Log.w(TAG, "manualFocus(17) reply: " + e);
            } catch (Throwable t) {
                Log.w(TAG, "manualFocus(17): " + t);
            }
        });
    }

    /** Key DOWN with repeatCount 0. dir 1 = stock LEFT, 0 = stock RIGHT. */
    void start(final int d) {
        lastActivity = SystemClock.elapsedRealtime();
        h.post(() -> startNow(d));
    }

    /** Key repeat: re-arm the watchdog (no HAL call). */
    void feed() {
        lastActivity = SystemClock.elapsedRealtime();
        h.post(() -> {
            if (moving) {
                h.removeCallbacks(watchdog);
                h.postDelayed(watchdog, WATCHDOG_MS);
            }
        });
    }

    /** Key UP / focus loss / screen off: stop if anything may be moving. */
    void stop() {
        h.post(this::stopNow);
    }

    /** Overlay closed: stop if moving, or if 17 was sent (leave the manual mode). */
    void exit() {
        h.post(() -> {
            h.removeCallbacks(watchdog);
            if (moving || flagged() || entered) sendStop();
            entered = false;
        });
    }

    /** From other worker threads (keystone, handshake): stop and wait until done. */
    boolean stopAndWait(long timeoutMs) {
        return runAndWait(this::stopNow, timeoutMs);
    }

    /** At (re)connect: send a stop only if a previous move may still be running. */
    boolean stopIfFlaggedAndWait(long timeoutMs) {
        return runAndWait(() -> {
            if (flagged() || moving) {
                Log.w(TAG, "stale manual focus move (flag set): sending stop");
                sendStop();
            }
        }, timeoutMs);
    }

    /** HAL died: nothing is reachable; the persisted flag stays so the reconnect sends the stop. */
    void onHalDied() {
        h.post(() -> {
            h.removeCallbacks(watchdog);
            h.removeCallbacks(stopRetry);
            moving = false;
            dir = -1;
            entered = false;
        });
    }

    String describe() {
        return moving ? ("moving, direction " + dir) : (flagged() ? "stop not confirmed" : "idle");
    }

    // ------------------------------------------------------------------ motor thread
    private boolean runAndWait(Runnable r, long timeoutMs) {
        if (h.isCurrentThread()) {
            r.run();
            return true;
        }
        final CountDownLatch done = new CountDownLatch(1);
        h.post(() -> {
            try { r.run(); } finally { done.countDown(); }
        });
        try {
            return done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void startNow(int d) {
        if (d != 0 && d != 1) return;
        if (!halUsable()) return;
        if (kstInFlight.get()) {
            Log.i(TAG, "move refused: auto keystone in flight");
            Ui.toast(app, R.string.toast_kst_busy);
            return;
        }
        if (AkOverlay.isActive()) {          // vendor AK runs its own TOF AF / zoom motor
            Log.i(TAG, "move refused: auto keystone overlay active");
            return;
        }
        if (moving && dir == d) {            // a second DOWN of the same key: just feed
            h.removeCallbacks(watchdog);
            h.postDelayed(watchdog, WATCHDOG_MS);
            return;
        }
        if (moving || flagged()) {           // other direction or unconfirmed stop: stop first
            sendStop();
            if (moving || flagged()) return; // stop failed: never start on top of it
        }
        if (!setFlag(true)) {                // must be on disk BEFORE the motor starts
            // SharedPreferences already holds true in memory although the disk write failed:
            // drop it again (best effort) and do not start a move that a restarted process
            // could not stop.
            state.edit().putBoolean(KEY_MOVING, false).commit();
            Log.e(TAG, "move refused: " + KEY_MOVING + " could not be saved");
            Ui.toast(app, R.string.toast_failed);
            return;
        }
        moving = true;
        dir = d;
        h.removeCallbacks(watchdog);
        h.postDelayed(watchdog, WATCHDOG_MS);
        try {
            client.manualFocus(d == 1 ? GmpfClient.ManualFocus.STOCK_LEFT : GmpfClient.ManualFocus.STOCK_RIGHT);
            Log.i(TAG, "manualFocus(" + d + ")");
        } catch (Throwable t) {
            Log.w(TAG, "manualFocus(" + d + ") failed, stopping: " + t);
            sendStop();                      // fail-safe: it may have started
        }
    }

    private void stopNow() {
        h.removeCallbacks(watchdog);
        if (moving || flagged()) sendStop();
    }

    private void sendStop() {
        h.removeCallbacks(stopRetry);
        h.removeCallbacks(watchdog);
        try {
            client.manualFocus(GmpfClient.ManualFocus.STOP);
            Log.i(TAG, "manualFocus(2)");
            moving = false;
            dir = -1;
            entered = false;
            stopRetries = 0;
            setFlag(false);
        } catch (Throwable t) {
            if (client.isConnected()) {
                stopRetries++;
                if (stopRetries <= 3 || stopRetries % 25 == 0) Log.w(TAG, "manualFocus(2) failed (#" + stopRetries + "), retrying: " + t);
                h.postDelayed(stopRetry, stopRetries <= 50 ? STOP_RETRY_MS : STOP_RETRY_SLOW_MS);
            } else {
                // HAL gone: the flag stays set and the reconnect path sends the stop.
                Log.w(TAG, "manualFocus(2) failed, HAL disconnected; stop deferred to reconnect: " + t);
                moving = false;
                dir = -1;
                entered = false;
            }
        }
    }
}
