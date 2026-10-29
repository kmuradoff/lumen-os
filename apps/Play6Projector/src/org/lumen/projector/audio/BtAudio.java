package org.lumen.projector.audio;

import android.content.Context;

import org.lumen.projector.Hal;
import org.lumen.projector.hal.Play6Gmpf;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Play 6, Lumen 1.0.5: Bluetooth headphones get the sound (tester bug on play6-beta1: they connect, their keys
 * work, the sound stays on the speaker). NOT device-tested yet; every step is logged under P6BtAudio.
 *
 * 1. Route. On t10 the media force-use was FORCE_NO_BT_A2DP (10): AudioService set it with setBluetoothA2dpOn(false)
 *    when a phantom wired headset (h2w) connected, and Android 14 then keeps media on the speaker even with A2DP up.
 *    When an A2DP sink appears and the force-use is still 10, we ask AudioService itself to turn it back
 *    (IAudioService.setBluetoothA2dpOn(true), MODIFY_AUDIO_SETTINGS), so its own state stays consistent.
 * 2. Volume. The vendor primary HAL (audio.primary.mt5889, same design as the Z9X's mt5877) plays A2DP itself and
 *    keeps a per-device A2DP DSP volume table that starts at 0; stock's patched AudioService filled it with
 *    "bluetooth_volume=N;device=D". We send the neutral 100 for the three A2DP device types (Z9X BtDspVolume),
 *    at start and every time an A2DP sink appears (also covers an audio HAL restart).
 * 3. Sink (Lumen 1.0.6, the Z9X fix of 2026-10-23, AudioPathReporter.halSink). The HAL sends A2DP itself: a
 *    loopback thread copies the DSP output to the Bluetooth stream, and only while its sink device is A2DP
 *    ("doesn't set acap volume! current sink device is not a2dp device"). Only
 *    setParameters("xgimi_audiopath_switch_end=P") sets that sink (adev_set_parameters 0x21c96: sink = the
 *    Android device of path P, P 3 = A2DP, 0 = speaker; leaving A2DP suspends it; no volume change, unlike the
 *    Z9X). Stock's audio policy sent it; Lumen never did, so a connected speaker stayed silent on the Z9X.
 *    When media routes to A2DP we send switch_end=3 and then bluetooth_volume (the HAL drops a volume sent while its
 *    sink is not A2DP), when it leaves A2DP switch_end=0.
 */
public final class BtAudio {
    private static final String TAG = "P6BtAudio";
    private static final int FOR_MEDIA = 1;
    private static final int FORCE_NO_BT_A2DP = 10;
    private static final int[] A2DP_DEVICES = {0x80, 0x100, 0x200};
    private static final int NEUTRAL = 100;
    /** After an A2DP sink appears: AudioService has routed media by then. */
    private static final long RESYNC_MS = 800;
    /** Stock's switch: 59(100), then the path once the routing settled (the Z9X waits 300 ms). */
    private static final long SWITCH_MS = 300;
    private static final long[] BOOT_RESEND_MS = {12_000L, 30_000L};
    private static final long RESEND_MS = 3_000L;
    private static Context sApp;

    private BtAudio() { }

    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final AudioManager am = app.getSystemService(AudioManager.class);
        if (am == null) return;
        sApp = app;
        HandlerThread t = new HandlerThread("p6-btaudio");
        t.start();
        final Handler h = new Handler(t.getLooper());
        sH = h;
        h.post(() -> volume(am, "start"));
        try {
            am.addOnDevicesForAttributesChangedListener(MEDIA, h::post, (attrs, devices) -> sink(am, devices));
            h.post(() -> sink(am, am.getDevicesForAttributes(MEDIA)));
        } catch (Throwable e) {
            Log.w(TAG, "routing listener: " + e);
        }
        am.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                for (AudioDeviceInfo d : added) {
                    if (d.isSink() && d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                        h.post(() -> { route(); volume(am, "A2DP sink " + d.getProductName()); });
                        // a speaker that comes back: the HAL dropped its A2DP sink when it left, while the media
                        // route may read [A2DP] before and after (no routing callback), so the sink is sent again
                        // (owner 2026-10-24: Station connected, the sound on the projector speaker at full volume)
                        h.postDelayed(() -> resync(am, "A2DP sink added"), RESYNC_MS);
                        return;
                    }
                }
            }
        }, h);
        // The vendor audio manager ignores 59 until its init thread ends, a few seconds after boot ("Is sleeping/init,
        // return! ... isInitThreadFinish:0", device log 2026-10-24), and Msrv GoToShutdown resets the path to 100 on
        // sleep: the path is sent again after boot, after the screen comes on and after a HAL reconnect.
        for (long d : BOOT_RESEND_MS) h.postDelayed(() -> resend(am, "after boot"), d);
        Hal.addConnectedListener(() -> h.postDelayed(() -> resend(am, "HAL connected"), RESEND_MS));
        try {
            app.registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, android.content.Intent i) {
                    h.postDelayed(() -> resend(am, "screen on"), RESEND_MS);
                }
            }, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON), null, h);
        } catch (Throwable e) {
            Log.w(TAG, "screen-on receiver: " + e);
        }
        Log.i(TAG, "armed: A2DP sinks get the media route and bluetooth_volume=" + NEUTRAL);
    }

    private static void volume(AudioManager am, String why) {
        for (int device : A2DP_DEVICES) {
            String kv = "bluetooth_volume=" + NEUTRAL + ";device=" + device;   // one device per call (str_parms)
            try {
                am.setParameters(kv);
            } catch (Throwable e) {
                Log.w(TAG, kv + ": " + e);
                return;
            }
        }
        Log.i(TAG, "bluetooth_volume=" + NEUTRAL + " for A2DP (" + why + ")");
    }

    private static final android.media.AudioAttributes MEDIA = new android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build();
    private static final int PATH_SPEAKER = 0;
    private static final int PATH_A2DP = 3;
    /** p6-btaudio thread: the sink we selected with switch_end, -1 none this process, -2 send again (resync). */
    private static int sSink = -1;

    /** p6-btaudio thread: the vendor path we reported with 59 (-1: none this process, the boot state 100 stays). */
    private static int sPath = -1;
    private static Handler sH;

    /** The vendor path (59) of the media route: the Z9X AudioPathReporter / stock threadInit mapping. */
    private static int pathOf(java.util.List<android.media.AudioDeviceAttributes> devices) {
        if (devices == null) return Play6Gmpf.AUDIO_PATH_SPEAKER;
        for (android.media.AudioDeviceAttributes d : devices) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return Play6Gmpf.AUDIO_PATH_A2DP;
                case AudioDeviceInfo.TYPE_HDMI_ARC:
                case AudioDeviceInfo.TYPE_HDMI_EARC: return Play6Gmpf.AUDIO_PATH_ARC;
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET: return Play6Gmpf.AUDIO_PATH_WIRED;
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_HEADSET: return Play6Gmpf.AUDIO_PATH_USB;
                default: break;
            }
        }
        return Play6Gmpf.AUDIO_PATH_SPEAKER;
    }

    /**
     * Media routing changed: the vendor follows it, as stock's audio policy did (Lumen 1.0.6, device test 2026-10-24:
     * with only the HAL sink switched the Play 6 speaker kept playing at full level next to the Bluetooth speaker).
     *  1. IGmpf 59 setAudioOutput: 100 first when the path changes (stock switch start: Msrv mutes every TV path), 300 ms,
     *     then the path (Msrv SetOutputType: mutes the amp for A2DP / ARC / headphones / USB, unmutes it for the speaker).
     *     Nothing is sent while the route stays on the speaker since boot (the vendor keeps its boot state, as in 1.0.5).
     *  2. The audio HAL sink: xgimi_audiopath_switch_end=3 for A2DP, then bluetooth_volume (the HAL drops a volume sent
     *     while its sink is not A2DP); leaving A2DP switch_end=0.
     * p6-btaudio thread.
     */
    private static void sink(AudioManager am, java.util.List<android.media.AudioDeviceAttributes> devices) {
        final int want = pathOf(devices);
        final boolean a2dp = want == Play6Gmpf.AUDIO_PATH_A2DP;
        if (want == sPath) return;
        if (sPath < 0 && sSink == -1 && want == Play6Gmpf.AUDIO_PATH_SPEAKER) return;   // boot state, nothing to undo
        final int from = sPath;
        sPath = want;
        Log.i(TAG, "media path " + from + " -> " + want);
        Hal.run((g, g2) -> {
            try {
                g.setAudioOutput(Play6Gmpf.AUDIO_PATH_SWITCHING);                  // 59(100)
                android.os.SystemClock.sleep(SWITCH_MS);
                g.setAudioOutput(want);                                             // 59(P)
                Log.i(TAG, "59(" + want + ") sent, 60 reads " + g.getAudioOutput());
            } catch (Throwable t) {
                Log.w(TAG, "59(" + want + "): " + t);
            }
            Handler h = sH;
            if (h != null) h.post(() -> halSink(am, a2dp));
        });
    }

    /** p6-btaudio thread, after 59: the HAL sink (switch_end) and the A2DP DSP volume. */
    private static void halSink(AudioManager am, boolean a2dp) {
        int want = a2dp ? PATH_A2DP : PATH_SPEAKER;
        if (!a2dp && sSink != PATH_A2DP && sSink != -2) { sSink = want; return; }   // never on A2DP: nothing to undo
        try {
            am.setParameters("xgimi_audiopath_switch_end=" + want);
            // after the switch: the mt5889 HAL takes bluetooth_volume only while its sink is A2DP ("doesn't set acap
            // volume! current sink device is not a2dp device") and, unlike the Z9X's, does not restore it on the
            // switch, so a volume sent first left the A2DP DSP at 0 (device log 2026-10-24)
            if (a2dp) volume(am, "A2DP sink, after the switch");
            sSink = want;
            Log.i(TAG, "audio HAL sink: " + (a2dp ? "A2DP" : "speaker") + " (switch_end=" + want + ")");
        } catch (Throwable e) {
            Log.w(TAG, "switch_end=" + want + ": " + e);
        }
    }

    // Lumen 1.0.6: no codec preference from here any more. Asking SBC for 48 kHz made the Yandex Station renegotiate
    // and close the stream; the Play 6 base feeds SBC at 48 kHz and resamples to 44.1 kHz itself (lineage patch
    // 0005), so the speaker keeps its own choice.

    /** p6-btaudio thread: the path again when it is not the speaker (the vendor's own state then). */
    private static void resend(AudioManager am, String why) {
        int want = pathOf(am.getDevicesForAttributes(MEDIA));
        if (want == Play6Gmpf.AUDIO_PATH_SPEAKER && sPath < 0) return;
        resync(am, why);
    }

    /** p6-btaudio thread: forget what was sent and send the sink of the current media route again. */
    private static void resync(AudioManager am, String why) {
        Log.i(TAG, "sink again (" + why + ")");
        sSink = -2;                                            // neither path: the next sink() always sends
        sPath = -2;
        sink(am, am.getDevicesForAttributes(MEDIA));
    }

    /** A2DP sink up but media still forced off Bluetooth: ask AudioService to allow A2DP again. */
    private static void route() {
        try {
            Class<?> as = Class.forName("android.media.AudioSystem");
            int force = (int) as.getMethod("getForceUse", int.class).invoke(null, FOR_MEDIA);
            if (force != FORCE_NO_BT_A2DP) {
                Log.i(TAG, "media force-use " + force + ": A2DP allowed, nothing to do");
                return;
            }
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, Context.AUDIO_SERVICE);
            Class<?> stub = Class.forName("android.media.IAudioService$Stub");
            Object svc = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
            Method m = svc.getClass().getMethod("setBluetoothA2dpOn", boolean.class);
            m.invoke(svc, true);
            int now = (int) as.getMethod("getForceUse", int.class).invoke(null, FOR_MEDIA);
            Log.i(TAG, "media force-use was FORCE_NO_BT_A2DP (a phantom wired headset?): A2DP turned back on, now " + now);
        } catch (Throwable e) {
            Log.w(TAG, "route: " + e);
        }
    }
}
