package org.z9x.projector;

import android.app.DreamManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import org.z9x.projector.panel.QuickPanel;
import org.z9x.projector.source.SourceOverlay;
import org.z9x.projector.ui.OverlayHost;

/**
 * Receiver for android.intent.action.GLOBAL_BUTTON (protected broadcast, explicit, sent only by
 * PhoneWindowManager/GlobalKeyManager). Manifest: exported="false", no intent-filter (system_server
 * may deliver to non-exported receivers). Every DOWN, every repeated DOWN (from 400 ms, every 50 ms)
 * and the UP arrive separately; an UP may carry FLAG_CANCELED (remote disconnected while held).
 * Runs on the main thread: cheap work only, never a HAL call here (HalController/MotorController
 * post to their own threads).
 *
 * Setup gate: while the first-run setup is not complete (Secure user_setup_complete and
 * tv_user_setup_complete both 1), keys that would open an app or a screensaver over SetupWraith
 * (app slots, Ambient) are ignored, like the framework does for its own keys. Autofocus, manual
 * focus and the keystone panel stay available (a sharp, straight picture helps during setup).
 *
 * Any other global key DOWN also stops a MEDIA_STEP focus move (no-op unless something moves):
 * there is no other early stop for a key-held move, and POWER never reaches this receiver.
 *
 * While the AK overlay is up (AkOverlay.isActive(), also for a vendor-started AK) every key is
 * ignored except the motor-stop path. A first DOWN of any key except Ambient ends a running
 * screensaver first (Ui.wakeFromDream), because global keys never reach the dream window.
 *
 * v6.1 (V61_KEYS_PLAN.md):
 *  - Gear (KEYCODE_SETTINGS): UP without a long press -> QuickPanel.toggle (also during setup: the
 *    panel only changes projector settings); held (first repeated DOWN, ~400 ms+) -> close panels
 *    and open Android TV Settings (after setup only, like LineageParts KeyHandler did).
 *    Never acts on a key that began while the screen was off (EXTRA_BEGAN_FROM_NON_INTERACTIVE).
 *  - Source (KEYCODE_TV_INPUT): UP -> SourceOverlay.toggle (after setup).
 *  - KEYSTONE/LENS (KEYCODE_TV_NETWORK): UP -> QuickPanel.show(projection section).
 *
 * Lumen OS 1.0 (PLAN C4, Google Assistant removed): the mic key (KEYCODE_SEARCH) opens Lumen Home
 * search. DOWN (after setup, display on): VOICE_SEARCH with held=true (push-to-talk); UP: MIC_UP.
 * A press that began with the display off wakes and opens search without voice (the first syllables
 * would be lost); in lamp standby PowerUi does the same.
 */
public final class KeyReceiver extends BroadcastReceiver {
    private static final String TAG = "Z9xKeys";
    /** GlobalKeyIntent's extra (Lineage services/.../policy/GlobalKeyIntent.java:29-30, literal key). */
    private static final String EXTRA_BEGAN_FROM_NON_INTERACTIVE = "EXTRA_BEGAN_FROM_NON_INTERACTIVE";

    /** Gear long press already fired for the current hold (main thread only). */
    private static boolean sGearLongFired;
    private static long sGearDownTime = -1;
    /** Mic press that started voice search (its UP sends MIC_UP); main thread only. */
    private static long sMicDownTime = -1;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            handle(ctx, intent);
        } catch (Throwable t) {
            Log.e(TAG, "onReceive", t);
        }
    }

    private static void handle(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_GLOBAL_BUTTON.equals(intent.getAction())) return;
        final KeyEvent ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
        if (ev == null) return;
        final int action = ev.getAction();
        final boolean down = action == KeyEvent.ACTION_DOWN;
        final boolean firstDown = down && ev.getRepeatCount() == 0;
        final boolean up = action == KeyEvent.ACTION_UP;
        final boolean upOk = up && !ev.isCanceled();
        final int code = ev.getKeyCode();

        if (firstDown && code != Z9xKeys.FOCUS_STEP_LEFT && code != Z9xKeys.FOCUS_STEP_RIGHT) {
            HalController.get(ctx).motor().stop();                   // posts; sends 2 only if moving
        }

        // ===== v6.2 module "power" BEGIN =====
        // BT remote power key (KEYCODE_STB_POWER, BT .kl "STB_POWER WAKE") -> own short press (fade +
        // sleep) / long press (power menu); handled before the AK gate and before wakeFromDream (a
        // short press sleeps also during AK or a screensaver). For any other key's first DOWN it
        // aborts a running sleep-timer fade and lifts a wake curtain; the key then goes on as usual.
        if (org.z9x.projector.power.PowerUi.onGlobalKey(ctx, intent, ev)) return;
        // ===== v6.2 module "power" END =====

        // ===== v6.2b module "eye" =====
        // While the eye-protection mask is up (focusEvent 500 / 503) the gear key gives the picture
        // back (held: also turns eye protection off) and every other global key is eaten, so no
        // panel, app or screensaver opens behind the mask. The power key above still works.
        if (org.z9x.projector.eye.EyeGuard.onGlobalKey(ev)) return;

        // While the auto keystone overlay is up (our AK or one the vendor started itself: power-on
        // AK, curtain / obstacle trigger) every key is ignored except the motor-stop path above and
        // a focus-step UP: the vendor AK runs its own TOF autofocus and zoom motor, any manualFocus
        // or 307(44) would interrupt it, and no panel / card may be photographed with the pattern.
        if (org.z9x.projector.ak.AkOverlay.isActive()) {
            if (up && (code == Z9xKeys.FOCUS_STEP_LEFT || code == Z9xKeys.FOCUS_STEP_RIGHT)) {
                HalController.get(ctx).motor().stop();
            }
            if (firstDown) Log.i(TAG, "AK overlay active: key " + code + " ignored");
            return;
        }

        // Global keys never reach the screensaver window, so wake it like any other key would;
        // otherwise everything we open below would stay hidden under the always-on-top dream.
        if (firstDown && code != Z9xKeys.AMBIENT) Ui.wakeFromDream(ctx);

        if (code == Z9xKeys.VOICE) {
            handleMic(ctx, intent, ev);                              // push-to-talk: DOWN starts, UP stops
            return;
        }
        if (code == Z9xKeys.AUTOFOCUS) {
            if (upOk) HalController.get(ctx).requestAutofocus();     // 1 s debounce inside
            return;
        }
        if (code == Z9xKeys.MANUAL_FOCUS) {
            if (firstDown) ManualFocusActivity.open(ctx);
            return;
        }
        if (code == Z9xKeys.FOCUS_STEP_LEFT || code == Z9xKeys.FOCUS_STEP_RIGHT) {
            MotorController motor = HalController.get(ctx).motor();
            if (up) {                                                // ANY UP, also canceled -> stop
                motor.stop();
                return;
            }
            if (!down || !fromXgimi(ev)) return;
            if (!HalController.featureEnabled()) {
                if (firstDown) Ui.featureOffNotice(ctx);
                return;
            }
            if (firstDown) motor.start(code == Z9xKeys.FOCUS_STEP_LEFT ? 1 : 0);   // stock LEFT 1, RIGHT 0
            else motor.feed();                                       // repeats feed the 700 ms watchdog
            return;
        }
        if (code == Z9xKeys.AMBIENT) {
            if (upOk && setupDone(ctx, "ambient")) startAmbient(ctx);
            return;
        }
        if (code == Z9xKeys.GEAR) {
            handleGear(ctx, intent, ev);
            return;
        }
        if (code == Z9xKeys.SOURCE) {
            if (upOk && setupDone(ctx, "source")) SourceOverlay.toggle(ctx);
            return;
        }
        if (code == Z9xKeys.PROJECTOR_PANEL) {
            if (upOk) QuickPanel.show(ctx, QuickPanel.SECTION_PROJECTION);
            return;
        }
        for (int i = 0; i < Z9xKeys.APP_SLOTS.length; i++) {
            if (code == Z9xKeys.APP_SLOTS[i]) {
                if (upOk && setupDone(ctx, "app slot " + (i + 1))) AppSlots.launch(ctx, i + 1);
                return;
            }
        }
    }

    /**
     * Gear: DOWN(repeat 0) arms; the first repeated DOWN of the same press = long press (TvSettings);
     * UP without a long press = short press (quick panel). A canceled UP does nothing.
     */
    private static void handleGear(Context ctx, Intent intent, KeyEvent ev) {
        if (intent.getBooleanExtra(EXTRA_BEGAN_FROM_NON_INTERACTIVE, false)) return;
        int action = ev.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (ev.getRepeatCount() == 0) {
                sGearLongFired = false;
                sGearDownTime = ev.getDownTime();
            } else if (!sGearLongFired && ev.getDownTime() == sGearDownTime) {
                sGearLongFired = true;
                OverlayHost.get(ctx).dismissAll(false);
                if (setupDone(ctx, "gear long press")) openTvSettings(ctx);
            }
            return;
        }
        if (action == KeyEvent.ACTION_UP) {
            boolean shortPress = !sGearLongFired && !ev.isCanceled() && ev.getDownTime() == sGearDownTime;
            sGearLongFired = false;
            sGearDownTime = -1;
            if (shortPress) QuickPanel.toggle(ctx);
        }
    }

    /** Android TV Settings (what LineageParts KeyHandler opened for KEYCODE_SETTINGS). */
    static void openTvSettings(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Ui.wakeFromDream(ctx);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "TvSettings: " + t);
        }
    }

    /** Both setup flags are 1 (SetupWraith writes both when it finishes). Any thread. */
    public static boolean isSetupComplete(Context ctx) {
        return Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 0) == 1
                && Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.TV_USER_SETUP_COMPLETE, 0) == 1;
    }

    /** Setup gate for keys that open apps / a dream: logs and shows a short hint when it fails. */
    private static boolean setupDone(Context ctx, String what) {
        if (isSetupComplete(ctx)) return true;
        Log.i(TAG, "setup not complete: " + what + " key ignored");
        Ui.toast(ctx, R.string.toast_setup_running);
        return false;
    }

    /**
     * Mic key (PLAN C4). DOWN (repeat 0): setup gate; display off or the press began non-interactive ->
     * wake and keyboard search; else voice search (Lumen Home starts listening while the key is held).
     * UP of that press: MIC_UP. Never SearchManager.launchAssist (Katniss is gone; the ASSIST role is
     * ours = Recents).
     */
    private static void handleMic(Context ctx, Intent intent, KeyEvent ev) {
        int action = ev.getAction();
        if (action == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
            sMicDownTime = -1;
            if (!isSetupComplete(ctx)) {
                Log.i(TAG, "setup not complete: mic key ignored");
                return;
            }
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            boolean asleep = pm != null && !pm.isInteractive();
            if (asleep || intent.getBooleanExtra(EXTRA_BEGAN_FROM_NON_INTERACTIVE, false)) {
                if (asleep) {
                    try {
                        pm.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_WAKE_KEY, "z9x:mic");
                    } catch (Throwable t) {
                        Log.w(TAG, "wakeUp: " + t);
                    }
                }
                Log.i(TAG, "mic key from sleep: search without voice");
                org.z9x.projector.home.HomeSearch.open(ctx, false, "mic from sleep");
                return;
            }
            Log.i(TAG, "mic down t=" + ev.getDownTime());
            if (org.z9x.projector.home.HomeSearch.open(ctx, true, "mic down")) sMicDownTime = ev.getDownTime();
            return;
        }
        if (action == KeyEvent.ACTION_UP && ev.getDownTime() == sMicDownTime) {
            sMicDownTime = -1;
            org.z9x.projector.home.HomeSearch.micUp(ctx);
        }
    }

    /** Screensaver (Ambient mode) only if enabled and not already dreaming: never risk a nap -> sleep. */
    static void startAmbient(Context ctx) {
        // v6.5: the user's switch (IdleOwner); Android's screensaver_enabled stays 1 and "off" makes
        // the blank standby dream active, which must not start from this key
        if (!org.z9x.projector.dream.DreamSettings.isEnabled(ctx)) {
            Log.i(TAG, "screensaver disabled: ambient key ignored");
            return;
        }
        DreamManager dm = ctx.getSystemService(DreamManager.class);
        if (dm != null && !dm.isDreaming()) dm.startDream();
    }

    /** Motor keys only from XGIMI input devices; rejects keyboards and 'input keyevent' (device -1). */
    static boolean fromXgimi(KeyEvent ev) {
        InputDevice d = InputDevice.getDevice(ev.getDeviceId());
        if (d == null) return false;
        int vid = d.getVendorId();
        for (int v : Z9xKeys.XGIMI_VENDOR_IDS) if (vid == v) return true;
        return false;
    }
}
