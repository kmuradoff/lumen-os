/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.content.Context;
import android.media.tv.TvInputHardwareInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.util.SparseArray;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hardware TvInputService for the HDMI ports reported by the vendor AIDL HAL
 * android.hardware.tv.input (XGIMI implementation: deviceId == portId, 1 and 2, type HDMI,
 * audio = IN_DEVICE/CONNECTION_HDMI, cable status CONNECTED/DISCONNECTED on hotplug).
 *
 * <p>TvInputManagerService binds this service at boot (isHardware && neverConnected), calls
 * onHardwareAdded() for every TvInputHardwareInfo, and unbinds it ~10 s after its last session.
 * The service instance is therefore short-lived; the registry below is process-wide (the process
 * itself is persistent). All callbacks here run on the main thread and never throw.
 */
public class HdmiInputService extends TvInputService {
    static final String TAG = "Z9xHdmiTis";

    private static final Object sLock = new Object();
    /** deviceId -> hardware info (from onHardwareAdded). */
    private static final SparseArray<TvInputHardwareInfo> sHwByDevice = new SparseArray<>();
    /** inputId -> deviceId. */
    private static final Map<String, Integer> sDeviceByInput = new HashMap<>();

    private static HandlerThread sHwThread;
    private static Handler sHwHandler;

    /**
     * Single worker thread for every TvInputManager.Hardware call (synchronous binder calls into
     * system_server, and from there into the vendor TV input HAL: openStream/closeStream) and for
     * the CEC routing calls. Never the main thread.
     */
    static Handler hwHandler() {
        synchronized (sLock) {
            if (sHwHandler == null) {
                sHwThread = new HandlerThread("z9x-hdmi-hw", Process.THREAD_PRIORITY_DISPLAY);
                sHwThread.start();
                sHwHandler = new Handler(sHwThread.getLooper());
            }
            return sHwHandler;
        }
    }

    @Override
    public TvInputInfo onHardwareAdded(TvInputHardwareInfo hw) {
        try {
            if (hw == null || hw.getType() != TvInputHardwareInfo.TV_INPUT_TYPE_HDMI) {
                return null;
            }
            TvInputInfo info = new TvInputInfo.Builder(this, HdmiInputs.serviceComponent(this))
                    .setTvInputHardwareInfo(hw)
                    .setLabel(HdmiInputs.defaultLabel(this, hw.getHdmiPortId()))
                    .build();
            synchronized (sLock) {
                sHwByDevice.put(hw.getDeviceId(), hw);
                sDeviceByInput.put(info.getId(), hw.getDeviceId());
            }
            Log.i(TAG, "onHardwareAdded " + hw + " -> " + info.getId());
            PortState.changed(this, "hardware added");
            return info;
        } catch (Throwable t) {
            Log.e(TAG, "onHardwareAdded failed for " + hw, t);
            return null;
        }
    }

    @Override
    public String onHardwareRemoved(TvInputHardwareInfo hw) {
        try {
            if (hw == null || hw.getType() != TvInputHardwareInfo.TV_INPUT_TYPE_HDMI) {
                return null;
            }
            String inputId = null;
            synchronized (sLock) {
                sHwByDevice.remove(hw.getDeviceId());
                for (Map.Entry<String, Integer> e : sDeviceByInput.entrySet()) {
                    if (e.getValue() == hw.getDeviceId()) {
                        inputId = e.getKey();
                        break;
                    }
                }
                if (inputId != null) sDeviceByInput.remove(inputId);
            }
            if (inputId == null) {
                // Same id the Builder generated in onHardwareAdded (flattenToShortString + "/HW" + deviceId).
                inputId = HdmiInputs.serviceComponent(this).flattenToShortString()
                        + "/HW" + hw.getDeviceId();
            }
            Log.i(TAG, "onHardwareRemoved " + hw + " -> " + inputId);
            PortState.changed(this, "hardware removed");
            return inputId;
        } catch (Throwable t) {
            Log.e(TAG, "onHardwareRemoved failed for " + hw, t);
            return null;
        }
    }

    // ---- Lumen OS 1.0 (cec spec 3.1, F8): CEC child inputs. HdmiCecLocalDeviceTv.handleActiveSource only
    // processes a source's <Active Source> once a TvInputInfo carrying that device's HdmiDeviceInfo exists
    // (isInputReady); without it the message stays buffered forever and One Touch Play never fires.
    // So every CEC source device (playback / tuner / recorder) behind one of our ports gets a child input
    // (AOSP pattern, as the stock XGIMI tvinput did): parent = that port's input, label below.
    // Audio systems (soundbar / AVR) and devices on unknown ports get none. Our port lists ignore children
    // (HdmiInputs.isOurPortInput); Lumen Home groups them under their port (PLAN C15). The v6.2
    // ProjectorEvents sends (game module) are kept.
    // Child label: Brands.label (brand, else the real OSD name), else the port's own label; never the
    // framework's placeholder name. A device first appears as "Playback_1" and sends its OSD name and vendor id
    // about a second later, so onHdmiDeviceUpdated publishes the child again (TvInputManager.updateTvInputInfo)
    // whenever its OSD name or vendor id changes.

    /** HdmiDeviceInfo id -> child input id (main thread: TvInputService callbacks). */
    private static final SparseArray<String> sChildByDevice = new SparseArray<>();
    /** HdmiDeviceInfo id -> {@link #nameKey} the child input was last published with. */
    private static final SparseArray<String> sChildKey = new SparseArray<>();
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    /** One more look at a new child's device after this delay (see onHdmiDeviceAdded). */
    private static final long CHILD_RECHECK_MS = 3000;

    @Override
    public TvInputInfo onHdmiDeviceAdded(android.hardware.hdmi.HdmiDeviceInfo device) {
        try {
            if (device == null) return null;
            ProjectorEvents.send(this, device.getPortId(), true, "cec_added");
            PortState.changed(this, "cec added");
            if (!device.isCecDevice() || !isSourceType(device.getDeviceType())) {
                Log.i(TAG, "onHdmiDeviceAdded " + device + ": no child input (not a source)");
                return null;
            }
            String parent = portInputId(device.getPortId());
            if (parent == null) {
                Log.i(TAG, "onHdmiDeviceAdded " + device + ": no child input (port " + device.getPortId() + " unknown)");
                return null;
            }
            CharSequence label = childLabel(this, device);
            TvInputInfo info = childInfo(this, device, parent, label);
            synchronized (sLock) {
                sChildByDevice.put(device.getId(), info.getId());
                sChildKey.put(device.getId(), nameKey(device));
            }
            Log.i(TAG, "onHdmiDeviceAdded " + device + " -> child " + info.getId() + " of " + parent + " '" + label + "'");
            // An update that comes while the framework is still registering this child is dropped
            // (TvInputHardwareManager: "Could not resolve input ID"): look at the device once more.
            final Context app = getApplicationContext();
            final int deviceId = device.getId();
            sMain.postDelayed(Safe.wrap(TAG, "child recheck",
                    () -> updateChild(app, CecHelper.device(app, deviceId), "recheck")), CHILD_RECHECK_MS);
            return info;
        } catch (Throwable t) {
            Log.w(TAG, "onHdmiDeviceAdded: " + t);
            return null;
        }
    }

    @Override
    public String onHdmiDeviceRemoved(android.hardware.hdmi.HdmiDeviceInfo device) {
        try {
            if (device == null) return null;
            ProjectorEvents.send(this, device.getPortId(), false, "cec_removed");
            PortState.changed(this, "cec removed");
            String id;
            synchronized (sLock) {
                id = sChildByDevice.get(device.getId());
                sChildByDevice.remove(device.getId());
                sChildKey.remove(device.getId());
            }
            if (id == null && device.isCecDevice() && isSourceType(device.getDeviceType())) {
                // service instance recreated: the framework still knows the child under its generated id
                id = HdmiInputs.findChildInputId(this, device.getId());
            }
            Log.i(TAG, "onHdmiDeviceRemoved " + device + " -> " + id);
            return id;
        } catch (Throwable t) {
            Log.w(TAG, "onHdmiDeviceRemoved: " + t);
            return null;
        }
    }

    /** A CEC device behind one of our ports changed (OSD name, vendor id, power status ...). */
    @Override
    public void onHdmiDeviceUpdated(android.hardware.hdmi.HdmiDeviceInfo device) {
        try {
            PortState.changed(this, "cec update");
            updateChild(this, device, "update");
        } catch (Throwable t) {
            Log.w(TAG, "onHdmiDeviceUpdated: " + t);
        }
    }

    /**
     * Publishes the child input of {@code device} again (TvInputManager.updateTvInputInfo: same id, the new
     * HdmiDeviceInfo and label) when its OSD name or vendor id differs from what it was last published with.
     * Nothing for other changes (power status), for a device without a child input, or when the input id would
     * change (a new physical address: the framework removes and adds the device then). Main thread; never throws.
     */
    static void updateChild(Context c, android.hardware.hdmi.HdmiDeviceInfo device, String why) {
        try {
            if (device == null || !device.isCecDevice() || !isSourceType(device.getDeviceType())) return;
            String key = nameKey(device);
            String id;
            synchronized (sLock) {
                if (key.equals(sChildKey.get(device.getId()))) return;
                id = sChildByDevice.get(device.getId());
            }
            if (id == null) id = HdmiInputs.findChildInputId(c, device.getId());
            TvInputInfo old = HdmiInputs.info(c, id);
            TvInputManager tim = HdmiInputs.tim(c);
            if (old == null || old.getParentId() == null || tim == null) return;
            CharSequence label = childLabel(c, device);
            TvInputInfo info = childInfo(c, device, old.getParentId(), label);
            if (!id.equals(info.getId())) {
                Log.i(TAG, "child " + id + ": " + device + " would be " + info.getId() + " (" + why + "): not updated");
                return;
            }
            tim.updateTvInputInfo(info);
            synchronized (sLock) {
                sChildByDevice.put(device.getId(), id);
                sChildKey.put(device.getId(), key);
            }
            Log.i(TAG, "child " + id + " '" + label + "' (" + why + ": " + device.getDisplayName()
                    + ", vendor 0x" + Integer.toHexString(device.getVendorId()) + ")");
        } catch (Throwable t) {
            Log.w(TAG, "updateChild (" + why + "): " + t);
        }
    }

    private static TvInputInfo childInfo(Context c, android.hardware.hdmi.HdmiDeviceInfo device, String parent,
            CharSequence label) {
        return new TvInputInfo.Builder(c, HdmiInputs.serviceComponent(c))
                .setHdmiDeviceInfo(device)
                .setParentId(parent)
                .setLabel(label)
                .build();
    }

    /** Brand or real OSD name; until the device has told its name, the port's own label ("HDMI 1"). */
    private static CharSequence childLabel(Context c, android.hardware.hdmi.HdmiDeviceInfo device) {
        String l = Brands.label(device);
        return l != null ? l : HdmiInputs.defaultLabel(c, device.getPortId());
    }

    /** What the child's label and published device name depend on. */
    private static String nameKey(android.hardware.hdmi.HdmiDeviceInfo device) {
        return device.getVendorId() + "|" + device.getDisplayName();
    }

    static boolean isSourceType(int type) {
        return type == android.hardware.hdmi.HdmiDeviceInfo.DEVICE_PLAYBACK
                || type == android.hardware.hdmi.HdmiDeviceInfo.DEVICE_TUNER
                || type == android.hardware.hdmi.HdmiDeviceInfo.DEVICE_RECORDER;
    }

    /** Our port input for an HDMI port id: the registry first, else the framework's list. */
    private String portInputId(int port) {
        if (port <= 0) return null;
        synchronized (sLock) {
            for (int i = 0; i < sHwByDevice.size(); i++) {
                TvInputHardwareInfo hw = sHwByDevice.valueAt(i);
                if (hw != null && hw.getHdmiPortId() == port) {
                    for (Map.Entry<String, Integer> e : sDeviceByInput.entrySet()) {
                        if (e.getValue() == hw.getDeviceId()) return e.getKey();
                    }
                }
            }
        }
        return HdmiInputs.findInputIdForPort(this, port);
    }

    @Override
    public Session onCreateSession(String inputId) {
        try {
            TvInputManager tim = HdmiInputs.tim(this);
            if (tim == null || inputId == null) return null;
            TvInputInfo info = HdmiInputs.info(this, inputId);
            // Lumen OS 1.0: a CEC child input plays through its parent port's hardware (same stream and
            // audio patch as the port input); only the CEC routing differs (deviceSelect instead of portSelect).
            int cecDeviceId = -1;
            String hwInputId = inputId;
            if (info != null && info.getParentId() != null && info.getHdmiDeviceInfo() != null) {
                cecDeviceId = info.getHdmiDeviceInfo().getId();
                hwInputId = info.getParentId();
                info = HdmiInputs.info(this, hwInputId);
            }
            TvInputHardwareInfo hw = findHardware(tim, hwInputId);
            if (info == null || hw == null) {
                Log.e(TAG, "onCreateSession: unknown input " + inputId + " info=" + info + " hw=" + hw);
                return null;
            }
            Log.i(TAG, "onCreateSession " + inputId + " deviceId=" + hw.getDeviceId()
                    + " port=" + hw.getHdmiPortId() + (cecDeviceId >= 0 ? " cec device " + cecDeviceId : ""));
            return new HdmiSession(this, inputId, info, hw, cecDeviceId);
        } catch (Throwable t) {
            // null -> the framework reports onConnectionFailed to the TvView.
            Log.e(TAG, "onCreateSession failed for " + inputId, t);
            return null;
        }
    }

    private static TvInputHardwareInfo findHardware(TvInputManager tim, String inputId) {
        synchronized (sLock) {
            Integer dev = sDeviceByInput.get(inputId);
            if (dev != null) {
                TvInputHardwareInfo hw = sHwByDevice.get(dev);
                if (hw != null) return hw;
            }
        }
        // Fallback (e.g. the service instance was recreated and the registry is empty):
        // parse ".../HW<deviceId>" and ask the framework (TV_INPUT_HARDWARE).
        int idx = inputId.lastIndexOf("/HW");
        if (idx < 0) return null;
        int deviceId;
        try {
            deviceId = Integer.parseInt(inputId.substring(idx + 3));
        } catch (NumberFormatException e) {
            return null;
        }
        try {
            List<TvInputHardwareInfo> list = tim.getHardwareList();
            if (list == null) return null;
            for (TvInputHardwareInfo hw : list) {
                if (hw != null && hw.getDeviceId() == deviceId
                        && hw.getType() == TvInputHardwareInfo.TV_INPUT_TYPE_HDMI) {
                    synchronized (sLock) {
                        sHwByDevice.put(deviceId, hw);
                        sDeviceByInput.put(inputId, deviceId);
                    }
                    return hw;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getHardwareList failed: " + t);
        }
        return null;
    }
}
