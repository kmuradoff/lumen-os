package org.z9x.projector.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Lumen OS 1.0.6: media never goes to SPDIF, so the volume keys reach a Bluetooth speaker.
 *
 * The vendor audio policy lists "Spdif" as an attached device (an "xgimi patch" in attachedDevices; the Z9X has no
 * optical jack), and AOSP's engine duplicates media to an available SPDIF / ARC. With a Bluetooth speaker the media
 * route was {bt_a2dp, spdif}, and AudioService.selectOneAudioDevice prefers SPDIF over A2DP for the stream volume:
 * the remote's keys changed the index of a jack that does not exist, never the A2DP one, so absolute volume (AVRCP)
 * never moved the speaker and the speaker's own volume never moved the projector's. Marking SPDIF as non-default for
 * the media strategy (AudioManager.setDeviceAsNonDefaultForStrategy, DEVICE_ROLE_DISABLED, MODIFY_AUDIO_ROUTING) leaves
 * {bt_a2dp}: tested 2026-10-24 on the owner's Z9X with a Yandex Station Street, volume follows both ways.
 * No vendor file changes. Applied at process start and boot completed (AudioService keeps it until a reboot).
 */
final class SpdifOff {
    private static final String TAG = "Z9xSpdifOff";

    private SpdifOff() {}

    static void apply(Context ctx, String why) {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (am == null) return;
            AudioAttributes media = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
            Class<?> ps = Class.forName("android.media.audiopolicy.AudioProductStrategy");
            Object strategy = ps.getMethod("getAudioProductStrategyWithAudioAttributes", AudioAttributes.class)
                    .invoke(null, media);
            if (strategy == null) {
                Log.w(TAG, "no product strategy for media (" + why + ")");
                return;
            }
            AudioDeviceAttributes spdif = new AudioDeviceAttributes(AudioDeviceAttributes.ROLE_OUTPUT,
                    AudioDeviceInfo.TYPE_LINE_DIGITAL, "");
            Method get = AudioManager.class.getMethod("getNonDefaultDevicesForStrategy", ps);
            @SuppressWarnings("unchecked")
            List<AudioDeviceAttributes> already = (List<AudioDeviceAttributes>) get.invoke(am, strategy);
            if (already != null) {
                for (AudioDeviceAttributes d : already) {
                    if (d.getType() == AudioDeviceInfo.TYPE_LINE_DIGITAL) return;
                }
            }
            Object ok = AudioManager.class.getMethod("setDeviceAsNonDefaultForStrategy", ps, AudioDeviceAttributes.class)
                    .invoke(am, strategy, spdif);
            Log.i(TAG, "SPDIF is not a media output any more (" + why + "): " + ok);
        } catch (Throwable t) {
            Log.w(TAG, "SPDIF off (" + why + "): " + t);
        }
    }
}
