package org.z9x.projector.cec;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiTvClient;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import org.z9x.projector.KeyReceiver;
import org.z9x.projector.R;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.Ui;
import org.z9x.projector.power.StandbyController;
import org.z9x.projector.ui.DialogPanel;
import org.z9x.projector.ui.Notify;
import org.z9x.projector.ui.OverlayHost;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * MODULE "cec" (Lumen OS 1.0, HDMI-CEC phase 1: cec spec + PLAN C11 / C14 / C15, adapted to real STR).
 * The projector side of HDMI-CEC power: who may turn the projector on, what the projector tells the HDMI
 * devices when it goes dark, and the guards against unwanted power-ons. Routing (which input to show,
 * CEC child inputs, key forwarding) belongs to org.z9x.tvinput. Never a HAL call: only system_server's
 * HdmiControlService (@SystemApi, HDMI_CEC), PowerManager (DEVICE_POWER) and two init properties.
 *
 * <b>Turning on</b> ("Turn on with an HDMI device" = framework tv_wake_on_one_touch_play):
 * <ul>
 *   <li><b>Default OFF</b> (cec spec guard 8): Z9xFrameworkKeysOverlay ships tv_wake_on_one_touch_play
 *       off (user-configurable: our CEC page and TvSettings). Everything below applies once the user
 *       turns it on.</li>
 *   <li><b>From STR</b> (the normal "off"): init writes the PM51 wake source cec0 from {@link
 *       CecSettings#PROP_ARM}, decided here at every SCREEN_OFF ({@link #onScreenOff}): armed only with
 *       CEC on, the wake setting on, setup complete (C14), no storm pause and outside the night guard
 *       hours. When a sleep is NOT armed although the setting is on, the framework setting itself is
 *       switched off for that sleep ({@link #suspendOtp}) and restored at the next user wake (or the next
 *       start): HdmiControlService then cannot wake Android on &lt;Image/Text View On&gt; behind the guards
 *       (blocked sleep: Android asleep, CPU held awake) and the vendor HAL's enableWakeupByOtp is off too.
 *       While the CPU stays awake asleep, the guards are re-checked every {@value #RECHECK_MS} ms (night
 *       hours that begin during the sleep). The PM51 wakes the SoC, Android wakes (vendor KEY_POWER),
 *       PowerPolicy lights the lamp after the verdict ({@link #holdLampOn}). {@link #onScreenOn} asks the root helper z9x_power_wake
 *       (z9x_power_cec.rc: the app cannot read /sys/mtk_pm) for /sys/mtk_pm/wakeup_reason/name: "cec*"
 *       after an armed sleep = a CEC wake. Android's own wake reason is not readable by apps
 *       (PowerManager has no getLastWakeup on Android 14) and an STR wake is a KEY_POWER wake anyway. The
 *       guards are checked again: a wake they refuse (night hours reached during the sleep) goes back to
 *       sleep at once (StandbyController.enter("cec_veto: ...")); a second wake within {@value
 *       #VETO_GRACE_MS} ms of a veto is always the user's (a stale PM51 name can never lock the user
 *       out).</li>
 *   <li><b>Framework wake in the blocked sleep</b> (armed, STR blocked): org.z9x.tvinput's "cec_wake"
 *       call (the source's &lt;Active Source&gt;) within {@value #CEC_AFTER_WAKE_MS} ms of SCREEN_ON, with
 *       no remote key in between, marks the wake as a CEC wake (unattended return, storm limit).</li>
 *   <li><b>From the lamp-only standby</b> (STR blocked, e.g. USB host on the cable; Android awake):
 *       org.z9x.tvinput sees the source's &lt;Active Source&gt; (InputChangeListener) and calls
 *       StandbyStateProvider "cec_wake" -> {@link #onWakeRequest}: same guards plus the device type
 *       (playback / tuner / recorder only), then StandbyController.exit("cec_otp:...").</li>
 *   <li><b>Unattended return</b>: 90 s after an accepted CEC wake without a remote key and without an HDMI
 *       picture (tvinput: no streaming session), a card "Turned on by an HDMI device ... Stay on" gives
 *       the user 30 s; then StandbyController.enter("cec_unattended") (the normal sleep path).</li>
 *   <li><b>Storm limit</b>: more than {@value #STORM_MAX} CEC wakes within 30 min that ended unattended or
 *       in a sleep within 2 min pause the CEC wake until the next remote key (logged, one card).</li>
 * </ul>
 *
 * <b>Going dark</b>: a real STR sleep is an Android sleep, so HdmiControlService itself broadcasts
 * &lt;Standby&gt; (tv_send_standby_on_sleep); {@link #onScreenOff} only keeps the CPU up
 * {@value #TX_HOLD_MS} ms so that message leaves before the suspend. The lamp-only standby keeps Android
 * awake, so {@link #onLampOnlyStandby} sends it: the TV takes the active source back (an echoed
 * &lt;Standby&gt; from the old source is then "not from the active source"), 300 ms later a directed
 * &lt;Standby&gt; to every known device, the audio system first. A real power-off (ACTION_SHUTDOWN)
 * disarms cec0 and the framework OTP wake (restored at the next start): no CEC cold boot (PLAN Q13).
 *
 * <b>Console off</b> (phase 1): &lt;Standby&gt; from the watched source makes HdmiControlService call
 * goToSleep(HDMI); that is a foreign sleep and goes through PowerPolicy's sleep path (lamp off, STR gate).
 * Logged here. Phase 2 (framework hook, CecPowerReceiver) is deferred (C11).
 *
 * <b>Waking up</b> (user or CEC): {@value #AVR_ON_DELAY_MS} ms later, with "Turn on the soundbar with the
 * projector" on and an audio system known but system audio off: &lt;System Audio Mode Request&gt;
 * (HdmiTvClient.setSystemAudioMode), then once &lt;User Control Pressed&gt; Power On (powerOnDevice).
 *
 * Threading: entry points on the main thread except {@link #onWakeRequest} (provider binder thread);
 * binder / sysfs work on "z9x-cec". Every entry point is shielded.
 */
public final class CecPolicy {
    private static final String TAG = "Z9xCec";

    static final long UNATTENDED_CHECK_MS = 90_000;
    static final long UNATTENDED_DIALOG_MS = 30_000;
    static final long STORM_WINDOW_MS = 30 * 60_000L;
    static final int STORM_MAX = 3;
    /** A sleep this soon after a CEC wake counts towards the storm limit. */
    static final long QUICK_SLEEP_MS = 120_000;
    /** CPU kept up after SCREEN_OFF so the framework's &lt;Standby&gt; leaves before the suspend. */
    static final long TX_HOLD_MS = 2_000;
    static final long AVR_ON_DELAY_MS = 4_000;
    static final long AVR_FALLBACK_MS = 4_000;
    static final long INTERNAL_TO_STANDBY_MS = 300;
    private static final long STARTUP_DELAY_MS = 15_000;
    /** Guards re-checked this often while Android sleeps with the CPU awake (blocked sleep). */
    static final long RECHECK_MS = 60_000;
    /** A wake this soon after a veto is the user insisting: never vetoed again. */
    static final long VETO_GRACE_MS = 120_000;
    /** tvinput's &lt;Active Source&gt; this soon after SCREEN_ON (no remote key between) = a CEC wake. */
    static final long CEC_AFTER_WAKE_MS = 10_000;
    /** Root helper z9x_power_wake (overlay/v1/z9x_power_cec.rc): query token in, "token pm51 kernel" out. */
    static final String PROP_WAKE_QUERY = "sys.z9x.wake_query";
    static final String PROP_WAKE_NOW = "sys.z9x.wake_now";
    private static final long WAKE_QUERY_TIMEOUT_MS = 1_500;
    /**
     * After a sleep with cec0 armed the lamp waits for the CEC verdict ({@link #holdLampOn}); never longer
     * than this (the helper answers within {@value #WAKE_QUERY_TIMEOUT_MS} ms): a user must never stay dark.
     */
    static final long LAMP_HOLD_MAX_MS = 4_000;

    private static Context sApp;
    private static SafeHandler sWorker;
    private static PowerManager sPm;
    private static PowerManager.WakeLock sTxLock;

    // ---- main thread
    /** Uptime of the last accepted CEC wake, -1 = none pending. */
    private static long sCecWakeAt = -1;
    private static String sCecWakeWho = "";
    private static long sLastUserKeyAt;
    private static boolean sStormNotify;
    private static final ArrayDeque<Long> sBad = new ArrayDeque<>();
    private static DialogPanel sDialog;
    private static long sLastVetoAt = -VETO_GRACE_MS;
    /** PowerPolicy's lamp-on, held until the CEC verdict of this wake (null = nothing held). */
    private static Runnable sHeldLampOn;
    private static final Runnable sHoldTimeout = () -> releaseLamp("no CEC verdict within " + LAMP_HOLD_MAX_MS + " ms");
    // ---- any thread
    private static volatile boolean sStormPaused;
    /** PM51 wake name read at the last SCREEN_OFF (the previous wake's reason; null = unknown). */
    private static volatile String sPmNameAtOff;
    /** cec0 armed for the current / last sleep (decided in sleepWork, z9x-cec). */
    private static volatile boolean sArmedAtSleep;
    private static volatile long sScreenOnAt = -CEC_AFTER_WAKE_MS;
    private static int sQuerySeq;

    private static final Runnable sUnattendedRun = CecPolicy::unattendedCheck;
    private static final Object AVR_TOKEN = new Object();
    private static final Object RECHECK_TOKEN = new Object();

    private CecPolicy() {}

    // =================================================================== install

    /** App.onCreate (main thread); idempotent, also called lazily by every entry point. */
    public static synchronized void install(Context ctx) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        sWorker = SafeHandler.newThread("z9x-cec");
        try {
            sPm = sApp.getSystemService(PowerManager.class);
            if (sPm != null) {
                sTxLock = sPm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "z9x:cecstandby");
                sTxLock.setReferenceCounted(false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "wakelock: " + t);
        }
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (i != null && Intent.ACTION_SHUTDOWN.equals(i.getAction())) onShutdown();
                }
            };
            // handled on z9x-cec, synchronously inside onReceive (ShutdownThread waits for receivers)
            sApp.registerReceiver(r, new IntentFilter(Intent.ACTION_SHUTDOWN), null, sWorker, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "shutdown receiver: " + t);
        }
        sWorker.postDelayed(CecPolicy::startup, STARTUP_DELAY_MS);
    }

    static SafeHandler worker() {
        return sWorker;
    }

    /**
     * z9x-cec, once per process: undo the power-off's OTP switch-off, mirror the boot default of cec0.
     * Only while the projector is on: a restart in the middle of a guarded sleep (the CPU held awake) keeps
     * the OTP wake off for that sleep; the next user wake ({@link #onScreenOn}) restores it.
     */
    private static void startup() {
        try {
            if (CecSettings.restoreOtpPending(sApp) && !interactive()) {
                Log.i(TAG, "start while asleep: turn on with an HDMI device stays off until the next user wake");
            } else if (CecSettings.restoreOtpPending(sApp)) {
                boolean ok = CecSettings.setWake(sApp, true);
                Log.i(TAG, "turn on with an HDMI device restored at the start (it was off for a power-off or a guarded sleep): " + ok);
                if (ok) CecSettings.setRestoreOtpPending(sApp, false);
            }
            CecSettings.syncWakeProp(sApp);
            Log.i(TAG, "installed: CEC " + CecSettings.masterOn(sApp) + ", turn on with device " + CecSettings.wakeOn(sApp)
                    + ", send standby " + CecSettings.sendStandbyOn(sApp) + ", night guard "
                    + CecSettings.describeNight(CecSettings.nightChoice(sApp)));
        } catch (Throwable t) {
            Log.w(TAG, "startup: " + t);
        }
    }

    // =================================================================== guards

    /** Null when a CEC wake is allowed right now, else the reason. Any thread (binder reads). */
    static String wakeBlock() {
        if (!KeyReceiver.isSetupComplete(sApp)) return "setup not complete";
        Boolean m = CecSettings.masterOn(sApp);
        if (!Boolean.TRUE.equals(m)) return m == null ? "HDMI-CEC not available" : "HDMI-CEC off";
        if (!Boolean.TRUE.equals(CecSettings.wakeOn(sApp))) return "turn on with an HDMI device is off";
        if (sStormPaused) return "paused: storm";
        if (CecSettings.inNight(sApp, System.currentTimeMillis())) {
            return "night guard " + CecSettings.describeNight(CecSettings.nightChoice(sApp));
        }
        return null;
    }

    static boolean stormPaused() {
        return sStormPaused;
    }

    private static boolean isSourceType(int type) {
        return type == HdmiDeviceInfo.DEVICE_PLAYBACK || type == HdmiDeviceInfo.DEVICE_TUNER
                || type == HdmiDeviceInfo.DEVICE_RECORDER;
    }

    private static boolean interactive() {
        try {
            return sPm == null || sPm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    // =================================================================== tvinput: <Active Source>

    /**
     * StandbyStateProvider call "cec_wake" from org.z9x.tvinput (binder thread): a CEC source became the
     * active source. Extras port, la, type, vendor, osd. Returns {accepted, standby}: accepted = the
     * projector is (or is now becoming) on for this device, so tvinput may open its input.
     */
    public static Bundle onWakeRequest(Context ctx, Bundle ex) {
        install(ctx);
        Bundle r = new Bundle();
        boolean standby = StandbyController.isActive();
        r.putBoolean("standby", standby);
        r.putBoolean("accepted", false);
        try {
            int port = ex == null ? -1 : ex.getInt("port", -1);
            int la = ex == null ? -1 : ex.getInt("la", -1);
            int type = ex == null ? -1 : ex.getInt("type", -1);
            String osd = ex == null ? null : ex.getString("osd");
            final String who = TextUtils.isEmpty(osd) ? "" : osd.trim();
            String what = "src=" + la + " type=" + type + " port=" + port + " '" + who + "'";
            boolean asleep = !standby && !interactive();
            if (!standby && !asleep) {
                r.putBoolean("accepted", true);                 // projector on: tvinput's own switch rule
                final long onAt = sScreenOnAt;
                if (sArmedAtSleep && SystemClock.uptimeMillis() - onAt < CEC_AFTER_WAKE_MS && isSourceType(type)) {
                    // the framework woke Android on <Image/Text View On> in a blocked sleep (armed, so the
                    // guards passed at the sleep): no veto, but the unattended return and the storm limit
                    Ui.main().post(() -> {
                        if (sCecWakeAt >= 0 || sLastUserKeyAt > onAt || sScreenOnAt != onAt) return;
                        Log.i(TAG, "wake by HDMI-CEC (" + what + " right after SCREEN_ON, no remote key)");
                        noteCecWake(who);
                    });
                }
                return r;
            }
            String block = !isSourceType(type) || la <= 0 || la >= 15 ? "not a source device" : wakeBlock();
            if (block == null && StandbyController.shutdownInFlight()) block = "power-off in progress";
            if (block != null) {
                Log.i(TAG, "wake cec_as " + what + " ignored: " + block);
                return r;
            }
            if (standby) {
                Log.i(TAG, "wake cec_as " + what + " accepted: leaving standby");
                Ui.main().post(() -> {
                    noteCecWake(who);
                    StandbyController.exit("cec_otp:" + (who.isEmpty() ? "src " + la : who));
                });
            } else {
                Log.i(TAG, "wake cec_as " + what + " accepted: Android asleep, waking it");
                Ui.main().post(() -> noteCecWake(who));
                sPm.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_HDMI, "z9x:cec");
            }
            r.putBoolean("accepted", true);
        } catch (Throwable t) {
            Log.w(TAG, "wake request: " + t);
        }
        return r;
    }

    // =================================================================== screen hooks (App, main thread)

    /** SCREEN_OFF: arm / disarm the PM51 CEC wake for this sleep; hold the CPU for the &lt;Standby&gt; TX. */
    public static void onScreenOff(Context ctx) {
        try {
            install(ctx);
            dropLamp("screen off");
            acquireTx();
            dismissDialog();
            noteSleep("sleep");
            sWorker.post(CecPolicy::sleepWork);
        } catch (Throwable t) {
            Log.w(TAG, "screen off: " + t);
        }
    }

    /**
     * PowerPolicy.resumeSleep (main thread): this process (re)started with the display off, so SCREEN_OFF
     * will not come for the current sleep. Decide cec0 and the framework OTP wake for it like SCREEN_OFF.
     */
    public static void onSleepResumed(Context ctx) {
        try {
            install(ctx);
            Log.i(TAG, "process (re)started asleep: deciding this sleep's CEC wake again");
            sWorker.post(CecPolicy::sleepWork);
        } catch (Throwable t) {
            Log.w(TAG, "sleep resumed: " + t);
        }
    }

    /** z9x-cec, right after SCREEN_OFF (the "z9x:cecstandby" wakelock is held). */
    private static void sleepWork() {
        try {
            if (lastSleepReasonHdmi()) {
                Log.i(TAG, "sleep: HdmiControlService put Android to sleep (the watched device sent <Standby> or a"
                        + " CEC power key): the projector's sleep path runs (lamp off, STR)");
            }
            // boot default first: a persist.z9x.cec_wake change makes init write cec0 (z9x_power.rc), so the
            // per-sleep decision below must be the LAST cec0 write
            CecSettings.syncWakeProp(sApp);
            String block = wakeBlock();
            sArmedAtSleep = block == null;
            CecSettings.arm(block == null, block == null ? "sleep" : "sleep, " + block);
            if (block != null) suspendOtp(block);
            sWorker.removeCallbacksAndMessages(RECHECK_TOKEN);
            if (block == null) sWorker.postDelayed(CecPolicy::recheckAsleep, RECHECK_TOKEN, RECHECK_MS);
            sPmNameAtOff = queryPmWakeName();
            boolean tx = Boolean.TRUE.equals(CecSettings.masterOn(sApp)) && Boolean.TRUE.equals(CecSettings.sendStandbyOn(sApp))
                    && CecDevices.any(sApp);
            if (!tx) releaseTx();                           // nothing to send: suspend at once
        } catch (Throwable t) {
            Log.w(TAG, "sleep work: " + t);
            releaseTx();
        }
    }

    /** SCREEN_ON: was it a CEC wake (then guards again, or veto), else a user wake (soundbar on). */
    public static void onScreenOn(Context ctx) {
        try {
            install(ctx);
            releaseTx();
            sScreenOnAt = SystemClock.uptimeMillis();
            final boolean graced = SystemClock.uptimeMillis() - sLastVetoAt < VETO_GRACE_MS;
            sWorker.post(() -> {
                try {
                    sWorker.removeCallbacksAndMessages(RECHECK_TOKEN);
                    String src = cecWakeSource();
                    if (src == null || graced) {
                        if (src != null) Log.i(TAG, "wake (" + src + ") within " + VETO_GRACE_MS / 1000 + " s of a veto: the user's, stays on");
                        Ui.main().post(() -> releaseLamp("user wake"));
                        restoreOtp("user wake");
                        Ui.main().post(() -> onUserWake("screen on"));
                        return;
                    }
                    String block = wakeBlock();
                    if (block != null) {
                        Log.w(TAG, "wake from STR by HDMI-CEC (" + src + ") refused: " + block + ": back to sleep");
                        // the lamp stays off: the held lamp-on is dropped before anything else
                        Ui.main().post(() -> dropLamp("veto, " + block));
                        CecSettings.arm(false, "veto, " + block);
                        sArmedAtSleep = false;
                        suspendOtp(block);
                        Ui.main().post(() -> veto(block));
                        return;
                    }
                    Log.i(TAG, "wake from STR by HDMI-CEC (" + src + ") accepted");
                    Ui.main().post(() -> {
                        releaseLamp("CEC wake accepted");
                        noteCecWake("");
                        avrOnLater("cec wake");
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "screen on verdict: " + t);
                    Ui.main().post(() -> releaseLamp("verdict failed"));
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "screen on: " + t);
            releaseLamp("screen on failed");
        }
    }

    /**
     * PowerPolicy.onScreenOn (main thread, runs before {@link #onScreenOn}): a wake after a sleep with cec0
     * armed may be a CEC wake the guards refuse (night hours reached while the SoC was in STR, where
     * {@link #recheckAsleep} cannot run). Then the lamp waits for the verdict instead of lighting for
     * 1-3 s before the veto: returns true and runs {@code lampOn} once the wake is accepted (user or
     * allowed CEC), drops it on a veto (StandbyController.enter then sends 195(false) if the vendor resume
     * relit the lamp). At most {@value #LAMP_HOLD_MAX_MS} ms. False = not held, the caller lights now.
     */
    public static boolean holdLampOn(Context ctx, Runnable lampOn) {
        try {
            install(ctx);
            if (lampOn == null || !sArmedAtSleep) return false;
            if (SystemClock.uptimeMillis() - sLastVetoAt < VETO_GRACE_MS) return false;   // the user insisting
            Ui.main().removeCallbacks(sHoldTimeout);
            sHeldLampOn = lampOn;
            Ui.main().postDelayed(sHoldTimeout, LAMP_HOLD_MAX_MS);
            Log.i(TAG, "wake after an armed sleep: lamp-on waits for the CEC verdict");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "hold lamp: " + t);
            return false;
        }
    }

    /** Main thread: run the held lamp-on (if any). */
    private static void releaseLamp(String why) {
        Ui.main().removeCallbacks(sHoldTimeout);
        Runnable r = sHeldLampOn;
        sHeldLampOn = null;
        if (r == null) return;
        Log.i(TAG, "lamp-on released (" + why + ")");
        try {
            r.run();
        } catch (Throwable t) {
            Log.w(TAG, "lamp-on: " + t);
        }
    }

    /** Main thread: forget the held lamp-on (veto, or the display went off again). */
    private static void dropLamp(String why) {
        Ui.main().removeCallbacks(sHoldTimeout);
        if (sHeldLampOn != null) Log.i(TAG, "held lamp-on dropped (" + why + ")");
        sHeldLampOn = null;
    }

    private static void veto(String block) {
        sLastVetoAt = SystemClock.uptimeMillis();
        if (!interactive()) return;
        if (!StandbyController.enter(sApp, "cec_veto: " + block)) {
            Log.w(TAG, "veto: standby not possible, Android sleep");
            try {
                sPm.goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
            } catch (Throwable t) {
                Log.w(TAG, "goToSleep: " + t);
            }
        }
    }

    // =================================================================== StandbyController hooks (main thread)

    /**
     * The lamp-only standby holds (STR blocked, Android stays awake): no framework &lt;Standby&gt; comes,
     * so send it ourselves (tv_send_standby_on_sleep is the single setting for both paths).
     */
    public static void onLampOnlyStandby(Context ctx, final String reason) {
        try {
            install(ctx);
            dismissDialog();
            noteSleep("lamp-only standby");
            sWorker.post(() -> sendStandbyToDevices(reason));
        } catch (Throwable t) {
            Log.w(TAG, "lamp-only standby: " + t);
        }
    }

    /** StandbyController.exit (any wake from the lamp-only standby). */
    public static void onStandbyExit(Context ctx, String why) {
        try {
            install(ctx);
            if (why != null && why.startsWith("cec_otp")) {
                avrOnLater(why);                            // noteCecWake ran in onWakeRequest's post
                return;
            }
            onUserWake(why);
        } catch (Throwable t) {
            Log.w(TAG, "standby exit: " + t);
        }
    }

    /**
     * Main thread: this wake was accepted from an HDMI device over CEC and no remote key came since
     * (Lumen OS 1.0: RemoteAutoPair does not ask for the remote then; the user may only watch HDMI).
     */
    public static boolean cecWakeActive() {
        return sCecWakeAt >= 0 && sLastUserKeyAt <= sCecWakeAt;
    }

    /** Any global remote key (PowerUi -> StandbyController.noteUserActivity). */
    public static void onUserKey() {
        sLastUserKeyAt = SystemClock.uptimeMillis();
        if (sStormPaused) {
            sStormPaused = false;
            sBad.clear();
            Log.i(TAG, "CEC wake resumed (remote key)");
        }
    }

    // =================================================================== shutdown (z9x-cec, inside onReceive)

    private static void onShutdown() {
        try {
            CecSettings.arm(false, "power-off");
            if (Boolean.TRUE.equals(CecSettings.wakeOn(sApp))) {
                // the vendor HAL arms its own OTP wake from this setting (enableWakeupByOtp); a real
                // power-off must never end in a CEC cold boot (PLAN Q13)
                CecSettings.setRestoreOtpPending(sApp, true);
                if (!CecSettings.setWake(sApp, false)) CecSettings.setRestoreOtpPending(sApp, false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "shutdown: " + t);
        }
    }

    // =================================================================== wake bookkeeping (main thread)

    private static void noteCecWake(String who) {
        sCecWakeAt = SystemClock.uptimeMillis();
        sCecWakeWho = who == null ? "" : who;
        Ui.main().removeCallbacks(sUnattendedRun);
        Ui.main().postDelayed(sUnattendedRun, UNATTENDED_CHECK_MS);
    }

    /** The projector goes dark: a CEC wake that ends this soon counts towards the storm limit. */
    private static void noteSleep(String what) {
        Ui.main().removeCallbacks(sUnattendedRun);
        long at = sCecWakeAt;
        sCecWakeAt = -1;
        if (at >= 0) {
            long secs = (SystemClock.uptimeMillis() - at) / 1000;
            if (secs * 1000 < QUICK_SLEEP_MS) recordBad(what + " " + secs + " s after a CEC wake");
        }
    }

    private static void recordBad(String why) {
        long now = SystemClock.uptimeMillis();
        sBad.addLast(now);
        while (!sBad.isEmpty() && now - sBad.peekFirst() > STORM_WINDOW_MS) sBad.removeFirst();
        Log.i(TAG, "unwanted CEC wake (" + why + "): " + sBad.size() + " in 30 min");
        if (sBad.size() > STORM_MAX && !sStormPaused) {
            sStormPaused = true;
            sStormNotify = true;
            Log.w(TAG, "CEC wake paused: storm (" + sBad.size() + " unwanted wakes in 30 min) until the next remote key");
        }
    }

    private static void onUserWake(String why) {
        if (sStormNotify) {
            sStormNotify = false;
            try { Notify.show(sApp, sApp.getString(R.string.cec_title), sApp.getString(R.string.cec_paused)); } catch (Throwable t) { Log.w(TAG, "notify: " + t); }
        }
        avrOnLater(why);
    }

    private static void unattendedCheck() {
        if (sCecWakeAt < 0 || !interactive() || StandbyController.isActive()) return;
        if (sLastUserKeyAt > sCecWakeAt) {
            Log.i(TAG, "after the CEC wake: remote used, stays on");
            return;
        }
        final long wakeAt = sCecWakeAt;
        sWorker.post(() -> {
            int[] s = CecSettings.tvinputStreams(sApp);
            final boolean picture = s != null && s.length > 0;
            Ui.main().post(() -> {
                if (sCecWakeAt != wakeAt) return;
                if (picture) {
                    Log.i(TAG, "after the CEC wake: HDMI picture present, stays on");
                    return;
                }
                showUnattended(wakeAt);
            });
        });
    }

    private static void showUnattended(final long wakeAt) {
        if (!interactive() || StandbyController.isActive()) return;
        final long shownAt = SystemClock.uptimeMillis();
        String who = sCecWakeWho.isEmpty() ? sApp.getString(R.string.cec_device_generic) : sCecWakeWho;
        Log.i(TAG, "after the CEC wake: no picture, no remote key: asking (" + UNATTENDED_DIALOG_MS / 1000 + " s)");
        DialogPanel d = new DialogPanel(sApp.getString(R.string.cec_unattended_title),
                sApp.getString(R.string.cec_unattended_msg, who), sApp.getString(R.string.cec_stay_on), null, ok -> {
            sDialog = null;
            if (ok) {
                Log.i(TAG, "unattended check: 'Stay on'");
                sLastUserKeyAt = SystemClock.uptimeMillis();
                return;
            }
            if (SystemClock.uptimeMillis() - shownAt < UNATTENDED_DIALOG_MS - 1_000) {
                Log.i(TAG, "unattended check: card closed by the user, stays on");
                return;
            }
            if (sCecWakeAt != wakeAt || !interactive() || StandbyController.isActive()) return;
            Log.i(TAG, "unattended check: nobody there, back to sleep");
            recordBad("no picture, nobody there");
            sCecWakeAt = -1;
            StandbyController.enter(sApp, "cec_unattended");
        }).setAutoHideMs(UNATTENDED_DIALOG_MS);
        sDialog = d;
        try {
            OverlayHost.get(sApp).show(d);
        } catch (Throwable t) {
            Log.w(TAG, "unattended card: " + t);
            sDialog = null;
        }
    }

    private static void dismissDialog() {
        DialogPanel d = sDialog;
        sDialog = null;
        if (d != null) {
            try { d.dismiss(); } catch (Throwable ignored) { }
        }
    }

    // =================================================================== z9x-cec work

    private static void sendStandbyToDevices(String reason) {
        try {
            if (!Boolean.TRUE.equals(CecSettings.masterOn(sApp)) || !Boolean.TRUE.equals(CecSettings.sendStandbyOn(sApp))) return;
            if (!CecDevices.any(sApp)) return;
            HdmiControlManager m = CecSettings.hm(sApp);
            HdmiTvClient tv = m == null ? null : m.getTvClient();
            if (tv == null) return;
            // the TV takes the active source back first (F9: an echoed <Standby> is then ignored)
            tv.selectDevice(HdmiDeviceInfo.ADDR_INTERNAL, Runnable::run,
                    (result, la) -> Log.i(TAG, "standby: internal source selected -> " + result));
            SystemClock.sleep(INTERNAL_TO_STANDBY_MS);
            if (!StandbyController.isActive()) return;          // woken meanwhile
            // directed <Standby> from the TV (LA 0) to every known device, the audio system first
            // (HdmiTvClient.sendStandby; HdmiControlManager.powerOffDevice would send from the
            // "remote control source" address, which a TV does not have)
            List<CecDevices.Dev> devs = CecDevices.read(sApp);
            int sent = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (CecDevices.Dev d : devs) {
                    if (d.isAudioSystem() != (pass == 0)) continue;
                    try {
                        tv.sendStandby(d.id);
                        sent++;
                    } catch (Throwable t) {
                        Log.w(TAG, "sendStandby " + d.la + ": " + t);
                    }
                }
            }
            Log.i(TAG, "standby: <Standby> to " + sent + " HDMI device(s) (lamp-only standby, " + reason + ")");
        } catch (Throwable t) {
            Log.w(TAG, "standby to devices: " + t);
        }
    }

    private static void avrOnLater(String why) {
        sWorker.removeCallbacksAndMessages(AVR_TOKEN);
        sWorker.postDelayed(() -> avrOn(why, false), AVR_TOKEN, AVR_ON_DELAY_MS);
    }

    private static void avrOn(String why, boolean fallback) {
        try {
            if (!interactive() || StandbyController.isActive()) return;
            if (!CecSettings.avrOn(sApp)) return;
            if (!Boolean.TRUE.equals(CecSettings.masterOn(sApp)) || !Boolean.TRUE.equals(CecSettings.soundbarOn(sApp))) return;
            HdmiDeviceInfo avr = CecDevices.audioSystem(sApp);
            if (avr == null) return;
            HdmiControlManager m = CecSettings.hm(sApp);
            if (m == null) return;
            if (m.getSystemAudioMode()) {
                if (fallback) Log.i(TAG, "soundbar on (system audio active)");
                return;
            }
            HdmiTvClient tv = m.getTvClient();
            if (!fallback) {
                if (tv == null) return;
                Log.i(TAG, "soundbar: <System Audio Mode Request> (" + why + ")");
                tv.setSystemAudioMode(true, result -> Log.i(TAG, "soundbar: system audio mode -> " + result));
                sWorker.postDelayed(() -> avrOn(why, true), AVR_TOKEN, AVR_FALLBACK_MS);
            } else {
                Log.i(TAG, "soundbar still off: <User Control Pressed> Power On to " + avr.getLogicalAddress());
                m.powerOnDevice(avr);
            }
        } catch (Throwable t) {
            Log.w(TAG, "soundbar on: " + t);
        }
    }

    /**
     * z9x-cec: was this wake HDMI-CEC? Null = a user wake (or unknown). Only an ARMED sleep can end in a
     * PM51 CEC wake (cec0 is 0 otherwise); the fresh /sys/mtk_pm/wakeup_reason/name then says "cec*".
     * A name that did not change since the sleep (the previous wake was CEC too, or the node is only
     * updated at boot) still counts, the veto grace protects the user from a stale value.
     */
    private static String cecWakeSource() {
        String before = sPmNameAtOff;
        String now = queryPmWakeName();
        if (now == null) {
            // no answer: after an ARMED sleep it may well be the CEC wake, so it goes through the guards
            // (a refused one is vetoed, the veto grace still protects a real user); else a user wake
            return sArmedAtSleep ? "PM51 unknown (wake reason helper did not answer)" : null;
        }
        if (!now.equals(before)) Log.i(TAG, "PM51 wake reason '" + before + "' -> '" + now + "'");
        if (!sArmedAtSleep || !now.toLowerCase(Locale.ROOT).contains("cec")) return null;
        return "PM51 " + now + (now.equals(before) ? ", unchanged since the sleep" : "");
    }

    /**
     * z9x-cec: asks the root helper z9x_power_wake (init, z9x_power_cec.rc) for the PM51 wake name.
     * Returns null without an answer within {@value #WAKE_QUERY_TIMEOUT_MS} ms.
     */
    private static String queryPmWakeName() {
        try {
            String token = Long.toString(SystemClock.uptimeMillis(), 36) + "-" + (++sQuerySeq);
            SystemProperties.set(PROP_WAKE_QUERY, token);
            long end = SystemClock.uptimeMillis() + WAKE_QUERY_TIMEOUT_MS;
            while (true) {
                String v = SystemProperties.get(PROP_WAKE_NOW, "");
                if (v.startsWith(token + " ")) {
                    String[] p = v.split(" ");
                    String name = p.length > 1 ? p[1] : "";
                    Log.i(TAG, "wake reason: pm51=" + name + (p.length > 2 ? " kernel=" + p[2] : ""));
                    return "unknown".equals(name) ? "" : name;
                }
                if (SystemClock.uptimeMillis() >= end) {
                    Log.w(TAG, "wake reason helper: no answer (" + PROP_WAKE_NOW + "='" + v + "')");
                    return null;
                }
                SystemClock.sleep(40);
            }
        } catch (Throwable t) {
            Log.w(TAG, "wake reason helper: " + t);
            return null;
        }
    }

    /**
     * z9x-cec: a sleep the guards do not allow while "Turn on with an HDMI device" is on: switch the
     * framework setting off for this sleep (HdmiControlService's own &lt;Image/Text View On&gt; wake in a
     * blocked sleep and the vendor HAL's enableWakeupByOtp), restored by {@link #restoreOtp}.
     */
    private static void suspendOtp(String why) {
        try {
            if (!Boolean.TRUE.equals(CecSettings.masterOn(sApp)) || !Boolean.TRUE.equals(CecSettings.wakeOn(sApp))) return;
            CecSettings.setRestoreOtpPending(sApp, true);
            if (CecSettings.setWake(sApp, false)) {
                Log.i(TAG, "turn on with an HDMI device: off for this sleep (" + why + ")");
            } else {
                CecSettings.setRestoreOtpPending(sApp, false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "suspend OTP: " + t);
        }
    }

    /** z9x-cec: undo {@link #suspendOtp} (a user wake). */
    private static void restoreOtp(String why) {
        try {
            if (!CecSettings.restoreOtpPending(sApp)) return;
            if (CecSettings.setWake(sApp, true)) {
                CecSettings.setRestoreOtpPending(sApp, false);
                Log.i(TAG, "turn on with an HDMI device: back on (" + why + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "restore OTP: " + t);
        }
    }

    /**
     * z9x-cec, every {@value #RECHECK_MS} ms of an armed sleep while the CPU runs (blocked sleep; in STR
     * uptime stands still and nothing runs): night hours that begin during the sleep disarm it.
     */
    private static void recheckAsleep() {
        try {
            if (interactive() || !sArmedAtSleep) return;
            String block = wakeBlock();
            if (block != null) {
                sArmedAtSleep = false;
                CecSettings.arm(false, "asleep, " + block);
                suspendOtp(block);
                return;
            }
            sWorker.postDelayed(CecPolicy::recheckAsleep, RECHECK_TOKEN, RECHECK_MS);
        } catch (Throwable t) {
            Log.w(TAG, "recheck: " + t);
        }
    }

    private static boolean lastSleepReasonHdmi() {
        try {
            return sPm != null && sPm.getLastSleepReason() == PowerManager.GO_TO_SLEEP_REASON_HDMI;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void acquireTx() {
        try { if (sTxLock != null) sTxLock.acquire(TX_HOLD_MS); } catch (Throwable t) { Log.w(TAG, "tx wakelock: " + t); }
    }

    private static void releaseTx() {
        try { if (sTxLock != null && sTxLock.isHeld()) sTxLock.release(); } catch (Throwable t) { Log.w(TAG, "tx wakelock release: " + t); }
    }
}
