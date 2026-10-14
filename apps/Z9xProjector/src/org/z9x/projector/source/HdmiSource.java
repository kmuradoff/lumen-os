package org.z9x.projector.source;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.Ui;
import org.z9x.projector.hal.GmpfClient;
import org.z9x.projector.power.StandbyController;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lumen OS 1.0.3: puts the HDMI input on the screen. The vendor TV input HAL only hands out a sideband
 * handle and never switches the video window; on stock firmware the HDMI player connects HDMI to the main
 * video window itself with IGmpf 698 switchInputSourceWithCallingPid(source, its pid) when it tunes, and
 * (0, pid) when it leaves. org.z9x.tvinput (TIF, no IGmpf) never did, so the HDMI picture stayed black.
 * This seam sends the same call for every HDMI view:
 *
 *  - an HDMI_EVENT with reason "session" (game/HdmiEventReceiver: one of tvinput's sessions took or released
 *    the HDMI hardware of a port) schedules one decision {@value #SETTLE_MS} ms later on z9x-hal (a newer
 *    event replaces a pending one, which absorbs the off / on pair when one session hands the port to
 *    another): read which ports tvinput shows (TifHdmiState.readPorts; null = unknown: nothing is sent); the
 *    target is that port's vendor source (port 1 -> 1 HDMI 1, port 2 -> 2 HDMI 2, GmpfClient SOURCE_*: the
 *    numbers 696 reports and the stock player sends for its HDMI1 / HDMI2 inputs; tvinput's ports are the
 *    hdmi_port of TV input hardware 1 / 2), 0 when no port is shown; IGmpf 698 (target, our pid) only when
 *    the target differs from the last value sent (at the start of a connection: what IGmpf 696 reads);
 *  - IGmpf connect (also after a gmpf_main restart): the same decision against 696, so an event lost before
 *    the connect is made good, and a tvinput that died on HDMI leaves no HDMI window behind (we never
 *    register IGmpf 699, so the vendor's own "client died, switch to MM" does not cover us);
 *  - SCREEN_ON (App): self-heal only: 696 reads HDMI and tvinput shows no HDMI -> 698(0). Never a switch to
 *    HDMI from a wake; tvinput's new session does that;
 *  - the lamp-only standby (power/StandbyController) never gets an HDMI source: a decision for HDMI during it
 *    is held and made again when the standby ends ({@link #onStandbyEnd}, or the next SCREEN_ON after a
 *    sleep), so a device that opens a session in standby gets no picture or vendor sound path behind the
 *    black screen. A switch back to 0 is sent at once.
 * A ReplyException is not retried (the call reached the HAL); a call that was not delivered is not counted
 * as sent, so the next event or connect tries again. One Z9xHdmi line per 698 call with its result.
 * GameProfile and the quick panel keep their own 696 / tvinput checks; 696 now reads the shown port, which
 * their per-source rules always expected.
 *
 * Volume is not set here (CHANGES.md, 1.0.3): this audio HAL only stores the HDMI In port gain and
 * spk_volume while Lumen selects no XGIMI audio path, so neither changes the HDMI loudness yet.
 */
public final class HdmiSource {
    private static final String TAG = "Z9xHdmi";
    static final long SETTLE_MS = 150;

    private static final AtomicInteger TOKEN = new AtomicInteger();
    private static volatile Context sApp;
    /** A decision for HDMI came during the standby: made again when it ends. Any thread. */
    private static volatile boolean sHeld;
    /** The port of the latest "session opened" event (preferred when tvinput shows more than one). */
    private static volatile int sLastPort = -1;
    // z9x-hal thread state
    /** The last source 698 delivered (0 / 1 / 2); -1 = not known: 696 is read first. */
    private static int sLastSent = -1;

    private HdmiSource() {}

    /**
     * App.onCreate (main thread): the connect hook (the decision against 696 after every IGmpf connect).
     * No HAL call here.
     */
    public static void install(Context ctx) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        Hal.addConnectedListener(() -> {
            sLastSent = -1;                                     // a new connection: the vendor state is 696's
            final int t = TOKEN.incrementAndGet();
            Hal.run((g, g2) -> decide(g, t, "IGmpf connect", true));
        });
        Log.i(TAG, "armed: IGmpf 698 follows tvinput's HDMI sessions");
    }

    // ================================================================== events (main thread)

    /** game/HdmiEventReceiver: tvinput's session took (active) or released the HDMI hardware of {@code port}. */
    public static void onSessionEvent(int port, boolean active) {
        if (sApp == null) return;
        if (active) sLastPort = port;
        schedule("session HDMI " + port + (active ? " opened" : " closed"));
    }

    /** App SCREEN_ON: a decision held during the standby, else the self-heal (never a switch to HDMI). */
    public static void onScreenOn() {
        if (sApp == null || StandbyController.isActive()) return;
        if (sHeld) {
            sHeld = false;
            schedule("HDMI session held during the standby");
            return;
        }
        Hal.run((g, g2) -> selfHeal(g, "screen on"));
    }

    /** power/StandbyController.exit (main thread): a decision held during the standby is made now. */
    public static void onStandbyEnd() {
        if (!sHeld || sApp == null) return;
        sHeld = false;
        schedule("standby ended");
    }

    private static void schedule(String why) {
        final int t = TOKEN.incrementAndGet();
        Hal.runDelayed((g, g2) -> decide(g, t, why, false), SETTLE_MS);
    }

    // ================================================================== decisions (z9x-hal)

    /** Port -> vendor source of 698 / 696, -1 for a port this projector does not have. */
    static int sourceOf(int port) {
        switch (port) {
            case 1: return GmpfClient.SOURCE_HDMI1;
            case 2: return GmpfClient.SOURCE_HDMI2;
            default: return -1;
        }
    }

    /**
     * z9x-hal: HDMI port shown -> its source, none -> 0; 698 only when that differs from the last value sent
     * (or from 696 when nothing was sent on this connection, or always against 696 when {@code against696}).
     */
    private static void decide(GmpfClient g, int t, String why, boolean against696) {
        if (t != TOKEN.get()) return;                           // a newer event replaced this one
        Context app = sApp;
        if (app == null) return;
        int[] ports = TifHdmiState.readPorts(app);
        if (ports == null) {
            Log.w(TAG, "HDMI ports of tvinput unknown (" + why + "): nothing sent");
            return;
        }
        int port = pick(ports);
        int target = port < 0 ? GmpfClient.SOURCE_MEDIA : sourceOf(port);
        if (target < 0) {
            Log.w(TAG, "HDMI " + port + " has no vendor source (" + why + "): nothing sent");
            return;
        }
        if (target != GmpfClient.SOURCE_MEDIA && StandbyController.isActive()) {
            sHeld = true;
            Log.i(TAG, "HDMI " + port + " shown during the standby (" + why + "): source switch held until it ends");
            return;
        }
        sHeld = false;                                          // this decision covers a held one
        // Lumen OS 1.0.4: the remote's volume keys follow an HDMI picture (HdmiVolume), 100 again without one
        final boolean hdmi = target != GmpfClient.SOURCE_MEDIA;
        Ui.main().post(() -> {
            if (hdmi) HdmiVolume.start(app);
            else HdmiVolume.stop(app, "no HDMI shown, " + why);
        });
        int now = against696 || sLastSent < 0 ? read696(g) : sLastSent;
        if (now == target) {
            sLastSent = target;
            return;
        }
        send(g, target, why + (now < 0 ? "" : ", was " + now));
    }

    /** The port to show: the latest opened one when tvinput still shows it, else the first; -1 for none. */
    private static int pick(int[] ports) {
        if (ports.length == 0) return -1;
        int last = sLastPort;
        for (int p : ports) if (p == last) return p;
        return ports[0];
    }

    /** z9x-hal: 696 reads HDMI while tvinput shows no HDMI -> 698(0). Never switches to HDMI. */
    private static void selfHeal(GmpfClient g, String why) {
        Context app = sApp;
        if (app == null) return;
        int cur = read696(g);
        if (cur != GmpfClient.SOURCE_HDMI1 && cur != GmpfClient.SOURCE_HDMI2) {
            if (cur >= 0) sLastSent = cur;
            return;
        }
        int[] ports = TifHdmiState.readPorts(app);
        if (ports == null || ports.length > 0) {
            sLastSent = cur;                                    // HDMI shown (or unknown): leave it
            return;
        }
        send(g, GmpfClient.SOURCE_MEDIA, "self-heal on " + why + ": 696 reads " + cur + ", no HDMI session");
        Ui.main().post(() -> HdmiVolume.stop(app, "self-heal on " + why));
    }

    /** z9x-hal: one 698 call and its Z9xHdmi line. */
    private static void send(GmpfClient g, int source, String why) {
        String head = "IGmpf 698 (" + source + ", pid " + Process.myPid() + ")";
        try {
            boolean r = g.switchInputSource(source);
            sLastSent = source;
            Log.i(TAG, head + " -> " + r + " (" + why + ")");
        } catch (GmpfClient.ReplyException e) {
            sLastSent = source;                                 // reached the HAL: not repeated
            Log.w(TAG, head + " -> bad reply, not repeated (" + why + "): " + e.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, head + " -> not delivered (" + why + "): " + t);
        }
    }

    /** z9x-hal: 696, -1 when it failed. */
    private static int read696(GmpfClient g) {
        try {
            return g.getCurrentInputSource();
        } catch (Throwable t) {
            Log.w(TAG, "696: " + t);
            return -1;
        }
    }
}
