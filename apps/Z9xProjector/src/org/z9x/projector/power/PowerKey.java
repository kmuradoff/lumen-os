package org.z9x.projector.power;

import android.app.DreamManager;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import org.z9x.projector.R;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.Ui;
import org.z9x.projector.ui.Notify;
import org.z9x.projector.ui.OverlayHost;

/**
 * MODULE "power" (v6.2): own handling of the BT remote power key (V62_REQUIREMENTS 2,
 * research/v62/powerkey, PowerKey reference).
 *
 * Input: KEYCODE_STB_POWER (179), routed by the framework RRO global_keys.xml to
 * org.z9x.projector/.KeyReceiver WITHOUT dispatchWhenNonInteractive. v6.2: the BT remote .kl files
 * ("key 113 STB_POWER WAKE"; remotes c081 / af02: 116). v6.5: also the IR remote
 * (Vendor_3697_Product_0001 116/190 and its dedicated sleep key 75, which was KEYCODE_SLEEP =
 * PhoneWindowManager goToSleep), the projector keypad and LAN remote (Vendor_3697_Product_0002
 * 30/116/260) and the TV-style remote (Vendor_0001_Product_0001 116/183), so every user power / sleep
 * key gives the lamp-only standby and never the display power cycle of an Android sleep
 * (z9x_v65.sh preflight: no SLEEP / SOFT_SLEEP / POWER / TV_POWER label in a user .kl). Review 6.5:
 * any other keyboard / remote / air mouse without its own layout uses Generic.kl, where KEY_SLEEP
 * (142) was SLEEP; the image ships /product/usr/keylayout/Generic.kl with 142 = STB_POWER WAKE
 * (Generic.kl's 116 / 152 POWER stay: harmless while awake, config_shortPressOnPowerBehavior 0). Only the
 * factory remotes (9999_0666, 9999_0777) keep KEYCODE_POWER. A system KEYCODE_POWER (factory remotes,
 * a phone remote app's POWER, KEYCODE_TV_POWER which PhoneWindowManager turns into POWER on leanback)
 * does NOTHING on a short press while Android is awake (Z9xFrameworkKeysOverlay v6.5:
 * config_shortPressOnPowerBehavior 0), also during our standby, so it can never hand standby over to
 * a display-off sleep; its long press still opens the system power menu and from a real sleep it
 * still wakes. Volume keys during standby: STREAM_MUSIC is re-muted (StandbyController volume watch).
 *
 * ACCEPTED TRADE-OFF (v6.5): there is no system POWER fallback on the user's remotes or the keypad
 * any more. If this persistent app is down, the system restarts it (persistent process; a stuck main
 * thread ends in an ANR kill and restart); while it stays down (a crash loop), no user key can sleep
 * or power off the projector; the way out is adb, a factory remote (long press: system power menu), or the mains plug. Keeping the
 * keypad as system POWER was rejected: its sleep / wake power-cycles the display chain, which is
 * exactly what leaves block noise in one quadrant after the wake.
 *
 * What the framework guarantees (Lineage 21 PhoneWindowManager, research VERIFIED):
 *  - screen off: the DOWN only wakes (result 0, nothing broadcast); mPendingWakeKey swallows the
 *    UP; synthesized repeats carry no PASS_TO_USER. The waking press never reaches this class.
 *  - screen on (also while a screensaver runs): DOWN (repeat 0), repeated DOWNs (first after
 *    400 ms, then every 50 ms, same downTime) and the UP arrive as separate GLOBAL_BUTTON
 *    broadcasts, in order. An UP may carry FLAG_CANCELED.
 *
 * State machine (main thread):
 *   DOWN r0 -> guard? -> IGNORED (until its UP) | ARMED(downTime)
 *   ARMED + repeat with eventTime - downTime >= LONG_PRESS_MS -> LONG: power menu
 *   ARMED + UP (not canceled) -> short press: fade to black (400 ms), then StandbyController.enter:
 *     Lumen OS 1.0 = lamp off at once, then Android sleep and real STR (the v6.5 lamp-only standby only
 *     while STR is blocked, e.g. a USB host on the cable); Android goToSleep directly if enter fails
 *   DOWN r0 while in standby -> StandbyController.exit (wake); the rest of that press is ignored
 *   any event whose downTime is not the armed one -> ignored
 * Guards on DOWN r0: began non-interactive; !isInteractive; SCREEN_OFF seen and SCREEN_ON not yet;
 * a "wake echo" (DOWN within WAKE_GUARD_MS after SCREEN_ON); a power action already running.
 * v6.2b: the wake curtain still up (black after a wake, up to LAMP_WAIT_MS / HARD_CAP_MS on the
 * STR path) and not fading to black: the press only lifts it; neither its short nor its long
 * press acts (a person pressing power "to wake" a black picture must not put it back to sleep).
 */
public final class PowerKey {
    private static final String TAG = "Z9xPowerKey";
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

    private PowerKey() {}

    static void onScreenOn() {
        sScreenOnUptime = SystemClock.uptimeMillis();
        sScreenOnSeen = true;
        reset();
    }

    static void onScreenOff() {
        sScreenOnSeen = false;
        reset();
    }

    private static void reset() {
        sDownTime = -1;
        sIgnore = false;
        sLongFired = false;
    }

    /** From PowerUi for code == KEYCODE, before the AK gate and before Ui.wakeFromDream. Main thread. */
    static void onKey(Context ctx, Intent intent, KeyEvent ev) {
        final int action = ev.getAction();
        if (action == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
            reset();
            sDownTime = ev.getDownTime();
            if (StandbyController.isActive()) {
                // v6.5: the display is on in standby, so the press arrives here: wake on the DOWN
                // (also while a standby of this boot is only pending its re-enter after a process
                // restart: the press that started the process must wake, not be a short press)
                sIgnore = true;
                StandbyController.exit("power key");
                return;
            }
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
        if (org.z9x.projector.ak.AkOverlay.isActive()) {
            Log.i(TAG, "long press ignored: AK overlay active");
            return;
        }
        if (SleepTimer.isFading()) SleepTimer.abortFade(ctx, "power key held");
        // v6.2b: the curtain (TYPE_SYSTEM_OVERLAY) sits above our panels. Never open the menu
        // underneath it, not even while it fades out: lift it and let this press end here; the
        // menu needs a fresh press once the picture is visible (the rest of this hold is ignored,
        // sLongFired is set).
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
     * calls (reboot waits for ShutdownThread), which run on "z9x-powerkey".
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
        /** The blocking shutdown / reboot call is in flight on z9x-powerkey: stay busy. */
        private static volatile boolean sHaltInFlight;
        /**
         * sBusy has no other way back while the screen stays on: a fade whose end action was
         * dropped (another fadeToBlack replaced it) would otherwise block every BT power press.
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

        /** Screen on/off (PowerUi): a finished or aborted action ends here. */
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
            if (sWorker == null) sWorker = SafeHandler.newThread("z9x-powerkey");
            return sWorker;
        }

        /** Short press: fade to black, then StandbyController.enter (lamp off, then STR). */
        public static void sleepWithFade(Context ctx, String why) {
            if (sBusy) return;
            final Context app = ctx.getApplicationContext();
            setBusy(app, FADE_SHORT_MS + SLEEP_CHECK_MS + 1000);
            OverlayHost.get(app).dismissAll(false);
            Log.i(TAG, "sleep (" + why + "), fade " + FADE_SHORT_MS + " ms");
            boolean shown = WakeCurtain.fadeToBlack(app, FADE_SHORT_MS, false, false, () -> standbyOrSleep(app, why));
            if (!shown) standbyOrSleep(app, why);                       // never block sleep on the UI
        }

        /** The curtain is already black (sleep-timer end): standby at once. */
        static void sleepNow(Context ctx, String why) {
            if (sBusy) return;
            setBusy(ctx.getApplicationContext(), SLEEP_CHECK_MS + 1000);
            Log.i(TAG, "sleep (" + why + ")");
            standbyOrSleep(ctx.getApplicationContext(), why);
        }

        /**
         * v6.5: lamp-only standby (the display stays on, so the display chain is never power-cycled);
         * the Android sleep only when standby cannot start. Main thread.
         */
        private static void standbyOrSleep(Context app, String why) {
            boolean ok = false;
            try {
                ok = StandbyController.enter(app, why);
            } catch (Throwable t) {
                Log.e(TAG, "standby", t);
            }
            if (!ok) goToSleep(app);
        }

        private static void goToSleep(Context app) {
            final PowerManager pm = app.getSystemService(PowerManager.class);
            try {
                // Same call PhoneWindowManager makes for SHORT_PRESS_POWER_GO_TO_SLEEP (live value 1):
                // goToSleep(eventTime, GO_TO_SLEEP_REASON_POWER_BUTTON, 0) (PhoneWindowManager.java
                // :1322-1323, :1461-1467). @hide, DEVICE_POWER (granted live). PowerPolicy then
                // turns the lamp off on SCREEN_OFF exactly as for the system POWER key.
                pm.goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
            } catch (Throwable t) {
                Log.e(TAG, "goToSleep failed", t);
            }
            Ui.main().postDelayed(() -> {
                try {
                    if (pm != null && pm.isInteractive()) {
                        Log.w(TAG, "still interactive " + SLEEP_CHECK_MS + " ms after goToSleep: revealing");
                        sBusy = false;
                        WakeCurtain.fastReveal();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "sleep check: " + t);
                }
            }, SLEEP_CHECK_MS);
        }

        /**
         * Power menu "Power off": the same ShutdownThread path and reason as the system menu's
         * PowerAction (WindowManagerService.java:3625-3637), so sys.powerctl becomes
         * "shutdown,userrequested".
         *
         * TODO(v6.2 power-off auto-boot): research/v62/poweroff found the likely cause (the PM-domain
         * hardware watchdog of wdt-mt5896.ko keeps running after "reboot: Power down" because
         * nothing opened /dev/watchdog0), but it is NOT proven on the device yet (test plan T1..T4).
         * Its proposed fix lives in the image, not here: /system/etc/init/z9x_poweroff.rc
         * ("on shutdown" -> z9x_poweroff.sh, magic close of /dev/watchdog0 only when sys.powerctl
         * starts with "shutdown"; kill switch persist.z9x.poweroff.wdtstop=0). It keys on
         * sys.powerctl, so this normal shutdown call is all the app has to do. Until that test
         * passes the projector may still power itself back on after a power-off.
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
                try {
                    Log.i(TAG, reboot ? "reboot (user requested)" : "shutdown (user requested)");
                    // REBOOT permission (signature|privileged, granted by the platform signature;
                    // PowerManagerService.java:6817 / :6882). Reason SHUTDOWN_USER_REQUESTED, like
                    // the system menu.
                    if (reboot) {
                        // v6.5: an orderly reboot, never an unattended reset (boot-dark check)
                        StandbyController.noteOrderlyReboot("power menu restart");
                        pm.reboot(PowerManager.SHUTDOWN_USER_REQUESTED);           // blocks
                    } else {
                        // v6.5: the same 'off' marker (state + wall time) as the standby deadline's
                        // power-off, so the next boot's boot-dark check treats both alike
                        StandbyController.notePowerOff("power menu");
                        pm.shutdown(false, PowerManager.SHUTDOWN_USER_REQUESTED, false);
                    }
                } catch (Throwable t) {
                    sHaltInFlight = false;
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
