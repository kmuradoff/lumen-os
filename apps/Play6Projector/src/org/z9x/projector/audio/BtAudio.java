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
