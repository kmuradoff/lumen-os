package org.z9x.projector.audio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

/**
 * Z9X audio fix that replaces the v5 init script (unmute_system_sounds.sh, exec_background in
 * the su domain).
 *
 * Facts (LineageOS 21 AudioService):
 *  - UI clicks are dropped while STREAM_SYSTEM is muted: playSoundEffectVolume() returns early
 *    when isStreamMute(STREAM_SYSTEM). The volume index does not matter on the speaker (the
 *    vendor "system" group uses FULL_SCALE_VOLUME_CURVE).
 *  - On the TV build every stream is aliased to STREAM_MUSIC. A stream's mute flag is OR-ed into
 *    its volume group's mute and the group pushes its mute back to the stream
 *    (updateVolumeGroupIndex / VolumeGroupState.applyAllVolumes), so a mute caused by a
 *    zero index on any device latches until an explicit unmute.
 *  - Stream mute is never persisted across a reboot (VolumeStreamState.mIsMuted starts false and
 *    readSettings() reads no mute), so any mute present right after boot is spurious.
 *
 * Feature gate: nothing is ever unmuted unless sys.z9x.feat == "1" (z9x_features.rc), so a
 * kill-switch boot is v4-identical for audio too.
 *
 * Arming (lamp research: the v5 unmute was cleared only because it ran after boot_completed):
 * no check runs before BOOT_COMPLETED. After BOOT_COMPLETED the fixer waits for
 * vendor.xgimi.ledOn=true (or 30 s), and only then arms both rules. A process that starts more
 * than 10 min after boot (restart of the persistent app; BOOT_COMPLETED is not re-delivered)
 * arms rule 2 only, at once.
 *
 * Rules (once armed):
 *  1) Boot window (arming + 60 s): if STREAM_MUSIC or STREAM_SYSTEM is muted, unmute.
 *  2) Any time: if STREAM_SYSTEM is muted while STREAM_MUSIC is not, unmute. A user mute (mute
 *     key, or volume set to 0) always mutes MUSIC together with SYSTEM, so it is never undone.
 * The unmute is adjustStreamVolume(STREAM_SYSTEM, ADJUST_UNMUTE, 0): AudioService turns it into
 * muteAliasStreams(STREAM_MUSIC, false), which clears the mute of every stream aliased to music
 * and of their volume groups and changes no volume index. flags = 0: no volume UI.
 *
 * Public SDK API plus SystemProperties.get (read-only). Wiring (persistent, platform-signed app):
 *   Application.onCreate():            Z9xAudioFixer.install(this);
 *   BOOT_COMPLETED receiver onReceive: Z9xAudioFixer.onBootCompleted(context);
 * Manifest:
 *   <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
 *   <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>
 */
public final class Z9xAudioFixer {
    private static final String TAG = "Z9xAudio";

    /** AudioManager.STREAM_MUTE_CHANGED_ACTION (@hide, protected broadcast, registered receivers only). */
    static final String ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";

    private static final long BOOT_WINDOW_AFTER_ARM_MS = 60_000L;
    private static final long[] BOOT_RECHECK_DELAYS_MS = {0L, 15_000L, 60_000L};
    private static final long LAMP_POLL_MS = 1_000L;
    private static final long LAMP_WAIT_MAX_MS = 30_000L;
    private static final long LATE_START_UPTIME_MS = 10 * 60_000L;
    private static final int MAX_HEALS_PER_WINDOW = 5;
    private static final long HEAL_WINDOW_MS = 60_000L;

    private static Handler sHandler;
    private static boolean sInstalled;
    /** False until BOOT_COMPLETED + lamp (or a late process start): heal() does nothing before. */
    private static boolean sArmed;
    private static boolean sArming;
    /** elapsedRealtime until which rule 1 applies; 0 = rule 1 off. */
    private static long sBootWindowEnd = 0L;
    private static long sRateWindowStart;
    private static int sHealsInWindow;

    private Z9xAudioFixer() {}

    /** Call once from Application.onCreate() of the persistent app. Idempotent. */
    public static synchronized void install(Context context) {
        if (sInstalled) return;
        sInstalled = true;
        final Context app = context.getApplicationContext();
        // A persistent app restarted long after boot: rule 2 only (rule 1 window stays 0).
        if (SystemClock.elapsedRealtime() > LATE_START_UPTIME_MS
                && "1".equals(SystemProperties.get("sys.boot_completed"))) {
            sArmed = true;
            Log.i(TAG, "late process start: rule 2 armed");
        }
        HandlerThread t = new HandlerThread("z9x-audio");
        t.start();
        sHandler = new Handler(t.getLooper());
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                // The handler re-reads the real state; a spoofed intent only causes a re-check.
                // A mute change is usually the user's MUTE key: only rule 2 applies here, the boot
                // window rule runs from the scheduled re-checks only.
                safeHeal(app, false);
            }
        };
        // Delivered on the worker thread. EXPORTED because the sender is system_server.
        app.registerReceiver(r, new IntentFilter(ACTION_STREAM_MUTE_CHANGED), null, sHandler,
                Context.RECEIVER_EXPORTED);
        if (sArmed) post(app, 0L);
        // No immediate check otherwise: nothing runs before BOOT_COMPLETED and the lamp.
    }

    /** Call from the app's BOOT_COMPLETED receiver. Starts the lamp wait, then arms the rules. */
    public static void onBootCompleted(Context context) {
        install(context);
        final Context app = context.getApplicationContext();
        synchronized (Z9xAudioFixer.class) {
            if (sArmed || sArming) return;                 // once per process
            sArming = true;
        }
        final long start = SystemClock.elapsedRealtime();
        Handler h = sHandler;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                boolean led = "true".equals(prop("vendor.xgimi.ledOn"));
                long waited = SystemClock.elapsedRealtime() - start;
                if (!led && waited < LAMP_WAIT_MAX_MS) {
                    sHandler.postDelayed(this, LAMP_POLL_MS);
                    return;
                }
                synchronized (Z9xAudioFixer.class) {
                    sArmed = true;
                    sBootWindowEnd = SystemClock.elapsedRealtime() + BOOT_WINDOW_AFTER_ARM_MS;
                }
                Log.i(TAG, "armed after BOOT_COMPLETED: ledOn=" + led + " waited=" + waited + " ms");
                for (long d : BOOT_RECHECK_DELAYS_MS) post(app, d);
            }
        });
    }

    private static String prop(String k) {
        try {
            return SystemProperties.get(k);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void post(final Context app, long delayMs) {
        Handler h = sHandler;
        if (h == null) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                safeHeal(app, true);
            }
        }, delayMs);
    }

    /** heal() that never throws: this runs inside the persistent app process. */
    static void safeHeal(Context app, boolean scheduled) {
        try {
            heal(app, scheduled);
        } catch (Throwable t) {
            Log.w(TAG, "heal: " + t);
        }
    }

    /** Returns true if an unmute was sent. Runs on the worker thread. */
    static synchronized boolean heal(Context app, boolean scheduled) {
        if (!sArmed) return false;                         // before BOOT_COMPLETED + lamp
        if (!"1".equals(prop("sys.z9x.feat"))) return false;  // kill-switch boot: v4-identical audio
        AudioManager am = app.getSystemService(AudioManager.class);
        if (am == null) return false;
        boolean systemMuted = am.isStreamMute(AudioManager.STREAM_SYSTEM);
        boolean musicMuted = am.isStreamMute(AudioManager.STREAM_MUSIC);
        boolean inBootWindow = SystemClock.elapsedRealtime() < sBootWindowEnd;
        boolean rule1 = scheduled && inBootWindow && (systemMuted || musicMuted);
        boolean rule2 = systemMuted && !musicMuted;
        if (!rule1 && !rule2) return false;

        // With an HDMI ARC/eARC audio system attached, AudioService forwards mute adjustments to
        // it over CEC as a mute *toggle* (KEYCODE_VOLUME_MUTE). Never risk toggling a soundbar.
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_HDMI_ARC || type == AudioDeviceInfo.TYPE_HDMI_EARC) {
                Log.w(TAG, "mute found (system=" + systemMuted + ", music=" + musicMuted
                        + ") but HDMI ARC/eARC present: not touching it");
                return false;
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (now - sRateWindowStart > HEAL_WINDOW_MS) {
            sRateWindowStart = now;
            sHealsInWindow = 0;
        }
        if (++sHealsInWindow > MAX_HEALS_PER_WINDOW) {
            Log.w(TAG, "streams keep re-muting; giving up for this minute");
            return false;
        }
        Log.i(TAG, "unmuting: system=" + systemMuted + " music=" + musicMuted
                + " bootWindow=" + inBootWindow);
        am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0);
        return true;
    }
}
