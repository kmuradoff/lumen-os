/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One state per HDMI port and the CEC source behind it, for the viewer and for other apps (HdmiStateProvider
 * "hdmi_state"). Device-neutral: it only combines what the framework and our own sessions report.
 *
 * <ul>
 *   <li>{@link #ON}: a session on the port shows a picture; or, without one, the port has +5V and its CEC source
 *       (if any) is not asleep.</li>
 *   <li>{@link #ASLEEP}: a CEC source is listed on the port and its power status is standby or transient to
 *       standby, or the port has no +5V while the source is still listed (not transient to on).</li>
 *   <li>{@link #NO_SIGNAL}: +5V and a session on the port reports no video; or a listed source is waking up
 *       (transient to on) and +5V is not back yet.</li>
 *   <li>{@link #DISCONNECTED}: no +5V and no CEC source listed on the port.</li>
 *   <li>{@link #UNKNOWN}: the port has no input (yet).</li>
 * </ul>
 * Inputs: the port input's TIF state (TvInputHardwareManager: CONNECTED when the HDMI hotplug event says +5V,
 * CONNECTED_STANDBY when it does not; the vendor HAL reports cable status unknown, so there is no other cable
 * signal), the CEC device list of HdmiControlService (fresh on every computation) with each source's power
 * status, and our sessions (HdmiStateProvider counts: streaming, no signal).
 *
 * <p>Why "no +5V while listed" is asleep: a source going to sleep drops +5V at once, but its CEC power status
 * can stay "on" for a while. On Android 14 a TV-side app cannot send &lt;Give Device Power Status&gt; on its own
 * (IHdmiControlService has no raw send; powerOn/OffRemoteDevice need a playback or audio local device;
 * deviceSelect also routes and wakes), so the framework refreshes it: devices report changes (CEC 2.0), the TV
 * asks on a timer, and deviceSelect asks before it wakes a device. A device whose cable is pulled stays listed
 * until the framework's hotplug poll drops it (a few seconds), then the port is DISCONNECTED.
 *
 * <p>Changes: {@link #changed} is called on every event the state depends on (port and child input states,
 * CEC device added / updated / removed, session video). It runs the in-process listeners and notifies
 * {@link #URI} for other apps, coalesced to one main-thread pass, and logs each port's transitions. Nothing
 * polls in the background.
 */
final class PortState {
    private static final String TAG = "Z9xHdmiPortState";

    static final int UNKNOWN = 0;
    static final int ON = 1;
    static final int ASLEEP = 2;
    static final int NO_SIGNAL = 3;
    static final int DISCONNECTED = 4;

    /** Notified (ContentResolver.notifyChange) when a port state may have changed. */
    static final String URI = "content://org.z9x.tvinput.hdmistate/ports";

    private static final long COALESCE_MS = 250;

    /** One port as seen now. */
    static final class Snapshot {
        int port = -1;
        String portInputId;
        String portLabel;
        /** The CEC child input of the device (open it to route to the device), or null. */
        String childInputId;
        /** The CEC source on the port (fresh), or null. */
        HdmiDeviceInfo device;
        Brands.Brand brand;
        /** What to call the device: the child's custom label, the brand, its own name; null without a name. */
        String title;
        int inputState = TvInputManager.INPUT_STATE_DISCONNECTED;
        boolean picture;
        int state = UNKNOWN;

        int power() {
            return device == null ? HdmiControlManager.POWER_STATUS_UNKNOWN : device.getDevicePowerStatus();
        }
    }

    private PortState() {}

    static String name(int state) {
        switch (state) {
            case ON:
                return "on";
            case ASLEEP:
                return "asleep";
            case NO_SIGNAL:
                return "no_signal";
            case DISCONNECTED:
                return "disconnected";
            default:
                return "unknown";
        }
    }

    /**
     * The rule table (pure, see the class comment). {@code listed}: a CEC source is listed on the port;
     * {@code power}: its CEC power status; {@code noSignal}: a session on the port reports no video.
     */
    static int classify(boolean portKnown, int inputState, boolean listed, int power, boolean picture,
            boolean noSignal) {
        if (!portKnown) return UNKNOWN;
        if (picture) return ON;
        boolean fiveVolt = inputState == TvInputManager.INPUT_STATE_CONNECTED;
        if (listed) {
            if (power == HdmiControlManager.POWER_STATUS_STANDBY
                    || power == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY) return ASLEEP;
            if (!fiveVolt && power != HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON) return ASLEEP;
        }
        if (fiveVolt) return noSignal ? NO_SIGNAL : ON;
        return listed ? NO_SIGNAL : DISCONNECTED;
    }

    /** The port of {@code inputId} (a port input or a CEC child input). Any thread; binder calls; never throws. */
    static Snapshot of(Context c, String inputId) {
        Snapshot s = new Snapshot();
        try {
            TvInputInfo info = HdmiInputs.info(c, inputId);
            if (info == null) return s;
            boolean child = info.getParentId() != null;
            TvInputInfo port = child ? HdmiInputs.info(c, info.getParentId()) : info;
            s.port = HdmiInputs.portOf(port != null ? port : info);
            s.portInputId = port != null ? port.getId() : null;
            s.portLabel = port != null ? HdmiInputs.labelOf(c, port) : HdmiInputs.defaultLabel(c, s.port);
            s.device = CecHelper.sourceFor(c, s.port, child ? info.getHdmiDeviceInfo() : null);
            s.childInputId = child ? info.getId()
                    : s.device != null ? HdmiInputs.findChildInputId(c, s.device.getId()) : null;
            s.brand = Brands.of(s.device);
            TvInputInfo childInfo = child ? info : HdmiInputs.info(c, s.childInputId);
            CharSequence custom = null;
            try {
                if (childInfo != null) custom = childInfo.loadCustomLabel(c);
            } catch (Throwable ignored) {
            }
            if (!TextUtils.isEmpty(custom)) {
                s.title = custom.toString();
            } else if (s.device != null) {
                s.title = s.brand != null ? s.brand.name : Brands.realName(s.device);
            }
            s.inputState = HdmiInputs.stateOf(c, s.portInputId);
            s.picture = HdmiStateProvider.streaming(s.port);
            s.state = classify(s.portInputId != null, s.inputState, s.device != null, s.power(), s.picture,
                    HdmiStateProvider.noSignal(s.port));
        } catch (Throwable t) {
            Log.w(TAG, "of(" + inputId + "): " + t);
        }
        return s;
    }

    /** Every HDMI port input of ours, by port id. */
    static List<Snapshot> all(Context c) {
        List<Snapshot> out = new ArrayList<>();
        for (TvInputInfo i : HdmiInputs.portInputs(c)) out.add(of(c, i.getId()));
        return out;
    }

    // ------------------------------------------------------------------ change notification

    /** Created on the first change (the rule table above stays usable without a Looper). */
    private static Handler sMain;
    /** Main thread only. */
    private static final List<Runnable> sListeners = new ArrayList<>();
    /** Main thread only: last logged state per port. */
    private static final Map<Integer, Integer> sLast = new HashMap<>();
    private static volatile Context sApp;

    private static final Runnable sNotify = Safe.wrap(TAG, "notify", () -> {
        Context c = sApp;
        if (c == null) return;
        for (Snapshot s : all(c)) {
            Integer last = sLast.get(s.port);
            int prev = last == null ? -1 : last;
            if (prev != s.state) {
                sLast.put(s.port, s.state);
                Log.i(TAG, "HDMI " + s.port + ": " + (prev < 0 ? "-" : name(prev)) + " -> " + name(s.state)
                        + " (input state " + s.inputState + ", " + (s.device == null ? "no CEC source"
                        : "CEC power " + s.power()) + (s.picture ? ", picture" : "") + ")");
            }
        }
        for (Runnable r : new ArrayList<>(sListeners)) r.run();
        try {
            c.getContentResolver().notifyChange(Uri.parse(URI), null);
        } catch (Throwable t) {
            Log.w(TAG, "notifyChange: " + t);
        }
    });

    /** Any thread: something a port state depends on changed ({@code why} for the log only). */
    static void changed(Context c, String why) {
        try {
            synchronized (PortState.class) {
                if (sMain == null) sMain = new Handler(Looper.getMainLooper());
            }
            if (sApp == null) sApp = c.getApplicationContext();
            sMain.removeCallbacks(sNotify);
            sMain.postDelayed(sNotify, COALESCE_MS);
        } catch (Throwable t) {
            Log.w(TAG, "changed (" + why + "): " + t);
        }
    }

    /** Main thread. Runs on the main thread after each coalesced change. */
    static void addListener(Runnable r) {
        if (!sListeners.contains(r)) sListeners.add(r);
    }

    static void removeListener(Runnable r) {
        sListeners.remove(r);
    }
}
