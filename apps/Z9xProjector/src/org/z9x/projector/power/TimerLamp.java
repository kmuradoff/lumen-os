package org.z9x.projector.power;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.hal.GmpfClient;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * MODULE "power" (v6.2): the lamp part of the sleep-timer fade-out. Steps IGmpf 176
 * setDlpLumensLevel down to level 1 while the curtain fades the picture, and gives the user's
 * level back before the lamp goes off (PowerPolicy's lamp-off task calls
 * {@link #restoreOnHalThread}), so the next wake comes up at the user's brightness.
 *
 * HAL (typed whitelist only): 177 getDlpLumensLevel, 176 setDlpLumensLevel (1..10, range-checked
 * by GmpfClient), 196 getScreenOnOff and IGmpf2 51 isBoost (read-only). At most
 * {@link #MAX_STEPS} 176 calls per fade (spread over ~18 s) plus one restore; never below 1, never
 * brighter than the user's level while fading. No dim while Boost is on or unknown.
 *
 * Crash safety (same idea as the dream module's DreamLamp): the vendor most likely persists 176
 * per lumens mode (research/v62/dream F9, LIKELY), so before the first 176 the marker
 * {saved, target} is committed to device-protected prefs. On every HAL (re)connect without live
 * state, a marker whose level still reads inside [target, saved) is restored, then cleared.
 *
 * Restore rule: write the saved level back only if 177 still reads inside [target, saved), i.e.
 * a level this fade produced (during the fade the user cannot reach the brightness slider: any key
 * aborts the fade first). 176 is never sent while 196 reads false (effect unknown); such a restore
 * waits for {@link #onLampOn}.
 *
 * Threads: API on main; HAL work on "z9x-hal" (sSaved / sLast / sTarget confined there); prefs on
 * "z9x-powerui". Everything wrapped: the persistent process must never crash.
 */
final class TimerLamp {
    private static final String TAG = "Z9xTimerLamp";
    private static final String PREFS = "z9x_timer_lamp";
    private static final String K_SAVED = "saved";
    private static final String K_TARGET = "target";
    static final int MAX_STEPS = 5;

    private static Context sApp;
    private static SafeHandler sWorker;
    private static final AtomicInteger sGen = new AtomicInteger();

    // ---- z9x-hal thread only
    private static int sSaved;        // user level before the fade, 0 = nothing to restore
    private static int sLast;         // last level we set
    private static int sTarget;       // fade target (1)
    private static boolean sPendingLampOn;

    // ---- marker mirror (written on z9x-powerui, read on z9x-hal)
    private static volatile int sMarkerSaved;
    private static volatile int sMarkerTarget;
    /**
     * True while the marker was written by this process for a fade whose first 176 has not run yet
     * (set on z9x-powerui right after the marker, cleared on z9x-hal by the first real step). Such a
     * marker is ours and harmless to drop when the fade is aborted before any step.
     */
    private static volatile boolean sMarkerUnstepped;

    private TimerLamp() {}

    static synchronized void install(Context ctx, SafeHandler worker) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        sWorker = worker;
        sWorker.post(() -> {
            try {
                SharedPreferences p = prefs();
                sMarkerSaved = p.getInt(K_SAVED, 0);
                sMarkerTarget = p.getInt(K_TARGET, 0);
                if (sMarkerSaved != 0) Log.i(TAG, "marker found: saved " + sMarkerSaved + " target " + sMarkerTarget);
            } catch (Throwable t) {
                Log.w(TAG, "marker load: " + t);
            }
        });
        Hal.addConnectedListener(TimerLamp::recover);
    }

    /**
     * Main thread. Steps the lamp from the current level down to {@code target} over
     * {@code durationMs}. Skipped (logged) when the HAL is not ready, Boost is on or unknown, or the
     * level is already at or below the target.
     */
    static void fadeDown(Context ctx, final int target, final long durationMs) {
        if (sApp == null || !Hal.isReady()) {
            Log.i(TAG, "HAL not ready: no lamp fade");
            return;
        }
        final int gen = sGen.incrementAndGet();
        Hal.query((g, g2) -> {
            int boost;
            try { boost = g2.isBoost() ? 1 : 0; } catch (Throwable t) { boost = -1; }
            return new int[]{g.getLampLevel(), boost};
        }, r -> {
            if (gen != sGen.get()) return;
            if (r == null) { Log.i(TAG, "177 unreadable: no lamp fade"); return; }
            final int cur = r[0];
            final int tgt = Math.max(1, Math.min(10, target));
            if (r[1] != 0) { Log.i(TAG, "Boost on or unknown (" + r[1] + "): no lamp fade"); return; }
            if (cur <= tgt || cur > 10) { Log.i(TAG, "level " + cur + ": nothing to fade"); return; }
            final int n = Math.min(MAX_STEPS, cur - tgt);
            sWorker.post(() -> {
                if (gen != sGen.get()) { Log.i(TAG, "fade aborted before start"); return; }
                writeMarker(cur, tgt);                                // write-ahead, before any 176
                sMarkerUnstepped = true;
                if (gen != sGen.get()) {                              // aborted while writing
                    sMarkerUnstepped = false;
                    clearMarker();
                    return;
                }
                for (int i = 1; i <= n; i++) {
                    final int level = cur - Math.round((cur - tgt) * (i / (float) n));
                    final boolean first = i == 1;
                    long at = Math.round(durationMs * (i - 0.5f) / n);
                    Hal.runDelayed((g, g2) -> step(g, gen, cur, tgt, level, first), at);
                }
            });
        });
    }

    /** z9x-hal: one step of the fade. */
    private static void step(GmpfClient g, int gen, int cur, int tgt, int level, boolean first) throws Exception {
        if (gen != sGen.get()) return;                               // aborted / restored meanwhile
        if (first) {
            if (sSaved == 0) { sSaved = cur; sLast = cur; }
            sTarget = tgt;
        }
        if (sSaved == 0) return;
        int now = g.getLampLevel();                                  // 177
        if (now != sLast) {
            Log.i(TAG, "level changed outside the fade (" + now + " != " + sLast + "): fade stopped");
            sGen.incrementAndGet();
            return;
        }
        sMarkerUnstepped = false;                                    // marker now guards a real 176
        g.setLampLevel(level);                                       // 176
        sLast = level;
        Log.i(TAG, "fade step " + now + " -> " + level);
    }

    /** Main thread: abort a running fade and give the level back now (fade aborted, SCREEN_ON). */
    static void restore() {
        if (sApp == null) return;
        sGen.incrementAndGet();
        Hal.run((g, g2) -> restoreCore(g, "restore"));
    }

    /** z9x-hal ONLY: PowerPolicy's lamp-off task, before 196 / 195(false). */
    static void restoreOnHalThread(GmpfClient g) {
        sGen.incrementAndGet();
        try {
            restoreCore(g, "before lamp off");
        } catch (Throwable t) {
            Log.w(TAG, "restore before lamp off: " + t);
        }
    }

    /**
     * Any thread: the lamp is confirmed on after a wake. A fade level still left (restore deferred
     * because the lamp was off, or the screen came back before the lamp-off task) is given back now,
     * while the wake curtain is still black.
     */
    static void onLampOn() {
        if (sApp == null) return;
        Hal.run((g, g2) -> {
            if (sSaved != 0) {
                sGen.incrementAndGet();
                restoreCore(g, "after lamp on");
            } else if (sMarkerUnstepped) {
                sGen.incrementAndGet();
                restoreCore(g, "after lamp on");                     // drops an unstepped marker
            } else if (sMarkerSaved != 0) {
                recover();                                           // marker left from a lamp-off connect
            }
        });
    }

    /** z9x-hal. */
    private static void restoreCore(GmpfClient g, String why) throws Exception {
        if (sSaved == 0) {
            if (sMarkerUnstepped) {                                  // fade aborted before its first step
                sMarkerUnstepped = false;
                Log.i(TAG, why + ": fade never stepped, marker dropped");
                sWorker.post(TimerLamp::clearMarker);
            }
            return;
        }
        if (!g.getScreenOn()) {                                      // 196: never 176 with the lamp off
            sPendingLampOn = true;
            Log.i(TAG, why + ": lamp is off, restore deferred to lamp-on");
            return;
        }
        sPendingLampOn = false;
        int now = g.getLampLevel();
        int saved = sSaved, tgt = sTarget;
        sSaved = 0;
        if (now >= tgt && now < saved) {
            g.setLampLevel(saved);
            Log.i(TAG, why + ": lamp " + now + " -> " + saved);
        } else {
            Log.i(TAG, why + ": lamp reads " + now + " (not ours), kept");
        }
        sWorker.post(TimerLamp::clearMarker);
    }

    /**
     * z9x-hal (Hal.addConnectedListener, no clients there): process restarted or power lost while
     * faded. The body is queued through Hal.run to get the clients.
     */
    private static void recover() {
        try {
            if (sSaved != 0 || sMarkerSaved == 0 || sMarkerUnstepped) return;   // live fade of ours
            final int saved = sMarkerSaved, tgt = sMarkerTarget;
            Hal.run((g, g2) -> {
                if (sSaved != 0 || sMarkerSaved == 0 || sMarkerUnstepped) return;
                if (!g.getScreenOn()) return;                        // retried on the next connect
                int now = g.getLampLevel();
                if (now >= tgt && now < saved) {
                    g.setLampLevel(saved);
                    Log.i(TAG, "recovered after restart: lamp " + now + " -> " + saved);
                } else {
                    Log.i(TAG, "marker found, lamp reads " + now + ": kept");
                }
                sWorker.post(TimerLamp::clearMarker);
            });
        } catch (Throwable t) {
            Log.w(TAG, "recover: " + t);
        }
    }

    private static void writeMarker(int saved, int tgt) {
        try {
            prefs().edit().putInt(K_SAVED, saved).putInt(K_TARGET, tgt).commit();
            sMarkerSaved = saved;
            sMarkerTarget = tgt;
        } catch (Throwable t) {
            Log.w(TAG, "marker write: " + t);
        }
    }

    private static void clearMarker() {
        try {
            if (sMarkerSaved == 0) return;
            prefs().edit().clear().commit();
            sMarkerSaved = 0;
            sMarkerTarget = 0;
        } catch (Throwable t) {
            Log.w(TAG, "marker clear: " + t);
        }
    }

    private static SharedPreferences prefs() {
        return sApp.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
