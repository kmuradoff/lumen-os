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
 * Lumen OS 1.0.4, "Open at power-on" (Projector settings), the stock boot source (开机源: Default, HDMI 1,
 * HDMI 2; stock SignalRepository.setBootSource, applied by XGIMI's own boot service, which Lumen does not
 * have): when the projector comes on (a cold start, a wake from sleep, the end of the lamp-only standby) the
 * chosen HDMI input opens. "Home screen" (the default) changes nothing. Nothing opens when the setup has not
 * finished, the projector is not on, an HDMI picture is already shown (a source that switched the projector
 * on through HDMI-CEC wins, as the stock tip says) or the input does not exist. One power-on opens once
 * (the wake and the end of the standby arrive together).
 */
public final class BootSource {
    private static final String TAG = "Z9xBootSource";
    private static final String PREFS = "boot_source";
    private static final String KEY = "source";
    public static final int HOME = 0, HDMI1 = 1, HDMI2 = 2;
    /** After the wake: the lamp, the wake curtain and org.z9x.tvinput's inputs settle first. */
    private static final long DELAY_MS = 3_000;
    private static final long SAME_POWER_ON_MS = 15_000;
    private static long sLastAt = -SAME_POWER_ON_MS;

    private BootSource() {}

    public static int get(Context c) {
        int v = prefs(c).getInt(KEY, HOME);
        return v == HDMI1 || v == HDMI2 ? v : HOME;
    }

    public static void set(Context c, int v) {
        prefs(c).edit().putInt(KEY, v == HDMI1 || v == HDMI2 ? v : HOME).apply();
        Log.i(TAG, "open at power-on: " + v);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The projector came on (BootReceiver, App SCREEN_ON, StandbyController's standby exit). Main thread. */
    public static void onPowerOn(Context ctx, String why) {
        final Context c = ctx.getApplicationContext();
        final int port = get(c);
        if (port == HOME) return;
        long now = SystemClock.uptimeMillis();
        if (now - sLastAt < SAME_POWER_ON_MS) return;
        sLastAt = now;
        Log.i(TAG, "power on (" + why + "): HDMI " + port + " in " + DELAY_MS + " ms");
        Ui.main().postDelayed(() -> new Thread(() -> check(c, port, why), "z9x-bootsource").start(), DELAY_MS);
    }

    /** Worker thread: the checks that need IPC, then the open on the main thread. */
    private static void check(Context c, int port, String why) {
        try {
            if (!KeyReceiver.isSetupComplete(c)) { Log.i(TAG, "setup not complete"); return; }
            PowerManager pm = c.getSystemService(PowerManager.class);
            if (pm == null || !pm.isInteractive() || StandbyController.isActive()) { Log.i(TAG, "not on"); return; }
            if (Boolean.TRUE.equals(TifHdmiState.read(c))) { Log.i(TAG, "an HDMI picture is already on"); return; }
            Ui.main().post(() -> {
                try {
                    Ui.wakeFromDream(c);
                    if (!SourceOverlay.openPort(c, port, "boot_source")) Log.i(TAG, "HDMI " + port + " not opened");
                } catch (Throwable t) {
                    Log.w(TAG, "open: " + t);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "power on (" + why + "): " + t);
        }
    }
}
