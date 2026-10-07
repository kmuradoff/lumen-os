package org.z9x.projector.power;

import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.hal.GmpfClient;

import java.util.function.BooleanSupplier;

/**
 * MODULE "power" (v6.5): the one place that switches the projector lamp off and on (IGmpf 196 / 195,
 * 147 / 148), shared by {@link PowerPolicy} (Android screen off / on, the "foreign" sleeps) and
 * {@link StandbyController} (our lamp-only standby, the display stays on). Moved here unchanged from
 * PowerPolicy v6.4 (lamp-off task, no-STR and STR lamp-on paths, HAL reconnect re-runs); new are the
 * owner guard, the 60 s escalation and the lamp re-check.
 *
 * <b>Generations.</b> Every {@link #lampOff} / {@link #lampOn} call starts a new generation; queued
 * work of an older one is dropped. Work also stops when its {@link Guard} says the request is no
 * longer wanted (PowerPolicy: the screen is on again; StandbyController: standby ended).
 *
 * <b>Lamp off</b> (z9x-hal): confirmed focus-motor stop (retried once; the vendor ignores
 * manualFocus(2) once the display is off) -> give back a lamp level dimmed by the sleep timer
 * (TimerLamp) or the screensaver (DreamLamp) while the lamp is still on -> 196, and 195(false) when it
 * reads true (stock xgimiPrepareSleep step 7). A skipped (HAL not connected) or failed attempt is
 * retried 1 s apart three times, then every 5 s; {@link Result#done} gets {@code true} on success, or
 * {@code false} once {@link #OFF_GIVE_UP_MS} passed without success (the caller then powers off: the
 * lamp must never stay lit behind a "sleeping" projector). The request stays pending until it
 * succeeded, so a HAL (re)connect runs it again. That result only comes when an attempt fails or is
 * skipped: an attempt that BLOCKS in a gmpf binder call (gmpf_main hung, not dead) or a wedged z9x-hal
 * queue gives no result at all, so the callers do not rely on it (review 6.5): StandbyController
 * arms the deadline and a main-thread give-up timer ({@link #isOffPending} still true after
 * {@link #OFF_GIVE_UP_MS} + 5 s -> power off) when the lamp-off starts.
 *
 * <b>Lamp on</b>: noStr -> 196, then 147 -> 148(true) when eye protection had blanked the light
 * (only with the NUI fifo reader up), then 195(true); after an STR resume -> poll 196 1 s x7 like
 * stock XgimiWindowManager.checkWakeUpStatus, then force 195(true). Skips / errors re-arm 1 s apart
 * up to 10 times, then a HAL reconnect runs the STR-path sequence again. Every confirmed lamp-on
 * calls PowerUi.onLampOn (wake curtain reveal, lamp level / eye restore hooks).
 *
 * <b>Re-check</b> ({@link #recheckOff}): while the lamp should stay off, 196 true -> 195(false); used
 * by StandbyController's lamp watchdog and on every HAL reconnect (a gmpf_main restart may light it).
 *
 * Never: IGmpf 241 / 422 / 229 / 233 / 543, PowerManager.forceSuspend; 148 only with true.
 * HAL calls only through {@link Hal#runDelayed} (z9x-hal); scheduling on "z9x-lampctl".
 */
final class LampControl {
    private static final String TAG = "Z9xLamp";

    /** Is the request still wanted (besides being the newest generation)? Any thread. */
    interface Guard { boolean ok(); }

    /** Lamp-off outcome. ok=false only after {@link #OFF_GIVE_UP_MS}. Any thread. */
    interface Result { void done(boolean ok, String detail); }

    /** Re-check outcome: relit = 196 read true and 195(false) was sent. z9x-hal thread. */
    interface Recheck { void checked(boolean relit); }

    static final long OFF_GIVE_UP_MS = 60_000;
    private static final long MOTOR_STOP_WAIT_MS = 1_500;
    private static final long RETRY_MS = 1_000;
    private static final long SLOW_RETRY_MS = 5_000;
    private static final int FAST_OFF_TRIES = 3;
    private static final long STR_POLL_MS = 1_000;          // stock checkWakeUpStatus: 1 s
    /**
     * Lumen OS 1.0: STR is the normal "off", so the resume path decides how long the picture stays black
     * after a wake. Stock checkWakeUpStatus polled 196 up to 7 times; we poll
     * persist.z9x.str_lamp_polls times (default 3, 1..7), then force 195(true).
     */
    private static final int STR_POLL_DEFAULT = 3, STR_POLL_MAX = 7;
    private static final int LAMP_ON_TRIES = 10;

    private static final int WANT_NONE = 0, WANT_OFF = 1, WANT_ON = 2;

    private static SafeHandler sWorker;
    private static volatile int sGen;
    private static volatile int sWant = WANT_NONE;
    private static volatile String sOwner = "-";
    private static volatile Guard sGuard;
    private static volatile Result sOffResult;
    private static volatile long sOffStart;
    /** WANT_OFF not yet confirmed (196 false or 195(false) done). */
    private static volatile boolean sOffPending;
    /** WANT_ON not yet confirmed (196 true or 195(true) sent). */
    private static volatile boolean sOnPending;
    /** True after our own 195(false) (z9x-hal only). For logging. */
    private static boolean sLampOffByUs;

    private LampControl() {}

    static synchronized void install() {
        if (sWorker != null) return;
        sWorker = SafeHandler.newThread("z9x-lampctl");
        Hal.addConnectedListener(LampControl::onHalConnected);
    }

    private static SafeHandler worker() {
        if (sWorker == null) install();
        return sWorker;
    }

    // ================================================================== API

    /** Starts a lamp-off request. Returns its generation. Any thread. */
    static synchronized int lampOff(String owner, Guard guard, Result result) {
        final int gen = ++sGen;
        sWant = WANT_OFF;
        sOwner = owner;
        sGuard = guard;
        sOffResult = result;
        sOffStart = android.os.SystemClock.uptimeMillis();
        sOffPending = true;
        sOnPending = false;
        Log.i(TAG, "lamp off requested by " + owner + " gen=" + gen);
        worker().post(() -> offStep(gen, 1));
        return gen;
    }

    /**
     * Starts a lamp-on request. {@code noStr} is evaluated on the lamp thread (it may read sysfs):
     * true = 147/148/195(true) path after {@code delayMs}, false = the STR poll path. Any thread.
     */
    static synchronized int lampOn(String owner, BooleanSupplier noStr, long delayMs, Guard guard) {
        final int gen = ++sGen;
        sWant = WANT_ON;
        sOwner = owner;
        sGuard = guard;
        sOffResult = null;
        sOffPending = false;
        sOnPending = true;
        Log.i(TAG, "lamp on requested by " + owner + " gen=" + gen);
        worker().post(() -> {
            if (!stillWanted(gen, WANT_ON)) return;
            boolean ns = true;
            try { ns = noStr.getAsBoolean(); } catch (Throwable t) { Log.w(TAG, "noStr: " + t); }
            onStep(gen, ns, 1, 1, ns ? delayMs : STR_POLL_MS);
        });
        return gen;
    }

    /** Drops every queued step (nothing is sent). Any thread. */
    static synchronized void cancel(String why) {
        ++sGen;
        sWant = WANT_NONE;
        sOffPending = false;
        sOnPending = false;
        sOffResult = null;
        Log.i(TAG, "lamp requests canceled: " + why);
    }

    /** gen is the newest request. */
    static boolean isCurrent(int gen) {
        return gen == sGen;
    }

    static boolean isOffPending() {
        return sWant == WANT_OFF && sOffPending;
    }

    /**
     * While the lamp should be off (gen still current): 196 true -> 195(false). {@code cb} (may be
     * null) gets the result on z9x-hal. Returns false when the check could not be queued.
     */
    static boolean recheckOff(final int gen, final String why, final Recheck cb) {
        return Hal.runDelayed((g, g2) -> {
            if (!stillWanted(gen, WANT_OFF)) return;
            boolean relit = false;
            try {
                if (g.getScreenOn()) {                              // 196
                    g.setScreenOn(false);                           // 195(false)
                    sLampOffByUs = true;
                    relit = true;
                    Log.w(TAG, "lamp was on again (" + why + "): 195(false)");
                }
            } catch (Throwable t) {
                Log.w(TAG, "re-check (" + why + "): " + t);
            }
            if (cb != null) {
                try { cb.checked(relit); } catch (Throwable t) { Log.w(TAG, "re-check callback: " + t); }
            }
        }, 0, null);
    }

    private static boolean stillWanted(int gen, int want) {
        if (gen != sGen || sWant != want) return false;
        Guard g = sGuard;
        try {
            return g == null || g.ok();
        } catch (Throwable t) {
            Log.w(TAG, "guard: " + t);
            return true;
        }
    }

    // ================================================================== lamp off

    /** z9x-lampctl (or a retry): queue one lamp-off attempt on the HAL thread. */
    private static void offStep(final int gen, final int attempt) {
        if (!stillWanted(gen, WANT_OFF)) return;
        boolean queued = Hal.runDelayed((g, g2) -> {
            if (!stillWanted(gen, WANT_OFF)) return;
            try {
                boolean stopped = Hal.stopMotorAndWait(MOTOR_STOP_WAIT_MS);
                if (!stopped) {
                    Log.w(TAG, "lamp off: motor stop not confirmed, retrying once");
                    stopped = Hal.stopMotorAndWait(MOTOR_STOP_WAIT_MS);
                }
                Log.i(TAG, "lamp off: motor stop confirmed=" + stopped);
                if (!stillWanted(gen, WANT_OFF)) return;
                // give back a lamp level dimmed by the sleep timer / the screensaver while the lamp is
                // still on, so the next wake has the user's brightness (176 is never sent after 195(false))
                PowerUi.restoreLampOnHalThread(g);
                try {
                    org.z9x.projector.dream.DreamLamp.restoreOnHalThread(g);
                } catch (Throwable t) {
                    Log.w(TAG, "DreamLamp restore: " + t);
                }
                String detail;
                if (g.getScreenOn()) {                              // 196
                    g.setScreenOn(false);                           // 195(false)
                    sLampOffByUs = true;
                    detail = "195(false)";
                    Log.i(TAG, "lamp off: 195(false) (" + sOwner + ")");
                } else {
                    detail = "already off (196=false)";
                    Log.i(TAG, "lamp already off (196=false), nothing sent (" + sOwner + ")");
                }
                offDone(gen, true, detail);
            } catch (Throwable e) {
                offFailed(gen, attempt, "196/195(false) failed: " + e);
            }
        }, 0, () -> offFailed(gen, attempt, "HAL not connected"));
        if (!queued) offFailed(gen, attempt, "HAL not available");
    }

    private static void offFailed(int gen, int attempt, String why) {
        if (!stillWanted(gen, WANT_OFF)) return;
        long spent = android.os.SystemClock.uptimeMillis() - sOffStart;
        if (spent >= OFF_GIVE_UP_MS) {
            Log.w(TAG, "lamp off: " + why + "; still not off after " + spent / 1000 + " s ("
                    + attempt + " tries); request stays pending for a HAL reconnect");
            Result r = sOffResult;
            if (r != null) {
                try { r.done(false, why); } catch (Throwable t) { Log.w(TAG, "off result: " + t); }
            }
            return;
        }
        long next = attempt < FAST_OFF_TRIES ? RETRY_MS : SLOW_RETRY_MS;
        Log.w(TAG, "lamp off attempt " + attempt + ": " + why + "; retry in " + next + " ms");
        worker().postDelayed(() -> offStep(gen, attempt + 1), next);
    }

    private static void offDone(int gen, boolean ok, String detail) {
        if (gen != sGen) return;
        sOffPending = false;
        Result r = sOffResult;
        if (r != null) {
            try { r.done(ok, detail); } catch (Throwable t) { Log.w(TAG, "off result: " + t); }
        }
    }

    // ================================================================== lamp on

    private static void onStep(final int gen, final boolean noStr, final int poll, final int tries, long delayMs) {
        if (!stillWanted(gen, WANT_ON)) return;
        boolean queued = Hal.runDelayed((g, g2) -> {
            if (!stillWanted(gen, WANT_ON)) return;
            try {
                if (noStr) {
                    lightOnNoStr(g, gen);
                } else if (!pollAfterStr(g, gen, poll)) {
                    onStep(gen, false, poll + 1, tries, STR_POLL_MS);   // next 196 poll
                }
            } catch (Throwable e) {
                onFailed(gen, noStr, poll, tries, "error: " + e);
            }
        }, delayMs, () -> onFailed(gen, noStr, poll, tries, "HAL not connected"));
        if (!queued) onFailed(gen, noStr, poll, tries, "HAL not available");
    }

    private static void onFailed(int gen, boolean noStr, int poll, int tries, String why) {
        if (!stillWanted(gen, WANT_ON)) return;
        if (tries >= LAMP_ON_TRIES) {
            Log.w(TAG, "lamp on gave up after " + tries + " tries (" + why + "); a HAL reconnect will retry");
            return;
        }
        Log.w(TAG, "lamp on try " + tries + ": " + why + "; retry in " + RETRY_MS + " ms");
        worker().postDelayed(() -> onStep(gen, noStr, poll, tries + 1, 0), RETRY_MS);
    }

    /** No STR happened: give the light back promptly (147 -> 148(true) if needed, then 195(true)). */
    private static void lightOnNoStr(GmpfClient g, int gen) throws Exception {
        if (!stillWanted(gen, WANT_ON)) return;
        if (g.getScreenOn()) {                                   // 196
            Log.i(TAG, "lamp on: already on (" + sOwner + ")");
            onConfirmed(gen);
            return;
        }
        Log.i(TAG, "lamp on: lamp off (ours=" + sLampOffByUs + "), restoring (" + sOwner + ")");
        boolean eyeOn;
        try {
            eyeOn = g.getEyeProtectionScreenOn();                // 147
        } catch (Exception e) {
            Log.w(TAG, "147 failed: " + e);
            eyeOn = true;                                        // unknown: do not send 148
        }
        if (!eyeOn && !GmpfClient.nuiReaderReady()) {
            // 148's on-path calls the vendor nuiCommand, which blocks this thread and a HAL binder
            // thread until reboot when no fifo reader (z9x_nui) exists.
            Log.w(TAG, "eye protection had blanked the light, but no NUI fifo reader ("
                    + GmpfClient.NUI_READY_PROP + " != ready): 148 not sent");
        } else if (!eyeOn) {
            try {
                boolean r = g.restoreEyeProtectionScreen();      // 148(true) only
                Log.i(TAG, "eye protection had blanked the light: 148(true) -> " + r);
            } catch (Exception e) {
                Log.w(TAG, "148(true) failed: " + e);
            }
        }
        if (!stillWanted(gen, WANT_ON)) return;
        g.setScreenOn(true);                                     // 195(true)
        Log.i(TAG, "lamp on: 195(true) (" + sOwner + ")");
        onConfirmed(gen);
    }

    /** After a real STR resume: poll 196 1 s x7 like stock, then force 195(true). true = done. */
    private static boolean pollAfterStr(GmpfClient g, int gen, int attempt) throws Exception {
        if (!stillWanted(gen, WANT_ON)) return true;
        boolean on;
        try {
            on = g.getScreenOn();                                // 196
        } catch (Exception e) {
            Log.w(TAG, "196 poll " + attempt + " failed: " + e);
            on = false;
        }
        if (on) {
            Log.i(TAG, "STR resume: lamp on after " + attempt + " poll(s), vendor restored it");
            onConfirmed(gen);
            return true;
        }
        int tries = strPollTries();
        if (attempt < tries) return false;
        g.setScreenOn(true);                                     // 195(true); throws -> retried
        Log.i(TAG, "STR resume: lamp still off after " + tries + " poll(s), forced 195(true)");
        onConfirmed(gen);
        return true;
    }

    private static int strPollTries() {
        int n = STR_POLL_DEFAULT;
        try {
            n = Integer.parseInt(android.os.SystemProperties.get("persist.z9x.str_lamp_polls", "").trim());
        } catch (Throwable ignored) { }
        return Math.max(1, Math.min(STR_POLL_MAX, n));
    }

    private static void onConfirmed(int gen) {
        sLampOffByUs = false;
        if (gen == sGen) sOnPending = false;
        PowerUi.onLampOn();                                      // wake curtain reveal etc.
    }

    // ================================================================== HAL (re)connect

    /**
     * z9x-hal (Hal.addConnectedListener): the HAL (re)connected.
     *  - lamp-off still pending: run the lamp-off task again (same generation);
     *  - lamp off already confirmed and still wanted: re-check (a restarted gmpf_main may light it);
     *  - lamp-on still pending: the STR-path sequence (poll 196 up to 7 times, then 195(true)).
     */
    private static void onHalConnected() {
        final int gen = sGen;
        if (sWant == WANT_OFF) {
            if (!stillWanted(gen, WANT_OFF)) return;
            if (sOffPending) {
                Log.i(TAG, "HAL connected with a lamp-off pending (" + sOwner + "): running it again");
                worker().post(() -> offStep(gen, 1));
            } else {
                Log.i(TAG, "HAL connected while the lamp should be off (" + sOwner + "): re-checking 196");
                recheckOff(gen, "HAL reconnect", null);
            }
            return;
        }
        if (sWant == WANT_ON && sOnPending && stillWanted(gen, WANT_ON)) {
            Log.i(TAG, "HAL connected with a lamp-on pending (" + sOwner + "): checking 196");
            worker().post(() -> onStep(gen, false, 1, 1, 0));
        }
    }
}
