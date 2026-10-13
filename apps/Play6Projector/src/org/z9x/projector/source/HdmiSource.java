package org.z9x.projector.source;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.Ui;
import org.z9x.projector.game.GameMode;
import org.z9x.projector.hal.Play6Gmpf;
import org.z9x.projector.hal.Play6Gmpf2;
import org.z9x.projector.power.StandbyController;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The HDMI input source of the XGIMI Play 6 (t14; docs/play6/research/hdmi_stock.md 1, 3 and 7). The vendor
 * TV input HAL only hands out a sideband handle; the stock HDMI player connects HDMI to the main video window
 * itself with IGmpf 698 switchInputSourceWithCallingPid(1, pid) when it tunes and (0, pid) when it leaves.
 * org.z9x.tvinput (TIF, no IGmpf) does not, so this projector-side seam does it for every HDMI view:
 *
 *  - an HDMI_EVENT with reason "session" (game/HdmiEventReceiver: tvinput's session took or released the
 *    HDMI hardware) schedules one decision {@value #SETTLE_MS} ms later on p6-hal (a newer event replaces a
 *    pending one, which absorbs the off / on pair when one session hands the port to another): read whether
 *    tvinput shows HDMI (TifHdmiState: its hdmi_state "active", true while a session holds the hardware;
 *    null = unknown: nothing is sent); target 1 when it does, 0 when not; IGmpf 698 (target, our pid) only
 *    when the target differs from the last value sent (at the start: what IGmpf 696 reads);
 *  - IGmpf connect (also after a gmpf_main restart): the same decision against 696, so an event lost before
 *    the start gate or during a reconnect is made good, and a tvinput that died on HDMI leaves no black
 *    HDMI window behind (we do not register IGmpf 699, so the vendor's own "client died, switch to MM" does
 *    not cover us);
 *  - SCREEN_ON: self-heal only: 696 reads 1 and no HDMI session is shown -> 698(0). Never 698(1) from a wake;
 *    tvinput's new session does that;
 *  - the lamp-only standby (power/StandbyController) leaves the source alone: a session event during it is
 *    held and decided when the standby ends; entering or leaving it sends nothing by itself (Android stays
 *    awake and the TIF session stays, so HDMI is shown again at once).
 * A ReplyException is not retried (the call reached the HAL); a call that was not delivered is not counted
 * as sent, so the next event or connect tries again. One P6Hdmi line per event and per 698 call with its
 * result. Game mode (game/GameMode) is not touched here; it keeps checking 696 / tvinput before its writes,
 * and 696 now reads 1 while HDMI is shown.
 *
 * t15 (docs/play6/research/hdmi_volume_game.md): whenever a decision finds HDMI shown, HdmiVolume follows the
 * music volume (spk_volume), a stored aspect ratio above the vendor range (the 5 that t14 wrote) is repaired
 * once with 665(2), and {@value #CEC_SETTLE_MS} ms later game/GameMode.onHdmiShown runs (default Auto once; in
 * Auto the console check sets the game state); while HDMI is shown a TV input rename (the CEC OSD name arriving after "Playback_1") or a CEC
 * device event ({@link #onCecEvent}) runs the console check again {@value #CEC_SETTLE_MS} ms later. When a
 * decision finds no HDMI shown, HdmiVolume sends spk_volume=100 (also at the IGmpf connect).
 *
 * Debug fallback (research 7.4 item 2), only when {@value #PROP_FALLBACK} is 1 (default 0): after a 698(1)
 * that returned true, IGmpf 592 is read every {@value #STABLE_POLL_MS} ms for up to {@value #STABLE_MAX_MS} ms;
 * at the first stable signal (592 == 3, UNVERIFIED) IGmpf 659 setDisplayArea(0, 0, 1920, 1080) once. Nothing
 * is re-opened here: re-opening the stream would be tvinput's job.
 */
public final class HdmiSource {
    private static final String TAG = "P6Hdmi";
    static final long SETTLE_MS = 150;
    static final String PROP_FALLBACK = "persist.lumen.p6.hdmi_fallback";
    static final long STABLE_POLL_MS = 300;
    static final long STABLE_MAX_MS = 6_000;
    /** 592 value of a stable signal (the type 0 TV event's "status:3"; UNVERIFIED for 592). */
    static final int SIGNAL_STABLE = 3;
    /** A CEC device event or input rename: the console check this long after it (the OSD name settles). */
    static final long CEC_SETTLE_MS = 1_500;

    private static final AtomicInteger TOKEN = new AtomicInteger();
    private static volatile Context sApp;
    /** Main thread: a session event came during the lamp-only standby; decided when it ends. */
    private static boolean sHeld;
    /** HDMI is shown (the last decision); main-thread hooks follow it. */
    private static volatile boolean sShown;
    /** Main thread: TV input renames while HDMI is shown (CEC OSD name updates). */
    private static TvInputManager.TvInputCallback sInputCallback;
    private static final AtomicInteger GAME_TOKEN = new AtomicInteger();
    // p6-hal thread state
    /** The aspect ratio repair (665(2) for a stored value above 4) ran in this process. */
    private static boolean sAspectChecked;
    /** The last source 698 delivered (0 / 1); -1 = not known: 696 is read first. */
    private static int sLastSent = -1;
    private static volatile String sLastLine = "no 698 call yet";

    private HdmiSource() {}

    /**
     * Play6App at the start gate (p6-hal), before the Hal facade connects: the connected hook (the decision
     * against 696 after every IGmpf connect) and the SCREEN_ON self-heal. No HAL call here.
     */
    public static void install(Context ctx) {
        if (sApp != null) return;
        final Context app = ctx.getApplicationContext();
        sApp = app;
        Hal.addConnectedListener(() -> {
            sLastSent = -1;                                     // a new connection: the vendor state is 696's
            Hal.run((g, g2) -> decide(g, g2, TOKEN.incrementAndGet(), "IGmpf connect", true));
        });
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    if (i == null || !Intent.ACTION_SCREEN_ON.equals(i.getAction())) return;
                    if (!Hal.isGateOpen() || StandbyController.isActive()) return;
                    Hal.run((g, g2) -> selfHeal(g, "screen on"));
                } catch (Throwable t) {
                    Log.w(TAG, "screen on: " + t);
                }
            }
        };
        app.registerReceiver(r, new IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_EXPORTED);
        Log.i(TAG, "armed: IGmpf 698 follows tvinput's HDMI sessions" + (fallback() ? "; fallback 659 on" : ""));
    }

    /** One line for Projector settings > Diagnostics. Main thread. */
    public static String describe() {
        return sLastLine + "; " + HdmiVolume.describe() + (fallback() ? "; fallback " + PROP_FALLBACK + "=1" : "");
    }

    // ================================================================== events (main thread)
    /** game/HdmiEventReceiver: tvinput's session took (active) or released the HDMI hardware of {@code port}. */
    public static void onSessionEvent(Context ctx, int port, boolean active) {
        final Context app = sApp != null ? sApp : ctx.getApplicationContext();
        Log.i(TAG, "HDMI_EVENT session port=" + port + " active=" + active);
        if (!Hal.isGateOpen()) {
            Log.i(TAG, "start gate closed: decided at the IGmpf connect");
            return;
        }
        if (StandbyController.isActive()) {
            sHeld = true;
            Log.i(TAG, "lamp-only standby: the source stays, decided when the standby ends");
            return;
        }
        schedule("session HDMI " + port + (active ? " opened" : " closed"));
    }

    /**
     * game/HdmiEventReceiver: a CEC device came or went (cec_added / cec_removed). While HDMI is shown the
     * console check runs again once its OSD name has settled. Main thread.
     */
    public static void onCecEvent(String reason, int port) {
        Log.i(TAG, "HDMI_EVENT " + reason + " port=" + port);
        if (sShown && Hal.isGateOpen()) scheduleGameCheck("CEC " + reason);
    }

    /** Any thread: the console check {@link #CEC_SETTLE_MS} ms from now (a newer request replaces it). */
    private static void scheduleGameCheck(String why) {
        final int t = GAME_TOKEN.incrementAndGet();
        Hal.runDelayed((g, g2) -> {
            Context app = sApp;
            if (t == GAME_TOKEN.get() && sShown && app != null) GameMode.onHdmiShown(app, g, g2, why);
        }, CEC_SETTLE_MS);
    }

    /** power/StandbyController.exit (main thread): a session event held during the standby is decided now. */
    public static void onStandbyEnd() {
        if (!sHeld) return;
        sHeld = false;
        if (Hal.isGateOpen()) schedule("session change during the standby");
    }

    private static void schedule(String why) {
        final int t = TOKEN.incrementAndGet();
        Hal.runDelayed((g, g2) -> decide(g, g2, t, why, false), SETTLE_MS);
    }

    // ================================================================== decisions (p6-hal)
    /**
     * p6-hal: HDMI shown -> 1, not shown -> 0; 698 only when that differs from the last value sent (or from
     * 696 when nothing was sent on this connection, or always against 696 when {@code against696}).
     */
    private static void decide(Play6Gmpf g, Play6Gmpf2 g2, int t, String why, boolean against696) {
        if (t != TOKEN.get()) return;                           // a newer event replaced this one
        Context app = sApp;
        if (app == null) return;
        Boolean shown = TifHdmiState.read(app);
        if (shown == null) {
            Log.w(TAG, "HDMI state of tvinput unknown (" + why + "): nothing sent");
            return;
        }
        int target = shown ? Play6Gmpf.SOURCE_HDMI1 : Play6Gmpf.SOURCE_MEDIA;
        int now = against696 || sLastSent < 0 ? read696(g) : sLastSent;
        if (now == target) {
            sLastSent = target;
            Log.i(TAG, "HDMI shown=" + shown + ", source already " + target + " (" + why + "): nothing sent");
        } else {
            send(g, target, why + (now < 0 ? "" : ", was " + now));
        }
        onShown(g, g2, shown, why);
    }

    /**
     * p6-hal, after every decision (t15): HDMI shown -> volume follows, aspect repair, game check; not shown
     * -> spk_volume back to 100. The main-thread parts are posted.
     */
    private static void onShown(Play6Gmpf g, Play6Gmpf2 g2, boolean shown, String why) {
        final Context app = sApp;
        if (app == null) return;
        sShown = shown;
        if (!shown) {
            Ui.main().post(() -> {
                HdmiVolume.stop(app, "no HDMI shown, " + why);
                watchInputs(app, false);
            });
            return;
        }
        Ui.main().post(() -> {
            HdmiVolume.start(app);
            watchInputs(app, true);
        });
        repairAspect(g);
        scheduleGameCheck(why);                                 // CEC names settle first (PS5: "Playback_1")
    }

    /** p6-hal, once per process while HDMI is the current source: a 664 reply above 4 -> 665(2). */
    private static void repairAspect(Play6Gmpf g) {
        if (sAspectChecked) return;
        try {
            if (g.getCurrentInputSource() != Play6Gmpf.SOURCE_HDMI1) return;              // 696
            int a = g.getAspectRatio();                                                  // 664
            sAspectChecked = true;
            if (a <= Play6Gmpf.ASPECT_MAX) return;
            boolean r = g.setAspectRatio(Play6Gmpf.ASPECT_AUTO);                         // 665(2)
            Log.i(TAG, "aspect ratio " + a + " is outside the vendor range (t14 wrote 5): 665(2) -> " + r);
        } catch (Throwable t) {
            Log.w(TAG, "aspect check: " + t);
        }
    }

    /**
     * Main thread: while HDMI is shown, a TV input added or updated (tvinput republishes a CEC child input
     * when its OSD name or vendor id arrives) runs the console check again.
     */
    private static void watchInputs(Context app, boolean on) {
        try {
            TvInputManager tim = app.getSystemService(TvInputManager.class);
            if (tim == null) return;
            if (on && sInputCallback == null) {
                TvInputManager.TvInputCallback cb = new TvInputManager.TvInputCallback() {
                    @Override public void onInputAdded(String inputId) { changed("input added"); }
                    @Override public void onTvInputInfoUpdated(TvInputInfo info) { changed("input renamed"); }
                };
                tim.registerCallback(cb, Ui.main());
                sInputCallback = cb;
            } else if (!on && sInputCallback != null) {
                tim.unregisterCallback(sInputCallback);
                sInputCallback = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "TV input callback: " + t);
        }
    }

    private static void changed(String why) {
        if (sShown && Hal.isGateOpen()) scheduleGameCheck(why);
    }

    /** p6-hal: 696 reads 1 while tvinput shows no HDMI -> 698(0). Never switches to HDMI. */
    private static void selfHeal(Play6Gmpf g, String why) {
        Context app = sApp;
        if (app == null) return;
        int cur = read696(g);
        if (cur != Play6Gmpf.SOURCE_HDMI1) {
            if (cur >= 0) sLastSent = cur;
            return;
        }
        Boolean shown = TifHdmiState.read(app);
        if (!Boolean.FALSE.equals(shown)) {
            sLastSent = cur;
            return;                                             // HDMI shown (or unknown): leave it
        }
        send(g, Play6Gmpf.SOURCE_MEDIA, "self-heal on " + why + ": 696 reads 1, no HDMI session");
        sShown = false;
        final Context a = app;
        Ui.main().post(() -> {
            HdmiVolume.stop(a, "self-heal on " + why);
            watchInputs(a, false);
        });
    }

    /** p6-hal: one 698 call and its P6Hdmi line. */
    private static void send(Play6Gmpf g, int source, String why) {
        String head = "IGmpf 698 (" + source + ", pid " + Process.myPid() + ")";
        try {
            boolean r = g.switchInputSource(source);
            sLastSent = source;
            sLastLine = head + " -> " + r + " (" + why + ")";
            Log.i(TAG, sLastLine);
            if (r && source == Play6Gmpf.SOURCE_HDMI1 && fallback()) {
                final int t = TOKEN.get();
                Hal.runDelayed((g1, g2) -> waitStable(g1, t, 0), STABLE_POLL_MS);
            }
        } catch (Play6Gmpf.ReplyException e) {
            sLastSent = source;                                 // reached the HAL: not repeated
            sLastLine = head + " -> bad reply, not repeated (" + why + "): " + e.getMessage();
            Log.w(TAG, sLastLine);
        } catch (Throwable t) {
            sLastLine = head + " -> not delivered (" + why + "): " + t;
            Log.w(TAG, sLastLine);
        }
    }

    /** p6-hal: 696, -1 when it failed. */
    private static int read696(Play6Gmpf g) {
        try {
            return g.getCurrentInputSource();
        } catch (Throwable t) {
            Log.w(TAG, "696: " + t);
            return -1;
        }
    }

    // ================================================================== debug fallback
    private static boolean fallback() {
        try {
            return SystemProperties.getInt(PROP_FALLBACK, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** p6-hal: 592 until stable (then 659 once) or {@link #STABLE_MAX_MS}; a newer decision stops it. */
    private static void waitStable(Play6Gmpf g, int t, long waited) {
        if (t != TOKEN.get() || sLastSent != Play6Gmpf.SOURCE_HDMI1) return;
        int status = -1;
        try { status = g.getSignalStatus(); } catch (Throwable e) { Log.w(TAG, "592: " + e); }        // 592
        if (status == SIGNAL_STABLE) {
            try {
                boolean r = g.setFullDisplayArea();                                                     // 659
                Log.i(TAG, "fallback: IGmpf 659 (0, 0, 1920, 1080) -> " + r + " (592 stable after " + waited + " ms)");
            } catch (Throwable e) {
                Log.w(TAG, "fallback: IGmpf 659 (0, 0, 1920, 1080) failed: " + e);
            }
            return;
        }
        if (waited + STABLE_POLL_MS > STABLE_MAX_MS) {
            Log.w(TAG, "fallback: no stable signal in " + STABLE_MAX_MS + " ms (592 " + status + "): 659 not sent");
            return;
        }
        final long w = waited + STABLE_POLL_MS;
        Hal.runDelayed((g1, g2) -> waitStable(g1, t, w), STABLE_POLL_MS);
    }
}
