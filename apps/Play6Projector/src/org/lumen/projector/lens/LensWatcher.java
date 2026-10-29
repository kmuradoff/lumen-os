package org.lumen.projector.lens;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.PowerManager;
import android.util.Log;

import org.lumen.projector.Hal;
import org.lumen.projector.KeyReceiver;
import org.lumen.projector.Ui;
import org.lumen.projector.power.StandbyController;

/**
 * Detection of the magnetic lenses of the XGIMI Play 6 for {@link LensMode}.
 *
 * Stock: gmpf_main reads the lens over the MCU (G58 convexLens.ini, 10 ADC ranges) and sends the system event 26
 * ("1" in, "0" out) to the listeners of IGmpf 414 connectSystemManager + 428 setSystemListener (stock XRMService
 * MachineStateService); the lens id is IGmpf2 75 getConvexLensType, the state IGmpf2 84 isConvexLensIn. Lumen
 * registers no system listener yet (the Hal facade has the focus 298 / 306, power 235 / 242 and music player
 * 215 / 227 listeners only; 414 / 428 would need a reviewed hwbinder callback of their own), so this class polls
 * the existing guarded reads instead:
 *  - IGmpf2 84 every {@value #POLL_MS} ms on p6-hal (Hal.query), only while the screen is on, the setup is
 *    complete, the IGmpf2 guard is open and no long HAL call (326 auto keystone, 404) runs; SCREEN_OFF stops the
 *    loop, SCREEN_ON and every IGmpf (re)connect start it again; the first read after the start gate is the boot
 *    check (stock enters the lens mode at boot with a lens on);
 *  - a change is confirmed by a second read {@value #CONFIRM_MS} ms later (84, and 75 when the lens is in), so
 *    the contact of a lens being put on is not taken twice;
 *  - in: a soft chime (stock plays its own lens-in sound; that file is part of the stock system, so this one is
 *    synthesised), then LensMode for the lens id; lens 0 (stock EN_CONVEX_LENS_TYPE_EMEISNOW, the starry-sky lens
 *    for which stock opens com.xgimi.atmosphere's screensaver 7002, not on Lumen) only logs;
 *  - out: LensMode ends, then its removal hooks run (LensMode.addOnLensRemoved);
 *  - a lens that stays in: the mode comes back by itself after a screen off or the lamp-only standby, not after
 *    the user left it (BACK, HOME, another panel) or its content was missing: then only a new insert starts it.
 * HAL: the existing Play6Gmpf2 methods isConvexLensIn (84) and getConvexLensType (75), read only. Log tag P6Lens.
 */
public final class LensWatcher {
    private static final String TAG = LensMode.TAG;
    static final long POLL_MS = 2_000;
    static final long CONFIRM_MS = 600;
    private static final long SCREEN_ON_DELAY_MS = 1_000;
    /** Stock XRM EN_CONVEX_LENS_TYPE_EMEISNOW: not a starmirror lens. */
    static final int LENS_EMEISNOW = 0;

    private static Context sApp;
    private static boolean sInstalled;
    // main thread state
    private static volatile Boolean sIn;                 // null = not read yet
    private static volatile int sType = -1;
    private static boolean sSuppressed, sQuerying, sConfirming;
    private static final Runnable TICK = LensWatcher::tick;

    private LensWatcher() {}

    /** A lens is on the projector (last confirmed read; false before the first one). Any thread. */
    public static boolean isLensIn() { return Boolean.TRUE.equals(sIn); }

    /** The id of the lens that is in (IGmpf2 75), or -1. Any thread. */
    public static int lensType() { return isLensIn() ? sType : -1; }

    /**
     * Play6App at the start gate (p6-hal): the connected hook (the first read, then the loop), the screen hooks and
     * LensMode's brightness recovery. No HAL call here.
     */
    public static void install(Context ctx) {
        synchronized (LensWatcher.class) {
            if (sInstalled) return;
            sInstalled = true;
        }
        final Context app = ctx.getApplicationContext();
        sApp = app;
        LensMode.install(app);
        Hal.addConnectedListener(() -> Ui.main().post(LensWatcher::kick));
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    Ui.main().removeCallbacks(TICK);
                    if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) Ui.main().postDelayed(TICK, SCREEN_ON_DELAY_MS);
                }
            }, f, null, Ui.main(), Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "screen receiver: " + t);
        }
        Log.i(TAG, "lens watcher installed (IGmpf2 84 every " + POLL_MS + " ms while the screen is on)");
    }

    private static void kick() {
        Ui.main().removeCallbacks(TICK);
        Ui.main().post(TICK);
    }

    private static boolean interactive() {
        try {
            PowerManager pm = sApp.getSystemService(PowerManager.class);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Main thread: one poll, and the next one scheduled while the screen is on. */
    private static void tick() {
        Context app = sApp;
        if (app == null) return;
        Ui.main().removeCallbacks(TICK);
        if (!interactive()) return;                                     // SCREEN_ON starts the loop again
        Ui.main().postDelayed(TICK, POLL_MS);
        if (sQuerying || sConfirming) return;
        if (!Hal.hasGmpf2() || Hal.isLongRunning() || !KeyReceiver.isSetupComplete(app)) return;
        sQuerying = true;
        Hal.query((g, g2) -> g2.isConvexLensIn(), in -> {               // IGmpf2 84
            sQuerying = false;
            if (in != null) onRead(in);
        });
    }

    /** Main thread: a poll result. */
    private static void onRead(boolean in) {
        Boolean known = sIn;
        if (known == null || known != in) {
            confirm(in, known == null);
            return;
        }
        if (in && !LensMode.isActive() && mayEnter(sType)) {
            Log.i(TAG, "lens " + sType + " still in: lens mode again");
            LensMode.open(sApp, sType);
        }
    }

    /** Main thread: a second read {@value #CONFIRM_MS} ms later decides a change (84, and 75 when in). */
    private static void confirm(boolean seen, boolean first) {
        sConfirming = true;
        Ui.main().postDelayed(() -> {
            if (!Hal.hasGmpf2()) { sConfirming = false; return; }
            Hal.query((g, g2) -> {
                boolean in = g2.isConvexLensIn();                       // IGmpf2 84
                return new int[] {in ? 1 : 0, in ? g2.getConvexLensType() : -1};   // IGmpf2 75
            }, r -> {
                sConfirming = false;
                if (r == null) return;
                boolean in = r[0] == 1;
                if (in != seen) {
                    Log.i(TAG, "lens state " + (seen ? "in" : "out") + " not confirmed: ignored");
                    return;
                }
                apply(in, r[1], first);
            });
        }, CONFIRM_MS);
    }

    /** Main thread: a confirmed state. */
    private static void apply(boolean in, int type, boolean first) {
        sIn = in;
        sSuppressed = false;
        if (!in) {
            sType = -1;
            if (first) {
                Log.i(TAG, "no lens at the start");
                return;
            }
            Log.i(TAG, "lens out (IGmpf2 84 false)");
            LensMode.lensRemoved();
            return;
        }
        sType = type;
        Log.i(TAG, (first ? "lens on at the start" : "lens in") + ": IGmpf2 75 type " + type);
        if (!first && interactive()) chime();
        if (type == LENS_EMEISNOW) {
            Log.i(TAG, "lens type 0 (stock EMEISNOW starry-sky lens: stock opens com.xgimi.atmosphere's screensaver 7002,"
                    + " not part of Lumen): no lens mode");
            sSuppressed = true;
            return;
        }
        if (LensMode.isActive()) {
            Log.i(TAG, "lens mode already on");
            return;
        }
        if (mayEnter(type)) LensMode.open(sApp, type);
        else Log.i(TAG, "lens mode not started now (screen off, standby or setup), later by the poll");
    }

    /** The mode may start for {@code lens} now: lens in and not left by the user, screen on, setup done, no standby. */
    static boolean mayEnter(int lens) {
        Context app = sApp;
        return app != null && isLensIn() && sType == lens && lens != LENS_EMEISNOW && !sSuppressed && interactive()
                && !StandbyController.isActive() && Hal.hasGmpf2() && KeyReceiver.isSetupComplete(app);
    }

    /**
     * LensMode.onDismissed: {@code byUser} (screen on, no standby, lens still in) = the user left it: it stays off
     * until the lens is put on again. After a screen off or a standby the poll starts it again.
     */
    static void onModeEnded(boolean byRemoval, boolean byUser) {
        if (!byRemoval && byUser && isLensIn()) {
            sSuppressed = true;
            Log.i(TAG, "left by the user: lens mode off until the lens is put on again");
        }
    }

    /** LensMode.open could not show anything for this insert: no retry every poll. */
    static void onOpenFailed() {
        sSuppressed = true;
    }

    // ================================================================== lens-in chime
    private static final int RATE = 44_100;
    private static short[] sChime;
    private static AudioTrack sTrack;

    /** A soft rising three-note chime (sine partials, quick attack, long tail, quiet). No file. */
    private static void chime() {
        new Thread(() -> {
            try {
                synchronized (LensWatcher.class) {
                    if (sChime == null) sChime = makeChime();
                    if (sTrack != null) { try { sTrack.release(); } catch (Throwable ignored) { } }
                    AudioTrack tr = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(sChime.length * 2).build();
                    tr.write(sChime, 0, sChime.length);
                    tr.play();
                    sTrack = tr;
                }
            } catch (Throwable t) {
                Log.w(TAG, "chime: " + t);
            }
        }, "p6-lens-chime").start();
    }

    private static short[] makeChime() {
        float[] notes = {659.25f, 987.77f, 1318.51f};                    // E5, B5, E6
        int step = (int) (RATE * 0.11f), len = (int) (RATE * 0.9f);
        short[] out = new short[step * (notes.length - 1) + len];
        for (int n = 0; n < notes.length; n++) {
            int at = n * step;
            for (int i = 0; i < len && at + i < out.length; i++) {
                float t = i / (float) RATE;
                float env = Math.min(1f, t / 0.008f) * (float) Math.exp(-t * 5f);
                double w = 2 * Math.PI * notes[n] * t;
                float v = (float) (Math.sin(w) + 0.25 * Math.sin(2 * w) + 0.08 * Math.sin(3 * w));
                int s = out[at + i] + (int) (v * env * 0.12f * Short.MAX_VALUE);
                out[at + i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
            }
        }
        return out;
    }
}
