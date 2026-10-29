package org.lumen.projector.audio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.lumen.projector.Hal;
import org.lumen.projector.Ui;
import org.lumen.projector.hal.Play6Gmpf;

import java.util.Arrays;

/**
 * Equalizer of the XGIMI Play 6 (owner 2026-10-21: "like the Z9X, Equalizer = Bass"). Not in the stock firmware;
 * the same mechanism as the Z9X app's audio/SoundEq: the SoC PEQ of the built-in speaker path, IGmpf 50 SetPEQ /
 * 51 SetPEQEnable (Play 6 research 2026-10-21: libporting PlatformTvCtrl::SetPEQ @0x37530, band 1..13, gain 0.1 dB
 * -180..180, fc 20..20000 Hz, Q 0.1 5..160, the same table layout as the Z9X, MI_AOUT path 4 = built-in speaker).
 *
 * The Play 6 always runs the AMP process (26 == 2, CustomerEnvTbl sound_process_type 2, DTS off 43 == false); the
 * vendor touches the PEQ only on a process change (setAdvSnd turns it off) and in DTS (setPeqForINI): neither
 * happens in AMP, so a curve survives every sound mode (61). It is lost when the output leaves the speaker and
 * comes back, at the vendor's boot restore and after a gmpf_main restart: the triggers below write it again.
 *
 * Read first, every time: 26 == 2, 43 false, 60 == 0 (speaker); anything else or a failed read: nothing is sent
 * (logged). A curve: 50 for all 13 bands (1..7 the curve, 8..13 0 dB / 1 kHz / Q 1.0), stop at the first reply
 * other than 1, then 51(true) only after all 13 succeeded. Flat: 51(false) (only when this app turned it on, or
 * on the user's own choice). The same curve is not rewritten within {@link #DEDUP_MS}. Never 27 / 44 (process
 * switch: DTS load and amp bypass), 45..49 (HPF, DRC, prescale, volume dB), libharman_audio (not loaded on stock).
 *
 * Presets for the Play 6 speaker (its amp tables already cut below ~90 Hz and lift ~150 Hz by about +10 dB, so the
 * Z9X 60 Hz band is not used): bands 100 / 150 / 400 / 1k / 2.4k / 6k / 12k Hz, Q 1.0; Bass +3 / +1.5 dB at 100 /
 * 150 Hz; Voice and Night as the Z9X shapes. Boosts below 200 Hz stay <= +3 dB; an automatic downward shift of all
 * bands (0.5 dB steps) keeps the combined curve at or below +3 dB. One preset for every sound mode.
 * Triggers: the user's choice; HAL (re)connect +5 s / +30 s / +130 s; SCREEN_ON +3 s / +20 s; after our own sound
 * mode change +600 ms (Play6PanelController). Log tag P6Eq. HAL on p6-hal only.
 */
public final class P6SoundEq {
    static final String TAG = "P6Eq";
    private static final String PREFS = "p6_eq";
    private static final String K_PRESET = "preset";
    private static final String K_ENABLED = "enabled_by_us";

    public static final int FLAT = 0, BASS = 1, VOICE = 2, NIGHT = 3;
    static final int[] FC_HZ = {100, 150, 400, 1000, 2400, 6000, 12000};
    private static final int Q_TENTH = 10;
    /** Presets in 0.1 dB, bands of FC_HZ. */
    static final int[][] PRESETS = {
            {0, 0, 0, 0, 0, 0, 0},
            {30, 15, 0, 0, 0, 0, 0},              // Bass
            {-20, -15, -5, 15, 30, 15, 0},        // Voice
            {-40, -30, -10, 0, 15, 10, 0},        // Night: less bass, slight presence
    };
    private static final double HEADROOM_DB = 3.0;
    private static final long DEDUP_MS = 1_500;
    private static final long[] CONNECT_MS = {5_000, 30_000, 130_000};
    private static final long[] SCREEN_ON_MS = {3_000, 20_000};

    private static volatile String sLastSent;
    private static volatile long sLastSentAt;

    private P6SoundEq() {}

    private static SharedPreferences prefs(Context c) {
        try {
            return c.getApplicationContext().createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    public static int preset(Context c) {
        int v = prefs(c).getInt(K_PRESET, FLAT);
        return v >= FLAT && v <= NIGHT ? v : FLAT;
    }

    /** The equalizer can work now: AMP process, no DTS, output on the built-in speaker (p6-hal). */
    public static boolean usable(Play6Gmpf g) {
        try {
            int proc = g.getSoundProcess();                          // 26
            boolean dts = g.getDtsEffects();                         // 43
            int out = g.getAudioOutput();                            // 60
            boolean ok = proc == Play6Gmpf.SOUND_PROCESS_AMP && !dts && out == Play6Gmpf.AUDIO_OUTPUT_SPEAKER;
            if (!ok) Log.i(TAG, "not usable now: process " + proc + ", dts " + dts + ", output " + out);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "gate reads: " + t);
            return false;
        }
    }

    /** User choice (main thread): stored, then applied on p6-hal. */
    public static void choose(Context ctx, int p) {
        final Context app = ctx.getApplicationContext();
        if (p < FLAT || p > NIGHT) return;
        prefs(app).edit().putInt(K_PRESET, p).apply();
        Log.i(TAG, "preset " + p + " chosen");
        sLastSent = null;
        Hal.run((g, g2) -> apply(app, g, true, "user"));
    }

    /** Play6App at the start gate: the re-apply triggers. No HAL call here. */
    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Hal.addConnectedListener(() -> {
            sLastSent = null;                                        // gmpf_main restart: its table is gone
            for (long d : CONNECT_MS) Hal.runDelayed((g, g2) -> apply(app, g, false, "connect +" + d / 1000 + " s"), d);
        });
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    for (long d : SCREEN_ON_MS) Hal.runDelayed((g, g2) -> apply(app, g, false, "screen on +" + d / 1000 + " s"), d);
                }
            }, new IntentFilter(Intent.ACTION_SCREEN_ON), null, Ui.main(), Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "screen receiver: " + t);
        }
    }

    /** After a sound mode change of ours (the panel): the curve again, once the vendor switched (p6-hal). */
    public static void afterSoundModeChange(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Hal.runDelayed((g, g2) -> apply(app, g, false, "sound mode"), 600);
    }

    /** p6-hal. Writes the stored preset when the gate allows it. */
    static void apply(Context app, Play6Gmpf g, boolean user, String why) {
        int p = preset(app);
        if (p == FLAT) {
            if (!user && !prefs(app).getBoolean(K_ENABLED, false)) return;     // nothing of ours to undo
            if (!usable(g)) return;
            try {
                int r = g.setPeqEnabled(false);                                  // 51(false)
                prefs(app).edit().putBoolean(K_ENABLED, false).apply();
                sLastSent = "flat";
                Log.i(TAG, "flat (" + why + "): 51(false) -> " + r);
            } catch (Throwable t) {
                Log.w(TAG, "flat (" + why + "): " + t);
            }
            return;
        }
        int[] sent = withPreamp(PRESETS[p]);
        String key = p + Arrays.toString(sent);
        if (!user && key.equals(sLastSent) && SystemClock.uptimeMillis() - sLastSentAt < DEDUP_MS) return;
        if (!usable(g)) return;
        try {
            for (int b = 1; b <= Play6Gmpf.PEQ_BANDS; b++) {
                int gain = b <= FC_HZ.length ? sent[b - 1] : 0;
                int fc = b <= FC_HZ.length ? FC_HZ[b - 1] : 1000;
                int r = g.setPeqBand(b, gain, fc, Q_TENTH);                       // 50
                if (r != 1) {
                    Log.w(TAG, "preset " + p + " (" + why + "): 50 band " + b + " -> " + r + ", stopped (no 51)");
                    return;
                }
            }
            int r = g.setPeqEnabled(true);                                       // 51(true)
            prefs(app).edit().putBoolean(K_ENABLED, true).apply();
            sLastSent = key;
            sLastSentAt = SystemClock.uptimeMillis();
            Log.i(TAG, "preset " + p + " (" + why + "): bands " + Arrays.toString(sent) + " x0.1 dB, 51(true) -> " + r);
        } catch (Throwable t) {
            Log.w(TAG, "preset " + p + " (" + why + "): " + t);
        }
    }

    /** The curve shifted down in 0.5 dB steps until its combined peak (RBJ peaking, 48 kHz) is <= HEADROOM_DB. */
    static int[] withPreamp(int[] tenth) {
        int[] out = tenth.clone();
        for (int shift = 0; shift <= 60; shift += 5) {
            int[] t = new int[tenth.length];
            for (int i = 0; i < t.length; i++) {
                t[i] = Math.max(Play6Gmpf.PEQ_GAIN_MIN, Math.min(Play6Gmpf.PEQ_GAIN_MAX, tenth[i] - shift));
            }
            out = t;
            if (peakDb(t) <= HEADROOM_DB) break;
        }
        return out;
    }

    private static double peakDb(int[] tenth) {
        double peak = -100;
        double lo = Math.log(20.0), hi = Math.log(20_000.0);
        for (int k = 0; k < 300; k++) {
            double f = Math.exp(lo + (hi - lo) * k / 299);
            double sum = 0;
            for (int i = 0; i < tenth.length; i++) sum += peakingDb(f, FC_HZ[i], tenth[i] / 10.0, Q_TENTH / 10.0);
            peak = Math.max(peak, sum);
        }
        return peak;
    }

    /** Magnitude in dB of an RBJ peaking biquad at f. */
    private static double peakingDb(double f, double f0, double gainDb, double q) {
        if (gainDb == 0) return 0;
        double fs = 48_000.0, a = Math.pow(10, gainDb / 40), w0 = 2 * Math.PI * f0 / fs, alpha = Math.sin(w0) / (2 * q);
        double b0 = 1 + alpha * a, b1 = -2 * Math.cos(w0), b2 = 1 - alpha * a;
        double a0 = 1 + alpha / a, a1 = -2 * Math.cos(w0), a2 = 1 - alpha / a;
        double w = 2 * Math.PI * f / fs, c1 = Math.cos(w), s1 = Math.sin(w), c2 = Math.cos(2 * w), s2 = Math.sin(2 * w);
        double nr = b0 + b1 * c1 + b2 * c2, ni = -(b1 * s1 + b2 * s2);
        double dr = a0 + a1 * c1 + a2 * c2, di = -(a1 * s1 + a2 * s2);
        return 10 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di));
    }
}
