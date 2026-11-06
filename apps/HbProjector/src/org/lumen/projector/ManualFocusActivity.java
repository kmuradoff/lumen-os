package org.lumen.projector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * Translucent manual-focus overlay: small bottom panel "Manual focus  ◀ ▶   OK/Back: done".
 * onResume -> manualFocus(17); DPAD_LEFT DOWN (repeat 0) -> start(1), DPAD_RIGHT -> start(0),
 * repeats -> feed, UP -> stop; any other key DOWN -> stop first, then close (the volume keys pass
 * through); onPause / onStop / focus loss -> stop; closes itself after 3 s without keys.
 * Lumen 1.0.8 (parity focus items 9, 10, 11): 3 s and "any other key closes" as stock FocusAdjust
 * (HIDE_MENU_DELAY_TIME 3000, every key but LEFT / RIGHT / BACK and service keys hides it); an autofocus
 * start (focus key, AF card) closes it through {@link #closeForAutofocus} without manualFocus(2).
 * All motor work goes through MotorController (its own thread).
 */
public final class ManualFocusActivity extends Activity {
    private static final String TAG = "Z9xManualFocus";
    private static final long IDLE_CLOSE_MS = 3_000;

    /** The window on screen (main thread), for {@link #closeForAutofocus}. */
    private static ManualFocusActivity sOpen;

    private MotorController motor;
    private long lastKeyAt;
    /** Closing because an autofocus starts: leave the 17 mode without manualFocus(2). */
    private boolean closingForAf;
    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            long last = Math.max(lastKeyAt, motor == null ? 0 : motor.lastActivity());
            long idle = SystemClock.elapsedRealtime() - last;
            if (motor != null && motor.isMoving()) idle = 0;      // held key: never close mid-move
            if (idle >= IDLE_CLOSE_MS) {
                Log.i(TAG, "closing after " + IDLE_CLOSE_MS + " ms without keys");
                finish();
            } else {
                Ui.main().postDelayed(this, Math.max(250, IDLE_CLOSE_MS - idle));
            }
        }
    };

    /** From KeyReceiver (focus long press) or the settings screen. Main thread. */
    static void open(Context ctx) {
        if (!HalController.featureEnabled()) { Ui.featureOffNotice(ctx); return; }
        if (!HalController.get(ctx).isConnected()) { Ui.toast(ctx, R.string.toast_not_ready); return; }
        try {
            Intent i = new Intent(ctx, ManualFocusActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            Ui.wakeFromDream(ctx);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "open: " + t);
        }
    }

    /**
     * Main thread: an autofocus starts (focus key, vendor AF start event). Closes the window if it is open,
     * as stock (FocusAdjust code 2099, FocusUIV2 AF events hide it). True when a window was closed.
     */
    static boolean closeForAutofocus() {
        ManualFocusActivity a = sOpen;
        if (a == null || a.isFinishing()) return false;
        Log.i(TAG, "closed: an autofocus starts");
        a.closingForAf = true;
        a.finish();
        return true;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setContentView(R.layout.activity_manual_focus);
            motor = HalController.get(this).motor();
        } catch (Throwable t) {
            Log.e(TAG, "onCreate", t);
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (motor == null) return;
        if (!HalController.featureEnabled()) { Ui.featureOffNotice(this); finish(); return; }
        lastKeyAt = SystemClock.elapsedRealtime();
        sOpen = this;
        closingForAf = false;
        motor.enter();                                            // manualFocus(17)
        Ui.main().removeCallbacks(idleCheck);
        Ui.main().postDelayed(idleCheck, IDLE_CLOSE_MS);
    }

    @Override
    protected void onPause() {
        Ui.main().removeCallbacks(idleCheck);
        if (sOpen == this) sOpen = null;
        if (motor != null) {
            if (closingForAf) motor.exitForAutofocus();          // no 2: it would interrupt the AF
            else motor.exit();                                    // stop (and leave the 17 mode)
        }
        super.onPause();
        if (!isFinishing()) finish();                             // an overlay never stays in the background
    }

    @Override
    protected void onStop() {
        if (motor != null) motor.stop();
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && motor != null) motor.stop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (motor == null) return super.dispatchKeyEvent(ev);
        lastKeyAt = SystemClock.elapsedRealtime();
        final int code = ev.getKeyCode();
        final int action = ev.getAction();
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (ev.getRepeatCount() == 0) motor.start(code == KeyEvent.KEYCODE_DPAD_LEFT ? 1 : 0);
                else motor.feed();
            } else if (action == KeyEvent.ACTION_UP) {
                motor.stop();                                     // any UP, also canceled
            }
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return super.dispatchKeyEvent(ev);                // volume keys keep working
            default:
                break;
        }
        if (action == KeyEvent.ACTION_DOWN) {
            motor.stop();                                         // any other key: stop first, then close
            if (!isFinishing()) finish();
        }
        return true;                                              // its UP stays here too
    }
}
