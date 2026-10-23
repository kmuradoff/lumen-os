package org.z9x.projector.audio;

import android.content.Context;
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
 *    When media routes to A2DP we send switch_end=3 (after bluetooth_volume), when it leaves A2DP switch_end=0.
 */
public final class BtAudio {
    private static final String TAG = "P6BtAudio";
    private static final int FOR_MEDIA = 1;
    private static final int FORCE_NO_BT_A2DP = 10;
    private static final int[] A2DP_DEVICES = {0x80, 0x100, 0x200};
    private static final int NEUTRAL = 100;

    private BtAudio() { }

    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final AudioManager am = app.getSystemService(AudioManager.class);
        if (am == null) return;
        HandlerThread t = new HandlerThread("p6-btaudio");
        t.start();
        final Handler h = new Handler(t.getLooper());
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
                        return;
                    }
                }
            }
        }, h);
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
    /** p6-btaudio thread: the sink we selected with switch_end, -1 none this process. */
    private static int sSink = -1;

    /** Media routing changed: the HAL's sink follows it (A2DP or the speaker). p6-btaudio thread. */
    private static void sink(AudioManager am, java.util.List<android.media.AudioDeviceAttributes> devices) {
        boolean a2dp = false;
        if (devices != null) {
            for (android.media.AudioDeviceAttributes d : devices) {
                if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) a2dp = true;
            }
        }
        int want = a2dp ? PATH_A2DP : PATH_SPEAKER;
        if (want == sSink || (!a2dp && sSink < 0)) return;     // the speaker is the HAL's default: nothing to undo
        try {
            if (a2dp) volume(am, "A2DP sink");
            am.setParameters("xgimi_audiopath_switch_end=" + want);
            sSink = want;
            Log.i(TAG, "audio HAL sink: " + (a2dp ? "A2DP" : "speaker") + " (switch_end=" + want + ")");
        } catch (Throwable e) {
            Log.w(TAG, "switch_end=" + want + ": " + e);
        }
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
