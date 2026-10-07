/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.HdmiTvClient;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * Best-effort HDMI-CEC helpers. Only @SystemApi of HdmiControlManager / HdmiTvClient / HdmiClient
 * (android.permission.HDMI_CEC), i.e. calls into system_server's HdmiControlService; nothing here
 * calls a HAL directly. Every call is wrapped: CEC may be disabled or unavailable on this unit
 * (HdmiControlService reported mIsCecAvailable=false once), and a CEC failure must never break
 * HDMI video or the process.
 */
final class CecHelper {
    private static final String TAG = "Z9xHdmiCec";

    private static final Object sLock = new Object();
    private static long sDeviceListTime;
    private static List<HdmiDeviceInfo> sDeviceList;

    /** uptimeMillis of our last portSelect(); 0 = never. Lets the watcher ignore self-caused routing. */
    private static volatile long sLastPortSelectAt;

    private CecHelper() {}

    static HdmiControlManager manager(Context c) {
        try {
            return c.getApplicationContext().getSystemService(HdmiControlManager.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /** null when the device has no CEC TV logical device (ro.hdmi.device_type without 0). */
    static HdmiTvClient tvClient(Context c) {
        try {
            HdmiControlManager m = manager(c);
            return m == null ? null : m.getTvClient();
        } catch (Throwable t) {
            Log.w(TAG, "getTvClient failed: " + t);
            return null;
        }
    }

    /** Port info from the HDMI connection HAL via HdmiControlService (ids 1 and 2 on the Z9X). May be empty early in boot. */
    static HdmiPortInfo portInfo(Context c, int portId) {
        try {
            HdmiControlManager m = manager(c);
            if (m == null) return null;
            List<HdmiPortInfo> ports = m.getPortInfo();
            if (ports == null) return null;
            for (HdmiPortInfo p : ports) {
                if (p != null && p.getId() == portId) return p;
            }
        } catch (Throwable t) {
            Log.w(TAG, "getPortInfo failed: " + t);
        }
        return null;
    }

    static long lastPortSelectAt() {
        return sLastPortSelectAt;
    }

    /** Routing: make the device on {@code portId} the active path (what stock CecHandler did on setMain(true)). */
    static void portSelect(Context c, int portId) {
        if (portId <= 0) return;
        try {
            HdmiTvClient tv = tvClient(c);
            if (tv == null) return;
            sLastPortSelectAt = SystemClock.uptimeMillis();
            tv.portSelect(portId, result -> Log.i(TAG, "portSelect(" + portId + ") -> " + result));
        } catch (Throwable t) {
            Log.w(TAG, "portSelect failed: " + t);
        }
    }

    /**
     * Lumen OS 1.0: routing to one CEC device (a CEC child input): HdmiTvClient.deviceSelect(id) ->
     * &lt;Set Stream Path&gt; to its physical address, which also reaches a source behind an AVR.
     * Falls back to {@link #portSelect} when the framework does not know the device (any more).
     */
    static void deviceSelect(Context c, int deviceId, int portId) {
        try {
            HdmiTvClient tv = tvClient(c);
            if (tv == null) return;
            sLastPortSelectAt = SystemClock.uptimeMillis();
            tv.deviceSelect(deviceId, result -> {
                Log.i(TAG, "deviceSelect(" + deviceId + ") -> " + result);
                if (result != HdmiControlManager.RESULT_SUCCESS) portSelect(c, portId);
            });
        } catch (Throwable t) {
            Log.w(TAG, "deviceSelect failed: " + t);
            portSelect(c, portId);
        }
    }

    /** Make the TV itself (internal source, logical address 0) the active source. Optional, off by default. */
    static void selectInternal(Context c) {
        try {
            HdmiTvClient tv = tvClient(c);
            if (tv == null) return;
            // HdmiClient.selectDevice -> IHdmiControlService.deviceSelect(0) ->
            // HdmiCecLocalDeviceTv.deviceSelect(ADDR_INTERNAL) -> handleSelectInternalSource().
            tv.selectDevice(HdmiDeviceInfo.ADDR_INTERNAL, Runnable::run,
                    (result, la) -> Log.i(TAG, "selectDevice(internal) -> " + result));
        } catch (Throwable t) {
            Log.w(TAG, "selectDevice(internal) failed: " + t);
        }
    }

    private static List<HdmiDeviceInfo> devices(Context c, long maxAgeMs) {
        synchronized (sLock) {
            long now = SystemClock.uptimeMillis();
            if (sDeviceList == null || now - sDeviceListTime > maxAgeMs) {
                try {
                    HdmiControlManager m = manager(c);
                    sDeviceList = m == null ? null : m.getConnectedDevices();
                } catch (Throwable t) {
                    sDeviceList = null;
                }
                sDeviceListTime = now;
            }
            return sDeviceList;
        }
    }

    /** True if a CEC source device is known behind {@code portId} (cached 2 s; binder call to system_server). */
    static boolean hasCecSourceOnPort(Context c, int portId) {
        if (portId <= 0) return false;
        List<HdmiDeviceInfo> list = devices(c, 2000);
        if (list == null) return false;
        try {
            for (HdmiDeviceInfo d : list) {
                if (d != null && d.isCecDevice() && d.isSourceType() && d.getPortId() == portId) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "device list scan failed: " + t);
        }
        return false;
    }

    /**
     * True if the only CEC device known behind {@code portId} is an audio system (soundbar/AVR on
     * the ARC port). Its HDMI output raises +5V like a player, so a plain hotplug must not switch to it.
     */
    static boolean isAudioSystemOnlyPort(Context c, int portId) {
        List<HdmiDeviceInfo> list = devices(c, 0);
        if (list == null) return false;
        boolean audio = false;
        boolean player = false;
        try {
            for (HdmiDeviceInfo d : list) {
                if (d == null || d.getPortId() != portId) continue;
                int type = d.getDeviceType();
                if (type == HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM) {
                    audio = true;
                } else if (type == HdmiDeviceInfo.DEVICE_PLAYBACK || type == HdmiDeviceInfo.DEVICE_TUNER
                        || type == HdmiDeviceInfo.DEVICE_RECORDER) {
                    player = true;
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return audio && !player;
    }

    /** Sends a remote key to the current CEC active source (User Control Pressed/Released). */
    static boolean sendKey(Context c, int keyCode, boolean pressed) {
        try {
            HdmiTvClient tv = tvClient(c);
            if (tv == null) return false;
            tv.sendKeyEvent(keyCode, pressed);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "sendKeyEvent failed: " + t);
            return false;
        }
    }

    /** Keys that make sense to pass to a CEC playback device. BACK/HOME/MENU/TV_INPUT stay local. */
    static boolean isForwardableKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                return true;
            default:
                return false;
        }
    }

    /**
     * CEC One Touch Play: HdmiControlService keeps a single InputChangeListener slot (normally the
     * TV app's). The listener is invoked on a binder thread; callers must hop to their own thread.
     */
    static boolean setInputChangeListener(Context c, HdmiTvClient.InputChangeListener l) {
        try {
            HdmiTvClient tv = tvClient(c);
            if (tv == null) return false;
            tv.setInputChangeListener(l);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setInputChangeListener failed: " + t);
            return false;
        }
    }
}
