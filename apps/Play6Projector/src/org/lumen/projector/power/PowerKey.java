package org.lumen.projector.power;

import android.app.DreamManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;
import android.view.KeyEvent;

import org.lumen.projector.Hal;
import org.lumen.projector.R;
import org.lumen.projector.SafeHandler;
import org.lumen.projector.Ui;
import org.lumen.projector.ui.Notify;
import org.lumen.projector.ui.OverlayHost;

/**
 * The remote power key of Lumen on the XGIMI Play 6, and the power UI wiring (docs/play6/SETTINGS_PLAN.md
 * 1.8). A fork of Z9xProjector power/PowerKey.java (tools/play6/forks.tsv) with the Z9X PowerUi entry
 * points folded in (the Play 6 has no PowerUi, PowerPolicy or TimerLamp). What differs from the Z9X:
 *  - the sleep (t13, docs/play6/research/sleep_stock.md 9): after the fade to black,
 *    {@link PowerActions#sleep} gives the lamp-only standby (StandbyController: IGmpf 195 lamp off,
 *    Android awake, any remote key back in under 1 s, a real power off after the standby deadline), the
 *    stock "Screen off". The t12 short press (Android sleep = the vendor's STR, lamp still on) came back
 *    only as a cold boot; with the lamp switched off first (195), STR suspends and resumes cleanly (owner's
 *    test B19b on t16, 2026-10-13: suspend_stats success 1, fail 0, no reboot). So from t17 the short press
 *    is STR (lamp off with 195, then PowerManager.goToSleep with no doze, as stock) when persist.lumen.p6.str
 *    is 1 (default 0 since beta 2: STR came back as a cold boot again on 2026-10-20), no USB host is on the cable
 *    or an update is being installed. No IGmpf 241, no PM sysfs, no rc file, no vendor property;
 *  - SCREEN_ON: the light does not simply follow the display: Lamp.awaitOn polls IGmpf 196 (as stock
 *    xgimiWakeUp), sends 195(true) only if the vendor did not relight it, and the curtain reveals when 196
 *    reads on (its LAMP_WAIT_MS / HARD_CAP_MS nets stay);
 *  - every global key while the standby is on ends it first (StandbyController.onGlobalKey);
 *  - Power off / Restart: the same ShutdownThread calls as the Z9X (PowerManager.shutdown / reboot,
 *    reason userrequested), without the Z9X boot-dark markers.
 *
 * Input: KEYCODE_STB_POWER (179), routed by the framework RRO global_keys.xml (shared, item keys) to
 * org.lumen.projector/.KeyReceiver, which hands every global key to {@link #onGlobalKey} first.
 * Framework behaviour (Lineage 21 PhoneWindowManager, as on the Z9X): screen off, the DOWN only wakes and
 * never reaches us; screen on, DOWN (repeat 0), repeated DOWNs (first after 400 ms, then every 50 ms,
 * same downTime) and the UP arrive as separate GLOBAL_BUTTON broadcasts; an UP may carry FLAG_CANCELED.
 *
 * State machine (main thread):
 *   DOWN r0 -> guard? -> IGNORED (until its UP) | ARMED(downTime)
 *   ARMED + repeat with eventTime - downTime >= LONG_PRESS_MS -> LONG: power menu
 *   ARMED + UP (not canceled) -> short press: fade to black (400 ms), then PowerActions.sleep
 *   any event whose downTime is not the armed one -> ignored
 * Guards on DOWN r0: began non-interactive; !isInteractive; SCREEN_OFF seen and SCREEN_ON not yet; a
 * "wake echo" (DOWN within WAKE_GUARD_MS after SCREEN_ON); a power action already running. The wake
 * curtain still up and not fading to black: the press only lifts it.
 */
public final class PowerKey {
    private static final String TAG = "P6Power";
    public static final int KEYCODE = KeyEvent.KEYCODE_STB_POWER;   // 179
    /** Hold time for the power menu (~7 framework repeats). Stock XGIMI used ~1.35 s; tune on device. */
    static final long LONG_PRESS_MS = 700;
    /** Presses that start this soon after SCREEN_ON belong to the wake. */
    static final long WAKE_GUARD_MS = 1500;
    /** GlobalKeyIntent extra (literal key, Lineage GlobalKeyIntent.java:29-30). */
    private static final String EXTRA_BEGAN_FROM_NON_INTERACTIVE = "EXTRA_BEGAN_FROM_NON_INTERACTIVE";

    private static long sDownTime = -1;
    private static boolean sIgnore;
    private static boolean sLongFired;
    private static long sScreenOnUptime = -1;
    private static boolean sScreenOnSeen = true;
    private static Context sApp;

    private PowerKey() {}

    // ================================================================== wiring (the Z9X PowerUi part)
    /**
     * Play6App.onProcessStart (posted, main thread), once per process: the screen hooks (curtain, timer,
     * this key, panels), the shutdown curtain and the AK / AF focus-event hook. Receivers only: no HAL,
     * no prefs (the sleep timer's volume restore runs at the start gate, SleepTimer.install).
     */
    public static void install(Context ctx) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        try { registerScreenReceiver(sApp); } catch (Throwable t) { Log.e(TAG, "screen receiver", t); }
        try { registerShutdownCurtain(sApp); } catch (Throwable t) { Log.e(TAG, "shutdown curtain", t); }
        try { Hal.addFocusEventListener(PowerKey::onFocusEvent); } catch (Throwable t) { Log.e(TAG, "focus listener", t); }
        try { StandbyController.install(sApp); } catch (Throwable t) { Log.e(TAG, "standby", t); }
        Log.i(TAG, "installed");
    }

    private static void registerScreenReceiver(Context app) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i == null ? null : i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) onScreenOff(c.getApplicationContext());
                else if (Intent.ACTION_SCREEN_ON.equals(a)) onScreenOn(c.getApplicationContext());
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
    }

    /** SCREEN_OFF, main thread: close panels at once, end the power action and the timer, curtain black. */
    private static void onScreenOff(Context c) {
        try { StandbyController.onScreenOff(c); } catch (Throwable t) { Log.w(TAG, "standby: " + t); }
        try { Hal.stopMotor(); } catch (Throwable t) { Log.w(TAG, "motor: " + t); }
        try { OverlayHost.get(c).onScreenOff(); } catch (Throwable t) { Log.w(TAG, "screen off overlays: " + t); }
        try { onScreenOffKey(); } catch (Throwable t) { Log.w(TAG, "key state: " + t); }
        try { PowerActions.onScreenChanged(); } catch (Throwable t) { Log.w(TAG, "actions: " + t); }
        try { SleepTimer.onScreenOff(c); } catch (Throwable t) { Log.w(TAG, "SleepTimer.onScreenOff: " + t); }
        try { WakeCurtain.onScreenOff(c); } catch (Throwable t) { Log.w(TAG, "WakeCurtain.onScreenOff: " + t); }
    }

    /** SCREEN_ON, main thread. */
    private static void onScreenOn(Context c) {
        try { onScreenOnKey(); } catch (Throwable t) { Log.w(TAG, "key state: " + t); }
        try { PowerActions.onScreenChanged(); } catch (Throwable t) { Log.w(TAG, "actions: " + t); }
        try {
            WakeCurtain.onScreenOn();
            // after an STR resume the vendor relights the lamp itself; reveal once 196 says so (unless it
            // stays definitely off: then the curtain's own LAMP_WAIT_MS net reveals)
            Lamp.awaitOn("screen on", on -> {
                if (!Boolean.FALSE.equals(on)) WakeCurtain.onLampOn();
                try { org.lumen.projector.eye.EyeGuard.onLampOn(); } catch (Throwable t) { Log.w(TAG, "eye: " + t); }
            });
        } catch (Throwable t) {
            Log.w(TAG, "WakeCurtain.onScreenOn: " + t);
        }
        try { SleepTimer.onScreenOn(c); } catch (Throwable t) { Log.w(TAG, "SleepTimer.onScreenOn: " + t); }
        try { org.lumen.projector.source.BootSource.onPowerOn(c, "screen on"); } catch (Throwable t) { Log.w(TAG, "BootSource: " + t); }
    }

    /**
     * Our curtain with the wordmark over the framework's shutdown dialog (Intent.ACTION_SHUTDOWN, any
     * framework reboot or power-off). Ordered broadcast: returns at once, never delays the shutdown.
     * Skipped while the display is off or our own curtain already runs (power menu Power off / Restart).
     */
    private static void registerShutdownCurtain(Context app) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    if (i == null || !Intent.ACTION_SHUTDOWN.equals(i.getAction())) return;
                    StandbyController.onShutdown(c);
                    if (WakeCurtain.isShowing()) return;
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
     * Main thread (Hal focus events): the vendor starts AK / AF, which needs light and an unobstructed
     * picture: abort a timer fade, lift a wake curtain.
     */
    private static void onFocusEvent(int type, String value) {
        boolean akAf = (type >= 105 && type <= 118) || type == 120 || (type >= 333 && type <= 335)
                || type == 1003 || type == 1004;
        if (!akAf || sApp == null) return;
        try {
            if (SleepTimer.isFading()) {
                SleepTimer.abortFade(sApp, "focus event " + type);
                return;
            }
            PowerManager pm = sApp.getSystemService(PowerManager.class);
            if (WakeCurtain.isShowing() && !WakeCurtain.isFadingToBlack() && !PowerActions.isBusy()
                    && pm != null && pm.isInteractive()) {
                Log.i(TAG, "focus event " + type + ": lifting the wake curtain");
                WakeCurtain.fastReveal();
            }
        } catch (Throwable t) {
            Log.w(TAG, "focus event: " + t);
        }
    }

    /**
     * KeyReceiver, every GLOBAL_BUTTON event, main thread, before the AK gate. Returns true when the event
     * was the power key or ended the lamp-only standby (consumed here, with the rest of that press). For
     * any other key's first DOWN: a running sleep-timer fade is
     * aborted (keep watching), and a wake curtain still up is revealed at once; the key then goes on to
     * its normal handling.
     */
    public static boolean onGlobalKey(Context ctx, Intent intent, KeyEvent ev) {
        try {
            if (StandbyController.onGlobalKey(ctx, ev)) return true;    // the standby ends, the press is eaten
            if (ev.getKeyCode() == KEYCODE) {
                onKey(ctx, intent, ev);
                return true;
            }
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                if (SleepTimer.isFading()) {
                    SleepTimer.abortFade(ctx, "key " + ev.getKeyCode());
                } else if (WakeCurtain.isShowing() && !WakeCurtain.isFadingToBlack() && !PowerActions.isBusy()) {
                    WakeCurtain.fastReveal();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onGlobalKey: " + t);
            return ev.getKeyCode() == KEYCODE;
        }
        return false;
    }

    // ================================================================== the key (as the Z9X)
    private static void onScreenOnKey() {
        sScreenOnUptime = SystemClock.uptimeMillis();
        sScreenOnSeen = true;
        reset();
    }

    private static void onScreenOffKey() {
        sScreenOnSeen = false;
        reset();
    }

    private static void reset() {
        sDownTime = -1;
        sIgnore = false;
        sLongFired = false;
    }

    /** Main thread, for code == KEYCODE. */
    static void onKey(Context ctx, Intent intent, KeyEvent ev) {
        final int action = ev.getAction();
        if (action == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
            reset();
            sDownTime = ev.getDownTime();
            String why = guard(ctx, intent, ev);
            if (why != null) {
                sIgnore = true;
                Log.i(TAG, "press ignored: " + why);
            } else if (WakeCurtain.isShowing() && !WakeCurtain.isFadingToBlack()
                    && !SleepTimer.isFading()) {
                WakeCurtain.fastReveal();
                sIgnore = true;
                Log.i(TAG, "curtain up: lifted, press again");
            }
            return;
        }
        if (ev.getDownTime() != sDownTime) return;                    // a press we never armed
        if (action == KeyEvent.ACTION_DOWN) {                          // framework repeat
            if (sIgnore || sLongFired) return;
            if (ev.getEventTime() - ev.getDownTime() >= LONG_PRESS_MS) {
                sLongFired = true;
                onLongPress(ctx);
            }
            return;
        }
        if (action == KeyEvent.ACTION_UP) {
            boolean shortPress = !sIgnore && !sLongFired && !ev.isCanceled();
            reset();
            if (shortPress) onShortPress(ctx);
        }
    }

    private static String guard(Context ctx, Intent intent, KeyEvent ev) {
        if (intent.getBooleanExtra(EXTRA_BEGAN_FROM_NON_INTERACTIVE, false)) return "began non-interactive";
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) return "screen off";
        if (!sScreenOnSeen) return "SCREEN_ON not yet seen (waking)";
        if (sScreenOnUptime >= 0 && ev.getDownTime() < sScreenOnUptime + WAKE_GUARD_MS) {
            return "wake echo (" + (ev.getDownTime() - sScreenOnUptime) + " ms after SCREEN_ON)";
        }
        if (PowerActions.isBusy()) return "power action running";
        return null;
    }

    private static void onShortPress(Context ctx) {
        if (SleepTimer.isFading()) {
            // the timer is already taking the projector down: finish now
            SleepTimer.finishNow(ctx, "power key during the timer fade");
            return;
        }
        PowerActions.sleepWithFade(ctx, "power key");
    }

    private static void onLongPress(Context ctx) {
        if (org.lumen.projector.ak.AkOverlay.isActive()) {
            Log.i(TAG, "long press ignored: AK overlay active");
            return;
        }
        if (SleepTimer.isFading()) SleepTimer.abortFade(ctx, "power key held");
        // the curtain (TYPE_SYSTEM_OVERLAY) sits above our panels: never open the menu underneath it;
        // lift it and let this press end here (the rest of this hold is ignored, sLongFired is set)
        if (WakeCurtain.isShowing()) {
            if (!WakeCurtain.isFadingToBlack()) WakeCurtain.fastReveal();
            Log.i(TAG, "long press: curtain up, lifted; the power menu needs a new press");
            return;
        }
        try {
            DreamManager dm = ctx.getSystemService(DreamManager.class);
            if (dm != null && dm.isDreaming()) dm.stopDream();      // the menu must not sit under a dream
        } catch (Throwable t) {
            Log.w(TAG, "stopDream: " + t);
        }
        Log.i(TAG, "long press: power menu");
        PowerMenu.show(ctx);
    }

    /**
     * Sleep / power off / restart, each behind the curtain. Main thread, except the blocking binder
     * calls (reboot waits for ShutdownThread), which run on "p6-power" (created on first use).
     */
    public static final class PowerActions {
        private static boolean sBusy;
        private static SafeHandler sWorker;
        /** If still interactive this long after goToSleep, give the picture back. */
        private static final long SLEEP_CHECK_MS = 2000;
        static final long FADE_SHORT_MS = 400;
        static final long FADE_HALT_MS = 450;
        /** Fallback for a halt whose curtain end action never ran (shutdown/reboot not started). */
        private static final long HALT_BUSY_TIMEOUT_MS = 60_000;
        private static Context sBusyApp;
        /** The blocking shutdown / reboot call is in flight on p6-power: stay busy. */
        private static volatile boolean sHaltInFlight;
        /**
         * sBusy has no other way back while the screen stays on: a fade whose end action was dropped
         * (another fadeToBlack replaced it) would otherwise block every power press.
         */
        private static final Runnable sBusyTimeout = () -> {
            try {
                if (!sBusy || sBusyApp == null) return;
                if (sHaltInFlight) {                                    // shutdown under way
                    Ui.main().postDelayed(PowerActions.sBusyTimeout, HALT_BUSY_TIMEOUT_MS);
                    return;
                }
                PowerManager pm = sBusyApp.getSystemService(PowerManager.class);
                if (pm != null && !pm.isInteractive()) return;          // SCREEN_OFF resets it
                Log.w(TAG, "power action did not complete: busy flag reset");
                sBusy = false;
                if (!SleepTimer.isFading()) WakeCurtain.fastReveal();
            } catch (Throwable t) {
                Log.w(TAG, "busy timeout: " + t);
            }
        };

        private PowerActions() {}

        static boolean isBusy() { return sBusy; }

        /** Screen on/off: a finished or aborted action ends here. */
        static void onScreenChanged() {
            sBusy = false;
            Ui.main().removeCallbacks(sBusyTimeout);
        }

        /** Main thread: sets sBusy and arms its fallback. */
        private static void setBusy(Context app, long timeoutMs) {
            sBusy = true;
            sBusyApp = app;
            Ui.main().removeCallbacks(sBusyTimeout);
            Ui.main().postDelayed(sBusyTimeout, timeoutMs);
        }

        private static synchronized SafeHandler worker() {
            if (sWorker == null) sWorker = SafeHandler.newThread("p6-power");
            return sWorker;
        }

        /**
         * Short press (also the Home tile and the power menu's Sleep): fade to black, then
         * {@link #sleep}.
         */
        public static void sleepWithFade(Context ctx, String why) {
            if (sBusy) return;
            final Context app = ctx.getApplicationContext();
            setBusy(app, FADE_SHORT_MS + SLEEP_CHECK_MS + STR_LAMP_WAIT_MS + 1000);
            OverlayHost.get(app).dismissAll(false);
            Log.i(TAG, "sleep (" + why + "), fade " + FADE_SHORT_MS + " ms");
            boolean shown = WakeCurtain.fadeToBlack(app, FADE_SHORT_MS, false, false, () -> sleep(app, why));
            if (!shown) sleep(app, why);                                // never block sleep on the UI
        }

        /** The curtain is already black (sleep-timer end): {@link #sleep} at once. */
        static void sleepNow(Context ctx, String why) {
            if (sBusy) return;
            setBusy(ctx.getApplicationContext(), SLEEP_CHECK_MS + STR_LAMP_WAIT_MS + 1000);
            Log.i(TAG, "sleep (" + why + ")");
            sleep(ctx.getApplicationContext(), why);
        }

        /** STR switch (research sleep_stock.md 9.2): 1 = STR when nothing blocks it; default 0. */
        static final String PROP_STR = "persist.lumen.p6.str";
        /** STR: the lamp-off may take this long before the Android sleep goes ahead anyway. */
        static final long STR_LAMP_WAIT_MS = 1_500;
        private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";

        /**
         * The sleep, with the curtain black (main thread): the lamp-only standby by default; STR (lamp off,
         * then the Android sleep) only when {@link #strBlocker} finds nothing. Before the start gate nothing
         * may reach the HAL: the picture comes back with "not ready".
         */
        static void sleep(Context app, String why) {
            if (!Hal.isGateOpen()) {
                Log.i(TAG, "sleep (" + why + ") refused: start gate closed");
                sBusy = false;
                Ui.main().removeCallbacks(sBusyTimeout);
                WakeCurtain.fastReveal();
                Ui.toast(app, R.string.toast_not_ready);
                return;
            }
            String blocker = strBlocker(app);
            if (blocker != null) {
                Log.i(TAG, "lamp-only standby (" + why + "; STR: " + blocker + ")");
                sBusy = false;                                          // no SCREEN_OFF will come
                Ui.main().removeCallbacks(sBusyTimeout);
                StandbyController.enter(app, why);
                return;
            }
            Log.i(TAG, "STR (" + why + "): lamp off, then the Android sleep");
            final boolean[] sent = {false};
            final Runnable go = () -> {
                if (sent[0]) return;
                sent[0] = true;
                goToSleep(app);
            };
            Lamp.off("before STR", on -> go.run());
            Ui.main().postDelayed(go, STR_LAMP_WAIT_MS);                // never block the sleep on the HAL
        }

        /**
         * Why STR may not run now, or null when it may: the switch {@value #PROP_STR} is not 1 (default 0),
         * a USB host is on the cable (sticky USB_STATE "connected"; on the Z9X a host on the cable reset the
         * chip after the suspend), or an update is being installed (update_engine running; the Play 6 has
         * none today). Neutral reads only: a property, a sticky broadcast. Any thread.
         */
        static String strBlocker(Context app) {
            // beta 2 (2026-10-21): off by default. On the owner's unit STR came back as a cold boot again
            // (sys.boot.reason "reboot" with the clock unset) after a sleep from the quick panel; the lamp-only
            // standby keeps the session and wakes in under 1 s. setprop persist.lumen.p6.str 1 turns STR on.
            if (SystemProperties.getInt(PROP_STR, 0) != 1) return PROP_STR + " is not 1";
            if (app == null) return "not started";
            try {
                Intent i = app.registerReceiver(null, new IntentFilter(ACTION_USB_STATE), Context.RECEIVER_EXPORTED);
                if (i != null && i.getBooleanExtra("connected", false)) return "USB host connected";
            } catch (Throwable t) {
                return "USB state unknown (" + t.getClass().getSimpleName() + ")";
            }
            if ("running".equals(SystemProperties.get("init.svc.update_engine", ""))) return "update installing";
            return null;
        }

        private static void goToSleep(Context app) {
            final PowerManager pm = app.getSystemService(PowerManager.class);
            try {
                // As stock reallyGoToSleep: no doze, the power-button reason (PhoneWindowManager's own
                // SHORT_PRESS_POWER_GO_TO_SLEEP reason). @hide, DEVICE_POWER (held).
                pm.goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON,
                        PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);
            } catch (Throwable t) {
                Log.e(TAG, "goToSleep failed", t);
            }
            Ui.main().postDelayed(() -> {
                try {
                    if (pm != null && pm.isInteractive()) {
                        Log.w(TAG, "still interactive " + SLEEP_CHECK_MS + " ms after goToSleep: lamp on, revealing");
                        sBusy = false;
                        Lamp.on("sleep refused", null);                 // the STR path turned it off first
                        WakeCurtain.fastReveal();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "sleep check: " + t);
                }
            }, SLEEP_CHECK_MS);
        }

        /**
         * Power menu "Power off": the same ShutdownThread path and reason as the system menu's
         * PowerAction, so sys.powerctl becomes "shutdown,userrequested". Whether the Play 6 stays off
         * afterwards is observed in test B22 only (the vendor power area is not analysed).
         */
        public static void powerOff(Context ctx) {
            halt(ctx, false);
        }

        /** Power menu "Restart": the same ShutdownThread path as the system menu's RestartAction. */
        public static void restart(Context ctx) {
            halt(ctx, true);
        }

        private static void halt(Context ctx, final boolean reboot) {
            if (sBusy) return;
            final Context app = ctx.getApplicationContext();
            setBusy(app, HALT_BUSY_TIMEOUT_MS);
            OverlayHost.get(app).dismissAll(false);
            SleepTimer.cancel(app, false);
            Runnable go = () -> worker().post(() -> {
                PowerManager pm = app.getSystemService(PowerManager.class);
                sHaltInFlight = true;
                StandbyController.noteShutdown();
                try {
                    Log.i(TAG, reboot ? "reboot (user requested)" : "shutdown (user requested)");
                    // REBOOT permission (signature|privileged, granted by the platform signature).
                    // Reason SHUTDOWN_USER_REQUESTED, like the system menu.
                    if (reboot) {
                        pm.reboot(PowerManager.SHUTDOWN_USER_REQUESTED);           // blocks
                    } else {
                        pm.shutdown(false, PowerManager.SHUTDOWN_USER_REQUESTED, false);
                    }
                } catch (Throwable t) {
                    sHaltInFlight = false;
                    StandbyController.clearShutdown();
                    Log.e(TAG, (reboot ? "reboot" : "shutdown") + " failed", t);
                    Ui.main().post(() -> {
                        sBusy = false;
                        WakeCurtain.fastReveal();
                        Notify.show(app, app.getString(R.string.power_action_failed));
                    });
                }
            });
            if (!WakeCurtain.fadeToBlack(app, FADE_HALT_MS, true, false, go)) go.run();
        }
    }
}
