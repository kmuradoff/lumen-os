package org.z9x.projector.power;

import android.app.DreamManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;
import android.view.KeyEvent;

import org.z9x.projector.Hal;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.Ui;
import org.z9x.projector.ui.Notify;
import org.z9x.projector.ui.OverlayHost;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayDeque;
import java.util.List;

/**
 * MODULE "power": the projector's own "off" (Lumen OS 1.0: lamp off at once, then real STR).
 *
 * <b>Lumen OS 1.0</b> (supersedes the v6.5 lamp-only standby with its quick-wake window; VERIFIED on the
 * device 2026-10-06): real STR works. A short POWER press (BT / IR / keypad STB_POWER after the fade),
 * the sleep timer, the power menu's Sleep, the idle timeout (blank idle dream) and the screensaver's
 * "turn off after" all call {@link #enter}: the lamp goes off at once (LampControl: motor stop, 196 ->
 * 195(false)) with every playing media session paused and the sound muted, and as soon as the lamp-off
 * is confirmed Android goes to sleep (PowerManager.goToSleep) and suspends at once. There is no
 * quick-wake window and no power-off after a window any more. Wake: a remote key (PM51 wake sources
 * keypad0 / ir0 / bt-gpio / mute-gpio, set at boot by overlay/v1/z9x_power.rc) wakes the SoC, the vendor
 * power HAL injects KEY_POWER, Android wakes and PowerPolicy's SCREEN_ON path lights the lamp (no boot
 * animation, the same app resumes). If the lamp-off is not confirmed within {@value #STR_LAMP_WAIT_MS}
 * ms, Android sleeps anyway (the vendor suspend powers the lamp down).
 *
 * <b>STR blockers</b> ({@link StrGate}): kill switch persist.z9x.allow_str=0, an update being installed,
 * an updated slot not yet confirmed, and above all a connected USB host (with the Mac's adb on the
 * cable the USB device controller keeps a clock on and PM51 resets the chip 20 s after the suspend,
 * boot_reason 0xF1: the root cause of every self-reboot on 2026-10-06). While a blocker holds, the
 * projector stays in the v6.5 lamp-only standby below (display on, lamp off, no reset); when it clears
 * (USB_STATE broadcast, the updater, or the {@value #GATE_RECHECK_MS} ms re-check) the STR follows.
 * Behind persist.z9x.str_usb_drop=1 (default 0) the USB gadget is dropped before the sleep instead
 * and restored at SCREEN_ON (for a later device test). Only with allow_str=0 the v6.5 standby window
 * ends in the real power-off (deadline); a blocked standby otherwise waits for the user or the gate.
 *
 * <b>Lamp-only standby</b> (v6.5, now only while STR is blocked; main thread): IDLE -> {@link #enter} ->
 * ENTERING (lamp-off running) -> STANDBY (lamp confirmed off) -> {@link #exit} -> IDLE (lamp-on via
 * 147/148 + 195(true), curtain reveal). Enter: user activity first (so a running dream ends awake, never
 * dozing), the screen wakelock (SCREEN_BRIGHT | ON_AFTER_RELEASE "z9x:standby") and partial
 * "z9x:quickwake"; overlays closed; the wake curtain black and the opaque {@link StandbyActivity} started
 * BEFORE a running dream is ended ({@value #STOP_DREAM_DELAY_MS} ms later); the vendor's user-level
 * motion triggers (586 -> 585(false), 588 -> 587(false)) switched off if they were on (remembered in the
 * marker, restored at exit / SCREEN_ON / the next start); playing media paused, STREAM_MUSIC (or with an
 * HDMI ARC / eARC output the master mute, no CEC toggle) muted, AUDIOFOCUS_GAIN held; then
 * {@link LampControl#lampOff}. While STANDBY: the lamp watchdog (196 every 15 s; relit -> 195(false);
 * more than 3 relights an hour -> power off), a thermal listener (SEVERE -> power off), the black screen
 * re-fronted when covered, an HDMI session never wakes, vendor AK / AF / eye events re-check 196.
 * The lamp-off not confirmed {@link LampControl#OFF_GIVE_UP_MS} + {@value #LAMP_GIVE_UP_EXTRA_MS} ms
 * after enter: power off (blocked) / STR (allowed). Exit by the user: STB_POWER, OK / ENTER, HOME,
 * the mic key; never by an HDMI plug / HPD / +5V; by HDMI-CEC only through org.z9x.projector.cec.CecPolicy
 * and its guards (exit reason "cec_otp:..."). The lamp-only standby sends the CEC &lt;Standby&gt; itself
 * (CecPolicy.onLampOnlyStandby): Android stays awake, so the framework does not. A wake request while our power-off or the STR sleep is already in flight is only logged.
 *
 * <b>Foreign sleeps</b> (CEC standby, adb, a factory remote's POWER): PowerPolicy turns the lamp off on
 * SCREEN_OFF; with STR allowed a short partial wakelock "z9x:sleepprep" (at most {@value
 * #SLEEP_PREP_MS} ms) lets that lamp-off finish before the suspend; with a blocker "z9x:quickwake" is
 * held until it clears ({@link #onSleepGate}). A SCREEN_OFF during our standby hands over (the lamp is
 * already off: nothing more is sent to the HAL before the suspend).
 *
 * <b>Updates</b> (PLAN C16): an update being installed or a slot on probation blocks STR (see above);
 * the updater's "restart when turned off" ({@link OtaStateReceiver}) reboots with reason z9x-ota
 * instead of the next STR, and that next boot goes dark again by itself (marker {@value #K_DARK_NEXT}).
 *
 * <b>Boot-dark</b> ({@link #onBootCompleted}, v6.5, kept): a reset nobody asked for while the projector
 * was dark and ours powers it off again: previous state 'standby' or 'sleep' (now also the STR state),
 * no orderly shutdown mark, sys.boot.reason.last not an orderly init reason, boot_reason 0xF1 or 0xD1,
 * wake source "(null)", wdt_reset_chk pm51-wdt-reset, RTC valid; or previous state 'off' and a 0xF1 reset
 * less than {@value #OFF_RESET_WINDOW_MS} ms after our power-off. At most {@value
 * #BOOTDARK_MAX_CONSECUTIVE} in a row; never while the running slot is an unconfirmed update (C16:
 * the health gate must run; "reboot,z9x-ota*" reasons are orderly anyway); kill switch
 * persist.z9x.bootdark=0. An AC plug-in (0xF1 + keypad0 + no-pm-wdt-reset) is left alone.
 *
 * <b>Failed power-off</b>: retried {@value #SHUTDOWN_RETRY_MS} ms later, up to 3 times, then every
 * {@value #SHUTDOWN_LATE_RETRY_MS} ms while the projector stays dark.
 *
 * <b>Persistence</b>: prefs z9x_power keep the power state (active / standby / sleep / off), the
 * deadline (allow_str=0 only), the boot id, our last power-off time, the orderly-shutdown mark, the mute /
 * motion-trigger marks, the previous boot's state (for this boot), the boot-dark counter and the dark-boot
 * request, so a restarted process re-enters standby in the same boot and the next boot knows the previous
 * state. Read synchronously in {@link #install} (App.onCreate).
 *
 * Broadcast {@link #ACTION_STANDBY_CHANGED} (extra {@link #EXTRA_STANDBY}) under the signature
 * permission {@link #PERMISSION_STANDBY_STATE} for other Z9X apps, and {@link StandbyStateProvider}
 * (same permission) for the current value; in this app EyeGuard, PowerUi and the key path ask
 * {@link #isActive()}.
 */
public final class StandbyController {
    private static final String TAG = "Z9xStandby";

    public static final String ACTION_STANDBY_CHANGED = "org.z9x.projector.action.STANDBY_CHANGED";
    public static final String EXTRA_STANDBY = "standby";
    public static final String PERMISSION_STANDBY_STATE = "org.z9x.projector.permission.STANDBY_STATE";

    private static final int IDLE = 0, ENTERING = 1, STANDBY = 2;

    private static final String PREFS = "z9x_power";
    private static final String K_STATE = "pw_state";
    private static final String K_BOOT = "pw_boot";
    private static final String K_DEADLINE = "pw_deadline_wall";
    private static final String K_REASON = "pw_reason";
    private static final String K_BOOTDARK_BOOT = "bootdark_boot";
    /** Consecutive boot-dark power-offs (reset by any boot that is not an unattended reset). */
    private static final String K_BOOTDARK_COUNT = "bootdark_count";
    /** Wall clock (ms) of our last power-off request (deadline, boot-dark, power menu); -1 = none. */
    private static final String K_OFF_WALL = "pw_off_wall";
    /** STREAM_MUSIC was muted by standby and must be unmuted again. */
    private static final String K_MUTED = "pw_music_muted";
    /** The master mute (HDMI ARC / eARC output) was set by standby and must be undone again. */
    private static final String K_MASTER_MUTED = "pw_master_muted";
    /** Boot id of the boot that saw Intent.ACTION_SHUTDOWN (orderly framework reboot / power-off). */
    private static final String K_ORDERLY_BOOT = "pw_orderly_boot";
    private static final String K_ORDERLY_WHY = "pw_orderly_why";
    /** The previous boot's state as seen by this boot (K_PREV_FOR = this boot id). */
    private static final String K_PREV_FOR = "pw_prev_for_boot";
    private static final String K_PREV_STATE = "pw_prev_state";
    private static final String K_PREV_OFF_WALL = "pw_prev_off_wall";
    private static final String K_PREV_ORDERLY = "pw_prev_orderly";
    /** Boot id whose boot-dark check reached its verdict (once per boot, across process restarts). */
    private static final String K_DARK_CHECKED = "bootdark_checked_boot";
    /** Motion triggers standby switched off (MOVE_AK | MOVE_AF) and must switch on again. */
    private static final String K_MOVE_OFF = "pw_move_off";
    /** Lumen OS 1.0: the next boot goes dark at once (an update restart instead of STR). */
    private static final String K_DARK_NEXT = "pw_dark_next_boot";
    private static final int MOVE_AK = 1, MOVE_AF = 2;
    static final String ST_ACTIVE = "active", ST_STANDBY = "standby", ST_SLEEP = "sleep", ST_OFF = "off";

    private static final String PROP_BOOTDARK = "persist.z9x.bootdark";
    private static final String PROP_BOOT_REASON = "sys.z9x.boot_reason";
    private static final String PROP_WAKE_NAME = "sys.z9x.wake_name";
    /** wdt_extend/wdt_reset_chk (z9x_bootinfo.sh): sticky, cleared only by AC loss. */
    private static final String PROP_WDT_CHK = "sys.z9x.wdt_reset_chk";
    private static final String WDT_RESET = "pm51-wdt-reset";
    /** wdt_reset_chk after AC loss (an AC plug-in, never an unattended reset). */
    private static final String WDT_NO_RESET = "no-pm-wdt-reset";
    /** bootstat: init's persisted reason of the last orderly reboot / shutdown, else the bootloader's. */
    private static final String PROP_LAST_REASON = "sys.boot.reason.last";
    /** 2001-01-01 UTC: an RTC before this was reset (AC loss). */
    private static final long RTC_VALID_WALL = 978_307_200_000L;
    /** A boot-dark check a restarted process still owes runs only this soon after boot. */
    private static final long LATE_CHECK_MAX_MS = 10 * 60_000L;

    private static final long WATCH_MS = 15_000;
    private static final int RELIGHT_MAX_PER_HOUR = 3;
    private static final long REPAUSE_MS = 5_000;
    /** After StandbyActivity's onStop: a covering app gets this long before the black screen returns. */
    static final long COVER_SETTLE_MS = 1_000;
    static final int REFRONT_MAX_PER_MIN = 3;
    private static final long SHUTDOWN_RETRY_MS = 30_000;
    private static final int SHUTDOWN_FAST_TRIES = 3;
    /** After the fast retries: another power-off attempt this often while the projector stays dark. */
    static final long SHUTDOWN_LATE_RETRY_MS = 10 * 60_000L;
    /** A shutdown requested without an error that has not happened after this long counts as failed. */
    static final long SHUTDOWN_STUCK_MS = 120_000;
    /** Give-up timer slack after LampControl.OFF_GIVE_UP_MS (its own result normally comes first). */
    static final long LAMP_GIVE_UP_EXTRA_MS = 5_000;
    /** Temperatures are logged every this many watchdog ticks (15 s each). */
    private static final int TEMP_LOG_EVERY = 4;
    private static final String[] THERMAL_ZONES = {
            "/sys/class/thermal/thermal_zone0/temp", "/sys/class/thermal/thermal_zone1/temp"};
    /** Our audio focus is asked for again this long after a loss during standby. */
    static final long REFOCUS_MS = 1_000;
    private static final long REFOCUS_FAILED_RETRY_MS = 5_000;
    /** The curtain and StandbyActivity get this long to draw before a running dream is ended. */
    static final long STOP_DREAM_DELAY_MS = 150;
    /** Boot-dark after prev 'off': the reset must come this soon (wall clock) after our power-off. */
    static final long OFF_RESET_WINDOW_MS = 240_000;
    static final int BOOTDARK_MAX_CONSECUTIVE = 2;
    /** Extra 196 re-checks after a vendor AK / AF event during standby (ms after the event). */
    static final long[] AK_RECHECK_MS = {500, 1_000, 2_000, 4_000, 8_000};
    private static final Object AK_RECHECK_TOKEN = new Object();
    /** Motion-trigger restore: first try after the lamp-on, then retries while the HAL is not up. */
    private static final long MOVE_RESTORE_DELAY_MS = 1_500, MOVE_RESTORE_RETRY_MS = 5_000;
    /** STR: Android sleeps this long after enter even if the lamp-off is not confirmed yet. */
    static final long STR_LAMP_WAIT_MS = 6_000;
    /** STR blocked: the gate is re-checked this often (besides the USB_STATE / updater events). */
    static final long GATE_RECHECK_MS = 15_000;
    /** Still interactive this long after our goToSleep: retried through the gate re-check. */
    private static final long SLEEP_CHECK_MS = 3_000;
    /** Foreign sleep with STR allowed: the suspend waits at most this long for our lamp-off. */
    static final long SLEEP_PREP_MS = 10_000;
    /** persist.z9x.str_usb_drop=1: the gadget gets this long to go down before the gate looks again. */
    private static final long USB_DROP_SETTLE_MS = 2_000;
    private static final int MOVE_RESTORE_MAX_TRIES = 120;

    private static Context sApp;
    private static PowerManager sPm;
    private static AudioManager sAm;
    private static SafeHandler sWorker;
    private static PowerManager.WakeLock sScreenLock;
    private static PowerManager.WakeLock sHoldLock;
    /** "z9x:sleepprep": a foreign sleep's lamp-off finishes before the suspend (timeout SLEEP_PREP_MS). */
    private static PowerManager.WakeLock sPrepLock;

    private static volatile int sState = IDLE;
    private static String sReason = "";
    private static int sLampGen = -1;
    /** Deadline (uptime) or -1. Main thread. */
    private static long sDeadlineUptime = -1;
    private static String sDeadlineWhy = "";
    private static boolean sPoweringOff;
    private static int sShutdownTries;
    /** PowerManager.shutdown returned without an error (z9x-standby writes, main reads). */
    private static volatile boolean sShutdownInFlight;
    private static volatile long sShutdownAt;
    /**
     * The power-off in flight is the boot-dark one: retried although standby could not be entered,
     * until any user activity (a global key, a wake) is seen. Main thread.
     */
    private static boolean sBootDark;
    /** Uptime of the last enter (log). Main thread. */
    private static long sEnteredAt;
    /** STREAM_MUSIC was muted by us (mirrors K_MUTED). Main thread. */
    private static boolean sMutedByUs;
    /** The master mute was set by us (mirrors K_MASTER_MUTED). Main thread. */
    private static boolean sMasterMutedByUs;
    /** Watchdog ticks of the current standby (temperature log). Main thread. */
    private static int sWatchTicks;
    private static PowerManager.OnThermalStatusChangedListener sThermal;
    /** Motion triggers switched off by us (MOVE_AK | MOVE_AF, mirrors K_MOVE_OFF). Main thread. */
    private static int sMoveOffByUs;
    /**
     * The marker says this boot is in standby, but the process (re)started and the main-thread
     * re-enter has not run yet: {@link #isActive()} is already true, a wake key ends it ({@link #exit}).
     */
    private static volatile boolean sRestorePending;
    /** Vendor AK / AF focus events seen during the current standby (diagnostic). Main thread. */
    private static int sVendorMotionEvents;
    /** Deadline (wall clock) of this boot's standby, restored after a process restart; -1 = none. */
    private static long sRestoreDeadlineWall = -1;
    /** The current standby's lamp-off was confirmed (196 false / 195(false) done). Main thread. */
    private static boolean sLampOffConfirmed;
    /** Our goToSleep for STR is in flight (until SCREEN_OFF or the sleep check). Main thread. */
    private static boolean sStrRequested;
    /** The STR blocker that keeps the current standby / sleep awake, null = none. Main thread. */
    private static String sBlockedWhy;
    /** persist.z9x.str_usb_drop=1: the gadget drop was tried for this standby / sleep. Main thread. */
    private static boolean sUsbDropTried;
    /** An update restart (reboot instead of STR) is in flight. */
    private static volatile boolean sUpdateReboot;
    private static BroadcastReceiver sGateWatch;
    private static AudioFocusRequest sFocus;
    private static BroadcastReceiver sHomeWatch;
    private static BroadcastReceiver sVolumeWatch;
    private static final ArrayDeque<Long> sRelights = new ArrayDeque<>();
    private static final ArrayDeque<Long> sRefronts = new ArrayDeque<>();
    /** Power state of the previous boot (marker of another boot id), read at install. */
    private static volatile String sPrevBootState;
    /** Wall clock of the previous boot's last power-off request (K_OFF_WALL), -1 = none. */
    private static volatile long sPrevOffWall = -1;
    /** The previous boot ended through ShutdownThread (ACTION_SHUTDOWN) or the power menu's Restart. */
    private static volatile boolean sPrevOrderly;
    /** z9x-standby only: a boot-dark check is queued or running in this process. */
    private static boolean sDarkCheckQueued;

    private static final Runnable sDeadlineRun = StandbyController::onDeadline;
    private static final Runnable sWatchRun = StandbyController::watchLamp;
    private static final Runnable sLampGiveUpRun = StandbyController::onStandbyLampGiveUp;
    private static final Runnable sSleepGiveUpRun = StandbyController::onSleepLampGiveUp;
    private static final Runnable sBootDarkRetryRun = () -> {
        if (sBootDark && sState == IDLE) powerOffNow("boot-dark power-off (late retry)");
    };
    private static final Runnable sRepauseRun = () -> {
        if (sState != IDLE) pauseMedia("re-check");
    };
    private static final Runnable sRefocusRun = () -> {
        if (sState == IDLE) return;
        if (!requestFocus()) Ui.main().postDelayed(StandbyController.sRefocusRun, REFOCUS_FAILED_RETRY_MS);
    };
    private static final Runnable sStopDreamRun = () -> {
        if (sState != IDLE) stopDream();
    };
    private static final Runnable sStrLampWaitRun = StandbyController::onStrLampWait;
    private static final Runnable sGateRecheckRun = () -> onGateChanged(sApp, "re-check");
    private static final Runnable sSleepCheckRun = StandbyController::onSleepCheck;

    private StandbyController() {}

    // =================================================================== install / state

    /**
     * Main thread (PowerPolicy.install, App.onCreate). Reads the marker synchronously and restores a
     * standby / deadline of this boot after a process restart; registers the orderly-shutdown mark.
     */
    static synchronized void install(Context ctx) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        sPm = sApp.getSystemService(PowerManager.class);
        sAm = sApp.getSystemService(AudioManager.class);
        sWorker = SafeHandler.newThread("z9x-standby");
        if (sPm != null) {
            sScreenLock = sPm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                    "z9x:standby");
            sScreenLock.setReferenceCounted(false);
            sHoldLock = sPm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "z9x:quickwake");
            sHoldLock.setReferenceCounted(false);
            sPrepLock = sPm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "z9x:sleepprep");
            sPrepLock.setReferenceCounted(false);
        }
        LampControl.install();
        registerShutdownWatch();
        restoreFromMarker();
    }

    /**
     * True while our lamp-only standby is entered or held, or a standby of this boot waits for its
     * re-enter after a process restart. Any thread.
     */
    public static boolean isActive() {
        return sState != IDLE || sRestorePending;
    }

    /** Lumen OS 1.0: STR unless the kill switch persist.z9x.allow_str=0 is set. */
    static boolean allowStr() {
        return StrGate.allowStr();
    }

    private static boolean interactive() {
        try {
            return sPm == null || sPm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    // =================================================================== enter / exit

    /**
     * Enters lamp-only standby. Returns false (nothing changed) when the display is already off or
     * the wakelock is not available; the caller then uses the normal Android sleep. Main thread
     * (re-posted otherwise, then reported as true).
     */
    public static boolean enter(Context ctx, String reason) {
        if (!Ui.main().isCurrentThread()) {
            Ui.main().post(() -> {
                if (!enter(ctx, reason)) fallbackSleep(reason);
            });
            return true;
        }
        try {
            install(ctx);
            if (sState != IDLE) {
                Log.i(TAG, "enter (" + reason + "): already in standby (" + sReason + ")");
                return true;
            }
            if (sScreenLock == null || !interactive()) {
                Log.w(TAG, "enter (" + reason + "): " + (sScreenLock == null ? "no wakelock" : "display already off")
                        + ", normal sleep instead");
                return false;
            }
            // user activity FIRST: with the summary BRIGHT, any end of a running dream before our own
            // stopDream (the dream finishing itself, WindowManager ending it for StandbyActivity)
            // wakes instead of dozing; a SCREEN_BRIGHT wakelock does not count while DREAMING
            // (PowerManagerService.adjustWakeLockSummary), and a doze is a display power cycle
            userActivity();
            sState = ENTERING;
            sRestorePending = false;
            sReason = reason;
            sPoweringOff = false;
            sShutdownTries = 0;
            sEnteredAt = SystemClock.uptimeMillis();
            sVendorMotionEvents = 0;
            sWatchTicks = 0;
            sRelights.clear();
            sRefronts.clear();
            sStrRequested = false;
            sUsbDropTried = false;
            sLampOffConfirmed = false;
            clearBlocked();
            Log.i(TAG, "standby: enter (" + reason + ")" + (allowStr() ? "" : " [allow_str=0: v6.5 standby window]"));
            acquire(sScreenLock);
            acquire(sHoldLock);
            motionTriggersOff();
            try { OverlayHost.get(sApp).dismissAll(false); } catch (Throwable t) { Log.w(TAG, "dismiss: " + t); }
            try { Notify.hide(); } catch (Throwable t) { Log.w(TAG, "notify: " + t); }
            // curtain black and the black activity first; the dream window goes only after they
            // had time to draw (the dream's lamp dim is restored before 195(false))
            try { PowerUi.onStandbyEnter(sApp); } catch (Throwable t) { Log.w(TAG, "PowerUi.onStandbyEnter: " + t); }
            StandbyActivity.show(sApp);
            Ui.main().removeCallbacks(sStopDreamRun);
            Ui.main().postDelayed(sStopDreamRun, STOP_DREAM_DELAY_MS);
            pauseMedia("enter");
            muteMusic();
            requestFocus();
            Ui.main().postDelayed(sRepauseRun, REPAUSE_MS);
            registerHomeWatch();
            registerVolumeWatch();
            writeState(ST_STANDBY, -1, reason);
            sLampGen = LampControl.lampOff("standby", () -> sState != IDLE,
                    (ok, detail) -> Ui.main().post(() -> onLampOffResult(ok, detail)));
            // review 6.5: the deadline and the lamp give-up do not wait for any HAL result (a gmpf
            // call blocking on z9x-hal must never keep the projector up with the lamp maybe lit).
            // Lumen OS 1.0: the power-off deadline exists only with the kill switch allow_str=0.
            long restore = sRestoreDeadlineWall;
            sRestoreDeadlineWall = -1;
            if (!allowStr()) {
                if (restore > 0) {
                    setDeadline(SystemClock.uptimeMillis() + Math.max(0, restore - System.currentTimeMillis()), "standby (restored)");
                } else {
                    armDeadline(PowerPolicy.quickWakeMinutesNow(sApp), "standby", false);
                }
            }
            Ui.main().removeCallbacks(sLampGiveUpRun);
            Ui.main().postDelayed(sLampGiveUpRun, LampControl.OFF_GIVE_UP_MS + LAMP_GIVE_UP_EXTRA_MS);
            Ui.main().removeCallbacks(sStrLampWaitRun);
            Ui.main().postDelayed(sStrLampWaitRun, STR_LAMP_WAIT_MS);
            broadcast(true);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "enter failed", t);
            if (sState != IDLE) exit("enter failed");
            return false;
        }
    }

    /** z9x thread hop of a failed enter: the Android sleep, like the system POWER key. */
    private static void fallbackSleep(String reason) {
        try {
            if (sPm != null && sPm.isInteractive()) {
                Log.w(TAG, "standby not possible (" + reason + "): Android sleep");
                sPm.goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
            }
        } catch (Throwable t) {
            Log.e(TAG, "goToSleep", t);
        }
    }

    private static void onLampOffResult(boolean ok, String detail) {
        if (sState == IDLE) return;
        if (!ok) {
            powerOffNow("lamp still on " + LampControl.OFF_GIVE_UP_MS / 1000 + " s after standby started (" + detail + ")");
            return;
        }
        sLampOffConfirmed = true;
        if (sState == STANDBY) return;                      // a HAL reconnect confirmed it again
        sState = STANDBY;
        Ui.main().removeCallbacks(sLampGiveUpRun);
        Ui.main().removeCallbacks(sStrLampWaitRun);
        Log.i(TAG, "standby: lamp off (" + detail + ") after " + (SystemClock.uptimeMillis() - sEnteredAt) + " ms");
        evaluateStr("lamp off");                            // Lumen OS 1.0: STR now unless blocked
        if (sStrRequested || sUpdateReboot || sState != STANDBY) return;
        // STR blocked: the v6.5 lamp-only standby with its watchdog
        Ui.main().removeCallbacks(sWatchRun);
        Ui.main().postDelayed(sWatchRun, WATCH_MS);        // a deadline (allow_str=0) was armed at enter
        registerThermal();
        // Android stays awake: the framework sends no CEC <Standby>, the cec module does (tv_send_standby_on_sleep)
        org.z9x.projector.cec.CecPolicy.onLampOnlyStandby(sApp, sReason);
    }

    /**
     * Main thread, {@value #STR_LAMP_WAIT_MS} ms after enter: the lamp-off is still not confirmed (a slow
     * motor stop, a busy HAL). With STR allowed Android sleeps anyway: the vendor suspend (gmpf_suspend)
     * powers the lamp down with everything else. Blocked: the 65 s give-up below decides.
     */
    private static void onStrLampWait() {
        if (sState != ENTERING || sStrRequested || sUpdateReboot || sPoweringOff) return;
        String b = StrGate.blocker(sApp);
        if (b != null) return;
        Log.w(TAG, "standby: lamp-off not confirmed " + STR_LAMP_WAIT_MS / 1000 + " s after enter (pending="
                + LampControl.isOffPending() + "): STR anyway, the vendor suspend powers the lamp down");
        sState = STANDBY;
        goDark("lamp-off not confirmed");
    }

    // =================================================================== STR gate (Lumen OS 1.0)

    /** Main thread, STANDBY: STR now unless a blocker holds (then lamp-only standby until it clears). */
    private static void evaluateStr(String why) {
        if (sState != STANDBY || sStrRequested || sUpdateReboot || sPoweringOff) return;
        String b = StrGate.blocker(sApp);
        if (StrGate.isUsb(b) && StrGate.usbDropEnabled() && !sUsbDropTried) {
            sUsbDropTried = true;
            StrGate.dropUsb("standby: " + b);
            Ui.main().removeCallbacks(sGateRecheckRun);
            Ui.main().postDelayed(sGateRecheckRun, USB_DROP_SETTLE_MS);
            return;
        }
        if (b == null) {
            goDark(why);
            return;
        }
        noteBlocked(b);
    }

    /**
     * Display off (PowerPolicy at SCREEN_OFF, a process restart while asleep, the gate re-check): keep
     * the CPU awake ("z9x:quickwake") while a blocker holds, else let it suspend. Main thread.
     */
    static void onSleepGate(String why) {
        if (sApp == null || interactive()) return;
        String b = StrGate.blocker(sApp);
        if (StrGate.isUsb(b) && StrGate.usbDropEnabled() && !sUsbDropTried) {
            sUsbDropTried = true;
            acquire(sHoldLock);
            StrGate.dropUsb("sleep: " + b);
            Ui.main().removeCallbacks(sGateRecheckRun);
            Ui.main().postDelayed(sGateRecheckRun, USB_DROP_SETTLE_MS);
            return;
        }
        if (b != null) {
            acquire(sHoldLock);
            noteBlocked(b);
            return;
        }
        if (sUpdateReboot) return;
        if (OtaStateReceiver.consumeRebootWhenOff(sApp)) {
            rebootForUpdate();
            return;
        }
        boolean held = sHoldLock != null && sHoldLock.isHeld();
        clearBlocked();
        release(sHoldLock);
        Log.i(TAG, "sleep: STR allowed (" + why + ")" + (held ? ", wakelock released" : ""));
    }

    /**
     * Something the gate depends on changed (USB_STATE, the updater, the periodic re-check). Main thread
     * (OtaStateReceiver posts here).
     */
    static void onGateChanged(Context ctx, String why) {
        if (sApp == null) {
            if (ctx == null) return;
            install(ctx);
        }
        Ui.main().removeCallbacks(sGateRecheckRun);
        if (sState == STANDBY) {
            evaluateStr(why);
        } else if (sState == IDLE && !interactive()) {
            onSleepGate(why);
        } else if (sState == IDLE) {
            clearBlocked();
        }
    }

    private static void noteBlocked(String b) {
        if (!b.equals(sBlockedWhy)) {
            Log.i(TAG, "STR blocked (" + b + "): " + (sState != IDLE ? "lamp-only standby" : "display off, CPU kept awake")
                    + " until it clears");
        }
        sBlockedWhy = b;
        registerGateWatch();
        Ui.main().removeCallbacks(sGateRecheckRun);
        Ui.main().postDelayed(sGateRecheckRun, GATE_RECHECK_MS);
    }

    private static void clearBlocked() {
        if (sBlockedWhy != null) Log.i(TAG, "STR no longer blocked (was: " + sBlockedWhy + ")");
        sBlockedWhy = null;
        unregisterGateWatch();
        Ui.main().removeCallbacks(sGateRecheckRun);
    }

    /** The current STR blocker of this standby / sleep, null = none (diagnostics). */
    public static String strBlocker() {
        return sBlockedWhy;
    }

    /**
     * STR allowed (main thread, STANDBY): reboot for a pending update ("restart when turned off"), else
     * Android sleep now; the SoC suspends right after (no wakelock of ours is left once the display is off).
     */
    private static void goDark(String why) {
        clearBlocked();
        if (sUpdateReboot) return;
        if (OtaStateReceiver.consumeRebootWhenOff(sApp)) {
            rebootForUpdate();
            return;
        }
        if (sState == IDLE || sStrRequested || sPoweringOff) return;   // a power-off (boot-dark) wins
        if (!interactive()) return;                         // already asleep: SCREEN_OFF does the rest
        sStrRequested = true;
        Log.i(TAG, "STR: lamp off, Android sleep now (" + sReason + "; " + why + ")");
        writeState(ST_SLEEP, -1, "str: " + sReason);
        try {
            sPm.goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
        } catch (Throwable t) {
            Log.e(TAG, "goToSleep failed: lamp-only standby, retried", t);
            sStrRequested = false;
            writeState(ST_STANDBY, -1, sReason);
            noteBlocked("goToSleep failed");
            return;
        }
        Ui.main().removeCallbacks(sSleepCheckRun);
        Ui.main().postDelayed(sSleepCheckRun, SLEEP_CHECK_MS);
    }

    /** Main thread, SLEEP_CHECK_MS after our goToSleep: still awake -> lamp-only standby, retried. */
    private static void onSleepCheck() {
        if (!sStrRequested || sState == IDLE || !interactive()) return;
        sStrRequested = false;
        Log.w(TAG, "still awake " + SLEEP_CHECK_MS / 1000 + " s after goToSleep: lamp-only standby, STR retried");
        writeState(ST_STANDBY, -1, sReason);
        noteBlocked("Android did not go to sleep");
        if (sState == STANDBY) {
            Ui.main().removeCallbacks(sWatchRun);
            Ui.main().postDelayed(sWatchRun, WATCH_MS);
            registerThermal();
            org.z9x.projector.cec.CecPolicy.onLampOnlyStandby(sApp, sReason);
        }
    }

    /**
     * "Restart when I turn the projector off" (OtaStateReceiver): the reboot replaces this STR; the
     * next boot goes dark at once (K_DARK_NEXT, {@link #bootDarkCheck}). The reason "reboot,z9x-ota" is an
     * orderly init reason, so the boot-dark check never treats it as an unattended reset.
     */
    private static void rebootForUpdate() {
        if (sUpdateReboot) return;
        sUpdateReboot = true;
        clearBlocked();
        Log.w(TAG, "update ready: restarting instead of STR (the next boot stays dark)");
        sWorker.post(() -> {
            try {
                prefs().edit().putBoolean(K_DARK_NEXT, true).commit();
                markOrderly("update restart");
                sPm.reboot("z9x-ota");                      // REBOOT (platform signature); blocks
            } catch (Throwable t) {
                Log.e(TAG, "update restart failed: STR instead", t);
                try { prefs().edit().putBoolean(K_DARK_NEXT, false).commit(); } catch (Throwable ignored) { }
                Ui.main().post(() -> {
                    sUpdateReboot = false;
                    onGateChanged(sApp, "update restart failed");
                });
            }
        });
    }

    /** USB_STATE while a blocker holds: the cable or the host may be gone. Main thread. */
    private static void registerGateWatch() {
        if (sGateWatch != null || sApp == null) return;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    if (sGateWatch != this) return;
                    onGateChanged(sApp, "usb state");
                } catch (Throwable t) {
                    Log.w(TAG, "gate watch: " + t);
                }
            }
        };
        try {
            sApp.registerReceiver(r, StrGate.usbStateFilter(), Context.RECEIVER_EXPORTED);
            sGateWatch = r;
        } catch (Throwable t) {
            Log.w(TAG, "gate watch register: " + t);
        }
    }

    private static void unregisterGateWatch() {
        BroadcastReceiver r = sGateWatch;
        sGateWatch = null;
        if (r != null) {
            try { sApp.unregisterReceiver(r); } catch (Throwable t) { Log.w(TAG, "gate watch unregister: " + t); }
        }
    }

    /** Foreign sleep with STR allowed: the suspend waits for our lamp-off (at most SLEEP_PREP_MS). */
    static void acquireSleepPrep() {
        try { if (sPrepLock != null) sPrepLock.acquire(SLEEP_PREP_MS); } catch (Throwable t) { Log.w(TAG, "sleepprep: " + t); }
    }

    static void releaseSleepPrep() {
        release(sPrepLock);
    }

    /**
     * Main thread, {@link LampControl#OFF_GIVE_UP_MS} + {@value #LAMP_GIVE_UP_EXTRA_MS} ms after enter:
     * the lamp-off is still not confirmed (a HAL call blocked, the z9x-hal queue wedged, or no result
     * at all). The lamp may be lit behind a "sleeping" projector: power off. powerOffNow runs on
     * z9x-standby, never on z9x-hal.
     */
    private static void onStandbyLampGiveUp() {
        if (sState != ENTERING) return;
        if (StrGate.blocker(sApp) == null && !sStrRequested && !sPoweringOff) {
            sState = STANDBY;
            goDark("lamp-off not confirmed after " + (SystemClock.uptimeMillis() - sEnteredAt) / 1000 + " s");
            return;
        }
        powerOffNow("lamp-off not confirmed " + (SystemClock.uptimeMillis() - sEnteredAt) / 1000
                + " s after standby started (HAL blocked or not answering; pending=" + LampControl.isOffPending() + ")");
    }

    /**
     * PowerPolicy.onScreenOff / resumeSleep (main thread): the same give-up for a foreign sleep, armed
     * when its lamp-off starts. Fires only while the display is still off and that lamp-off is still
     * pending (a SCREEN_ON starts a lamp-on, which ends the pending lamp-off).
     */
    static void armSleepGiveUp() {
        Ui.main().removeCallbacks(sSleepGiveUpRun);
        Ui.main().postDelayed(sSleepGiveUpRun, LampControl.OFF_GIVE_UP_MS + LAMP_GIVE_UP_EXTRA_MS);
    }

    private static void onSleepLampGiveUp() {
        if (interactive() || !LampControl.isOffPending()) return;
        if (StrGate.blocker(sApp) == null) return;          // STR: the vendor suspend powers the lamp down
        powerOffNow("lamp-off not confirmed " + (LampControl.OFF_GIVE_UP_MS + LAMP_GIVE_UP_EXTRA_MS) / 1000
                + " s after the display went off (HAL blocked or not answering)");
    }

    /**
     * Leaves standby: lamp on, picture back. Also ends a standby that is only pending its re-enter
     * after a process restart (the lamp is still off from the old process). Main thread (re-posted
     * otherwise).
     */
    public static void exit(String why) {
        if (!Ui.main().isCurrentThread()) {
            Ui.main().post(() -> exit(why));
            return;
        }
        if (sState == IDLE && !sRestorePending) return;
        if ((sStrRequested && interactive()) || sUpdateReboot) {
            // goToSleep (or the update restart) is already under way: 195(true) now would only flash
            // the lamp before the display goes off; the next press wakes from STR
            Log.i(TAG, "standby: wake (" + why + ") ignored: " + (sUpdateReboot ? "update restart" : "STR sleep")
                    + " already in progress");
            return;
        }
        if (shutdownInFlight()) {
            // review 6.5: 195(true) now would light the lamp while ShutdownThread powers off anyway
            Log.i(TAG, "standby: wake (" + why + ") ignored: power-off in progress ("
                    + (SystemClock.uptimeMillis() - sShutdownAt) + " ms since the shutdown request)");
            return;
        }
        if (sState == IDLE) {
            Log.i(TAG, "standby: exit (" + why + ") before the restart re-enter ran");
        } else {
            Log.i(TAG, "standby: exit (" + why + ") after " + sReason
                    + (sVendorMotionEvents > 0 ? "; vendor AK/AF events during standby: " + sVendorMotionEvents : ""));
        }
        sState = IDLE;
        sRestorePending = false;
        sRestoreDeadlineWall = -1;
        sBootDark = false;                                  // a wake is user activity
        sShutdownTries = 0;
        sStrRequested = false;
        sUsbDropTried = false;
        cancelDeadline();
        removeStandbyCallbacks();
        clearBlocked();
        StrGate.restoreUsb("standby exit");
        unregisterHomeWatch();
        unregisterVolumeWatch();
        unregisterThermal();
        try { PowerUi.onStandbyExit(sApp); } catch (Throwable t) { Log.w(TAG, "PowerUi.onStandbyExit: " + t); }
        LampControl.lampOn("standby exit", () -> true, 0, () -> sState == IDLE);
        StandbyActivity.finishIfShown();
        abandonFocus();
        unmuteMusic("standby exit");
        restoreMotionTriggers("standby exit", 0);
        writeState(ST_ACTIVE, -1, why);
        release(sHoldLock);
        userActivity();
        release(sScreenLock);                               // ON_AFTER_RELEASE: idle timer restarts
        broadcast(false);
        org.z9x.projector.cec.CecPolicy.onStandbyExit(sApp, why);   // soundbar back on (CEC)
    }

    // =================================================================== screen hooks (PowerPolicy)

    /**
     * SCREEN_OFF (main thread, before PowerPolicy's own lamp-off): our STR sleep or a foreign sleep during
     * standby. Hand over: activity, focus, the screen lock and the watchdog go; the mute stays until
     * SCREEN_ON; a deadline (allow_str=0) is kept. Returns true when this standby had already confirmed
     * the lamp off (PowerPolicy then sends nothing more to the HAL before the suspend).
     */
    static boolean onScreenOff() {
        Ui.main().removeCallbacks(sSleepCheckRun);
        boolean ours = sStrRequested;
        sStrRequested = false;
        if (sState == IDLE) return false;
        boolean lampOff = sLampOffConfirmed;
        Log.i(TAG, "display off " + (ours ? "for STR" : "during standby") + " (" + sReason + ")"
                + (lampOff ? ", lamp already off" : ", lamp-off not confirmed yet: the sleep path takes over"));
        sState = IDLE;
        sRestorePending = false;
        removeStandbyCallbacks();
        unregisterHomeWatch();
        unregisterVolumeWatch();
        unregisterThermal();
        clearBlocked();                                     // PowerPolicy re-evaluates for the sleep
        StandbyActivity.finishIfShown();
        abandonFocus();
        release(sScreenLock);
        broadcast(false);
        // the motion triggers stay off while the display is off (a bump must not run AK / AF on a dark
        // projector); they are restored at SCREEN_ON (onScreenOn)
        return lampOff;
    }

    /** SCREEN_ON (main thread): any deadline ends, the wakelocks go, a dropped USB gadget comes back. */
    static void onScreenOn() {
        sShutdownTries = 0;
        sStrRequested = false;
        sUsbDropTried = false;
        cancelDeadline();
        Ui.main().removeCallbacks(sSleepGiveUpRun);
        Ui.main().removeCallbacks(sSleepCheckRun);
        release(sPrepLock);
        StrGate.restoreUsb("screen on");
        if (sState == IDLE) {
            clearBlocked();
            release(sHoldLock);
            writeState(ST_ACTIVE, -1, "screen on");
            unmuteMusic("screen on");                       // muted by the standby before the sleep
            restoreMotionTriggers("screen on", 0);          // switched off by that standby
        }
    }

    // =================================================================== deadline

    /**
     * Arms the power-off deadline (minutes: 0 = now, PowerPolicy.QUICK_WAKE_ALWAYS = never).
     * keepEarlier: an already armed deadline stays when it is earlier. Main thread.
     */
    static void armDeadline(int minutes, String why, boolean keepEarlier) {
        if (minutes == PowerPolicy.QUICK_WAKE_ALWAYS) {
            Log.i(TAG, "deadline: never (" + why + "); wakelock held until the projector is used again");
            if (!keepEarlier) cancelDeadline();
            writeState(sState != IDLE ? ST_STANDBY : ST_SLEEP, keepEarlier && sDeadlineUptime >= 0
                    ? wallOf(sDeadlineUptime) : -1, why);
            return;
        }
        long at = SystemClock.uptimeMillis() + Math.max(0, minutes) * 60_000L;
        if (keepEarlier && sDeadlineUptime >= 0 && sDeadlineUptime <= at) {
            Log.i(TAG, "deadline kept (" + sDeadlineWhy + ", in " + (sDeadlineUptime - SystemClock.uptimeMillis()) / 1000
                    + " s); not re-armed for " + why);
            writeState(sState != IDLE ? ST_STANDBY : ST_SLEEP, wallOf(sDeadlineUptime), why);
            return;
        }
        setDeadline(at, why);
    }

    private static void setDeadline(long uptimeAt, String why) {
        Ui.main().removeCallbacks(sDeadlineRun);
        sDeadlineUptime = uptimeAt;
        sDeadlineWhy = why;
        long in = Math.max(0, uptimeAt - SystemClock.uptimeMillis());
        Log.i(TAG, "deadline in " + in / 1000 + " s (" + why + "): power off (allow_str=0)");
        Ui.main().postAtTime(sDeadlineRun, uptimeAt);
        writeState(sState != IDLE ? ST_STANDBY : ST_SLEEP, wallOf(uptimeAt), why);
    }

    static void cancelDeadline() {
        if (sDeadlineUptime >= 0) Log.i(TAG, "deadline canceled (" + sDeadlineWhy + ")");
        Ui.main().removeCallbacks(sDeadlineRun);
        sDeadlineUptime = -1;
    }

    private static long wallOf(long uptime) {
        return System.currentTimeMillis() + (uptime - SystemClock.uptimeMillis());
    }

    private static void onDeadline() {
        sDeadlineUptime = -1;
        if (allowStr()) {
            // the kill switch was lifted while a v6.5 window ran: STR instead of the power-off
            Log.i(TAG, "deadline (" + sDeadlineWhy + "): allow_str is on again, STR gate instead of a power-off");
            onGateChanged(sApp, "deadline");
            return;
        }
        if (sState == IDLE && interactive()) {
            Log.i(TAG, "deadline (" + sDeadlineWhy + ") while in use: ignored");
            return;
        }
        if (LampControl.isOffPending() && (sState != IDLE || !interactive())) {
            // never power off before the lamp-off result; the give-up timer handles a stuck lamp-off
            setDeadline(SystemClock.uptimeMillis() + 5_000, sDeadlineWhy);
            return;
        }
        powerOffNow("standby window over (" + sDeadlineWhy + ", allow_str=0)");
    }

    // =================================================================== power off

    /** Real power-off (ShutdownThread, reason userrequested -> z9x_poweroff.sh stops the PM watchdog). Main thread. */
    static void powerOffNow(final String why) {
        if (!Ui.main().isCurrentThread()) {
            Ui.main().post(() -> powerOffNow(why));
            return;
        }
        if (sPoweringOff) return;
        sPoweringOff = true;
        Log.w(TAG, "POWER OFF: " + why);
        cancelDeadline();
        Ui.main().removeCallbacks(sWatchRun);
        Ui.main().removeCallbacks(sLampGiveUpRun);
        Ui.main().removeCallbacks(sSleepGiveUpRun);
        Ui.main().removeCallbacks(sBootDarkRetryRun);
        try { SleepTimer.cancel(sApp, false); } catch (Throwable t) { Log.w(TAG, "timer: " + t); }
        sWorker.post(() -> {
            commitPowerOff(why);                            // before the shutdown request
            final long at = SystemClock.uptimeMillis();
            try {
                sShutdownAt = at;
                sShutdownInFlight = true;
                // REBOOT permission (signature|privileged, platform signature); wait=false: returns
                // once ShutdownThread has the request
                sPm.shutdown(false, PowerManager.SHUTDOWN_USER_REQUESTED, false);
                Log.i(TAG, "shutdown requested");
                Ui.main().postDelayed(() -> {
                    if (sShutdownInFlight && sShutdownAt == at) {
                        onShutdownFailed(why, "no shutdown " + SHUTDOWN_STUCK_MS / 1000 + " s after the request");
                    }
                }, SHUTDOWN_STUCK_MS);
            } catch (Throwable t) {
                sShutdownInFlight = false;
                Log.e(TAG, "shutdown failed", t);
                Ui.main().postDelayed(() -> onShutdownFailed(why, String.valueOf(t)), SHUTDOWN_RETRY_MS);
            }
        });
    }

    /** Our power-off was requested without an error less than SHUTDOWN_STUCK_MS ago. Any thread. */
    public static boolean shutdownInFlight() {
        return sShutdownInFlight && SystemClock.uptimeMillis() - sShutdownAt < SHUTDOWN_STUCK_MS;
    }

    /**
     * Main thread: a power-off did not happen. Retried while the projector is still dark and ours
     * (standby, a sleep) or the boot-dark power-off has seen no user activity yet: {@value
     * #SHUTDOWN_FAST_TRIES} times right away (the caller waited {@value #SHUTDOWN_RETRY_MS} ms), then
     * every {@value #SHUTDOWN_LATE_RETRY_MS} ms (review 6.5: never "give up" and leave the display
     * chain and the screen wakelock on until a key press).
     */
    private static void onShutdownFailed(String why, String detail) {
        sPoweringOff = false;
        sShutdownInFlight = false;
        boolean dark = sState != IDLE || !interactive() || sBootDark;
        Log.w(TAG, "power-off did not happen (" + detail + "): " + why);
        if (!dark) {
            Log.w(TAG, "shutdown not retried: the projector is in use again");
            sShutdownTries = 0;
            writeState(interactive() ? ST_ACTIVE : ST_SLEEP, -1, "shutdown failed");
            return;
        }
        String base = why.replaceAll(" \\((retry|late retry) [0-9]+\\)$", "");
        if (++sShutdownTries <= SHUTDOWN_FAST_TRIES) {
            powerOffNow(base + " (retry " + sShutdownTries + ")");
            return;
        }
        Log.w(TAG, "shutdown failed " + sShutdownTries + " times: next try in " + SHUTDOWN_LATE_RETRY_MS / 60_000
                + " min while the projector stays dark");
        writeState(sState != IDLE ? ST_STANDBY : interactive() ? ST_ACTIVE : ST_SLEEP, -1, "shutdown failed");
        if (sState != IDLE || !interactive()) {
            setDeadline(SystemClock.uptimeMillis() + SHUTDOWN_LATE_RETRY_MS, "power-off retry after " + sShutdownTries + " failures");
        } else {                                            // boot-dark without standby: until a key
            Ui.main().removeCallbacks(sBootDarkRetryRun);
            Ui.main().postDelayed(sBootDarkRetryRun, SHUTDOWN_LATE_RETRY_MS);
        }
    }

    // =================================================================== lamp watchdog

    /** Main thread, every 15 s while STANDBY: 196 true -> 195(false); too many relights -> power off. */
    private static void watchLamp() {
        if (sState != STANDBY) return;
        recheckLamp("standby watchdog");
        refront("watchdog");                                // a covering app never keeps the front
        if (++sWatchTicks % TEMP_LOG_EVERY == 1) logTemperatures();
        Ui.main().postDelayed(sWatchRun, WATCH_MS);
    }

    /**
     * Read-only thermal log of the display-on, lamp-off standby (review 6.5: its thermal behaviour was
     * never measured): thermal zones 0/1 (sysfs, on z9x-standby) and IGmpf 438 getSystemTemperature
     * (z9x-hal), once a minute, plus PowerManager's thermal status.
     */
    private static void logTemperatures() {
        final long mins = (SystemClock.uptimeMillis() - sEnteredAt) / 60_000;
        sWorker.post(() -> {
            StringBuilder sb = new StringBuilder("standby temperatures after " + mins + " min:");
            for (String z : THERMAL_ZONES) {
                try (BufferedReader r = new BufferedReader(new FileReader(z))) {
                    String s = r.readLine();
                    sb.append(' ').append(z.substring(z.indexOf("zone") + 4, z.lastIndexOf('/'))).append('=')
                            .append(s == null ? "?" : s.trim());
                } catch (Throwable t) {
                    sb.append(" zone?=err");
                }
            }
            try { sb.append(" thermalStatus=").append(sPm.getCurrentThermalStatus()); } catch (Throwable t) { sb.append(" thermalStatus=?"); }
            final String zones = sb.toString();
            boolean queued = Hal.run((g, g2) -> {
                String sys;
                try { sys = String.valueOf(g.getSystemTemperature()); } catch (Throwable t) { sys = "err"; }
                Log.i(TAG, zones + " system(438)=" + sys);
            });
            if (!queued) Log.i(TAG, zones + " system(438)=HAL not available");
        });
    }

    /** STANDBY (main thread): THERMAL_STATUS_SEVERE or worse -> power off (review 6.5). */
    private static void registerThermal() {
        if (sThermal != null || sPm == null) return;
        PowerManager.OnThermalStatusChangedListener l = status -> {
            if (sThermal == null || sState == IDLE) return;
            Log.i(TAG, "standby: thermal status " + status);
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                powerOffNow("thermal status " + status + " during standby");
            }
        };
        try {
            sThermal = l;
            sPm.addThermalStatusListener(r -> Ui.main().post(r), l);
        } catch (Throwable t) {
            sThermal = null;
            Log.w(TAG, "thermal listener: " + t);
        }
    }

    private static void unregisterThermal() {
        PowerManager.OnThermalStatusChangedListener l = sThermal;
        sThermal = null;
        if (l == null || sPm == null) return;
        try { sPm.removeThermalStatusListener(l); } catch (Throwable t) { Log.w(TAG, "thermal listener remove: " + t); }
    }

    private static void recheckLamp(String why) {
        boolean queued = LampControl.recheckOff(sLampGen, why, relit -> {
            if (relit) Ui.main().post(StandbyController::onRelit);
        });
        if (!queued) Log.w(TAG, "lamp watchdog (" + why + "): HAL not available");
    }

    /**
     * PowerUi (main thread): the vendor started auto keystone / autofocus (focus events 105-118, 120,
     * 333-335, 1003/1004) during standby although the motion triggers are off (another vendor path).
     * Counted and logged, and the lamp is re-checked at once and again {@link #AK_RECHECK_MS} ms later
     * (a vendor AK may light the lamp for its pattern only after its first event; the 15 s watchdog
     * alone would let it shine up to 15 s). Each new event restarts that series.
     */
    public static void onVendorMotionEvent(int type) {
        if (sState == IDLE) return;
        sVendorMotionEvents++;
        Log.w(TAG, "vendor AK/AF focus event " + type + " during standby (#" + sVendorMotionEvents + ")");
        recheckSeries("vendor AK/AF " + type);
    }

    /**
     * PowerUi (main thread): an eye-protection focus event (500 / 501 / 601 / 608) during standby. The
     * vendor's own restore path (setHumanDetectScreenOnOff(1), the ToF HumDet thread keeps running in
     * standby) may relight the lamp: re-check at once and {@link #AK_RECHECK_MS} ms later.
     */
    public static void onVendorLampEvent(int type) {
        if (sState == IDLE) return;
        sVendorMotionEvents++;
        Log.w(TAG, "vendor eye-protection event " + type + " during standby (#" + sVendorMotionEvents + ")");
        recheckSeries("vendor eye " + type);
    }

    /**
     * Main thread: 196 re-checked now and {@link #AK_RECHECK_MS} ms later (each new trigger restarts the
     * series), so a vendor path that lights the lamp is caught within 0.5 s instead of the 15 s
     * watchdog. ENTERING: nothing (LampControl's own lamp-off still runs).
     */
    private static void recheckSeries(final String why) {
        if (sState != STANDBY) return;
        recheckLamp(why);
        Ui.main().removeCallbacksAndMessages(AK_RECHECK_TOKEN);
        long now = SystemClock.uptimeMillis();
        for (final long d : AK_RECHECK_MS) {
            Ui.main().postAtTime(() -> {
                if (sState == STANDBY) recheckLamp(why + " +" + d + " ms");
            }, AK_RECHECK_TOKEN, now + d);
        }
    }

    private static void onRelit() {
        if (sState == IDLE) return;
        long now = SystemClock.uptimeMillis();
        sRelights.addLast(now);
        while (!sRelights.isEmpty() && now - sRelights.peekFirst() > 3_600_000L) sRelights.removeFirst();
        Log.w(TAG, "lamp watchdog: lamp was on again in standby (" + sRelights.size() + " in the last hour)");
        if (sRelights.size() > RELIGHT_MAX_PER_HOUR) powerOffNow("lamp relit " + sRelights.size() + " times within an hour");
    }

    // =================================================================== StandbyActivity hooks

    /** StandbyActivity left the front without a wake (another activity covered it). Main thread. */
    static void onActivityCovered() {
        if (sState == IDLE) return;
        // not at once: the covering app's sound is already handled by the focus loss + mute; the lamp
        // is re-checked at once (a covering vendor / HDMI window may come with a lamp-on)
        recheckSeries("standby screen covered");
        Ui.main().postDelayed(() -> refront("covered"), COVER_SETTLE_MS);
    }

    /** Brings StandbyActivity back on top, at most REFRONT_MAX_PER_MIN times a minute. Main thread. */
    private static void refront(String why) {
        if (sState == IDLE || StandbyActivity.isShownOnTop()) return;
        long now = SystemClock.uptimeMillis();
        while (!sRefronts.isEmpty() && now - sRefronts.peekFirst() > 60_000L) sRefronts.removeFirst();
        if (sRefronts.size() >= REFRONT_MAX_PER_MIN) {
            Log.w(TAG, "standby screen covered (" + why + "): re-front budget used up this minute, next try "
                    + "from the watchdog (lamp off, sound paused + muted)");
            return;
        }
        sRefronts.addLast(now);
        Log.i(TAG, "standby screen covered (" + why + "): bringing it back to the front");
        pauseMedia("covered");
        StandbyActivity.show(sApp);
        recheckSeries("re-front (" + why + ")");
    }

    // =================================================================== HDMI events (HdmiEventReceiver; never a wake)

    /**
     * tvinput's HDMI_EVENT (main thread). NEVER a wake (review 6.5 blocker, class javadoc "Exit"):
     * tvinput opens its viewer on its own for a cable plug, an HPD / +5V re-assert or any CEC
     * &lt;Active Source&gt;, with no user action. "session" active=true during standby: logged, media
     * paused (its sound is also held by the mute and our audio focus) and the black standby screen
     * brought back over the viewer; the lamp stays off.
     */
    public static void onHdmiEvent(int port, boolean active, String reason) {
        if (!isActive() || !active || !"session".equals(reason)) return;
        long since = SystemClock.uptimeMillis() - sEnteredAt;
        Log.w(TAG, "HDMI " + port + " session started during standby (" + since + " ms after enter): "
                + "not a wake (no user action), lamp stays off");
        if (sState == IDLE) return;                         // restore pending: the re-enter covers it
        pauseMedia("HDMI session");
        recheckSeries("HDMI " + port + " session");
        refront("HDMI " + port + " session");
    }

    /** PowerUi (main thread): any global key = user activity (stops a boot-dark shutdown retry). */
    public static void noteUserActivity() {
        if (sBootDark) Log.i(TAG, "user activity: boot-dark power-off retry dropped");
        sBootDark = false;
        try { org.z9x.projector.cec.CecPolicy.onUserKey(); } catch (Throwable t) { Log.w(TAG, "cec user key: " + t); }
    }

    // =================================================================== helpers

    private static void removeStandbyCallbacks() {
        Ui.main().removeCallbacks(sStrLampWaitRun);
        Ui.main().removeCallbacks(sSleepCheckRun);
        Ui.main().removeCallbacksAndMessages(AK_RECHECK_TOKEN);
        Ui.main().removeCallbacks(sWatchRun);
        Ui.main().removeCallbacks(sRepauseRun);
        Ui.main().removeCallbacks(sRefocusRun);
        Ui.main().removeCallbacks(sStopDreamRun);
        Ui.main().removeCallbacks(sLampGiveUpRun);
    }

    /**
     * Standby enter: STREAM_MUSIC muted unless it already is (then it is not ours to unmute). With an
     * HDMI ARC / eARC output the MASTER mute instead (review 6.5): AudioService forwards a STREAM_MUSIC
     * mute adjustment to the audio system over CEC as a mute TOGGLE (same rule as Z9xAudioFixer), which
     * could leave a soundbar muted or unmute one the user muted, while setMasterMute sends no CEC at
     * all (AudioService.setMasterMuteInternalNoCallerCheck: AudioSystem + a broadcast only; live
     * mUseFixedVolume=false, so it applies) and silences every AudioFlinger output, ARC included.
     */
    private static void muteMusic() {
        if (sAm == null) return;
        try {
            for (AudioDeviceInfo d : sAm.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int type = d.getType();
                if (type == AudioDeviceInfo.TYPE_HDMI_ARC || type == AudioDeviceInfo.TYPE_HDMI_EARC) {
                    masterMute();
                    return;
                }
            }
            if (!sMutedByUs && sAm.isStreamMute(AudioManager.STREAM_MUSIC)) {
                Log.i(TAG, "music already muted (not by standby): left as it is");
                return;
            }
            // ours already (process restart in standby): mute again in case something unmuted it
            sAm.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            if (!sMutedByUs) {
                sMutedByUs = true;
                writeMuted(true);
            }
            Log.i(TAG, "music muted for standby");
        } catch (Throwable t) {
            Log.w(TAG, "mute: " + t);
        }
    }

    /** HDMI ARC / eARC: the master mute (no CEC), unless it is already set by someone else. Main thread. */
    private static void masterMute() {
        if (!sMasterMutedByUs && sAm.isMasterMute()) {
            Log.i(TAG, "HDMI ARC/eARC output: master mute already set (not by standby): left as it is");
            return;
        }
        sAm.setMasterMute(true, 0);
        if (!sMasterMutedByUs) {
            sMasterMutedByUs = true;
            writeMasterMuted(true);
        }
        Log.i(TAG, "HDMI ARC/eARC output: master mute for standby (no CEC toggle)");
    }

    /** Undoes the STREAM_MUSIC mute / master mute if standby set it. Main thread. */
    private static void unmuteMusic(String why) {
        if (sMasterMutedByUs) {
            sMasterMutedByUs = false;
            writeMasterMuted(false);
            try {
                if (sAm != null) sAm.setMasterMute(false, 0);
                Log.i(TAG, "master mute off (" + why + ")");
            } catch (Throwable t) {
                Log.w(TAG, "master unmute: " + t);
            }
        }
        if (!sMutedByUs) return;
        sMutedByUs = false;
        writeMuted(false);
        try {
            if (sAm != null) sAm.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            Log.i(TAG, "music unmuted (" + why + ")");
        } catch (Throwable t) {
            Log.w(TAG, "unmute: " + t);
        }
    }

    private static void stopDream() {
        try {
            DreamManager dm = sApp.getSystemService(DreamManager.class);
            if (dm != null && dm.isDreaming()) {
                Log.i(TAG, "screensaver running: stopDream (awaken; the display stays on)");
                dm.stopDream();
            }
        } catch (Throwable t) {
            Log.w(TAG, "stopDream: " + t);
        }
    }

    /** Pauses every playing media session (MEDIA_CONTENT_CONTROL) and, if audio still plays, MEDIA_PAUSE. */
    private static void pauseMedia(String why) {
        int paused = 0;
        try {
            MediaSessionManager msm = sApp.getSystemService(MediaSessionManager.class);
            List<MediaController> list = msm == null ? null : msm.getActiveSessions(null);
            if (list != null) {
                for (MediaController c : list) {
                    try {
                        PlaybackState s = c.getPlaybackState();
                        int st = s == null ? PlaybackState.STATE_NONE : s.getState();
                        if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
                                || st == PlaybackState.STATE_FAST_FORWARDING || st == PlaybackState.STATE_REWINDING
                                || st == PlaybackState.STATE_CONNECTING) {
                            c.getTransportControls().pause();
                            paused++;
                            Log.i(TAG, "media (" + why + "): paused " + c.getPackageName());
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "pause " + c.getPackageName() + ": " + t);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "media sessions: " + t);
        }
        try {
            if (sAm != null && sAm.isMusicActive()) {
                long t = SystemClock.uptimeMillis();
                sAm.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
                sAm.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
                Log.i(TAG, "media (" + why + "): audio still active after " + paused + " pause(s): MEDIA_PAUSE sent");
            }
        } catch (Throwable t) {
            Log.w(TAG, "media key: " + t);
        }
    }

    /** Returns false only when the request was refused (then it is retried later). */
    private static boolean requestFocus() {
        try {
            if (sAm == null) return true;
            if (sFocus == null) {
                sFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(StandbyController::onFocusChange, Ui.main())
                        .build();
            }
            int r = sAm.requestAudioFocus(sFocus);
            Log.i(TAG, "audio focus GAIN -> " + r);
            return r != AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        } catch (Throwable t) {
            Log.w(TAG, "audio focus: " + t);
            return true;
        }
    }

    /**
     * Main thread. During standby another player took the focus (AirPlay, Spotify Connect, a cast,
     * an HDMI viewer): pause every session and take the focus back a moment later, so the new
     * player gets AUDIOFOCUS_LOSS too (AirPlay then ends its session).
     */
    private static void onFocusChange(int fc) {
        Log.i(TAG, "audio focus change " + fc);
        if (sState == IDLE) return;
        if (fc == AudioManager.AUDIOFOCUS_LOSS || fc == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pauseMedia("focus lost");
            Ui.main().removeCallbacks(sRefocusRun);
            Ui.main().postDelayed(sRefocusRun, REFOCUS_MS);
        }
    }

    private static void abandonFocus() {
        try {
            if (sAm != null && sFocus != null) sAm.abandonAudioFocusRequest(sFocus);
        } catch (Throwable t) {
            Log.w(TAG, "abandon focus: " + t);
        }
    }

    /** HOME wakes (PhoneWindowManager sends close-system-dialogs "homekey" before starting Home). */
    private static void registerHomeWatch() {
        if (sHomeWatch != null) return;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    if (sHomeWatch != this || sState == IDLE) return;
                    if ("homekey".equals(i.getStringExtra("reason"))) exit("home key");
                } catch (Throwable t) {
                    Log.w(TAG, "home watch: " + t);
                }
            }
        };
        try {
            sApp.registerReceiver(r, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), Context.RECEIVER_EXPORTED);
            sHomeWatch = r;
        } catch (Throwable t) {
            Log.w(TAG, "home watch register: " + t);
        }
    }

    private static void unregisterHomeWatch() {
        BroadcastReceiver r = sHomeWatch;
        sHomeWatch = null;
        if (r != null) {
            try { sApp.unregisterReceiver(r); } catch (Throwable t) { Log.w(TAG, "home watch unregister: " + t); }
        }
    }

    /**
     * Standby: a volume key (TV routing: straight to AudioService, ADJUST_RAISE unmutes) or an app
     * that unmutes STREAM_MUSIC / the master mute is undone at once while the mute is ours. Main thread.
     */
    private static void registerVolumeWatch() {
        if (sVolumeWatch != null) return;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    if (sVolumeWatch != this || sState == IDLE || sAm == null) return;
                    if (AudioManager.MASTER_MUTE_CHANGED_ACTION.equals(i.getAction())) {
                        if (sMasterMutedByUs && !sAm.isMasterMute()) {
                            Log.w(TAG, "master mute undone during standby: set again");
                            sAm.setMasterMute(true, 0);
                        }
                        return;
                    }
                    if (!sMutedByUs) return;
                    int stream = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                    if (stream != AudioManager.STREAM_MUSIC) return;
                    if (sAm.isStreamMute(AudioManager.STREAM_MUSIC)) return;
                    Log.w(TAG, "music unmuted during standby (" + i.getAction() + "): muted again");
                    sAm.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                } catch (Throwable t) {
                    Log.w(TAG, "volume watch: " + t);
                }
            }
        };
        try {
            IntentFilter f = new IntentFilter("android.media.STREAM_MUTE_CHANGED_ACTION");
            f.addAction("android.media.VOLUME_CHANGED_ACTION");
            f.addAction(AudioManager.MASTER_MUTE_CHANGED_ACTION);
            sApp.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            sVolumeWatch = r;
        } catch (Throwable t) {
            Log.w(TAG, "volume watch register: " + t);
        }
    }

    private static void unregisterVolumeWatch() {
        BroadcastReceiver r = sVolumeWatch;
        sVolumeWatch = null;
        if (r != null) {
            try { sApp.unregisterReceiver(r); } catch (Throwable t) { Log.w(TAG, "volume watch unregister: " + t); }
        }
    }

    /**
     * Standby enter (main thread): the vendor's user-level motion triggers (quick panel "Real-time
     * keystone" 586 / 585, "AF after moving" 588 / 587; not factory or calibration codes) are switched
     * off if on, so bumping the dark projector runs no AK / AF (no pattern light, no keystone / focus
     * saved on a dark picture). What we switched off is kept in the marker and restored at exit,
     * SCREEN_ON or the next start ({@link #restoreMotionTriggers}).
     */
    private static void motionTriggersOff() {
        boolean queued = Hal.run((g, g2) -> {
            int off = 0;
            try {
                if (g.getMoveAk()) {
                    g.setMoveAk(false);
                    off |= MOVE_AK;
                }
            } catch (Throwable t) {
                Log.w(TAG, "standby: real-time keystone 586/585: " + t);
            }
            try {
                if (g.getMoveAf()) {
                    g.setMoveAf(false);
                    off |= MOVE_AF;
                }
            } catch (Throwable t) {
                Log.w(TAG, "standby: AF after moving 588/587: " + t);
            }
            final int bits = off;
            if (bits == 0) return;
            Ui.main().post(() -> {
                sMoveOffByUs |= bits;
                writeMoveOff(sMoveOffByUs);
                Log.i(TAG, "standby: motion triggers off (" + moveName(bits) + ")");
                if (sState == IDLE && sApp != null && interactive()) restoreMotionTriggers("standby already over", 0);
            });
        });
        if (!queued) Log.w(TAG, "standby: HAL not available, motion triggers left as they are");
    }

    /**
     * Switches the motion triggers that standby switched off back on (585 through the ceiling check of
     * Hal.setMoveAkChecked, 587). Not while a standby holds or the display is off. Retried while the
     * HAL is not connected (boot). Main thread.
     */
    private static void restoreMotionTriggers(final String why, final int tries) {
        if (sMoveOffByUs == 0) return;
        final int bits = sMoveOffByUs;
        Runnable retry = () -> Ui.main().post(() -> {
            if (tries + 1 < MOVE_RESTORE_MAX_TRIES) {
                Ui.main().postDelayed(() -> restoreMotionTriggers(why, tries + 1), MOVE_RESTORE_RETRY_MS);
            } else {
                Log.w(TAG, "motion triggers not restored (" + why + "): HAL not available");
            }
        });
        boolean queued = Hal.runDelayed((g, g2) -> {
            if (sState != IDLE || sRestorePending || !interactive()) return;   // dark again: keep them off
            int done = 0;
            if ((bits & MOVE_AK) != 0) {
                try {
                    if (!Hal.setMoveAkChecked(g, true)) Log.i(TAG, "real-time keystone not restored: ceiling mount");
                    done |= MOVE_AK;                        // refused on a ceiling mount: nothing to restore
                } catch (Throwable t) {
                    Log.w(TAG, "restore 585: " + t);
                }
            }
            if ((bits & MOVE_AF) != 0) {
                try {
                    g.setMoveAf(true);
                    done |= MOVE_AF;
                } catch (Throwable t) {
                    Log.w(TAG, "restore 587: " + t);
                }
            }
            final int ok = done;
            Ui.main().post(() -> {
                if (ok == 0) return;
                sMoveOffByUs &= ~ok;
                writeMoveOff(sMoveOffByUs);
                Log.i(TAG, "motion triggers restored (" + why + "): " + moveName(ok));
            });
        }, tries == 0 ? MOVE_RESTORE_DELAY_MS : 0, retry);
        if (!queued) retry.run();
    }

    private static String moveName(int bits) {
        return ((bits & MOVE_AK) != 0 ? "real-time keystone" : "")
                + ((bits & MOVE_AK) != 0 && (bits & MOVE_AF) != 0 ? " + " : "")
                + ((bits & MOVE_AF) != 0 ? "AF after moving" : "");
    }

    private static void writeMoveOff(final int bits) {
        if (sWorker == null) return;
        sWorker.post(() -> {
            try {
                prefs().edit().putInt(K_MOVE_OFF, bits).commit();
            } catch (Throwable t) {
                Log.w(TAG, "marker (motion): " + t);
            }
        });
    }

    /**
     * Intent.ACTION_SHUTDOWN (ShutdownThread, FLAG_RECEIVER_REGISTERED_ONLY, ordered: it waits for
     * this receiver) marks this boot as ended orderly: a framework reboot or power-off, never a kernel
     * / vendor reset. Registered for the whole process (persistent), runs on z9x-standby.
     */
    private static void registerShutdownWatch() {
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (i != null && Intent.ACTION_SHUTDOWN.equals(i.getAction())) markOrderly("ACTION_SHUTDOWN");
                }
            };
            sApp.registerReceiver(r, new IntentFilter(Intent.ACTION_SHUTDOWN), null, sWorker,
                    Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "shutdown watch register: " + t);
        }
    }

    /** Any thread, synchronous (the caller reboots / shuts down next). */
    private static void markOrderly(String why) {
        try {
            String boot = Hal.bootId();
            if (boot == null || boot.isEmpty()) return;
            prefs().edit().putString(K_ORDERLY_BOOT, boot).putString(K_ORDERLY_WHY, why).commit();
            Log.i(TAG, "orderly shutdown / reboot marked (" + why + ")");
        } catch (Throwable t) {
            Log.w(TAG, "marker (orderly): " + t);
        }
    }

    /**
     * PowerKey.PowerActions (power menu "Restart", its worker thread, right before PowerManager.reboot);
     * Lumen OS 1.0.1 also display.UiResolution (the interface resolution restart, reason z9x-uires).
     */
    public static void noteOrderlyReboot(String why) {
        if (sApp == null) return;
        markOrderly(why);
    }

    private static void broadcast(boolean on) {
        try {
            org.z9x.projector.eye.EyeGuard.onStandbyChanged(on);
        } catch (Throwable t) {
            Log.w(TAG, "EyeGuard: " + t);
        }
        try {
            Intent i = new Intent(ACTION_STANDBY_CHANGED).putExtra(EXTRA_STANDBY, on)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            sApp.sendBroadcast(i, PERMISSION_STANDBY_STATE);
        } catch (Throwable t) {
            Log.w(TAG, "broadcast: " + t);
        }
    }

    private static void userActivity() {
        try {
            sPm.userActivity(SystemClock.uptimeMillis(), PowerManager.USER_ACTIVITY_EVENT_OTHER, 0);
        } catch (Throwable t) {
            Log.w(TAG, "userActivity: " + t);
        }
    }

    private static void acquire(PowerManager.WakeLock wl) {
        if (wl == null) return;
        try { if (!wl.isHeld()) wl.acquire(); } catch (Throwable t) { Log.w(TAG, "acquire: " + t); }
    }

    private static void release(PowerManager.WakeLock wl) {
        if (wl == null) return;
        try { if (wl.isHeld()) wl.release(); } catch (Throwable t) { Log.w(TAG, "release: " + t); }
    }

    // =================================================================== marker (prefs z9x_power)

    private static SharedPreferences prefs() {
        return sApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Any thread: written on z9x-standby with commit() (it must survive a sudden reset). */
    private static void writeState(final String state, final long deadlineWall, final String why) {
        if (sWorker == null) return;
        final String boot = Hal.bootId();
        sWorker.post(() -> {
            try {
                prefs().edit().putString(K_STATE, state).putString(K_BOOT, boot == null ? "" : boot)
                        .putLong(K_DEADLINE, deadlineWall).putString(K_REASON, why == null ? "" : why).commit();
            } catch (Throwable t) {
                Log.w(TAG, "marker: " + t);
            }
        });
    }

    private static void writeMuted(final boolean muted) {
        if (sWorker == null) return;
        sWorker.post(() -> {
            try {
                prefs().edit().putBoolean(K_MUTED, muted).commit();
            } catch (Throwable t) {
                Log.w(TAG, "marker (mute): " + t);
            }
        });
    }

    private static void writeMasterMuted(final boolean muted) {
        if (sWorker == null) return;
        sWorker.post(() -> {
            try {
                prefs().edit().putBoolean(K_MASTER_MUTED, muted).commit();
            } catch (Throwable t) {
                Log.w(TAG, "marker (master mute): " + t);
            }
        });
    }

    /** State 'off' + the wall time of this power-off, synchronously (the caller then shuts down). */
    private static void commitPowerOff(String why) {
        try {
            String boot = Hal.bootId();
            prefs().edit().putString(K_STATE, ST_OFF).putString(K_BOOT, boot == null ? "" : boot)
                    .putLong(K_DEADLINE, -1).putString(K_REASON, why == null ? "" : why)
                    .putLong(K_OFF_WALL, System.currentTimeMillis()).commit();
        } catch (Throwable t) {
            Log.w(TAG, "marker (off): " + t);
        }
    }

    /**
     * PowerKey.PowerActions (power menu "Power off", its worker thread, right before
     * PowerManager.shutdown): the same 'off' marker as our own power-off, so the next boot judges both
     * power-off paths alike.
     */
    static void notePowerOff(String why) {
        if (sApp == null) return;
        commitPowerOff(why);
    }

    /**
     * Once, synchronously, at install (App.onCreate, main thread): one small prefs read, like
     * PowerPolicy.loadPrefs. A new boot records the previous boot's state, also in the marker for this
     * boot (a process restart before the boot-dark check still knows it). A process restart in the
     * same boot restores this boot's standby (pending at once, re-entered on the main thread), sleep,
     * mute and motion triggers.
     */
    private static void restoreFromMarker() {
        String state, boot, why, orderlyBoot, prevFor, prevState, checked;
        long deadline, offWall, prevOffWall;
        boolean muted, masterMuted, prevOrderly;
        int moveOff;
        try {
            SharedPreferences p = prefs();
            state = p.getString(K_STATE, "");
            boot = p.getString(K_BOOT, "");
            deadline = p.getLong(K_DEADLINE, -1);
            why = p.getString(K_REASON, "");
            offWall = p.getLong(K_OFF_WALL, -1);
            muted = p.getBoolean(K_MUTED, false);
            masterMuted = p.getBoolean(K_MASTER_MUTED, false);
            moveOff = p.getInt(K_MOVE_OFF, 0);
            orderlyBoot = p.getString(K_ORDERLY_BOOT, "");
            prevFor = p.getString(K_PREV_FOR, "");
            prevState = p.getString(K_PREV_STATE, "");
            prevOffWall = p.getLong(K_PREV_OFF_WALL, -1);
            prevOrderly = p.getBoolean(K_PREV_ORDERLY, false);
            checked = p.getString(K_DARK_CHECKED, "");
        } catch (Throwable t) {
            Log.w(TAG, "marker read: " + t);
            return;
        }
        final String now = Hal.bootId();
        final boolean display = interactive();
        sMutedByUs = muted;                                 // unmuted at exit / SCREEN_ON / below
        sMasterMutedByUs = masterMuted;                     // (not persisted by AudioService: harmless)
        sMoveOffByUs = moveOff;                             // restored at exit / SCREEN_ON / below
        if (now == null || !now.equals(boot)) {
            final String prev = state.isEmpty() ? "unknown" : state;
            final long prevOff = ST_OFF.equals(state) ? offWall : -1;
            final boolean orderly = !boot.isEmpty() && boot.equals(orderlyBoot);
            sPrevBootState = prev;
            sPrevOffWall = prevOff;
            sPrevOrderly = orderly;
            Log.i(TAG, "previous boot ended in power state '" + prev + "' (" + why + ")"
                    + (orderly ? ", orderly shutdown / reboot" : ", no orderly shutdown seen")
                    + (muted ? ", music was muted by standby" : "")
                    + (masterMuted ? ", master mute was set by standby" : "")
                    + (moveOff != 0 ? ", motion triggers off by standby: " + moveName(moveOff) : ""));
            // this boot starts in use (or asleep): a reset later in this boot must not be judged by
            // the previous boot's state; the previous state is kept for this boot's boot-dark check
            final String cur = display ? ST_ACTIVE : ST_SLEEP;
            sWorker.post(() -> {
                try {
                    SharedPreferences.Editor e = prefs().edit().putString(K_STATE, cur)
                            .putString(K_BOOT, now == null ? "" : now).putLong(K_DEADLINE, -1)
                            .putString(K_REASON, "boot");
                    if (now != null) {
                        e.putString(K_PREV_FOR, now).putString(K_PREV_STATE, prev)
                                .putLong(K_PREV_OFF_WALL, prevOff).putBoolean(K_PREV_ORDERLY, orderly);
                    }
                    e.commit();
                } catch (Throwable t) {
                    Log.w(TAG, "marker (boot): " + t);
                }
            });
            Ui.main().post(() -> {
                if (sState != IDLE) return;                 // boot-dark standby: exit / the next boot does it
                unmuteMusic("new boot");
                restoreMotionTriggers("new boot", 0);
            });
            return;
        }
        // the same boot: this process was restarted
        if (now.equals(prevFor) && !now.equals(checked)) {
            sPrevBootState = prevState.isEmpty() ? "unknown" : prevState;
            sPrevOffWall = prevOffWall;
            sPrevOrderly = prevOrderly;
            if ("1".equals(SystemProperties.get("sys.boot_completed", ""))
                    && SystemClock.elapsedRealtime() < LATE_CHECK_MAX_MS) {
                Log.w(TAG, "process restarted before this boot's boot-dark check: running it now");
                sWorker.post(StandbyController::queueBootDarkCheck);
            }
        } else {
            sPrevBootState = null;
        }
        if (display && ST_STANDBY.equals(state)) {
            sRestorePending = true;                         // isActive(): a wake key now ends it
            Log.w(TAG, "process (re)started during standby (" + why + "): standby pending until re-entered");
        }
        final long dl = deadline;
        final String reason = why;
        final String st = state;
        Ui.main().post(() -> restoreSameBoot(st, dl, reason));
    }

    /** Main thread, after install: the standby / sleep of this boot after a process restart. */
    private static void restoreSameBoot(String st, long dl, String reason) {
        if (!interactive()) {
            sRestorePending = false;
            // the display is off: SCREEN_OFF is not sent again to a new process, so redo the
            // sleep path (lamp off, hold wakelock, deadline of this boot if there was one)
            Log.w(TAG, "process (re)started with the display off (marker '" + st + "', " + reason + ")");
            PowerPolicy.resumeSleep(sApp, ST_SLEEP.equals(st) || ST_STANDBY.equals(st) ? dl : -1);
            return;
        }
        if (ST_STANDBY.equals(st)) {
            if (!sRestorePending) {
                Log.i(TAG, "standby of this boot already ended before the re-enter (" + reason + ")");
            } else {
                sRestorePending = false;
                Log.w(TAG, "process restarted during standby (" + reason + "): entering standby again");
                sRestoreDeadlineWall = dl;
                if (!enter(sApp, "restart: " + reason)) sRestoreDeadlineWall = -1;
            }
        }
        if (sState == IDLE) {
            unmuteMusic("process restart, not in standby");
            restoreMotionTriggers("process restart, not in standby", 0);
        }
    }

    /** PowerPolicy.resumeSleep: a deadline (wall clock) of this boot to keep. Main thread. */
    static void restoreSleepDeadline(long wall) {
        if (wall <= 0) return;
        setDeadline(SystemClock.uptimeMillis() + Math.max(0, wall - System.currentTimeMillis()), "sleep (restored)");
    }

    /** SCREEN_OFF: remember the sleep at once (the deadline follows once the lamp is off). Main thread. */
    static void markSleep(String why) {
        if (sDeadlineUptime < 0) writeState(ST_SLEEP, -1, why);
    }

    // =================================================================== boot-dark

    /**
     * BOOT_COMPLETED: an unattended reset while the projector was dark and ours powers it off again
     * (class javadoc: the exact rule). Once per boot id (also across process restarts), at most
     * BOOTDARK_MAX_CONSECUTIVE in a row; kill switch persist.z9x.bootdark=0. Any thread.
     */
    public static void onBootCompleted(Context ctx) {
        install(ctx);
        sWorker.post(StandbyController::queueBootDarkCheck);
    }

    /** z9x-standby: at most one check per process (BOOT_COMPLETED and the restart path may both ask). */
    private static void queueBootDarkCheck() {
        if (sDarkCheckQueued) return;
        sDarkCheckQueued = true;
        if (darkBootRequested()) return;
        bootDarkCheck(0);
    }

    /**
     * z9x-standby: the previous boot restarted for an update instead of entering STR ("restart when I
     * turn the projector off"): this boot goes dark at once (lamp off, then STR as soon as the gate
     * allows it; an unconfirmed slot keeps the lamp-only standby until the health gate marked it).
     */
    private static boolean darkBootRequested() {
        boolean dark;
        try {
            SharedPreferences p = prefs();
            dark = p.getBoolean(K_DARK_NEXT, false);
            if (dark) p.edit().putBoolean(K_DARK_NEXT, false).commit();
        } catch (Throwable t) {
            Log.w(TAG, "dark boot marker: " + t);
            return false;
        }
        if (!dark) return false;
        Log.i(TAG, "boot after an update restart that replaced STR: going dark again");
        try {
            String boot = Hal.bootId();
            if (boot != null) prefs().edit().putString(K_DARK_CHECKED, boot).commit();
        } catch (Throwable ignored) { }
        Ui.main().post(() -> {
            if (sState != IDLE || !interactive()) return;
            if (!enter(sApp, "dark boot after the update restart")) fallbackSleep("dark boot");
        });
        return true;
    }

    /** init's persisted reason of an orderly reboot / shutdown (bootstat sys.boot.reason.last). */
    private static boolean orderlyInitReason(String last) {
        if (last == null) return false;
        return last.startsWith("reboot,") || last.startsWith("shutdown,") || last.equals("shutdown")
                || last.equals("bootloader") || last.equals("recovery") || last.equals("fastboot");
    }

    /** z9x-standby. */
    private static void bootDarkCheck(int tries) {
        String reason = SystemProperties.get(PROP_BOOT_REASON, "");
        if (reason.isEmpty() && tries < 30) {
            sWorker.postDelayed(() -> bootDarkCheck(tries + 1), 1_000);
            return;
        }
        String wake = SystemProperties.get(PROP_WAKE_NAME, "");
        String wdt = SystemProperties.get(PROP_WDT_CHK, "");
        String last = SystemProperties.get(PROP_LAST_REASON, "");
        String prev = sPrevBootState;
        boolean orderlyMark = sPrevOrderly;
        boolean orderlyInit = orderlyInitReason(last);
        long offWall = sPrevOffWall;
        long wallNow = System.currentTimeMillis();
        long sinceOff = offWall > 0 ? wallNow - offWall : -1;
        boolean nullWake = "(null)".equals(wake);
        boolean pmWdt = WDT_RESET.equals(wdt);              // WDT_NO_RESET = AC plug-in
        boolean rtcOk = wallNow >= RTC_VALID_WALL;
        boolean f1 = reason.equalsIgnoreCase("0xF1");
        boolean resetReason = f1 || reason.equalsIgnoreCase("0xD1");
        String dark = null;                                 // why this boot counts as unattended
        if (ST_STANDBY.equals(prev) || ST_SLEEP.equals(prev)) {
            if (resetReason && nullWake && pmWdt && rtcOk && !orderlyMark && !orderlyInit) {
                dark = "reset during " + prev + " (no orderly shutdown)";
            }
        } else if (ST_OFF.equals(prev) && sinceOff >= 0 && sinceOff <= OFF_RESET_WINDOW_MS) {
            if (f1 && nullWake && pmWdt) dark = "reset " + sinceOff / 1000 + " s after our power-off";
        }
        Log.i(TAG, "boot: reason=" + (reason.isEmpty() ? "?" : reason) + " wake=" + (wake.isEmpty() ? "?" : wake)
                + " wdt_reset_chk=" + (wdt.isEmpty() ? "?" : wdt) + " last_reason=" + (last.isEmpty() ? "?" : last)
                + " previous state=" + prev + (orderlyMark ? " (orderly shutdown marked)" : "")
                + (ST_OFF.equals(prev) ? " (" + (sinceOff >= 0 ? sinceOff / 1000 + " s since its power-off" : "power-off time unknown") + ")" : "")
                + (rtcOk ? "" : " RTC reset") + (WDT_NO_RESET.equals(wdt) ? " (AC plug-in)" : "")
                + (dark != null ? " -> UNATTENDED RESET (" + dark + ")" : ""));
        if (prev == null) {
            Log.w(TAG, "boot-dark: previous boot's state unknown in this process: no check");
            return;
        }
        SharedPreferences p;
        String boot = Hal.bootId();
        try {
            p = prefs();
            if (boot != null && boot.equals(p.getString(K_DARK_CHECKED, ""))) {
                Log.w(TAG, "boot-dark: already checked in this boot");
                return;
            }
            if (boot != null) p.edit().putString(K_DARK_CHECKED, boot).commit();
        } catch (Throwable t) {
            Log.w(TAG, "boot-dark marker: " + t);
            return;
        }
        if (dark == null) {
            try {
                if (p.getInt(K_BOOTDARK_COUNT, 0) != 0) p.edit().putInt(K_BOOTDARK_COUNT, 0).commit();
            } catch (Throwable t) {
                Log.w(TAG, "boot-dark counter: " + t);
            }
            return;                                         // a normal boot: the projector stays on
        }
        if ("0".equals(SystemProperties.get(PROP_BOOTDARK, ""))) {
            Log.w(TAG, "boot-dark: disabled by " + PROP_BOOTDARK + "=0");
            return;
        }
        if (StrGate.otaSlotUnmarkedRaw()) {
            // PLAN C16: a boot loop of a new slot must not look like "the projector stays off"; the
            // health gate has to run (it marks the slot or rolls back)
            Log.w(TAG, "boot-dark: skipped, the running slot is an update not confirmed yet");
            return;
        }
        int count;
        try {
            if (boot != null && boot.equals(p.getString(K_BOOTDARK_BOOT, ""))) {
                Log.w(TAG, "boot-dark: already tried in this boot");
                return;
            }
            count = p.getInt(K_BOOTDARK_COUNT, 0);
            if (count >= BOOTDARK_MAX_CONSECUTIVE) {
                Log.w(TAG, "boot-dark: " + count + " power-offs in a row did not stay off: staying on "
                        + "(the counter resets at the next normal boot)");
                return;
            }
            p.edit().putString(K_BOOTDARK_BOOT, boot == null ? "" : boot)
                    .putInt(K_BOOTDARK_COUNT, count + 1).commit();
        } catch (Throwable t) {
            Log.w(TAG, "boot-dark marker: " + t);
            return;
        }
        final String why = "unattended reset (boot_reason " + reason + ", no wake source, " + wdt + ", " + dark
                + ", boot-dark #" + (count + 1) + ")";
        Ui.main().post(() -> {
            if (sState != IDLE) return;
            // lamp off at once; a wake (key) during the power-off makes this boot a used one
            boolean standby = enter(sApp, "boot-dark");
            sBootDark = !standby;                           // retried without standby only until a key
            powerOffNow(why);
        });
    }
}
