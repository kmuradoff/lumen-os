package org.z9x.projector.panel;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.hal.Play6Gmpf;
import org.z9x.projector.hal.Play6Gmpf2;

/**
 * The vendor session shared by the two Play 6 picture adjustment cards of P3 item 21 (docs/play6/SETTINGS_PLAN.md
 * 1.3): {@link Play6ZoomShiftPanel} (stock ZoomDisplacementWind) and {@link Play6RotatePanel} (stock RotateWind).
 * Both stock windows bracket their work the same way, and so do we:
 *   open:  IGmpf2 125(1) setPushPullScreenState (stock GmSystemManager.setPushPullScreenState(1), first); when it
 *          is not delivered the card closes BEFORE any IGmpf write (as the manual keystone card, plan 1.3);
 *          586 real-time keystone, and only when it is on 573(2,false) (stock mAkStatus); the zoom card then
 *          121 zoomStart(0).
 *   close: the zoom card 120 zoomFinish(0); 573(2,true) only when this session paused it; IGmpf2 125(0).
 *          Never 158 generateLogoByTrapezoid (owner decision O4) and nothing of MBOOT, env or calibration.
 * Every call that a session still owes the vendor is covered by a device-protected marker written BEFORE the call
 * (prefs "p6_adjust"), so a process death mid-session is settled by {@link #install} 5 s after the next IGmpf
 * (re)connect, with a bounded retry. The manual keystone card keeps its own markers (prefs "p6_kst"); the three
 * cards never run together (each open checks the others). HAL thread only, except install. Log tag P6Adjust.
 */
public final class Play6AdjustSession {
    static final String TAG = "P6Adjust";
    private static final String PREFS = "p6_adjust";
    /** Real-time keystone was on and this session sent 573(2,false): 573(2,true) owed. */
    private static final String KEY_PAUSED = "rt_paused";
    /** IGmpf2 125(1) was sent: 125(0) owed. */
    private static final String KEY_PUSH_PULL = "push_pull";
    /** IGmpf 121 zoomStart(0) was sent: 120 zoomFinish(0) owed. */
    private static final String KEY_ZOOM = "zoom_session";
    private static final long RECOVER_DELAY_MS = 5_000;
    private static final int RETRY_MAX = 3;
    private static final long RETRY_GAP_MS = 5_000;

    private Play6AdjustSession() {}

    /** True while one of the adjustment cards (or the manual keystone card) is open. Main thread writes. */
    static boolean anyOpen() {
        return Play6ZoomShiftPanel.isOpen() || Play6RotatePanel.isOpen() || ManualKeystonePanel.isOpen();
    }

    /**
     * Play6App at the start gate: after every IGmpf (re)connect, whatever an adjustment session still owes the
     * vendor (markers) is sent once; a failure re-arms the bounded retry. No HAL call here.
     */
    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Hal.addConnectedListener(() -> {
            if (!owed(app)) return;                                    // nothing owed: no task
            Hal.runDelayed((g, g2) -> {
                if (!recover(app, g, g2, "connect")) scheduleRetry(app, 1);
            }, RECOVER_DELAY_MS);
        });
    }

    private static boolean owed(Context app) {
        return flag(app, KEY_PAUSED) || flag(app, KEY_PUSH_PULL) || flag(app, KEY_ZOOM);
    }

    private static SharedPreferences prefs(Context app) {
        try {
            return app.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    private static boolean flag(Context app, String key) {
        try { return prefs(app).getBoolean(key, false); } catch (Throwable t) { Log.w(TAG, "marker read: " + t); return false; }
    }

    /** Synchronous: a marker is on disk before the call it covers. */
    private static void mark(Context app, String key, boolean on) {
        try { prefs(app).edit().putBoolean(key, on).commit(); } catch (Throwable t) { Log.w(TAG, "marker " + key + ": " + t); }
    }

    // ------------------------------------------------------------------ open
    /**
     * HAL thread. IGmpf2 125(1) first. False = not delivered (guard closed, transport error): the caller closes
     * before any IGmpf write. A delivered call with a bad reply counts as sent (125(0) stays owed).
     */
    static boolean pushPullOn(Context app, Play6Gmpf2 g2, String who) {
        mark(app, KEY_PUSH_PULL, true);
        try {
            Log.i(TAG, who + ": IGmpf2 125(1) -> " + g2.setPushPullScreen(true));
            return true;
        } catch (Play6Gmpf2.ReplyException e) {
            Log.w(TAG, who + ": IGmpf2 125(1) delivered, bad reply (continuing): " + e.getMessage());
            return true;
        } catch (Throwable t) {
            mark(app, KEY_PUSH_PULL, false);
            Log.w(TAG, who + ": IGmpf2 125(1) not delivered: " + t);
            return false;
        }
    }

    /**
     * HAL thread. 586, then 573(2,false) only when real-time keystone is on (a "paused" marker left by an earlier
     * session counts as on). 586 unreadable and no marker: not paused. Returns true when this session paused it.
     */
    static boolean pauseRealtime(Context app, Play6Gmpf g, String who) {
        boolean on;
        if (flag(app, KEY_PAUSED)) {
            on = true;
            Log.i(TAG, who + ": paused marker present: real-time keystone counted as on");
        } else {
            try {
                on = g.getMoveAk();                                    // 586
                Log.i(TAG, who + ": 586 real-time keystone -> " + on);
            } catch (Throwable t) {
                Log.w(TAG, who + ": 586: " + t + " (573 not sent)");
                return false;
            }
        }
        if (!on) return false;
        mark(app, KEY_PAUSED, true);
        try {
            Log.i(TAG, who + ": 573(2,false) -> " + g.setRealtimeAkRunning(false));
        } catch (Throwable t) {
            Log.w(TAG, who + ": 573(2,false): " + t + " (continuing)");
        }
        return true;
    }

    /** HAL thread. 121 zoomStart(0) with its marker; throws when not delivered (nothing owed then). */
    static void zoomStart(Context app, Play6Gmpf g, String who) throws Exception {
        mark(app, KEY_ZOOM, true);
        try {
            Log.i(TAG, who + ": 121 zoomStart(0) -> " + g.zoomSessionStart());
        } catch (Play6Gmpf.ReplyException e) {
            Log.w(TAG, who + ": 121 delivered, bad reply (continuing): " + e.getMessage());
        } catch (Exception e) {
            mark(app, KEY_ZOOM, false);
            throw e;
        }
    }

    /**
     * HAL thread. 120 zoomFinish(0) then 121 zoomStart(0): a fresh zoom session (stock initZoom(stepChange = true)).
     * The zoom card runs it after a move: the vendor re-bases 116 to 0 on a move but keeps the 117 range of the
     * session start, so the last steps back to the full picture were refused (2026-10-24, device test); a fresh
     * session answers the true range.
     */
    static void zoomRestart(Context app, Play6Gmpf g, String who) throws Exception {
        if (flag(app, KEY_ZOOM) && !zoomFinish(app, g, who)) throw new IllegalStateException("120 not delivered");
        zoomStart(app, g, who);
    }

    // ------------------------------------------------------------------ close
    /** HAL thread. Settles everything the markers say is owed, in stock order. True = nothing left. */
    static boolean close(Context app, Play6Gmpf g, Play6Gmpf2 g2, String who) {
        boolean ok = true;
        if (flag(app, KEY_ZOOM)) ok &= zoomFinish(app, g, who);
        if (flag(app, KEY_PAUSED)) ok &= resume(app, g, who);
        if (flag(app, KEY_PUSH_PULL)) ok &= pushPullOff(app, g2, who);
        return ok;
    }

    private static boolean zoomFinish(Context app, Play6Gmpf g, String who) {
        try {
            int r = g.zoomSessionFinish();                             // 120(0)
            mark(app, KEY_ZOOM, false);
            Log.i(TAG, who + ": 120 zoomFinish(0) -> " + r);
            return true;
        } catch (Play6Gmpf.ReplyException e) {
            mark(app, KEY_ZOOM, false);
            Log.w(TAG, who + ": 120 delivered, bad reply: " + e.getMessage());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, who + ": 120: " + t + " (marker kept)");
            return false;
        }
    }

    private static boolean resume(Context app, Play6Gmpf g, String who) {
        try {
            boolean r = g.setRealtimeAkRunning(true);                  // 573(2,true)
            Log.i(TAG, who + ": 573(2,true) -> " + r);
            if (r) {
                mark(app, KEY_PAUSED, false);
                return true;
            }
            Log.w(TAG, who + ": 573(2,true) returned false (marker kept)");
        } catch (Throwable t) {
            Log.w(TAG, who + ": 573(2,true): " + t + " (marker kept)");
        }
        return false;
    }

    private static boolean pushPullOff(Context app, Play6Gmpf2 g2, String who) {
        try {
            boolean r = g2.setPushPullScreen(false);                   // IGmpf2 125(0)
            mark(app, KEY_PUSH_PULL, false);
            Log.i(TAG, who + ": IGmpf2 125(0) -> " + r);
            return true;
        } catch (Play6Gmpf2.ReplyException e) {
            mark(app, KEY_PUSH_PULL, false);
            Log.w(TAG, who + ": IGmpf2 125(0) delivered, bad reply: " + e.getMessage());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, who + ": IGmpf2 125(0): " + t + " (marker kept)");
            return false;
        }
    }

    // ------------------------------------------------------------------ recovery
    private static boolean recover(Context app, Play6Gmpf g, Play6Gmpf2 g2, String who) {
        if (Play6ZoomShiftPanel.isOpen() || Play6RotatePanel.isOpen()) return true;   // the open card settles them
        Log.i(TAG, who + ": markers found (zoom " + flag(app, KEY_ZOOM) + ", paused " + flag(app, KEY_PAUSED)
                + ", push-pull " + flag(app, KEY_PUSH_PULL) + ")");
        return close(app, g, g2, who);
    }

    private static void scheduleRetry(final Context app, final int attempt) {
        if (attempt > RETRY_MAX) {
            Log.w(TAG, "marker retries used up: left to the next IGmpf connect");
            return;
        }
        boolean q = Hal.runDelayed((g, g2) -> {
            if (!recover(app, g, g2, "retry " + attempt)) scheduleRetry(app, attempt + 1);
        }, RETRY_GAP_MS);
        if (!q) Log.w(TAG, "marker retry not queued: left to the next IGmpf connect");
    }
}
