package org.lumen.projector.power;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import org.lumen.projector.Ui;

/**
 * MODULE "power" (v6.5): the opaque black screen of the lamp-only standby ({@link StandbyController}).
 * It keeps the picture black under the lamp (the wake curtain is above it anyway), takes the focus so
 * the app underneath gets onPause / onStop (players pause) and no D-pad key reaches it, and turns
 * the wake keys into {@link StandbyController#exit}:
 *  - OK / ENTER / NUMPAD_ENTER / BUTTON_A / BUTTON_SELECT, POWER / STB_POWER / TV_POWER / WAKEUP
 *    (STB_POWER normally arrives as a global key through KeyReceiver -> PowerKey; handled here too
 *    in case a build routes it to the focused window);
 *  - every other key that reaches this window is swallowed (BACK included: the projector is "off").
 * HOME never reaches a window; StandbyController watches the "homekey" close-system-dialogs broadcast.
 * When another activity covers it without a wake (cast, AirPlay, a dialog, tvinput's HDMI viewer
 * after a plug / HPD / CEC &lt;Active Source&gt;) StandbyController brings it back 1 s after onStop, at
 * once for an HDMI session and from its 15 s watchdog, at most 3 times per minute. HDMI never ends
 * standby (v6.5 review: no self power-on).
 *
 * singleInstance, own task affinity, excludeFromRecents, no animation, FLAG_KEEP_SCREEN_ON.
 * Started only by StandbyController (not exported).
 *
 * HB fork (hb-t5, the device test of hb-t4 on 2026-10-29): after a wake by OK this window stays on top and keeps
 * the focus until the waking press is released and at least {@link #WAKE_HOLD_MS} have passed (cap
 * {@link #WAKE_HOLD_MAX_MS}); every key in that time is swallowed. Before, {@link #finishIfShown} closed it
 * inside the DOWN, so Lumen Home got the focus while the press was still going on, and Home acts on an OK
 * UP (HomeActivity.dispatchKeyEvent, no check that it saw the DOWN): on the "Sleep" tile that started the
 * standby again 357 ms after the wake (live log 19:04:59.765 exit, 19:05:00.122 action:lamp_standby, three
 * times in a row). {@link #msSinceWake} lets home.HbPanelRequestReceiver refuse a power tile right after a
 * wake as a second guard. A finish for a sleep (SCREEN_OFF) is never held.
 */
public final class StandbyActivity extends Activity {
    private static final String TAG = "Z9xStandby";

    private static StandbyActivity sInstance;
    private static boolean sResumed;

    /** HB: the window stays this long after a wake by OK (the lamp needs ~300 ms to come back anyway). */
    static final long WAKE_HOLD_MS = 600;
    /** HB: never longer than this, also when the waking key is held down. */
    static final long WAKE_HOLD_MAX_MS = 1500;
    /** HB: downTime of the OK press that ended the standby while its UP has not come yet, else -1. */
    private static long sWakeDownTime = -1;
    /** HB: uptimeMillis of the last wake by a key of this window, -1 before the first. */
    private static long sWakeAt = -1;
    /** HB: finishIfShown came while the hold was on: finish when it ends. */
    private static boolean sFinishHeld;
    private static final Runnable sHoldEnd = StandbyActivity::endHold;

    /** Main thread. */
    static void show(Context ctx) {
        try {
            Intent i = new Intent(ctx, StandbyActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "standby screen: " + t);
        }
    }

    /** Main thread. HB: held while a wake by OK is still going on (class comment). */
    static void finishIfShown() {
        StandbyActivity a = sInstance;
        if (a == null) return;
        if (holding()) {
            sFinishHeld = true;
            long left = Math.max(sWakeAt + WAKE_HOLD_MS - SystemClock.uptimeMillis(), 0);
            Ui.main().removeCallbacks(sHoldEnd);
            Ui.main().postDelayed(sHoldEnd, sWakeDownTime >= 0 ? WAKE_HOLD_MAX_MS : left);
            return;
        }
        finishNow(a);
    }

    /** HB: milliseconds since the last wake by a key of this window; Long.MAX_VALUE if none. */
    public static long msSinceWake() {
        return sWakeAt < 0 ? Long.MAX_VALUE : SystemClock.uptimeMillis() - sWakeAt;
    }

    /** HB: a wake by OK is still being held (press not released or WAKE_HOLD_MS not over). */
    private static boolean holding() {
        if (sWakeAt < 0) return false;
        long since = SystemClock.uptimeMillis() - sWakeAt;
        if (since >= WAKE_HOLD_MAX_MS) return false;
        return sWakeDownTime >= 0 || since < WAKE_HOLD_MS;
    }

    /** HB: end of a hold (timer or the waking press released). Main thread. */
    private static void endHold() {
        if (holding()) {                                    // released early: wait out WAKE_HOLD_MS
            Ui.main().removeCallbacks(sHoldEnd);
            Ui.main().postDelayed(sHoldEnd, Math.max(sWakeAt + WAKE_HOLD_MS - SystemClock.uptimeMillis(), 1));
            return;
        }
        sWakeDownTime = -1;
        if (!sFinishHeld) return;
        sFinishHeld = false;
        StandbyActivity a = sInstance;
        if (a != null && !StandbyController.isActive()) finishNow(a);
    }

    private static void finishNow(StandbyActivity a) {
        sFinishHeld = false;
        Ui.main().removeCallbacks(sHoldEnd);
        try {
            a.finish();
            a.overridePendingTransition(0, 0);
        } catch (Throwable t) {
            Log.w(TAG, "finish: " + t);
        }
    }

    static boolean isShownOnTop() {
        return sResumed;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sInstance = this;
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().setWindowAnimations(0);
            View v = new View(this);
            v.setBackgroundColor(Color.BLACK);
            v.setFocusable(true);
            v.setFocusableInTouchMode(true);
            setContentView(v);
            v.requestFocus();
        } catch (Throwable t) {
            Log.w(TAG, "onCreate: " + t);
        }
        if (!StandbyController.isActive()) {
            Log.i(TAG, "standby screen started without standby: closing");
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (StandbyController.isActive()) {
            sFinishHeld = false;                            // HB: a new standby keeps the window
            Ui.main().removeCallbacks(sHoldEnd);
        } else if (!holding()) {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sResumed = true;
        if (!StandbyController.isActive() && !holding()) finish();
    }

    @Override
    protected void onPause() {
        sResumed = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            if (!isFinishing() && StandbyController.isActive()) StandbyController.onActivityCovered();
        } catch (Throwable t) {
            Log.w(TAG, "onStop: " + t);
        }
    }

    @Override
    protected void onDestroy() {
        if (sInstance == this) {
            sInstance = null;
            sResumed = false;
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        try {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0 && isWakeKey(ev.getKeyCode())
                    && StandbyController.isActive()) {
                // HB: set before exit(), which calls finishIfShown() on this thread
                sWakeDownTime = ev.getDownTime();
                sWakeAt = SystemClock.uptimeMillis();
                StandbyController.exit("key " + KeyEvent.keyCodeToString(ev.getKeyCode()));
                if (StandbyController.isActive()) {         // exit refused (STR or power-off under way)
                    sWakeDownTime = -1;
                    sWakeAt = -1;
                }
            } else if (ev.getAction() == KeyEvent.ACTION_UP && sWakeDownTime >= 0
                    && ev.getDownTime() == sWakeDownTime) {
                sWakeDownTime = -1;                         // HB: the waking press is over
                if (sFinishHeld) {
                    Ui.main().removeCallbacks(sHoldEnd);
                    endHold();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "key: " + t);
        }
        return true;                                    // nothing reaches the app underneath
    }

    @Override
    public void onBackPressed() {
        // swallowed: the projector is in standby
    }

    private static boolean isWakeKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_STB_POWER:
            case KeyEvent.KEYCODE_TV_POWER:
            case KeyEvent.KEYCODE_WAKEUP:
                return true;
            default:
                return false;
        }
    }
}
