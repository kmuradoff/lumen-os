package org.z9x.projector.source;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.Log;

import org.z9x.projector.Ui;

/**
 * The HDMI volume of the XGIMI Play 6 (t15; docs/play6/research/hdmi_volume_game.md 2). The audio HAL treats
 * the HDMI In port gain the framework sends as mute / unmute only; its real speaker volume is the per-device
 * index of AudioManager.setParameters("spk_volume=N") (adev_set_parameters -> utils_set_tv_volume_index, the
 * curves of /vendor/etc/default_volume_tables.xml). Stock's patched AudioService sends it on every music
 * volume change; Lumen's framework does not, so HDMI played at a fixed level whatever the volume bar said.
 *
 * While an HDMI session is shown ({@link #start}, from source/HdmiSource): a receiver for VOLUME_CHANGED_ACTION
 * (STREAM_MUSIC only) and STREAM_MUTE_CHANGED_ACTION sends spk_volume = the STREAM_MUSIC index scaled to
 * 0..100 (0 while muted) on every change, and once at the start; an unchanged value is not sent again. When
 * HDMI ends ({@link #stop}, also at the IGmpf connect and the screen-on self-heal when no HDMI is shown):
 * the receiver goes and spk_volume=100 is sent once. Assumption (research 6, UNVERIFIED): 100 is the neutral
 * value, the speaker DSP's level on Lumen today, because Android media keeps its own software volume in
 * AudioFlinger; while HDMI is shown, Android sounds get both attenuations. Speaker only (no ARC, Bluetooth or
 * USB keys). Log tag P6Hdmi: one line per change, at most one every {@value #LOG_GAP_MS} ms while a volume key
 * repeats (the last value of a burst is logged when it settles). Main thread only; no thread, no wake lock.
 */
public final class HdmiVolume {
    private static final String TAG = "P6Hdmi";
    static final String ACTION_VOLUME = "android.media.VOLUME_CHANGED_ACTION";
    static final String ACTION_MUTE = "android.media.STREAM_MUTE_CHANGED_ACTION";
    static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    /** The value sent when HDMI ends: the full DSP level (research 2.2 step 3; assumption, see above). */
    static final int NEUTRAL = 100;
    static final long LOG_GAP_MS = 500;

    private static BroadcastReceiver sReceiver;
    private static int sSent = -1;
    private static long sLastLogAt = -LOG_GAP_MS;
    private static int sUnlogged;
    private static final Runnable sTrailingLog = HdmiVolume::logTrailing;

    private HdmiVolume() {}

    /** HDMI is shown: follow the music volume. Idempotent. Main thread. */
    static void start(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (sReceiver == null) {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    try {
                        String a = i == null ? null : i.getAction();
                        if (ACTION_VOLUME.equals(a) && i.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return;
                        apply(app, "volume");
                    } catch (Throwable t) {
                        Log.w(TAG, "volume: " + t);
                    }
                }
            };
            IntentFilter f = new IntentFilter(ACTION_VOLUME);
            f.addAction(ACTION_MUTE);
            app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            sReceiver = r;
            Log.i(TAG, "HDMI volume follows STREAM_MUSIC (spk_volume)");
        }
        apply(app, "HDMI shown");
    }

    /** HDMI ended (or is not shown at a connect / wake): stop following, spk_volume back to 100. Main thread. */
    static void stop(Context ctx, String why) {
        Context app = ctx.getApplicationContext();
        if (sReceiver != null) {
            try { app.unregisterReceiver(sReceiver); } catch (Throwable t) { Log.w(TAG, "unregister: " + t); }
            sReceiver = null;
        }
        Ui.main().removeCallbacks(sTrailingLog);
        sUnlogged = 0;
        if (sSent == NEUTRAL) return;
        send(app, NEUTRAL);
        sLastLogAt = SystemClock.uptimeMillis();
        Log.i(TAG, "spk_volume=" + NEUTRAL + " (" + why + "; assumed neutral: Android media keeps its own volume)");
    }

    /** One line for Diagnostics. Main thread. */
    static String describe() {
        return sReceiver != null ? "spk_volume=" + sSent + " (following the volume)"
                : sSent < 0 ? "spk_volume not sent yet" : "spk_volume=" + sSent;
    }

    /** STREAM_MUSIC scaled to 0..100, 0 while muted; sent when it changed. */
    private static void apply(Context app, String why) {
        AudioManager am = app.getSystemService(AudioManager.class);
        if (am == null) return;
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int v = am.isStreamMute(AudioManager.STREAM_MUSIC) ? 0
                : Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
        v = Math.max(0, Math.min(100, v));
        if (v == sSent) return;
        send(app, v);
        long now = SystemClock.uptimeMillis();
        if (now - sLastLogAt >= LOG_GAP_MS) {
            sLastLogAt = now;
            sUnlogged = 0;
            Log.i(TAG, "spk_volume=" + v + " (" + why + ")");
        } else {
            sUnlogged++;
            Ui.main().removeCallbacks(sTrailingLog);
            Ui.main().postDelayed(sTrailingLog, LOG_GAP_MS);
        }
    }

    private static void logTrailing() {
        if (sUnlogged == 0) return;
        sLastLogAt = SystemClock.uptimeMillis();
        Log.i(TAG, "spk_volume=" + sSent + " (volume, " + sUnlogged + " more step" + (sUnlogged == 1 ? "" : "s") + ")");
        sUnlogged = 0;
    }

    private static void send(Context app, int v) {
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            if (am == null) return;
            am.setParameters("spk_volume=" + v);
            sSent = v;
        } catch (Throwable t) {
            Log.w(TAG, "spk_volume=" + v + ": " + t);
        }
    }
}
