package org.z9x.projector.power;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;

import org.z9x.projector.Hal;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.hal.GmpfClient;

/**
 * MODULE "power" (v6.2): the one entry point of the power UI (own power key, power menu, sleep
 * timer, wake curtain) for the rest of the app. Every call is wrapped; one failing part never
 * stops the others or PowerPolicy's lamp handling.
 *
 * Wiring:
 *  - {@link #install}: from PowerPolicy.install (App.onCreate), once per process.
 *  - {@link #onScreenOff} / {@link #onScreenOn}: from PowerPolicy.onScreenOff / onScreenOn, i.e.
 *    App's single screen receiver, after OverlayHost.onScreenOff.
 *  - {@link #onLampOn}: from PowerPolicy wherever the lamp is confirmed on (196 true / 195(true)).
 *  - {@link #restoreLampOnHalThread}: from PowerPolicy's lamp-off task (z9x-hal) before 196.
 *  - {@link #onGlobalKey}: from KeyReceiver for every GLOBAL_BUTTON, before the AK gate.
 *  - v6.5 {@link #onStandbyEnter} / {@link #onStandbyExit}: from StandbyController (lamp-only standby;
 *    the display stays on, so there is no SCREEN_OFF / SCREEN_ON for these).
 *  - Lumen OS 1.0: Intent.ACTION_SHUTDOWN (any framework reboot / power-off: Settings Restart, the
 *    updater, adb reboot) fades our curtain with the wordmark over ShutdownThread's dialog (brand spec
 *    section 8), never while the lamp is already off (standby, display off) or our own curtain is up.
 *    The receiver returns at once and never delays the shutdown.
 *  - Lumen OS 1.0: the mic key in standby wakes the lamp and opens Lumen Home search without voice
 *    (PLAN C4: the first syllables would be lost anyway).
 */
public final class PowerUi {
    private static final String TAG = "Z9xPowerUi";
    private static Context sApp;
    private static SafeHandler sWorker;

    private PowerUi() {}

    static void install(Context ctx) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        sWorker = SafeHandler.newThread("z9x-powerui");
        try { TimerLamp.install(sApp, sWorker); } catch (Throwable t) { Log.e(TAG, "TimerLamp.install", t); }
        try { SleepTimer.install(sApp, sWorker); } catch (Throwable t) { Log.e(TAG, "SleepTimer.install", t); }
        try { Hal.addFocusEventListener(PowerUi::onFocusEvent); } catch (Throwable t) { Log.e(TAG, "focus listener", t); }
        try { registerShutdownCurtain(sApp); } catch (Throwable t) { Log.e(TAG, "shutdown curtain", t); }
        Log.i(TAG, "installed");
    }

    /** Main thread, from PowerPolicy.onScreenOff (before its own lamp-off work is queued). */
    static void onScreenOff(Context c) {
        try { PowerKey.onScreenOff(); } catch (Throwable t) { Log.w(TAG, "PowerKey.onScreenOff: " + t); }
        try { PowerKey.PowerActions.onScreenChanged(); } catch (Throwable t) { Log.w(TAG, "actions: " + t); }
        try { SleepTimer.onScreenOff(c); } catch (Throwable t) { Log.w(TAG, "SleepTimer.onScreenOff: " + t); }
        try { WakeCurtain.onScreenOff(c); } catch (Throwable t) { Log.w(TAG, "WakeCurtain.onScreenOff: " + t); }
    }

    /** Main thread, from PowerPolicy.onScreenOn. */
    static void onScreenOn(Context c) {
        try { PowerKey.onScreenOn(); } catch (Throwable t) { Log.w(TAG, "PowerKey.onScreenOn: " + t); }
        try { PowerKey.PowerActions.onScreenChanged(); } catch (Throwable t) { Log.w(TAG, "actions: " + t); }
        try { WakeCurtain.onScreenOn(); } catch (Throwable t) { Log.w(TAG, "WakeCurtain.onScreenOn: " + t); }
        try { SleepTimer.onScreenOn(c); } catch (Throwable t) { Log.w(TAG, "SleepTimer.onScreenOn: " + t); }
    }

    /**
     * v6.5, main thread, StandbyController.enter: like a screen-off for the power UI, except that
     * PowerKey keeps "screen on" (the next press must wake, not be taken for a waking press): power
     * action finished, sleep-timer fade over / timer dropped, wake curtain black.
     */
    static void onStandbyEnter(Context c) {
        try { PowerKey.PowerActions.onScreenChanged(); } catch (Throwable t) { Log.w(TAG, "actions: " + t); }
        try { SleepTimer.onScreenOff(c); } catch (Throwable t) { Log.w(TAG, "SleepTimer.onScreenOff: " + t); }
        try { WakeCurtain.onScreenOff(c); } catch (Throwable t) { Log.w(TAG, "WakeCurtain.onScreenOff: " + t); }
    }

    /** v6.5, main thread, StandbyController.exit: like a screen-on (quick curtain reveal after the lamp). */
    static void onStandbyExit(Context c) {
        try { PowerKey.onScreenOn(); } catch (Throwable t) { Log.w(TAG, "PowerKey.onScreenOn: " + t); }
        try { PowerKey.PowerActions.onScreenChanged(); } catch (Throwable t) { Log.w(TAG, "actions: " + t); }
        try { WakeCurtain.onStandbyWake(); } catch (Throwable t) { Log.w(TAG, "WakeCurtain.onStandbyWake: " + t); }
        try { SleepTimer.onScreenOn(c); } catch (Throwable t) { Log.w(TAG, "SleepTimer.onScreenOn: " + t); }
    }

    /** Any thread (PowerPolicy, z9x-hal): the lamp is confirmed on. */
    static void onLampOn() {
        try { WakeCurtain.onLampOn(); } catch (Throwable t) { Log.w(TAG, "WakeCurtain.onLampOn: " + t); }
        try { TimerLamp.onLampOn(); } catch (Throwable t) { Log.w(TAG, "TimerLamp.onLampOn: " + t); }
        try { org.z9x.projector.dream.DreamLamp.onLampOn(); } catch (Throwable t) { Log.w(TAG, "DreamLamp.onLampOn: " + t); }
        // v6.2b: masked at screen-off -> one gated 148(true) (EyeGuard decides; posts, never blocks)
        try { org.z9x.projector.eye.EyeGuard.onLampOn(); } catch (Throwable t) { Log.w(TAG, "EyeGuard.onLampOn: " + t); }
    }

    /** z9x-hal ONLY: PowerPolicy's lamp-off task, before 196 / 195(false). */
    static void restoreLampOnHalThread(GmpfClient g) {
        try { TimerLamp.restoreOnHalThread(g); } catch (Throwable t) { Log.w(TAG, "TimerLamp restore: " + t); }
    }

    /**
     * KeyReceiver, every GLOBAL_BUTTON event, main thread. Returns true when the event was the BT
     * power key (consumed here). For any other key's first DOWN: a running sleep-timer fade is
     * aborted (keep watching), and a wake curtain still up is revealed at once; the key then goes
     * on to its normal handling.
     */
    public static boolean onGlobalKey(Context ctx, Intent intent, KeyEvent ev) {
        try {
            if (ev.getAction() == KeyEvent.ACTION_DOWN) StandbyController.noteUserActivity();
            // v6.5 standby: the power key wakes (PowerKey); every other global key is swallowed, so
            // no panel, app or assistant opens behind the black standby screen with the lamp off
            if (StandbyController.isActive() && ev.getKeyCode() == KeyEvent.KEYCODE_SEARCH) {
                // PLAN C4: the mic key wakes and opens search without voice
                if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                    StandbyController.exit("mic key");
                    org.z9x.projector.home.HomeSearch.open(ctx, false, "standby wake");
                }
                return true;
            }
            if (StandbyController.isActive() && ev.getKeyCode() != PowerKey.KEYCODE) {
                if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                    Log.i(TAG, "standby: global key " + ev.getKeyCode() + " ignored (power key or OK wakes)");
                }
                return true;
            }
            if (ev.getKeyCode() == PowerKey.KEYCODE) {
                PowerKey.onKey(ctx, intent, ev);
                return true;
            }
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                if (SleepTimer.isFading()) {
                    SleepTimer.abortFade(ctx, "key " + ev.getKeyCode());
                } else if (WakeCurtain.isShowing() && !WakeCurtain.isFadingToBlack()
                        && !PowerKey.PowerActions.isBusy()) {
                    WakeCurtain.fastReveal();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onGlobalKey: " + t);
            return ev.getKeyCode() == PowerKey.KEYCODE;
        }
        return false;
    }

    /**
     * Lumen OS 1.0 (brand spec section 8): our curtain with the wordmark over the framework's shutdown
     * dialog. ACTION_SHUTDOWN is FLAG_RECEIVER_REGISTERED_ONLY, so a runtime receiver of this persistent
     * process gets it; it is ordered, so this returns at once (the curtain is a window add on the main
     * thread). Skipped while the lamp is off (standby, display off) or our own curtain already runs (the
     * power menu's Power off / Restart).
     */
    private static void registerShutdownCurtain(Context app) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    if (i == null || !Intent.ACTION_SHUTDOWN.equals(i.getAction())) return;
                    if (StandbyController.isActive() || WakeCurtain.isShowing()) return;
                    PowerManager pm = app.getSystemService(PowerManager.class);
                    if (pm != null && !pm.isInteractive()) return;
                    Log.i(TAG, "shutdown: curtain");
                    WakeCurtain.fadeToBlack(app, 150, true, false, null);
                } catch (Throwable t) {
                    Log.w(TAG, "shutdown curtain: " + t);
                }
            }
        };
        app.registerReceiver(r, new IntentFilter(Intent.ACTION_SHUTDOWN), Context.RECEIVER_EXPORTED);
    }

    /**
     * Main thread (Hal focus events, after AkOverlay): the vendor starts AK / AF, which needs light
     * and an unobstructed picture: abort a timer fade, lift a wake curtain. During the v6.5 standby
     * nothing is lifted: the event is counted and the lamp re-checked at once and 0.5-8 s later
     * (StandbyController.onVendorMotionEvent). Review 6.5: the eye-protection events 500 / 501 / 601 /
     * 608 during standby too (StandbyController.onVendorLampEvent): the vendor's restore path
     * setHumanDetectScreenOnOff(1) may relight the lamp (EyeGuard gets them as before).
     */
    private static void onFocusEvent(int type, String value) {
        boolean eye = type == 500 || type == 501 || type == 601 || type == 608;
        if (eye && sApp != null && StandbyController.isActive()) {
            try { StandbyController.onVendorLampEvent(type); } catch (Throwable t) { Log.w(TAG, "standby eye: " + t); }
            return;
        }
        boolean akAf = (type >= 105 && type <= 118) || type == 120 || (type >= 333 && type <= 335)
                || type == 1003 || type == 1004;
        if (!akAf || sApp == null) return;
        if (StandbyController.isActive()) {
            try { StandbyController.onVendorMotionEvent(type); } catch (Throwable t) { Log.w(TAG, "standby AK: " + t); }
            return;
        }
        try {
            if (SleepTimer.isFading()) {
                SleepTimer.abortFade(sApp, "focus event " + type);
                return;
            }
            PowerManager pm = sApp.getSystemService(PowerManager.class);
            if (WakeCurtain.isShowing() && !WakeCurtain.isFadingToBlack() && !PowerKey.PowerActions.isBusy()
                    && pm != null && pm.isInteractive()) {
                Log.i(TAG, "focus event " + type + ": lifting the wake curtain");
                WakeCurtain.fastReveal();
            }
        } catch (Throwable t) {
            Log.w(TAG, "focus event: " + t);
        }
    }
}
