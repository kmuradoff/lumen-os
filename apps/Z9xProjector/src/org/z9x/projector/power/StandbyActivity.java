package org.z9x.projector.power;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

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
 */
public final class StandbyActivity extends Activity {
    private static final String TAG = "Z9xStandby";

    private static StandbyActivity sInstance;
    private static boolean sResumed;

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

    /** Main thread. */
    static void finishIfShown() {
        StandbyActivity a = sInstance;
        if (a == null) return;
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
        if (!StandbyController.isActive()) finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        sResumed = true;
        if (!StandbyController.isActive()) finish();
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
                StandbyController.exit("key " + KeyEvent.keyCodeToString(ev.getKeyCode()));
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
