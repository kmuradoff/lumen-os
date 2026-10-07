/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * Tells org.z9x.projector whether one of our HdmiSessions holds an HDMI hardware right now
 * (= a TvView plays HDMI 1/2 through this TIS). The projector's quick panel needs it because
 * IGmpf 696 getCurrentInputSource() is UNVERIFIED during TIF playback: HDMI-only settings
 * (game mode, aspect ratio) and the Ultra 120 Hz refusal use both signals.
 *
 * Only {@link #call}: method "hdmi_state" -> Bundle {active: boolean, ports: int[] of open ports,
 * streams: int[] of ports whose session streams a picture (Lumen OS 1.0)}.
 * Lumen OS 1.0 (CEC settings live in the projector's quick panel, cec spec 3.1 Prefs):
 * "get_cec_prefs" -> {cec_one_touch_play, cec_control, cec_internal_on_exit}; "set_cec_pref", arg = one
 * of those keys, extras {value: boolean} -> {ok}.
 * Authority org.z9x.tvinput.hdmistate, exported, guarded by the signature permission
 * org.z9x.tvinput.permission.HDMI_STATE (checked by hand in call(): the framework enforces the
 * provider's read/write permissions only for query/insert/update/delete, not for call()).
 * The state lives in this (persistent) process: if it dies, every session died with it.
 */
public final class HdmiStateProvider extends ContentProvider {
    private static final String TAG = "Z9xHdmiState";
    static final String PERMISSION = "org.z9x.tvinput.permission.HDMI_STATE";
    private static final String METHOD = "hdmi_state";
    private static final String METHOD_GET_CEC = "get_cec_prefs";
    private static final String METHOD_SET_CEC = "set_cec_pref";
    private static final String[] CEC_KEYS = {Prefs.CEC_ONE_TOUCH_PLAY, Prefs.CEC_CONTROL, Prefs.CEC_INTERNAL_ON_EXIT};

    /** Open-session count per HDMI port (index = port id; the Z9X has ports 1 and 2). */
    private static final int[] sCount = new int[8];
    /** Streaming-session count per HDMI port (a picture is up), guarded by sCount. */
    private static final int[] sStreams = new int[8];

    /** HdmiSession worker thread: a session started (true) or stopped (false) streaming. */
    static void onSessionStreaming(int port, boolean on) {
        synchronized (sCount) {
            if (port < 0 || port >= sStreams.length) return;
            sStreams[port] = Math.max(0, sStreams[port] + (on ? 1 : -1));
        }
    }

    /** HdmiSession worker thread: a session got (true) or lost (false) its hardware. */
    static void onSessionHardware(int port, boolean holds) {
        synchronized (sCount) {
            if (port < 0 || port >= sCount.length) return;
            sCount[port] = Math.max(0, sCount[port] + (holds ? 1 : -1));
            Log.i(TAG, "HDMI " + port + " sessions " + sCount[port]);
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD.equals(method) && !METHOD_GET_CEC.equals(method) && !METHOD_SET_CEC.equals(method)) return null;
        if (getContext() == null
                || getContext().checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("needs " + PERMISSION);
        }
        Bundle b = new Bundle();
        if (METHOD_GET_CEC.equals(method)) {
            for (String k : CEC_KEYS) b.putBoolean(k, Prefs.get(getContext(), k));
            return b;
        }
        if (METHOD_SET_CEC.equals(method)) {
            boolean known = false;
            for (String k : CEC_KEYS) known |= k.equals(arg);
            if (!known || extras == null || !extras.containsKey("value")) {
                b.putBoolean("ok", false);
                return b;
            }
            boolean v = extras.getBoolean("value");
            Prefs.set(getContext(), arg, v);
            Log.i(TAG, "CEC pref " + arg + " = " + v + " (projector panel)");
            b.putBoolean("ok", true);
            return b;
        }
        int n = 0, ns = 0;
        int[] tmp = new int[sCount.length];
        int[] tmpS = new int[sStreams.length];
        synchronized (sCount) {
            for (int p = 0; p < sCount.length; p++) if (sCount[p] > 0) tmp[n++] = p;
            for (int p = 0; p < sStreams.length; p++) if (sStreams[p] > 0) tmpS[ns++] = p;
        }
        int[] ports = new int[n];
        System.arraycopy(tmp, 0, ports, 0, n);
        int[] streams = new int[ns];
        System.arraycopy(tmpS, 0, streams, 0, ns);
        b.putBoolean("active", n > 0);
        b.putIntArray("ports", ports);
        b.putIntArray("streams", streams);
        return b;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
