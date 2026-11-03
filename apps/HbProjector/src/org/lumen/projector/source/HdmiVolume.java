package org.lumen.projector.source;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.Log;

import org.lumen.projector.Ui;
import org.lumen.projector.audio.AudioPathReporter;
import org.lumen.projector.audio.HbAudioRules;

/**
 * Lumen OS 1.0.4: the HDMI volume of the Z9X (the remote's volume keys did nothing to an HDMI source in 1.0.3).
 * Same idea as the Play 6 (apps/Play6Projector source/HdmiVolume, stock analysis): the
 * HDMI sound never passes Android's mixer, so only the audio HAL's own speaker level changes it, and stock's
 * patched AudioService set that level with AudioManager.setParameters("spk_volume=N") (N = the music index,
 * 0..100) on every volume change. Lumen's framework sends nothing, so the HDMI level stayed where it started.
 *
 * The Z9X's HAL (audio.primary.mt5877 "HDMI volume") applies spk_volume only after a
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
 *
 * Z9X HB fork (2026-11-01, ARC volume spike, device tests): the HB HAL
 * carried ARC on its 'speaker' device, so the 1.0.4 rule "another output = spk_volume back to 100" put the full DSP
 * level on the soundbar (logcat_hdmi1.txt:74957-74963, spk_volume 7 -> 100). Stock HB setDspVolumesToHal (services.jar
 * AudioService) writes spk_volume only for DEVICE_OUT_SPEAKER and has no HDMI_ARC branch.
 *
 * Z9X HB fork, second change (2026-11-01, hb-t12 "sound to maximum by itself", ~/hb_session/audio/hb_full.txt on the
 * builder): Speaker chosen at 16:52:10, selectSpeakerSink stored spk_volume=100 (no HDMI picture) at 16:52:12, and the
 * volume keys then moved STREAM_MUSIC 10 -> 4 with no spk_volume at all: the speaker amp stayed at 100. Stock HB
 * setDspVolumesToHal sends spk_volume=N on the speaker for every music change, HDMI picture or not, and never 100. So on
 * the HB (HbAudioRules.volumeStep / speakerVolume / speakerSinkStore):
 *  - spk_volume follows STREAM_MUSIC whenever the speaker is both the routed path and the vendor's confirmed path
 *    (AudioPathReporter.targetPath / confirmedPath), HDMI picture or not: every music volume or mute change;
 *  - the current level is sent again when following starts (the speaker confirmed again, process start / boot, an
 *    HDMI picture, SCREEN_ON), with the speaker path selected (switch_start / switch_end) the first time in a process
 *    and at every HDMI start and wake, as the 1.0.4 HDMI start did;
 *  - before switch_end=0 (selectSpeakerSink) the music level is stored, never 100;
 *  - nothing is sent while the output is not the confirmed speaker (ARC, Bluetooth, wired, USB, a switch pending);
 *  - no 100 anywhere: on the Z9X it was the level for Android sounds without an HDMI picture; the HB stock has none.
 * The HDMI input keeps working as on 10-31 (audio_log.txt: HDMI shown, every key 'spk_volume=N (volume)' with the HAL's
 * 'mi_device_set: set volume: device speaker, volume index N'): HDMI only adds the path selection at its start.
 */
public final class HdmiVolume {
    private static final String TAG = "Z9xHdmiVol";
    static final String ACTION_VOLUME = "android.media.VOLUME_CHANGED_ACTION";
    static final String ACTION_MUTE = "android.media.STREAM_MUTE_CHANGED_ACTION";
    static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    /** The speaker path of the vendor (stock audio policy: SPEAKER and anything unlisted -> 0). */
    static final int PATH_SPEAKER = 0;
    static final long LOG_GAP_MS = 500;
    /** After SCREEN_ON: AudioPathReporter re-checks the vendor path 3 s after the wake. */
    static final long SCREEN_ON_MS = 3_500L;

    private static Context sApp;
    /** HDMI is shown (start called, stop not yet). Only adds the path selection at its start. */
    private static boolean sWanted;
    /** spk_volume follows STREAM_MUSIC (the speaker is confirmed). */
    private static boolean sFollowing;
    /** A switch_end=0 with spk_volume stored went to the HAL in this process. */
    private static boolean sPathSelected;
    private static volatile int sSent = -1;
    private static long sLastLogAt = -LOG_GAP_MS;
    private static int sUnlogged;
    private static final Runnable sTrailingLog = HdmiVolume::logTrailing;
    private static final Runnable sScreenOn = () -> follow("screen on", true, true);

    private HdmiVolume() {}

    /**
     * HB fork: once per process (AudioPathReporter.install posts it, HdmiSource's start / stop call it too): the music
     * volume and mute broadcasts, SCREEN_ON and the path state listener; follows at once when the speaker is confirmed.
     * Main thread.
     */
    public static void install(Context ctx) {
        if (sApp != null) return;
        final Context app = ctx.getApplicationContext();
        sApp = app;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    try {
                        String a = i == null ? null : i.getAction();
                        if (Intent.ACTION_SCREEN_ON.equals(a)) {
                            Ui.main().removeCallbacks(sScreenOn);
                            Ui.main().postDelayed(sScreenOn, SCREEN_ON_MS);
                            return;
                        }
                        if (ACTION_VOLUME.equals(a) && i.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return;
                        follow("volume", false, false);
                    } catch (Throwable t) {
                        Log.w(TAG, "volume: " + t);
                    }
                }
            };
            IntentFilter f = new IntentFilter(ACTION_VOLUME);
            f.addAction(ACTION_MUTE);
            f.addAction(Intent.ACTION_SCREEN_ON);
            app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "receiver: " + t);
        }
        AudioPathReporter.addStateListener((target, confirmed) -> Ui.main().post(() -> {
            try {
                follow("output path " + target + ", confirmed " + confirmed, false, false);
            } catch (Throwable t) {
                Log.w(TAG, "path " + target + "/" + confirmed + ": " + t);
            }
        }));
        Log.i(TAG, "speaker volume follows STREAM_MUSIC while the speaker is confirmed (spk_volume)");
        follow("process start", false, false);
    }

    /** HDMI is shown: the current level again with a fresh speaker path selection (audio server may have restarted). */
    static void start(Context ctx) {
        install(ctx);
        boolean fresh = !sWanted;
        sWanted = true;
        follow("HDMI shown", fresh, fresh);
    }

    /** HDMI ended (or is not shown). HB fork: nothing changes on the speaker (it keeps following, no 100). */
    static void stop(Context ctx, String why) {
        install(ctx);
        sWanted = false;
        follow(why, false, false);
    }

    /**
     * The one decision (HbAudioRules): off the confirmed speaker nothing is sent; on it spk_volume = the music level,
     * when it changed, or always with force (and at the start of following); select = switch_start / switch_end of the
     * speaker path after it (also the first time in a process). Main thread.
     */
    private static void follow(String why, boolean force, boolean select) {
        Context app = sApp;
        if (app == null) return;
        AudioManager am = app.getSystemService(AudioManager.class);
        if (am == null) return;
        boolean speaker = HbAudioRules.volumeStep(AudioPathReporter.targetPath(), AudioPathReporter.confirmedPath())
                == HbAudioRules.VolumeStep.FOLLOW;
        if (!speaker) {
            if (sFollowing) {
                sFollowing = false;
                Ui.main().removeCallbacks(sTrailingLog);
                sUnlogged = 0;
                Log.i(TAG, "spk_volume not followed (" + why + ", output is not the confirmed speaker), stays " + sSent);
            }
            return;
        }
        boolean entering = !sFollowing;
        sFollowing = true;
        boolean sel = select || !sPathSelected;
        int music = musicLevel(am);
        int v = HbAudioRules.speakerVolume(true, music, sSent, force || entering || sel);
        if (v >= 0) send(app, v);
        if (sel) selectSpeakerPath(am, music);
        if (v < 0 && !sel) return;
        long now = SystemClock.uptimeMillis();
        if (entering || sel || force || now - sLastLogAt >= LOG_GAP_MS) {
            sLastLogAt = now;
            sUnlogged = 0;
            Ui.main().removeCallbacks(sTrailingLog);
            Log.i(TAG, "spk_volume=" + sSent + " (" + why + (sel ? ", speaker path selected" : "") + ")");
        } else {
            sUnlogged++;
            Ui.main().removeCallbacks(sTrailingLog);
            Ui.main().postDelayed(sTrailingLog, LOG_GAP_MS);
        }
    }

    /** STREAM_MUSIC scaled to 0..100, 0 while muted. */
    private static int musicLevel(AudioManager am) {
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int v = am.isStreamMute(AudioManager.STREAM_MUSIC) ? 0
                : Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
        return Math.max(0, Math.min(100, v));
    }

    /** switch_start then switch_end of the speaker path: the HAL applies the stored spk_volume from now on. */
    private static void selectSpeakerPath(AudioManager am, int v) {
        if (sSent != v) return;                          // spk_volume did not reach the HAL: never end without it
        try {
            am.setParameters("xgimi_audiopath_switch_start=" + PATH_SPEAKER);
            am.setParameters("xgimi_audiopath_switch_end=" + PATH_SPEAKER);
            sPathSelected = true;
        } catch (Throwable t) {
            Log.w(TAG, "speaker path: " + t);
        }
    }

    /**
     * Lumen OS 1.0.6: the media output leaves Bluetooth (AudioPathReporter had selected the HAL's A2DP sink with
     * "xgimi_audiopath_switch_end=3"), or (HB fork) an ARC -> speaker switch ends: select the speaker sink again.
     * switch_end restores the speaker level from the stored spk_volume, which is 0 from adev_open, so it is stored
     * first. HB fork: always the music level, never 100 (HbAudioRules.speakerSinkStore). Main thread.
     */
    public static void selectSpeakerSink(Context ctx, String why) {
        AudioManager am = ctx.getApplicationContext().getSystemService(AudioManager.class);
        if (am == null) return;
        int store = HbAudioRules.speakerSinkStore(sSent, musicLevel(am));
        if (store >= 0) send(ctx.getApplicationContext(), store);
        if (sSent < 0) {
            Log.w(TAG, "speaker sink not selected (" + why + "): spk_volume not stored");
            return;
        }
        try {
            am.setParameters("xgimi_audiopath_switch_end=" + PATH_SPEAKER);
            sPathSelected = true;
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
