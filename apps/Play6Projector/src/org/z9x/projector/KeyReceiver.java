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
 * Receiver for android.intent.action.GLOBAL_BUTTON on the XGIMI Play 6: a fork of Z9xProjector
 * KeyReceiver.java (tools/play6/forks.tsv; check F compares the set of handled keys, which must stay the
 * Z9X set: the remote 000d:3841 and the shared global_keys.xml are the same). Protected broadcast,
 * explicit, sent only by PhoneWindowManager/GlobalKeyManager. Manifest: exported="false", no
 * intent-filter, directBootAware="false" (it never runs before the user unlocked). Every DOWN, every
 * repeated DOWN (from 400 ms, every 50 ms) and the UP arrive separately; an UP may carry FLAG_CANCELED.
 * Runs on the main thread: cheap work only, never a HAL call here (the Hal facade posts to p6-hal and is
 * closed until the start gate opens: a Focus key during the boot animation shows "not ready").
 *
 * Play 6 differences (docs/play6/SETTINGS_PLAN.md 1.1, 1.2, 1.8):
 *  - power key and the wake curtain: power/PowerKey (Android sleep only; no lamp-only standby);
 *  - manual focus (Focus long press, focus step keys) and the motor stop on any other key: the
 *    MotorController / ManualFocusActivity forks (p6-motor; nothing moves before the start gate);
 *  - Ambient key: Android's own screensaver switch (the Lumen dream module is plan P2);
 *  - eye protection and AK overlay gates: the P1 classes, both inactive until P2.
 *
 * Setup gate: while the first-run setup is not complete (Secure user_setup_complete and
 * tv_user_setup_complete both 1), keys that would open an app or a screensaver (app slots, Ambient,
 * Source) are ignored. Without Lumen Setup on the Play 6 image (owner decision O6) both flags come from
 * the stock data or the Provision app (A9).
 *
 * Gear (KEYCODE_SETTINGS): UP without a long press -> QuickPanel.toggle; held (first repeated DOWN) ->
 * close panels and open Android TV Settings (after setup only). Never acts on a key that began while the
 * screen was off. Source (KEYCODE_TV_INPUT): UP -> SourceOverlay.toggle (after setup). KEYSTONE/LENS
 * (KEYCODE_TV_NETWORK): UP -> QuickPanel.show(projection section). Mic key (KEYCODE_SEARCH): Lumen Home
 * search, push-to-talk while held; a press that began with the display off wakes and opens search
 * without voice.
 */
public final class KeyReceiver extends BroadcastReceiver {
    private static final String TAG = "P6Keys";
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
            stopMotor();                                             // no-op unless something moves
        }

        // Remote power key (KEYCODE_STB_POWER) -> own short press (fade + sleep) / long press (power
        // menu); handled before the AK gate and before wakeFromDream. For any other key's first DOWN it
        // aborts a running sleep-timer fade and lifts a wake curtain; the key then goes on as usual.
        if (org.z9x.projector.power.PowerKey.onGlobalKey(ctx, intent, ev)) return;

        // Eye-protection mask (plan P2): while it is up, every other global key is eaten.
        if (org.z9x.projector.eye.EyeGuard.onGlobalKey(ev)) return;

        // While the auto keystone overlay is up every key is ignored except the motor-stop path (P2:
        // the overlay port; in P1 AkOverlay.isActive() is false).
        if (org.z9x.projector.ak.AkOverlay.isActive()) {
            if (up && (code == Z9xKeys.FOCUS_STEP_LEFT || code == Z9xKeys.FOCUS_STEP_RIGHT)) stopMotor();
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
            if (upOk) Hal.requestAutofocus(ctx);                     // 1 s debounce inside
            return;
        }
        if (code == Z9xKeys.MANUAL_FOCUS) {
            if (firstDown) ManualFocusActivity.open(ctx);
            return;
        }
        if (code == Z9xKeys.FOCUS_STEP_LEFT || code == Z9xKeys.FOCUS_STEP_RIGHT) {
            if (up) {                                                // ANY UP, also canceled -> stop
                stopMotor();
                return;
            }
            if (!down || !fromXgimi(ev)) return;
            if (!Hal.isReady()) {                                    // start gate closed / not connected
                if (firstDown) Ui.toast(ctx, R.string.toast_not_ready);
                return;
            }
            MotorController motor = MotorController.get(ctx);
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

    /** Stops a manual focus move; posts, sends 2 only if moving (no-op while no controller exists). */
    private static void stopMotor() {
        MotorController.stopIfAny();
    }

    /**
     * Gear: DOWN(repeat 0) arms; the first repeated DOWN of the same press = long press (TvSettings);
     * UP without a long press = short press (quick panel). A canceled UP does nothing.
     */
    private static void handleGear(Context ctx, Intent intent, KeyEvent ev) {
        if (intent.getBooleanExtra(EXTRA_BEGAN_FROM_NON_INTERACTIVE, false)) return;
        int action = ev.getAction();
        // the zoom and shift card: the gear is the remote's menu key there (stock ZoomDisplacementWind MENU = the side
        // menu with Reset; owner 2026-10-24: "menu throws me into the quick panel instead of the reset")
        if (org.z9x.projector.panel.Play6ZoomShiftPanel.onMenuKey(ev)) return;
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

    /** Both setup flags are 1 (the setup writes both when it finishes). Any thread. */
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
     * Mic key. DOWN (repeat 0): setup gate; display off or the press began non-interactive -> wake and
     * keyboard search; else voice search (Lumen Home starts listening while the key is held). UP of
     * that press: MIC_UP. Never SearchManager.launchAssist (the ASSIST role is ours = Recents).
     */
    /** The current mic press went to the voice assistant (not to Lumen Home search). Main thread. */
    private static boolean sMicAssistant;

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
                        pm.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_WAKE_KEY, "p6:mic");
                    } catch (Throwable t) {
                        Log.w(TAG, "wakeUp: " + t);
                    }
                }
                Log.i(TAG, "mic key from sleep: search without voice");
                org.z9x.projector.home.HomeSearch.open(ctx, false, "mic from sleep");
                return;
            }
            Log.i(TAG, "mic down t=" + ev.getDownTime());
            // Lumen OS 1.0.5: the voice assistant (overlay over any app) where a speech recogniser exists; else
            // Lumen Home's search as before
            if (org.z9x.projector.voice.Assistant.micDown(ctx)) {
                sMicAssistant = true;
                sMicDownTime = ev.getDownTime();
                return;
            }
            sMicAssistant = false;
            if (org.z9x.projector.home.HomeSearch.open(ctx, true, "mic down")) sMicDownTime = ev.getDownTime();
            return;
        }
        if (action == KeyEvent.ACTION_UP && ev.getDownTime() == sMicDownTime) {
            sMicDownTime = -1;
            if (sMicAssistant) org.z9x.projector.voice.Assistant.micUp(ctx);
            else org.z9x.projector.home.HomeSearch.micUp(ctx);
        }
    }

    /**
     * Screensaver (Ambient key) only if Android's screensaver is on and nothing dreams yet. The Lumen
     * screensaver switch (the Z9X dream.DreamSettings / IdleOwner) is plan P2 on the Play 6.
     */
    static void startAmbient(Context ctx) {
        // Settings.Secure.SCREENSAVER_ENABLED (hidden constant), default on as in the framework
        if (Settings.Secure.getInt(ctx.getContentResolver(), "screensaver_enabled", 1) != 1) {
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
