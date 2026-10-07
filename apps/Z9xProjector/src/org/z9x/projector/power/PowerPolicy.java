package org.z9x.projector.power;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.R;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.Ui;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * MODULE "power": lamp handling around an Android screen-off / screen-on.
 *
 * Lumen OS 1.0: real STR (StandbyController / StrGate). Our own "off" (short POWER, sleep timer, power
 * menu Sleep, idle) switches the lamp off in StandbyController and then puts Android to sleep; every
 * other sleep (CEC standby, adb, a factory remote's system POWER) arrives here as a plain SCREEN_OFF.
 *
 * <b>SCREEN_OFF</b> (main thread, from App's single screen receiver):
 *  1. a standby in progress hands over (StandbyController.onScreenOff; it says whether the lamp is
 *     already confirmed off, then nothing more is sent to the HAL before the suspend);
 *  2. record /sys/power/suspend_stats/success (thread "z9x-power");
 *  3. otherwise {@link LampControl#lampOff}: confirmed focus-motor stop, lamp level restore, 196 ->
 *     195(false) (stock xgimiPrepareSleep step 7). With STR allowed a short partial wakelock
 *     "z9x:sleepprep" (at most 10 s) lets it finish before the suspend;
 *  4. the STR gate (StandbyController.onSleepGate): a blocker (USB host, update, allow_str=0) keeps the
 *     CPU awake with "z9x:quickwake" until it clears, otherwise nothing of ours holds the suspend;
 *  5. only with the kill switch allow_str=0 (v6.5 behaviour): the power-off deadline (pref
 *     "quick_wake_min", default 30 min) and the 65 s lamp give-up (power off).
 *
 * <b>SCREEN_ON</b>: deadline canceled, wakelocks released, a dropped USB gadget restored; then
 * {@link LampControl#lampOn}: no STR (suspend_stats/success unchanged) -> after ~400 ms 196, 147 ->
 * 148(true) if eye protection had blanked the light (only with the NUI fifo reader up), 195(true);
 * after a real STR resume (the normal wake now) -> poll 196 (persist.z9x.str_lamp_polls, default 3, 1 s
 * apart), then 195(true).
 *
 * CEC: Lumen OS 1.0 hands "wake on One Touch Play" to the cec module (org.z9x.projector.cec: the
 * panel's "Turn on with an HDMI device", default on through Z9xFrameworkKeysOverlay, guarded and mirrored
 * into the PM51 wake source cec0). The v6.5 one-shot switch-off (marker {@link #KEY_CEC_WAKE_OFF}) is
 * gone: it would undo that default on a fresh install. The marker key stays reserved.
 *
 * Never: IGmpf 241 goToSleep / 422 goToShutdown / 229 / 233 / 543, PowerManager.forceSuspend,
 * Wol/Wow/Cec setters, fan control (IGmpf 434 has no physical effect). 148 is only ever sent with true.
 * Read-only diagnostics (230, 639, 196) are logged once after the HAL connected.
 *
 * Public API for the quick panel: {@link #getQuickWakeMinutes}, {@link #setQuickWakeMinutes},
 * {@link #quickWakeChoices}, {@link #quickWakeLabel} (the v6.5 standby window; Lumen OS 1.0 uses it only
 * with allow_str=0 and no longer shows the row). Read synchronously in {@link #install}.
 */
public final class PowerPolicy {
    private static final String TAG = "Z9xPower";

    private static final String PREFS = "z9x_power";
    private static final String KEY_QUICK_WAKE_MIN = "quick_wake_min";
    /** One-shot marker of the v6.5 migration of a stored v6.4 value 0. */
    private static final String KEY_QUICK_WAKE_V65 = "quick_wake_v65";
    /** v6.5 one-shot marker (CEC "TV wake on One Touch Play" switched off); unused since Lumen OS 1.0. */
    @SuppressWarnings("unused")
    private static final String KEY_CEC_WAKE_OFF = "cec_tv_wake_otp_off_v65";

    /**
     * "Never": stay in standby / sleep (wakelock held, no power-off). Still understood by
     * StandbyController.armDeadline, but NOT offered in v6.5 (review): with only the lamp off the
     * MT9681, GOP, VB1 and DLPC / DMD stay powered and compositing, and the thermal / fan behaviour of
     * that state is not logged yet; a long window also leaves HDMI sources and CEC audio devices on
     * (CEC &lt;Standby&gt; is not sent at enter). A stored "Never" becomes {@link #QUICK_WAKE_LONGEST_MIN}.
     */
    public static final int QUICK_WAKE_ALWAYS = -1;
    /** "Right away": power off as soon as the lamp is off. */
    public static final int QUICK_WAKE_OFF = 0;
    /** Default standby window in minutes. */
    public static final int QUICK_WAKE_DEFAULT_MIN = 30;
    /** The longest offered window (a stored v6.5-beta "Never" is capped to it). */
    static final int QUICK_WAKE_LONGEST_MIN = 240;
    private static final int[] QUICK_WAKE_CHOICES = {QUICK_WAKE_OFF, 5, 15, 30, 60, 120, QUICK_WAKE_LONGEST_MIN};

    private static final String SUSPEND_SUCCESS = "/sys/power/suspend_stats/success";
    private static final long LIGHT_ON_DELAY_MS = 400;     // decision: 300-500 ms
    private static final long DIAG_POLL_MS = 5_000;
    private static final int DIAG_POLL_TRIES = 120;        // up to 10 min after process start

    private static Context sApp;
    private static SafeHandler sWorker;
    private static PowerManager sPm;

    /** suspend_stats/success at the last SCREEN_OFF (z9x-power thread), -1 = unknown. */
    private static volatile long sSuccessAtOff = -1;
    private static volatile int sQuickWakeMin = QUICK_WAKE_DEFAULT_MIN;
    private static volatile boolean sPrefsLoaded;

    private PowerPolicy() {}

    // =================================================================== entry points (main thread)

    public static void install(Context ctx) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        sWorker = SafeHandler.newThread("z9x-power");
        sPm = sApp.getSystemService(PowerManager.class);
        loadPrefs(sApp);                                    // synchronous: the deadline needs it
        sWorker.post(() -> Log.i(TAG, "installed: STR " + (StrGate.allowStr() ? "on" : "off (allow_str=0, standby window "
                + sQuickWakeMin + " min)") + ", suspend success=" + readSuspendSuccess()));
        sWorker.postDelayed(() -> logDiagnostics(0), DIAG_POLL_MS);
        // v6.2 power UI (own power key, power menu, sleep timer, wake curtain): PowerUi.
        try { PowerUi.install(sApp); } catch (Throwable t) { Log.e(TAG, "PowerUi.install", t); }
        // v6.5 lamp-only standby + power-off deadline (also restores them after a process restart)
        try { StandbyController.install(sApp); } catch (Throwable t) { Log.e(TAG, "StandbyController.install", t); }
    }

    public static void onScreenOff(Context ctx) {
        if (sApp == null) install(ctx);
        try { PowerUi.onScreenOff(sApp); } catch (Throwable t) { Log.w(TAG, "PowerUi.onScreenOff: " + t); }
        boolean lampOff = false;
        try {
            lampOff = StandbyController.onScreenOff();
            StandbyController.markSleep("screen off");
        } catch (Throwable t) {
            Log.w(TAG, "standby hand-over: " + t);
        }
        sWorker.post(() -> {
            sSuccessAtOff = readSuspendSuccess();
            Log.i(TAG, "SCREEN_OFF suspend success=" + sSuccessAtOff);
        });
        startSleep(lampOff, -1);
    }

    /**
     * The process (re)started while the display is off (StandbyController marker restore): the same
     * as SCREEN_OFF, keeping this boot's deadline (wall clock, -1 = none; allow_str=0 only). Main thread.
     */
    static void resumeSleep(Context ctx, long deadlineWall) {
        if (sApp == null) install(ctx);
        startSleep(false, deadlineWall);
        // SCREEN_OFF is not sent again to a new process: the cec module decides cec0 / the OTP wake for
        // this sleep here (else a guarded sleep could end in a CEC wake the guards refused)
        try { org.z9x.projector.cec.CecPolicy.onSleepResumed(sApp); } catch (Throwable t) { Log.w(TAG, "CecPolicy.onSleepResumed: " + t); }
    }

    /** Main thread: lamp-off for a foreign sleep (not when our standby already did it), then the STR gate. */
    private static void startSleep(final boolean lampAlreadyOff, final long restoreWall) {
        if (lampAlreadyOff) {
            Log.i(TAG, "sleep: lamp already off by the standby, nothing sent before the suspend");
        } else {
            if (StrGate.blocker(sApp) == null) StandbyController.acquireSleepPrep();
            LampControl.lampOff("screen off", PowerPolicy::screenOff,
                    (ok, detail) -> Ui.main().post(() -> onSleepLampOff(ok, detail)));
            // review 6.5: the give-up never waits for a HAL result (it only acts while the CPU is held)
            StandbyController.armSleepGiveUp();
        }
        if (!StrGate.allowStr()) {
            // kill switch: v6.5 sleep window, then the real power-off
            if (restoreWall > 0) {
                StandbyController.restoreSleepDeadline(restoreWall);
            } else {
                StandbyController.armDeadline(quickWakeMinutesNow(sApp), "sleep", true);
            }
        }
        StandbyController.onSleepGate("screen off");
    }

    /** Main thread: lamp-off outcome of a foreign sleep. */
    private static void onSleepLampOff(boolean ok, String detail) {
        StandbyController.releaseSleepPrep();
        if (!screenOff()) return;
        if (!ok) {
            if (StrGate.blocker(sApp) == null) {
                Log.w(TAG, "sleep: lamp-off not confirmed (" + detail + "); STR powers the lamp down");
                return;
            }
            StandbyController.powerOffNow("lamp still on " + LampControl.OFF_GIVE_UP_MS / 1000
                    + " s after the display went off (" + detail + ")");
            return;
        }
        Log.i(TAG, "sleep: lamp off (" + detail + ")");
    }

    private static boolean screenOff() {
        try {
            return sPm != null && !sPm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean screenOn() {
        try {
            return sPm == null || sPm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    public static void onScreenOn(Context ctx) {
        if (sApp == null) install(ctx);
        try { PowerUi.onScreenOn(sApp); } catch (Throwable t) { Log.w(TAG, "PowerUi.onScreenOn: " + t); }
        try { StandbyController.onScreenOn(); } catch (Throwable t) { Log.w(TAG, "standby screen on: " + t); }
        if (StandbyController.shutdownInFlight()) {
            // review 6.5: a wake while our power-off runs would light the lamp for the shutdown
            Log.i(TAG, "SCREEN_ON while our power-off is in progress: lamp stays off");
            return;
        }
        final long before = sSuccessAtOff;
        // the STR check reads sysfs, so it runs on the lamp thread (LampControl), never here
        Runnable lampOn = () -> LampControl.lampOn("screen on", () -> {
            long now = readSuspendSuccess();
            boolean noStr = now >= 0 && before >= 0 && now == before;
            Log.i(TAG, "SCREEN_ON suspend success " + before + " -> " + now
                    + (noStr ? " (no STR)" : " (STR resume or unknown)"));
            return noStr;
        }, LIGHT_ON_DELAY_MS, PowerPolicy::screenOn);
        // after a sleep with the CEC wake armed the lamp waits for the cec module's verdict (a refused
        // night-time CEC wake must not light the lamp, DLP and fans before the veto)
        boolean held = false;
        try { held = org.z9x.projector.cec.CecPolicy.holdLampOn(sApp, lampOn); } catch (Throwable t) { Log.w(TAG, "CecPolicy.holdLampOn: " + t); }
        if (!held) lampOn.run();
    }

    /** Read-only power diagnostics once the HAL is connected: 230, 639, 196. */
    private static void logDiagnostics(int tries) {
        if (!Hal.isReady()) {
            if (tries < DIAG_POLL_TRIES) sWorker.postDelayed(() -> logDiagnostics(tries + 1), DIAG_POLL_MS);
            return;
        }
        Hal.run((g, g2) -> {
            StringBuilder sb = new StringBuilder("diag:");
            try { sb.append(" bootReason(230)=").append(g.getBootReason()); } catch (Exception e) { sb.append(" 230 err ").append(e); }
            try { sb.append(" wakeUpSource(639)=").append(g.getWakeUpSource()); } catch (Exception e) { sb.append(" 639 err ").append(e); }
            try { sb.append(" screenOn(196)=").append(g.getScreenOn()); } catch (Exception e) { sb.append(" 196 err ").append(e); }
            Log.i(TAG, sb.toString());
        });
    }

    // =================================================================== panel API

    /** Choices for the quick panel, in display order (minutes; 0 = right away; no "never" in v6.5). */
    public static int[] quickWakeChoices() {
        return QUICK_WAKE_CHOICES.clone();
    }

    /** Current standby window in minutes (0 = right away). Any thread (one small read at most). */
    public static int getQuickWakeMinutes(Context ctx) {
        return quickWakeMinutesNow(ctx);
    }

    /** Same, for the power module: loads the pref first if that has not happened yet. */
    static int quickWakeMinutesNow(Context ctx) {
        if (!sPrefsLoaded && ctx != null) loadPrefs(ctx.getApplicationContext());
        return sQuickWakeMin;
    }

    /** Stores a new standby window. Any thread; applies from the next standby / sleep. */
    public static void setQuickWakeMinutes(Context ctx, int minutes) {
        int v = valid(minutes) ? minutes : QUICK_WAKE_DEFAULT_MIN;
        if (!sPrefsLoaded) loadPrefs(ctx.getApplicationContext());   // the migration must not undo v
        sQuickWakeMin = v;
        final Context app = ctx.getApplicationContext();
        Runnable save = () -> {
            try {
                prefs(app).edit().putInt(KEY_QUICK_WAKE_MIN, v).apply();
                Log.i(TAG, "standby window set to " + v);
            } catch (Throwable t) {
                Log.w(TAG, "save standby window: " + t);
            }
        };
        if (sWorker != null) sWorker.post(save); else save.run();
    }

    /** Display label for a choice: "Right away", "5 min", "2 h", "Never". */
    public static String quickWakeLabel(Context ctx, int minutes) {
        if (minutes == QUICK_WAKE_OFF) return ctx.getString(R.string.power_standby_now);
        if (minutes == QUICK_WAKE_ALWAYS) return ctx.getString(R.string.power_standby_never);
        if (minutes >= 60 && minutes % 60 == 0) {
            return ctx.getString(R.string.power_quick_wake_hours, minutes / 60);
        }
        return ctx.getString(R.string.power_quick_wake_minutes, minutes);
    }

    private static boolean valid(int m) {
        for (int c : QUICK_WAKE_CHOICES) if (c == m) return true;
        return m > 0 && m <= 24 * 60;
    }

    private static synchronized void loadPrefs(Context app) {
        if (sPrefsLoaded) return;
        try {
            SharedPreferences p = prefs(app);
            int v = p.getInt(KEY_QUICK_WAKE_MIN, QUICK_WAKE_DEFAULT_MIN);
            if (v == QUICK_WAKE_ALWAYS) {
                // not offered any more (QUICK_WAKE_ALWAYS javadoc): capped, and stored so the panel shows it
                v = QUICK_WAKE_LONGEST_MIN;
                p.edit().putInt(KEY_QUICK_WAKE_MIN, v).apply();
                Log.w(TAG, "standby window 'Never' is not offered: capped to " + v + " min");
            }
            if (!p.getBoolean(KEY_QUICK_WAKE_V65, false)) {
                SharedPreferences.Editor e = p.edit().putBoolean(KEY_QUICK_WAKE_V65, true);
                if (p.contains(KEY_QUICK_WAKE_MIN) && v == QUICK_WAKE_OFF) {
                    // v6.4: 0 = STR at once; v6.5: 0 = power off at once on every short press
                    v = QUICK_WAKE_DEFAULT_MIN;
                    e.putInt(KEY_QUICK_WAKE_MIN, v);
                    Log.w(TAG, "v6.4 standby window 0 ('STR at once') migrated to " + v + " min");
                }
                e.apply();
            }
            sQuickWakeMin = valid(v) ? v : QUICK_WAKE_DEFAULT_MIN;
            sPrefsLoaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "prefs: " + t + " (default " + QUICK_WAKE_DEFAULT_MIN + " min)");
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // =================================================================== sysfs

    /** /sys/power/suspend_stats/success (sysfs_suspend_stats, 0444), -1 if unreadable. */
    private static long readSuspendSuccess() {
        try (BufferedReader r = new BufferedReader(new FileReader(SUSPEND_SUCCESS))) {
            String s = r.readLine();
            return s == null ? -1 : Long.parseLong(s.trim());
        } catch (Throwable t) {
            Log.w(TAG, "read " + SUSPEND_SUCCESS + ": " + t);
            return -1;
        }
    }
}
