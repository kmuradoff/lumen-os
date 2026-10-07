/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared helpers: our HDMI port inputs, labels, and the intents we fire. Nothing here throws. */
final class HdmiInputs {
    static final String TAG = "Z9xHdmi";

    static final String TV_LAUNCHER_PKG = "com.google.android.tvlauncher";
    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    static final String EXTRA_REASON = "org.z9x.tvinput.extra.REASON";

    private HdmiInputs() {}

    static TvInputManager tim(Context c) {
        try {
            return c.getApplicationContext().getSystemService(TvInputManager.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The service component that owns our inputs. */
    static ComponentName serviceComponent(Context c) {
        return new ComponentName(c.getPackageName(), HdmiInputService.class.getName());
    }

    static TvInputInfo info(Context c, String inputId) {
        if (inputId == null) return null;
        TvInputManager m = tim(c);
        if (m == null) return null;
        try {
            return m.getTvInputInfo(inputId);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * True for the per-port hardware inputs registered by our service
     * (id "org.z9x.tvinput/.HdmiInputService/HW&lt;deviceId&gt;"), false for anything else.
     */
    static boolean isOurPortInput(Context c, TvInputInfo info) {
        if (info == null) return false;
        try {
            if (info.getServiceInfo() == null
                    || !c.getPackageName().equals(info.getServiceInfo().packageName)) {
                return false;
            }
            return info.getType() == TvInputInfo.TYPE_HDMI
                    && info.isHardwareInput()
                    && info.getParentId() == null;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isOurPortInput(Context c, String inputId) {
        return isOurPortInput(c, info(c, inputId));
    }

    /** HDMI port id (1, 2, ...) of an input, or -1. TvInputInfo.Builder sets hardwarePort(PATH_INVALID, port). */
    static int portOf(TvInputInfo info) {
        try {
            HdmiDeviceInfo d = info == null ? null : info.getHdmiDeviceInfo();
            return d == null ? -1 : d.getPortId();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** All our HDMI port inputs, sorted by port id. */
    static List<TvInputInfo> portInputs(Context c) {
        List<TvInputInfo> out = new ArrayList<>();
        TvInputManager m = tim(c);
        if (m == null) return out;
        try {
            List<TvInputInfo> all = m.getTvInputList();
            if (all != null) {
                for (TvInputInfo i : all) {
                    if (isOurPortInput(c, i)) out.add(i);
                }
            }
            Collections.sort(out, (a, b) -> Integer.compare(portOf(a), portOf(b)));
        } catch (Throwable t) {
            Log.w(TAG, "getTvInputList failed: " + t);
        }
        return out;
    }

    static String findInputIdForPort(Context c, int portId) {
        if (portId <= 0) return null;
        for (TvInputInfo i : portInputs(c)) {
            if (portOf(i) == portId) return i.getId();
        }
        return null;
    }

    /**
     * Lumen OS 1.0: our CEC child input of HdmiDeviceInfo id {@code deviceId} (parent = a port input),
     * or null.
     */
    static String findChildInputId(Context c, int deviceId) {
        TvInputManager m = tim(c);
        if (m == null) return null;
        try {
            List<TvInputInfo> all = m.getTvInputList();
            if (all == null) return null;
            for (TvInputInfo i : all) {
                if (i == null || i.getParentId() == null || i.getServiceInfo() == null
                        || !c.getPackageName().equals(i.getServiceInfo().packageName)) continue;
                HdmiDeviceInfo d = i.getHdmiDeviceInfo();
                if (d != null && d.getId() == deviceId) return i.getId();
            }
        } catch (Throwable t) {
            Log.w(TAG, "getTvInputList failed: " + t);
        }
        return null;
    }

    /**
     * The input whose state tells whether the cable / source is there: the port input itself, or for a
     * CEC child input its parent port (a child's own state follows the device's CEC power status).
     */
    static String stateInputId(Context c, String inputId) {
        TvInputInfo i = info(c, inputId);
        try {
            if (i != null && i.getParentId() != null) return i.getParentId();
        } catch (Throwable ignored) {
        }
        return inputId;
    }

    /** Cached client-side state (TvInputManager.mStateMap); unknown id -> DISCONNECTED. */
    static int stateOf(Context c, String inputId) {
        TvInputManager m = tim(c);
        if (m == null || inputId == null) return TvInputManager.INPUT_STATE_DISCONNECTED;
        try {
            return m.getInputState(inputId);
        } catch (Throwable t) {
            return TvInputManager.INPUT_STATE_DISCONNECTED;
        }
    }

    static String stateText(Context c, int state) {
        switch (state) {
            case TvInputManager.INPUT_STATE_CONNECTED:
                return c.getString(R.string.state_connected);
            case TvInputManager.INPUT_STATE_CONNECTED_STANDBY:
                return c.getString(R.string.state_standby);
            default:
                return c.getString(R.string.state_disconnected);
        }
    }

    /** Default label given to the framework at onHardwareAdded() time ("HDMI 1 (ARC)", "HDMI 2"). */
    static String defaultLabel(Context c, int portId) {
        try {
            HdmiPortInfo p = CecHelper.portInfo(c, portId);
            if (p != null && (p.isArcSupported() || p.isEarcSupported())) {
                return c.getString(R.string.input_label_arc, portId);
            }
            return c.getString(R.string.input_label, portId);
        } catch (Throwable t) {
            return "HDMI " + portId;
        }
    }

    /** User-visible label: custom label from TV settings first, then ours. A CEC child: "HDMI 2 · PS5". */
    static String labelOf(Context c, TvInputInfo info) {
        if (info == null) return "HDMI";
        try {
            if (info.getParentId() != null) {
                CharSequence l = info.loadCustomLabel(c);
                if (TextUtils.isEmpty(l)) l = info.loadLabel(c);
                String parent = labelOf(c, info(c, info.getParentId()));
                return TextUtils.isEmpty(l) ? parent : parent + " · " + l;
            }
        } catch (Throwable ignored) {
        }
        try {
            CharSequence l = info.loadCustomLabel(c);
            if (TextUtils.isEmpty(l)) l = info.loadLabel(c);
            if (!TextUtils.isEmpty(l)) return l.toString();
        } catch (Throwable ignored) {
        }
        int port = portOf(info);
        try {
            return port > 0 ? c.getString(R.string.input_label, port) : "HDMI";
        } catch (Throwable t) {
            return "HDMI";
        }
    }

    static String labelOf(Context c, String inputId) {
        return labelOf(c, info(c, inputId));
    }

    /** Explicit intent for our viewer, with the same URI the Google TV launcher uses. */
    static Intent viewerIntent(Context c, String inputId, String reason) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        i.setClass(c, PassthroughActivity.class);
        i.putExtra(EXTRA_REASON, reason);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    static boolean openViewer(Context c, String inputId, String reason) {
        if (inputId == null) return false;
        try {
            Log.i(TAG, "openViewer " + inputId + " reason=" + reason);
            c.startActivity(viewerIntent(c, inputId, reason));
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "openViewer failed", t);
            return false;
        }
    }

    static void goHome(Context c) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(home);
        } catch (Throwable t) {
            Log.e(TAG, "goHome failed", t);
        }
    }

    /**
     * Remote SOURCE key: the Google TV "Inputs" panel (tvlauncher InputsPanelActivity handles
     * com.android.tv.action.VIEW_INPUTS), otherwise our own list. startActivity() is tried directly:
     * a resolveActivity() pre-check would be subject to package-visibility filtering.
     */
    static void openInputsPanel(Context c) {
        Intent panel = new Intent(ACTION_VIEW_INPUTS);
        panel.setPackage(TV_LAUNCHER_PKG);
        panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(panel);
            Log.i(TAG, "opened Google TV inputs panel");
            return;
        } catch (ActivityNotFoundException e) {
            Log.i(TAG, "no Google TV inputs panel; using own source list");
        } catch (Throwable t) {
            Log.w(TAG, "VIEW_INPUTS failed: " + t);
        }
        openPicker(c);
    }

    /** org.z9x.projector's receiver that opens its Source overlay (signature permission SHOW_SOURCE). */
    static final String PROJECTOR_PKG = "org.z9x.projector";
    static final String ACTION_SHOW_SOURCE = "org.z9x.projector.action.SHOW_SOURCE";
    static final String SOURCE_RECEIVER = "org.z9x.projector.source.SourceRequestReceiver";

    /**
     * Asks org.z9x.projector to show its Source overlay. Returns false when the receiver is not
     * installed (then the caller falls back to {@link #openPicker}).
     */
    static boolean openSourceOverlay(Context c) {
        try {
            Intent i = new Intent(ACTION_SHOW_SOURCE)
                    .setComponent(new android.content.ComponentName(PROJECTOR_PKG, SOURCE_RECEIVER));
            if (c.getPackageManager().queryBroadcastReceivers(i, 0).isEmpty()) return false;
            c.sendBroadcast(i);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "openSourceOverlay failed: " + t);
            return false;
        }
    }

    static final String ACTION_NOTIFY = "org.z9x.projector.action.NOTIFY";
    static final String NOTIFY_RECEIVER = "org.z9x.projector.ui.NotifyRequestReceiver";
    static final String PERMISSION_NOTIFY = "org.z9x.projector.permission.NOTIFY";

    /**
     * A status message on org.z9x.projector's shared XGIMI-style card (V61 decision "one shared
     * card for all notifications"); a plain Toast only when the projector app (or its receiver /
     * our NOTIFY permission) is missing. Any thread.
     */
    static void notify(Context c, CharSequence text) {
        if (text == null || text.length() == 0) return;
        final Context app = c.getApplicationContext();
        try {
            Intent i = new Intent(ACTION_NOTIFY)
                    .setComponent(new android.content.ComponentName(PROJECTOR_PKG, NOTIFY_RECEIVER))
                    .putExtra("title", text.toString());
            if (app.checkSelfPermission(PERMISSION_NOTIFY) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    && !app.getPackageManager().queryBroadcastReceivers(i, 0).isEmpty()) {
                app.sendBroadcast(i);
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "notify via projector failed: " + t);
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
    }

    /** From an Activity the picker joins that task; from any other context it gets its own task. */
    static void openPicker(Context c) {
        try {
            Intent i = new Intent(c, InputPickerActivity.class);
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "openPicker failed", t);
        }
    }

    static boolean isInteractive(Context c) {
        try {
            PowerManager pm = c.getSystemService(PowerManager.class);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    /** org.z9x.projector's lamp-only standby state (v6.5, its power.StandbyStateProvider). */
    static final String PROJECTOR_STANDBY_AUTHORITY = "org.z9x.projector.standbystate";
    static final String PROJECTOR_STANDBY_METHOD = "standby_state";
    /** org.z9x.projector's STANDBY_CHANGED broadcast (extra "standby") and its signature permission. */
    static final String ACTION_PROJECTOR_STANDBY_CHANGED = "org.z9x.projector.action.STANDBY_CHANGED";
    static final String PERMISSION_PROJECTOR_STANDBY = "org.z9x.projector.permission.STANDBY_STATE";

    /**
     * v6.5: is org.z9x.projector in its lamp-only standby (lamp off, Android and its display stay on,
     * so {@link #isInteractive} is true)? Treated like "screen is off" for every automatic switch.
     * A projector app that is missing or does not answer counts as "not in standby".
     */
    static boolean isProjectorStandby(Context c) {
        try {
            Bundle b = c.getContentResolver().call(Uri.parse("content://" + PROJECTOR_STANDBY_AUTHORITY),
                    PROJECTOR_STANDBY_METHOD, null, null);
            return b != null && b.getBoolean("standby", false);
        } catch (Throwable t) {
            Log.w(TAG, "projector standby state: " + t);
            return false;
        }
    }

    static final String PROJECTOR_CEC_WAKE_METHOD = "cec_wake";

    /**
     * Lumen OS 1.0 (cec spec 3.1 HdmiWatcher): a CEC source became the active source while the projector
     * is in its lamp-only standby. org.z9x.projector (CecPolicy: setting, night guard, storm limit, setup)
     * decides; true = it is leaving standby for this device, so we may open its input. Main thread
     * (one binder call). A missing / failing projector app = false (never a wake).
     */
    static boolean projectorCecWake(Context c, HdmiDeviceInfo d) {
        try {
            Bundle ex = new Bundle();
            ex.putInt("port", d.getPortId());
            ex.putInt("la", d.getLogicalAddress());
            ex.putInt("type", d.getDeviceType());
            ex.putInt("vendor", d.getVendorId());
            ex.putString("osd", d.getDisplayName());
            Bundle b = c.getContentResolver().call(Uri.parse("content://" + PROJECTOR_STANDBY_AUTHORITY),
                    PROJECTOR_CEC_WAKE_METHOD, null, ex);
            return b != null && b.getBoolean("accepted", false);
        } catch (Throwable t) {
            Log.w(TAG, "projector cec_wake: " + t);
            return false;
        }
    }

    /**
     * No auto-switch during the first-run setup. Both flags are checked: SetupWraith writes
     * user_setup_complete=1 and tv_user_setup_complete=1 when it finishes (the Lineage RRO default
     * of tv_user_setup_complete is 0). A missing key counts as complete.
     */
    static boolean isSetupComplete(Context c) {
        try {
            return Settings.Secure.getInt(c.getContentResolver(), "user_setup_complete", 1) != 0
                    && Settings.Secure.getInt(c.getContentResolver(), "tv_user_setup_complete", 1) != 0;
        } catch (Throwable t) {
            return true;
        }
    }
}
