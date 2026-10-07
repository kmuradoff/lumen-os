package org.z9x.projector.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.hal.GmpfClient;

import java.util.ArrayList;
import java.util.List;

/**
 * v6.3.1: reports the current media output to the vendor, as the stock audioserver did.
 *
 * Stock (libxgimiaudiopolicy.so, research/audio/dis/xap.ann): Egn_getDeviceForMedia calls
 * setXgimiPathForDeviceDelay() whenever the media device maps to another EN_XGIMI_AUDIO_PATH. That
 * sends at once AudioSystem.setParameters("xgimi_audiopath_switch_start=P"), IGmpf 59
 * setAudioOutput(100) and a digital_output_format parameter, and arms a 50 x 20 ms counter. The
 * thread threadInit waits for sys.boot_completed, then for the counter to run out (1 s without a
 * further change) and sends IGmpf 59 setAudioOutput(P), then setParameters("xgimi_audiopath_switch_end=P").
 * Device -> P (threadInit): SPEAKER and anything unlisted 0, WIRED_HEADSET/HEADPHONE 4, BT A2DP 3,
 * HDMI ARC 2, USB_DEVICE/USB_HEADSET 6 (SPDIF 1 and the XGIMI wireless speaker paths are not sent).
 * Our GSI's AOSP audioserver does none of this, so Msrv m_enCurrentPath stays 100: every effect is
 * forced to the AMP process, 27/44 only write the DB and DTS never applies (research/v63/ampeq).
 *
 * What we send (evidence, research/audio/dis/hal64.ann and v63/sound/amc2.ann):
 *  - 59 setAudioOutput(P) after BOOT_COMPLETED, the boot handshake (237(1), the vendor audio thread
 *    waits for it) and a 1 s debounce, and again on every change of the media output. Msrv
 *    SetOutputType(P) sets the path, SetSinkDevice, mutes/unmutes the amp per path, re-applies the
 *    effects from its DB, saves the path in its DB and sets vender.xgimi.audio.ready=1.
 *  - 59 setAudioOutput(100) right away when the output changes AWAY from a path we already
 *    reported (stock switch start): Msrv mutes every TV path and keeps the current path until 59(P).
 *    Not sent for the first report of a boot (the vendor path is already 100 then).
 *  - NOT the audio HAL parameters. In audio.primary.mt5877 "xgimi_audiopath_switch_start=P" sets the
 *    device's TV volume index to 0 and "xgimi_audiopath_switch_end=P" restores it from the volume
 *    stored by "spk_volume=" (adev+0x5fcc+0x9ec, zeroed at adev_open). Only the XGIMI AudioService
 *    sent spk_volume; our AOSP AudioService never does (v6 RESULT_audio: do not send it), so
 *    switch_end would restore index 0 = a silent speaker. Same for digital_output_format (no
 *    SPDIF/ARC passthrough setting on the GSI). Device-test before ever adding them.
 *  - Never 1 (SPDIF), never 59 on the main thread (Hal.run, "z9x-hal").
 *
 * Idempotent: before 59(P) the vendor state is read; when vender.xgimi.audio.ready == 1 and
 * 60 getAudioOutput == P nothing is sent (60 alone is not proof: it returns the DB value of the
 * last boot, or 0 while the vendor audio thread has not started). After 59(P) the same check runs
 * 1.5 s later; on failure (vendor still initialising, sleeping, HAL reconnecting) 59(P) is retried
 * with backoff. SCREEN_ON and a HAL reconnect re-check (Msrv GoToShutdown resets the path to 100).
 *
 * {@link #isActive} = path reporting is active this boot: the last P we sent is confirmed by both
 * reads. SoundProfiles shows DTS only then (27/44 apply only with path 0).
 *
 * Threads: device callbacks and the debounce on "z9x-audiopath"; HAL calls on "z9x-hal".
 */
public final class AudioPathReporter {
    private static final String TAG = "Z9xAudioPath";

    /** Set by Msrv SetOutputType on its first real path (reset to 0 by GoToShutdown). */
    static final String PROP_READY = "vender.xgimi.audio.ready";

    /** Stock threadInit: 50 x 20 ms after the last change. */
    private static final long DEBOUNCE_MS = 1_000L;
    private static final long VERIFY_DELAY_MS = 1_500L;
    private static final long WAIT_POLL_MS = 1_000L;
    private static final long WAIT_MAX_MS = 5 * 60_000L;
    private static final long SCREEN_ON_CHECK_MS = 3_000L;
    private static final long[] RETRY_MS = {2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 60_000L};

    private static final AudioAttributes MEDIA = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).build();

    private static Handler sH;
    private static Context sApp;
    private static boolean sInstalled;
    /** BOOT_COMPLETED seen (or a late process start after it). */
    private static volatile boolean sBooted;
    private static long sWaitStart;

    /** z9x-audiopath thread: the path to report (latest evaluation), -1 none yet. */
    private static int sTarget = -1;
    /** HAL thread writes, any thread reads: the path last sent with 59 (not 100), -1 none. */
    private static volatile int sSent = -1;
    /** HAL thread writes, any thread reads: the path confirmed by ready + 60 this process, -1 none. */
    private static volatile int sConfirmed = -1;
    private static int sRetry;
    /** z9x-audiopath thread: a 59(P) was sent and its verification is still pending. */
    private static boolean sVerifying;
    /** Debounce generation: a newer change cancels the pending commit / retries. */
    private static int sGen;
    /**
     * HAL thread: 59(100) was sent and no 59(P) since (Msrv muted every TV path but kept its path,
     * so 60 still reads the old path): the next commit must send 59(P) even when 60 == P.
     */
    private static volatile boolean sSwitching;
    /** HAL thread: the vendor HAL reconnected (gmpf restarted: path 100, but the ready prop stays 1). */
    private static volatile boolean sForce;
    /** HAL thread: number of HAL connects seen by this process (the first one is the boot connect). */
    private static int sConnects;

    private static final Runnable COMMIT = AudioPathReporter::commit;

    /**
     * v6.4: run on the "z9x-hal" thread after a 59(P) we sent was confirmed (Msrv SetOutputType ran:
     * it re-applies the DB sound effect, with flag 1 in AI mode, which turns the SoC PEQ off; and in AI
     * mode it re-resolves the sub-mode from xgimi.pkg.effect.mod). Listeners must not block.
     */
    public interface PathListener { void onPathConfirmed(int path); }

    private static final java.util.concurrent.CopyOnWriteArrayList<PathListener> PATH_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** v6.4: SoundEq / AiSoundEngine re-apply hooks. Any thread. */
    public static void addPathListener(PathListener l) { if (l != null) PATH_LISTENERS.addIfAbsent(l); }

    private AudioPathReporter() {}

    /** Application.onCreate. Idempotent, never throws. Registers the callbacks; no HAL call here. */
    public static synchronized void install(Context ctx) {
        if (sInstalled) return;
        try {
            sInstalled = true;
            sApp = ctx.getApplicationContext();
            HandlerThread t = new HandlerThread("z9x-audiopath");
            t.start();
            sH = new Handler(t.getLooper());
            AudioManager am = sApp.getSystemService(AudioManager.class);
            if (am != null) {
                am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { evaluate("devices added"); }
                    @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { evaluate("devices removed"); }
                }, sH);
                try {
                    am.addOnDevicesForAttributesChangedListener(MEDIA, sH::post,
                            (attrs, devices) -> evaluate("media routing " + devices));
                } catch (Throwable e) {
                    Log.w(TAG, "routing listener not available (device callback only): " + e);
                }
            }
            Hal.addConnectedListener(() -> {                // "z9x-hal" thread
                if (++sConnects > 1) {
                    sForce = true;                          // reconnect: the vendor lost its path
                    sConfirmed = -1;
                }
                Handler h = sH;
                if (h != null) h.post(() -> check(sConnects > 1 ? "HAL reconnected" : "HAL connected"));
            });
            if ("1".equals(prop("sys.boot_completed"))) {
                sBooted = true;                             // persistent process restarted after boot
                sH.post(() -> evaluate("process start"));
                final long now = SystemClock.elapsedRealtime();
                sH.post(() -> bootCheck(now));
            }
            Log.i(TAG, "installed");
        } catch (Throwable t) {
            Log.e(TAG, "install", t);
        }
    }

    /** BOOT_COMPLETED receiver. */
    public static void onBootCompleted(Context ctx) {
        install(ctx);
        sBooted = true;
        Handler h = sH;
        if (h == null) return;
        h.post(() -> evaluate("boot completed"));
        final long now = SystemClock.elapsedRealtime();
        h.post(() -> bootCheck(now));
    }

    /** SCREEN_ON (main thread): re-check after a wake (Msrv GoToShutdown resets the path to 100). */
    public static void onScreenOn() {
        Handler h = sH;
        if (h != null) h.postDelayed(() -> check("screen on"), SCREEN_ON_CHECK_MS);
    }

    /**
     * True when output-path reporting is active this boot: the path we sent last is confirmed
     * (vender.xgimi.audio.ready == 1 and 60 getAudioOutput == it). HAL thread (reads 60).
     */
    public static boolean isActive(GmpfClient g) {
        int p = sConfirmed;
        if (p < 0 || !"1".equals(prop(PROP_READY))) return false;
        try {
            return g.getAudioOutputRaw() == p;
        } catch (Throwable t) {
            Log.w(TAG, "60: " + t);
            return false;
        }
    }

    /** The path confirmed last (0 speaker, 2 ARC, 3 BT, 4 wired, 6 USB), or -1. Any thread. */
    public static int confirmedPath() { return sConfirmed; }

    // ------------------------------------------------------------------ z9x-audiopath thread
    /** Recomputes the media output; a change starts the stock switch sequence. */
    private static void evaluate(String why) {
        try {
            GmpfClient.AudioPath p = currentPath();
            if (p == null) {
                Log.i(TAG, why + ": no media output device known, nothing reported");
                return;
            }
            if (p.wire == sTarget) {
                // Same path: only make sure it is confirmed (e.g. the boot report gave up earlier).
                if (sConfirmed != p.wire && !sVerifying && !sH.hasCallbacks(COMMIT)) {
                    sRetry = 0;
                    schedule(0);
                }
                return;
            }
            int prev = sConfirmed >= 0 ? sConfirmed : sSent;
            Log.i(TAG, why + ": media output -> path " + p.wire + " (" + p + "), was " + sTarget);
            sTarget = p.wire;
            sGen++;
            sRetry = 0;
            sVerifying = false;
            if (prev >= 0 && prev != p.wire && readyForHal()) {
                // Stock switch start: 59(100) at once (Msrv mutes every TV path until 59(P)).
                final int gen = sGen;
                Hal.run((g, g2) -> {
                    if (gen != sGen) return;
                    sSwitching = true;                      // set first: a failed call may still have muted
                    sConfirmed = -1;
                    g.setAudioOutput(GmpfClient.AudioPath.SWITCHING);
                    Log.i(TAG, "59(100) switch start (from " + prev + ")");
                });
            }
            schedule(DEBOUNCE_MS);
        } catch (Throwable t) {
            Log.w(TAG, "evaluate: " + t);
        }
    }

    private static void schedule(long delayMs) {
        sH.removeCallbacks(COMMIT);
        sH.postDelayed(COMMIT, delayMs);
    }

    /** SCREEN_ON / HAL reconnect: verify the confirmed path, report again when the vendor lost it. */
    private static void check(String why) {
        if (sTarget < 0) {
            evaluate(why);
            return;
        }
        final int gen = sGen;
        final int target = sTarget;
        Hal.run((g, g2) -> {
            if (gen != sGen) return;
            if (vendorHas(g, target)) {
                sConfirmed = target;
                return;
            }
            Log.i(TAG, why + ": vendor path is not " + target + " any more, reporting again");
            sConfirmed = -1;
            sH.post(() -> {
                if (gen != sGen || sVerifying) return;
                sRetry = 0;
                schedule(0);
            });
        });
    }

    /** After the debounce: 59(P) on the HAL thread once boot + handshake are done. */
    private static void commit() {
        final int target = sTarget;
        if (target < 0) return;
        if (!readyForHal()) {
            long now = SystemClock.elapsedRealtime();
            if (sWaitStart == 0) sWaitStart = now;
            if (now - sWaitStart < WAIT_MAX_MS) {
                sH.postDelayed(COMMIT, WAIT_POLL_MS);
            } else {
                Log.w(TAG, "boot handshake not done after " + WAIT_MAX_MS + " ms: path " + target + " not reported");
                sWaitStart = 0;
            }
            return;
        }
        sWaitStart = 0;
        final int gen = sGen;
        sVerifying = true;
        boolean queued = Hal.runDelayed((g, g2) -> {
            if (gen != sGen) return;
            // v6.3.1 boot safety (SoundProfiles): a valid amp table in the DB before the first report,
            // because 59(0) re-applies the DB sound mode.
            org.z9x.projector.panel.SoundProfiles.bootCheckOnce(sApp, g);
            if (vendorHas(g, target)) {
                sSent = target;
                sConfirmed = target;
                Log.i(TAG, "path " + target + " already set in the vendor (ready=1, 60=" + target + "): not sent");
                sH.post(() -> { if (gen == sGen) sVerifying = false; });
                return;
            }
            try {
                g.setAudioOutput(pathOf(target));
            } catch (Throwable e) {
                sH.post(() -> retry(gen, target, "59: " + e));
                return;
            }
            sSent = target;
            sSwitching = false;
            sForce = false;
            Log.i(TAG, "59(" + target + ") sent");
            sH.postDelayed(() -> verify(gen, target), VERIFY_DELAY_MS);
        }, 0, () -> sH.post(() -> retry(gen, target, "HAL not connected")));
        if (!queued) retry(gen, target, "HAL thread not available");
    }

    /** 1.5 s after 59(P): confirmed, or retried with backoff. */
    private static void verify(final int gen, final int target) {
        if (gen != sGen) return;
        boolean queued = Hal.runDelayed((g, g2) -> {
            if (gen != sGen) return;
            if (vendorHas(g, target)) {
                sConfirmed = target;
                Log.i(TAG, "path " + target + " confirmed (ready=1, 60=" + target + ")");
                sH.post(() -> { if (gen == sGen) sVerifying = false; });
                for (PathListener l : PATH_LISTENERS) {
                    try { l.onPathConfirmed(target); } catch (Throwable t) { Log.w(TAG, "path listener: " + t); }
                }
            } else {
                sH.post(() -> retry(gen, target, "not confirmed"));
            }
        }, 0, () -> sH.post(() -> retry(gen, target, "HAL not connected")));
        if (!queued) retry(gen, target, "HAL thread not available");
    }

    private static boolean readyForHal() {
        return sBooted && "1".equals(prop("sys.boot_completed")) && Hal.isHandshakeDone();
    }

    /** z9x-audiopath thread: the one-time / per-boot sound safety check, also without any output path. */
    private static void bootCheck(final long started) {
        if (!readyForHal()) {
            if (SystemClock.elapsedRealtime() - started < WAIT_MAX_MS) {
                sH.postDelayed(() -> bootCheck(started), WAIT_POLL_MS);
            }
            return;
        }
        Hal.runDelayed((g, g2) -> org.z9x.projector.panel.SoundProfiles.bootCheckOnce(sApp, g), 0,
                () -> sH.postDelayed(() -> bootCheck(started), WAIT_POLL_MS));
    }

    private static void retry(int gen, int target, String why) {
        if (gen != sGen) return;
        sVerifying = false;
        if (sRetry >= RETRY_MS.length) {
            Log.w(TAG, "path " + target + ": " + why + ", giving up until the next change / screen on");
            return;
        }
        long d = RETRY_MS[sRetry++];
        Log.i(TAG, "path " + target + ": " + why + ", retry " + sRetry + " in " + d + " ms");
        schedule(d);
    }

    // ------------------------------------------------------------------ helpers
    /**
     * HAL thread: the vendor holds {@code p} as its current path (both reads agree, no switch start
     * pending, no vendor restart since our last 59).
     */
    private static boolean vendorHas(GmpfClient g, int p) {
        if (sSwitching || sForce || !"1".equals(prop(PROP_READY))) return false;
        try {
            return g.getAudioOutputRaw() == p;
        } catch (Throwable t) {
            Log.w(TAG, "60: " + t);
            return false;
        }
    }

    private static GmpfClient.AudioPath pathOf(int wire) {
        for (GmpfClient.AudioPath p : GmpfClient.AudioPath.values()) if (p.wire == wire) return p;
        throw new IllegalArgumentException("path " + wire);
    }

    /**
     * The path of the current media output, or null when unknown. AudioManager.getDevicesForAttributes
     * (QUERY_AUDIO_STATE, platform signature) is what the policy routes media to; without it, the
     * connected outputs in AOSP media priority order.
     */
    private static GmpfClient.AudioPath currentPath() {
        AudioManager am = sApp == null ? null : sApp.getSystemService(AudioManager.class);
        if (am == null) return null;
        List<Integer> types = new ArrayList<>();
        try {
            for (AudioDeviceAttributes a : am.getDevicesForAttributes(MEDIA)) types.add(a.getType());
        } catch (Throwable t) {
            Log.w(TAG, "getDevicesForAttributes: " + t);
            types.clear();
        }
        if (!types.isEmpty()) {
            GmpfClient.AudioPath p = fromTypes(types);
            if (p != null) return p;
        }
        // Fallback: connected outputs, AOSP media priority (BT > wired > USB > ARC > speaker).
        types.clear();
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) types.add(d.getType());
        } catch (Throwable t) {
            Log.w(TAG, "getDevices: " + t);
            return null;
        }
        GmpfClient.AudioPath[] order = {GmpfClient.AudioPath.BT_A2DP, GmpfClient.AudioPath.WIRED,
                GmpfClient.AudioPath.USB, GmpfClient.AudioPath.ARC};
        for (GmpfClient.AudioPath want : order) {
            for (int t : types) if (map(t) == want) return want;
        }
        return types.isEmpty() ? null : GmpfClient.AudioPath.SPEAKER;
    }

    /** First routed device that maps to a path (SPDIF is skipped: never 1). */
    private static GmpfClient.AudioPath fromTypes(List<Integer> types) {
        GmpfClient.AudioPath speaker = null;
        for (int t : types) {
            GmpfClient.AudioPath p = map(t);
            if (p == null) continue;
            if (p != GmpfClient.AudioPath.SPEAKER) return p;
            speaker = p;
        }
        return speaker;
    }

    /** AudioDeviceInfo type -> path (stock threadInit table); null = skip (SPDIF). */
    static GmpfClient.AudioPath map(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return GmpfClient.AudioPath.WIRED;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return GmpfClient.AudioPath.BT_A2DP;
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_HDMI_EARC:
                return GmpfClient.AudioPath.ARC;
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return GmpfClient.AudioPath.USB;
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:            // SPDIF: path 1 is never sent
                return null;
            default:                                           // speaker and everything unlisted: 0 (stock)
                return GmpfClient.AudioPath.SPEAKER;
        }
    }

    private static String prop(String k) {
        try {
            return SystemProperties.get(k, "");
        } catch (Throwable t) {
            return "";
        }
    }
}
