package org.lumen.projector.source;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.Log;

import org.lumen.projector.Ui;
import org.lumen.projector.audio.AudioPathReporter;

/**
 * Lumen OS 1.0.4: the HDMI volume of the Z9X (the remote's volume keys did nothing to an HDMI source in 1.0.3).
 * Same idea as the Play 6 (apps/Play6Projector source/HdmiVolume, docs/play6/research/hdmi_volume_game.md): the
 * HDMI sound never passes Android's mixer, so only the audio HAL's own speaker level changes it, and stock's
 * patched AudioService set that level with AudioManager.setParameters("spk_volume=N") (N = the music index,
 * 0..100) on every volume change. Lumen's framework sends nothing, so the HDMI level stayed where it started.
 *
 * The Z9X's HAL (audio.primary.mt5877, CHANGES.md 1.0.3 "HDMI volume") applies spk_volume only after a
 * "xgimi_audiopath_switch_end=P" has selected a path (that state is zero from adev_open): before, it only stores
 * the value. "xgimi_audiopath_switch_start=P" sets the device's level to 0 and switch_end restores it from the
 * stored spk_volume, so the order is always spk_volume first, then start / end (never end without a stored
 * value: that would be a silent speaker). P = 0, the speaker path, as stock's audio policy for the speaker.
 *
 * While an HDMI picture is shown ({@link #start}, from HdmiSource's decision): spk_volume = STREAM_MUSIC scaled
 * to 0..100 (0 while muted), then the path selection once per start (the audio server may have restarted since
 * the last one); afterwards every music volume or mute change sends spk_volume again (unchanged values are not
 * sent). When HDMI ends ({@link #stop}): the receiver goes and spk_volume=100 is sent, the full DSP level, so
 * Android's own audio, which keeps its software volume in AudioFlinger, plays as before. While HDMI is shown,
 * Android sounds get both attenuations. Log tag Z9xHdmiVol. Main thread only.
 *
 * Speaker only: the path selection is the speaker's, so it is never sent while the media output is another device
 * (ARC soundbar, Bluetooth, wired, USB: AudioPathReporter's confirmed path, or before its first report the connected
 * outputs). Such an output keeps the 1.0.3 behaviour (its own volume). A later confirmed path switches this on or
 * off while HDMI is shown: speaker again = start with a fresh path selection; another device = stop following,
 * spk_volume back to 100.
 */
public final class HdmiVolume {
    private static final String TAG = "Z9xHdmiVol";
    static final String ACTION_VOLUME = "android.media.VOLUME_CHANGED_ACTION";
    static final String ACTION_MUTE = "android.media.STREAM_MUTE_CHANGED_ACTION";
    static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    /** The value sent when HDMI ends: the full DSP level. */
    static final int NEUTRAL = 100;
    /** The speaker path of the vendor (stock audio policy: SPEAKER and anything unlisted -> 0). */
    static final int PATH_SPEAKER = 0;
    static final long LOG_GAP_MS = 500;

    private static BroadcastReceiver sReceiver;
    /** HDMI is shown (start called, stop not yet): a confirmed speaker path then starts following. */
    private static boolean sWanted;
    private static boolean sPathListener;
    private static volatile int sSent = -1;
    private static long sLastLogAt = -LOG_GAP_MS;
    private static int sUnlogged;
    private static final Runnable sTrailingLog = HdmiVolume::logTrailing;

    private HdmiVolume() {}

    /** HDMI is shown: follow the music volume, and make the HAL apply it. Idempotent. Main thread. */
    static void start(Context ctx) {
        final Context app = ctx.getApplicationContext();
        sWanted = true;
        listenPath(app);
        if (!speakerOut(app)) {
            if (sReceiver != null) pause(app, "output is not the speaker");
            return;
        }
        boolean first = sReceiver == null;
        if (first) {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    try {
                        String a = i == null ? null : i.getAction();
                        if (ACTION_VOLUME.equals(a) && i.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return;
                        apply(app, "volume", false);
                    } catch (Throwable t) {
                        Log.w(TAG, "volume: " + t);
                    }
                }
            };
            IntentFilter f = new IntentFilter(ACTION_VOLUME);
            f.addAction(ACTION_MUTE);
            app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            sReceiver = r;
            Log.i(TAG, "HDMI volume follows STREAM_MUSIC (spk_volume + speaker path)");
        }
        apply(app, "HDMI shown", first);
    }

    /** HDMI ended (or is not shown): stop following, spk_volume back to 100. Main thread. */
    static void stop(Context ctx, String why) {
        sWanted = false;
        pause(ctx.getApplicationContext(), why);
    }

    /** Stop following (receiver off, spk_volume back to 100); HDMI may still be shown. Main thread. */
    private static void pause(Context app, String why) {
        if (sReceiver != null) {
            try { app.unregisterReceiver(sReceiver); } catch (Throwable t) { Log.w(TAG, "unregister: " + t); }
            sReceiver = null;
        }
        Ui.main().removeCallbacks(sTrailingLog);
        sUnlogged = 0;
        if (sSent < 0 || sSent == NEUTRAL) return;      // nothing changed the HAL level yet
        send(app, NEUTRAL);
        sLastLogAt = SystemClock.uptimeMillis();
        Log.i(TAG, "spk_volume=" + NEUTRAL + " (" + why + ")");
    }

    /**
     * The media output is the projector's speaker: AudioPathReporter's confirmed path 0, or (no path confirmed yet)
     * the user chose Speaker, or no ARC / eARC, Bluetooth, wired or USB output connected. 1.0.4: Spdif
     * (TYPE_LINE_DIGITAL) no longer counts: it is an attached port of the Z9X policy, always listed, and the Z9X has no
     * optical jack, so it made this fallback answer "not the speaker" on every boot.
     */
    private static boolean speakerOut(Context app) {
        int p = AudioPathReporter.confirmedPath();
        if (p >= 0) return p == 0;
        if (org.lumen.projector.audio.AudioOutputChoice.kind(app)
                == org.lumen.projector.audio.AudioOutputChoice.Kind.SPEAKER) return true;
        AudioManager am = app.getSystemService(AudioManager.class);
        if (am == null) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_HDMI_ARC: case AudioDeviceInfo.TYPE_HDMI_EARC:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_BLE_SPEAKER: case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                    return false;
                default:
            }
        }
        return true;
    }

    /** Once per process: a confirmed path change while HDMI is shown starts or stops following. */
    private static void listenPath(Context app) {
        if (sPathListener) return;
        sPathListener = true;
        AudioPathReporter.addPathListener(path -> Ui.main().post(() -> {
            if (!sWanted) return;
            try {
                if (path == 0) start(app);
                else if (sReceiver != null) pause(app, "output path " + path);
            } catch (Throwable t) {
                Log.w(TAG, "path " + path + ": " + t);
            }
        }));
    }

    /** STREAM_MUSIC scaled to 0..100, 0 while muted; sent when it changed, the path selected when asked. */
    private static void apply(Context app, String why, boolean selectPath) {
        AudioManager am = app.getSystemService(AudioManager.class);
        if (am == null) return;
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int v = am.isStreamMute(AudioManager.STREAM_MUSIC) ? 0
                : Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
        v = Math.max(0, Math.min(100, v));
        if (v != sSent || selectPath) send(app, v);
        if (selectPath) selectSpeakerPath(am, v);
        long now = SystemClock.uptimeMillis();
        if (selectPath || now - sLastLogAt >= LOG_GAP_MS) {
            sLastLogAt = now;
            sUnlogged = 0;
            Log.i(TAG, "spk_volume=" + v + " (" + why + (selectPath ? ", speaker path selected" : "") + ")");
        } else {
            sUnlogged++;
            Ui.main().removeCallbacks(sTrailingLog);
            Ui.main().postDelayed(sTrailingLog, LOG_GAP_MS);
        }
    }

    /** switch_start then switch_end of the speaker path: the HAL applies the stored spk_volume from now on. */
    private static void selectSpeakerPath(AudioManager am, int v) {
        if (sSent != v) return;                          // spk_volume did not reach the HAL: never end without it
        try {
            am.setParameters("xgimi_audiopath_switch_start=" + PATH_SPEAKER);
            am.setParameters("xgimi_audiopath_switch_end=" + PATH_SPEAKER);
        } catch (Throwable t) {
            Log.w(TAG, "speaker path: " + t);
        }
    }

    /**
     * Lumen OS 1.0.6: the media output leaves Bluetooth (AudioPathReporter had selected the HAL's A2DP sink with
     * "xgimi_audiopath_switch_end=3"): select the speaker sink again. switch_end restores the speaker level from the
     * stored spk_volume, which is 0 from adev_open, so it is stored first when nothing was sent this process (the
     * neutral 100; while HDMI is followed the value sent last is already there). Main thread.
     */
    public static void selectSpeakerSink(Context ctx, String why) {
        AudioManager am = ctx.getApplicationContext().getSystemService(AudioManager.class);
        if (am == null) return;
        if (sSent < 0) send(ctx.getApplicationContext(), NEUTRAL);
        if (sSent < 0) {
            Log.w(TAG, "speaker sink not selected (" + why + "): spk_volume not stored");
            return;
        }
        try {
            am.setParameters("xgimi_audiopath_switch_end=" + PATH_SPEAKER);
            Log.i(TAG, "speaker sink selected, spk_volume=" + sSent + " (" + why + ")");
        } catch (Throwable t) {
            Log.w(TAG, "speaker sink: " + t);
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
