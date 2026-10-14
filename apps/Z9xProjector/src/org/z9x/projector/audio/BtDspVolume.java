package org.z9x.projector.audio;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

/**
 * Lumen OS 1.0.4: Bluetooth headphones on the Z9X (no policy, vendor or blob change). NOT device-tested yet.
 *
 * audio.primary.mt5877 plays A2DP itself: adev_open loads hw_get_module_by_class("audio",
 * "bluetooth") = /vendor/lib64/hw/audio.bluetooth.default.so; on "connect=128" it opens a
 * loopback_out stream on it (utils_a2dp_open). The A2DP DSP volume is a per-device table
 * (adev+0x6394, bt_id 0..6, calloc'd = 0) written only by
 * "bluetooth_volume=N;device=D" (adev_set_parameters -> utils_bt_vol_put, and when a BT sink is
 * active also utils_set_tv_volume_index(0x80, N)). loopback_thread "reset sink device 0x%x volume
 * to %d" reads that table, so with nothing sent the headphones path sits at index 0 (silent).
 * Stock services.jar VolumeStreamState.setDspVolumesToHal sent it ("bluetooth_volume=" + index
 * + ";device=" + device) and then put AudioFlinger's music index at max. Lumen's AOSP AudioService
 * does neither, so we send the neutral 100 (0 dB on DEFAULT_MEDIA curve) and leave the volume to
 * Android (AudioFlinger software volume, or AVRCP absolute volume in the headphones).
 *
 * AudioPathReporter calls apply() at boot completed / process start and whenever it sees the media output
 * as Bluetooth A2DP (also covers a vendor.audio-hal restart, which zeroes the table). The HAL applies the
 * value at once while a BT sink is active. Thread "z9x-audiopath". Needs MODIFY_AUDIO_SETTINGS (held).
 */
final class BtDspVolume {
    private static final String TAG = "Z9xBtVolume";
    /** AUDIO_DEVICE_OUT_BLUETOOTH_A2DP, _A2DP_HEADPHONES, _A2DP_SPEAKER (bt_id 0, 1, 2). */
    private static final int[] A2DP_DEVICES = {0x80, 0x100, 0x200};
    private static final int NEUTRAL = 100;

    private BtDspVolume() {}

    static void apply(Context context, String why) {
        AudioManager am = context.getSystemService(AudioManager.class);
        if (am == null) return;
        for (int device : A2DP_DEVICES) {
            // One device per call: the HAL reads a single "device" key (str_parms).
            String kv = "bluetooth_volume=" + NEUTRAL + ";device=" + device;
            try {
                am.setParameters(kv);
            } catch (Throwable t) {
                Log.w(TAG, kv + ": " + t);
                return;
            }
        }
        Log.i(TAG, "bluetooth_volume=" + NEUTRAL + " for A2DP (" + why + ")");
    }
}
