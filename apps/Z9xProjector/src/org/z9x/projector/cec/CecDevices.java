package org.z9x.projector.cec;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MODULE "cec" (cec spec 3.2 CecDevices): a snapshot of the HDMI-CEC devices system_server knows
 * (HdmiControlManager.getConnectedDevices, @SystemApi, HDMI_CEC) for the panel's device list and the
 * soundbar wake. No polling of our own: system_server's HotplugDetectionAction already polls the bus;
 * the list is read when the panel page is shown and when a wake needs it. Worker thread only.
 */
final class CecDevices {
    private static final String TAG = "Z9xCec";

    /** One device as the panel shows it. */
    static final class Dev {
        final int id, la, port, type, power;
        final String name;
        /** org.z9x.tvinput's CEC child input for this device (null: only the port input exists). */
        String inputId;

        Dev(HdmiDeviceInfo d) {
            id = d.getId();
            la = d.getLogicalAddress();
            port = d.getPortId();
            type = d.getDeviceType();
            power = d.getDevicePowerStatus();
            String n = d.getDisplayName();
            name = TextUtils.isEmpty(n) ? "" : n.trim();
        }

        boolean isSource() {
            return type == HdmiDeviceInfo.DEVICE_PLAYBACK || type == HdmiDeviceInfo.DEVICE_TUNER
                    || type == HdmiDeviceInfo.DEVICE_RECORDER;
        }

        boolean isAudioSystem() {
            return type == HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM;
        }
    }

    private CecDevices() {}

    /** Every CEC device behind our HDMI ports (no TV, no unregistered), sources first. */
    static List<Dev> read(Context c) {
        List<Dev> out = new ArrayList<>();
        try {
            HdmiControlManager m = CecSettings.hm(c);
            List<HdmiDeviceInfo> list = m == null ? null : m.getConnectedDevices();
            if (list == null) return out;
            for (HdmiDeviceInfo d : list) {
                if (d == null || !d.isCecDevice()) continue;
                if (d.getLogicalAddress() == 0 || d.getLogicalAddress() == 15 || d.getPortId() <= 0) continue;
                out.add(new Dev(d));
            }
        } catch (Throwable t) {
            Log.w(TAG, "getConnectedDevices: " + t);
        }
        if (!out.isEmpty()) attachInputs(c, out);
        Collections.sort(out, (a, b) -> {
            if (a.isSource() != b.isSource()) return a.isSource() ? -1 : 1;
            if (a.port != b.port) return Integer.compare(a.port, b.port);
            return Integer.compare(a.la, b.la);
        });
        return out;
    }

    /** The first audio system (soundbar / AVR), null = none known. */
    static HdmiDeviceInfo audioSystem(Context c) {
        try {
            HdmiControlManager m = CecSettings.hm(c);
            List<HdmiDeviceInfo> list = m == null ? null : m.getConnectedDevices();
            if (list == null) return null;
            for (HdmiDeviceInfo d : list) {
                if (d != null && d.isCecDevice() && d.getDeviceType() == HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM) return d;
            }
        } catch (Throwable t) {
            Log.w(TAG, "getConnectedDevices: " + t);
        }
        return null;
    }

    /** Any CEC device at all (a reason to keep the CPU up for the &lt;Standby&gt; broadcast). */
    static boolean any(Context c) {
        try {
            HdmiControlManager m = CecSettings.hm(c);
            List<HdmiDeviceInfo> list = m == null ? null : m.getConnectedDevices();
            if (list == null) return false;
            for (HdmiDeviceInfo d : list) {
                if (d != null && d.isCecDevice() && d.getLogicalAddress() != 0) return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "getConnectedDevices: " + t);
        }
        return false;
    }

    /** Child inputs of org.z9x.tvinput (TvInputInfo with a parent and this device's HdmiDeviceInfo id). */
    private static void attachInputs(Context c, List<Dev> devs) {
        try {
            TvInputManager tim = c.getSystemService(TvInputManager.class);
            List<TvInputInfo> all = tim == null ? null : tim.getTvInputList();
            if (all == null) return;
            for (TvInputInfo i : all) {
                if (i == null || i.getParentId() == null || i.getHdmiDeviceInfo() == null) continue;
                if (i.getServiceInfo() == null || !"org.z9x.tvinput".equals(i.getServiceInfo().packageName)) continue;
                int id = i.getHdmiDeviceInfo().getId();
                for (Dev d : devs) if (d.id == id) d.inputId = i.getId();
            }
        } catch (Throwable t) {
            Log.w(TAG, "getTvInputList: " + t);
        }
    }
}
