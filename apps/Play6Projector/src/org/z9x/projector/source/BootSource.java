package org.z9x.projector.source;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.z9x.projector.KeyReceiver;
import org.z9x.projector.Ui;
import org.z9x.projector.power.StandbyController;

/**
 * "Open at power-on" (Projector settings), stock parity 2026-10-21: the stock boot source (signal_boot_source_info,
 * Default or HDMI; stock SignalRepository.setBootSource, applied by XGIMI's own boot service, which Lumen does not
 * have). The Play 6 port of the Z9X 1.0.4 class (apps/Z9xProjector source/BootSource), with the one HDMI port
 * (HW1): when the projector comes on (a cold start, an Android wake, the end of the lamp-only standby) the HDMI
 * input opens. "Home screen" (the default) changes nothing. Nothing opens when the setup has not finished, the
 * projector is not on, an HDMI picture is already shown (a source that switched the projector on wins) or the
 * input does not exist. One power-on opens once (the wake and the end of the standby can arrive together).
 */
public final class BootSource {
    private static final String TAG = "P6BootSource";
    private static final String PREFS = "boot_source";
    private static final String KEY = "source";
    public static final int HOME = 0, HDMI = 1;
    /** The one HDMI port of the Play 6 (org.z9x.tvinput HW1). */
    private static final int PORT = 1;
    /** After the wake: the lamp, the wake curtain and org.z9x.tvinput's inputs settle first. */
    private static final long DELAY_MS = 3_000;
    private static final long SAME_POWER_ON_MS = 15_000;
    private static long sLastAt = -SAME_POWER_ON_MS;

    private BootSource() {}

    public static int get(Context c) {
        return prefs(c).getInt(KEY, HOME) == HDMI ? HDMI : HOME;
    }

    public static void set(Context c, int v) {
        prefs(c).edit().putInt(KEY, v == HDMI ? HDMI : HOME).apply();
        Log.i(TAG, "open at power-on: " + (v == HDMI ? "HDMI" : "Home"));
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The projector came on (start gate, SCREEN_ON, the end of the lamp-only standby). Main thread. */
    public static void onPowerOn(Context ctx, String why) {
        final Context c = ctx.getApplicationContext();
        if (get(c) != HDMI) return;
        long now = SystemClock.uptimeMillis();
        if (now - sLastAt < SAME_POWER_ON_MS) return;
        sLastAt = now;
        Log.i(TAG, "power on (" + why + "): HDMI in " + DELAY_MS + " ms");
        Ui.main().postDelayed(() -> new Thread(() -> check(c, why), "p6-bootsource").start(), DELAY_MS);
    }

    /** Worker thread: the checks that need IPC, then the open on the main thread. */
    private static void check(Context c, String why) {
        try {
            if (!KeyReceiver.isSetupComplete(c)) { Log.i(TAG, "setup not complete"); return; }
            PowerManager pm = c.getSystemService(PowerManager.class);
            if (pm == null || !pm.isInteractive() || StandbyController.isActive()) { Log.i(TAG, "not on"); return; }
            if (Boolean.TRUE.equals(TifHdmiState.read(c))) { Log.i(TAG, "an HDMI picture is already on"); return; }
            Ui.main().post(() -> {
                try {
                    Ui.wakeFromDream(c);
                    if (!SourceOverlay.openPort(c, PORT, "boot_source")) Log.i(TAG, "HDMI not opened");
                } catch (Throwable t) {
                    Log.w(TAG, "open: " + t);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "power on (" + why + "): " + t);
        }
    }
}
