package org.z9x.projector.power;

import android.os.SystemClock;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.Ui;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The projector lamp of the XGIMI Play 6 while Android stays awake (docs/play6/research/sleep_stock.md
 * 9.2 item 2): only IGmpf 195 setScreenOnOff and 196 getScreenOnOff, the calls of the stock power menu's
 * "Screen off" and of the stock framework around its sleep. The Play 6 idea of the Z9X LampControl, without
 * its motor, eye-protection, lamp-level and escalation parts. No other power call: IGmpf 228 to 234, 241,
 * 422 and 543 stay banned, nothing under /sys, no property writes.
 *
 * All HAL I/O goes through Hal.query on the p6-hal thread, and only while the start gate is open (before
 * it nothing is sent and the result is null). Results come back on the MAIN thread. Every off / on starts
 * a new generation; the polling of an older one stops. Log tag P6Lamp. Callers: power/StandbyController (the
 * lamp-only standby) and speaker/SpeakerMode (the light off of the Bluetooth speaker mode, P3 item 21; it never
 * runs together with the standby: entering the standby closes the speaker mode's window first).
 *  - {@link #off}: 196, then 195(false) when it does not read off, then 196 again; tried again 1 s apart
 *    up to {@value #OFF_TRIES} times while the lamp does not read off.
 *  - {@link #on}: 195(true) at once, then 196 every {@value #POLL_MS} ms for up to {@value #ON_WAIT_MS} ms,
 *    with one more 195(true) after {@value #ON_RESEND_MS} ms if it still reads off (lamp-only standby exit:
 *    the picture is back in well under 1 s).
 *  - {@link #awaitOn}: after an Android wake (SCREEN_ON). Polls 196 first, because after an STR resume the
 *    vendor relights the lamp by itself (stock xgimiWakeUp polls 196 up to 6 s, sends nothing); only if it
 *    still reads off after {@value #AWAIT_SEND_MS} ms is 195(true) sent, and polling goes on to
 *    {@value #AWAIT_MAX_MS} ms.
 */
public final class Lamp {
    private static final String TAG = "P6Lamp";
    static final long POLL_MS = 250;
    static final long ON_WAIT_MS = 3_000;
    static final long ON_RESEND_MS = 1_500;
    static final long AWAIT_SEND_MS = 4_000;
    static final long AWAIT_MAX_MS = 6_000;
    static final int OFF_TRIES = 3;
    static final long OFF_RETRY_MS = 1_000;

    /** Result on the MAIN thread: the last 196 read (true on, false off), null when unknown. */
    public interface Done {
        void done(Boolean on);
    }

    private static final AtomicInteger GEN = new AtomicInteger();

    private Lamp() {}

    /** Lamp off (lamp-only standby, before STR). Main thread. */
    public static void off(String why, Done done) {
        final int gen = GEN.incrementAndGet();
        offTry(gen, why, 1, done);
    }

    private static void offTry(int gen, String why, int attempt, Done done) {
        if (!Hal.isGateOpen()) {
            Log.w(TAG, "off (" + why + "): start gate closed, nothing sent");
            deliver(done, null);
            return;
        }
        Hal.query((g, g2) -> {
            boolean before = g.getScreenOnOff();                     // 196
            if (before) g.setScreenOnOff(false);                     // 195(false)
            return g.getScreenOnOff();                               // 196
        }, on -> {
            if (gen != GEN.get()) return;                            // a newer on / off
            if (Boolean.FALSE.equals(on)) {
                Log.i(TAG, "off (" + why + "): 196 reads off" + (attempt > 1 ? " (try " + attempt + ")" : ""));
                deliver(done, false);
            } else if (attempt < OFF_TRIES) {
                Log.w(TAG, "off (" + why + "): 196 reads " + on + ", try " + (attempt + 1) + " in 1 s");
                Ui.main().postDelayed(() -> { if (gen == GEN.get()) offTry(gen, why, attempt + 1, done); },
                        OFF_RETRY_MS);
            } else {
                Log.w(TAG, "off (" + why + "): not confirmed after " + OFF_TRIES + " tries (196 " + on + ")");
                deliver(done, on);
            }
        });
    }

    /** Lamp on at once (lamp-only standby exit). Main thread. */
    public static void on(String why, Done done) {
        final int gen = GEN.incrementAndGet();
        poll(gen, why, SystemClock.uptimeMillis(), true, ON_RESEND_MS, ON_WAIT_MS, done);
    }

    /** After an Android wake: wait for the vendor to relight the lamp, send 195(true) only if it does not. */
    static void awaitOn(String why, Done done) {
        final int gen = GEN.incrementAndGet();
        poll(gen, why, SystemClock.uptimeMillis(), false, AWAIT_SEND_MS, AWAIT_MAX_MS, done);
    }

    /**
     * One step: 195(true) first when {@code send}, then 196. Read on: done(true). Else poll again in
     * POLL_MS; at {@code sendAt} (once) with 195(true); after {@code maxMs} done with the last read.
     */
    private static void poll(int gen, String why, long t0, boolean send, long sendAt, long maxMs, Done done) {
        if (!Hal.isGateOpen()) {
            Log.i(TAG, "on (" + why + "): start gate closed, nothing sent");
            deliver(done, null);
            return;
        }
        Hal.query((g, g2) -> {
            if (send) g.setScreenOnOff(true);                        // 195(true)
            return g.getScreenOnOff();                               // 196
        }, on -> {
            if (gen != GEN.get()) return;
            long el = SystemClock.uptimeMillis() - t0;
            if (Boolean.TRUE.equals(on)) {
                Log.i(TAG, "on (" + why + "): 196 reads on after " + el + " ms");
                deliver(done, true);
                return;
            }
            if (el >= maxMs) {
                Log.w(TAG, "on (" + why + "): 196 reads " + on + " after " + el + " ms, giving up");
                deliver(done, on);
                return;
            }
            final boolean sendNow = sendAt >= 0 && el >= sendAt;
            if (sendNow) Log.i(TAG, "on (" + why + "): 196 reads " + on + " after " + el + " ms, 195(true) again");
            Ui.main().postDelayed(() -> {
                if (gen == GEN.get()) poll(gen, why, t0, sendNow, sendNow ? -1 : sendAt, maxMs, done);
            }, POLL_MS);
        });
    }

    private static void deliver(Done done, Boolean on) {
        if (done == null) return;
        try { done.done(on); } catch (Throwable t) { Log.w(TAG, "result: " + t); }
    }
}
