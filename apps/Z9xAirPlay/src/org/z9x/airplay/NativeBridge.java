/*
 * Z9xAirPlay - AirPlay receiver for the XGIMI Z9X.
 * Java side of libz9xairplay.so (jni/glue/z9x_jni.cpp). The natives are bound with
 * RegisterNatives in JNI_OnLoad: names and signatures here must match its table exactly,
 * otherwise System.loadLibrary fails.
 *
 * Ported from jqssun/android-airplay-server v0.0.31 NativeBridge.kt + RaopCallbackHandler.kt
 * (GPL-3.0), rewritten for the Z9xAirPlay native API.
 *
 * Copyright (C) jqssun / android-airplay-server contributors
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.z9x.airplay;

import android.view.Surface;

final class NativeBridge {
    private NativeBridge() {}

    /* Event codes of Callback.onEvent (jni/glue/z9x_common.h Z9X_EV_*). */
    static final int EV_CONN_INIT = 1;       // a = open connections
    static final int EV_CONN_DESTROY = 2;    // a = connections left (0 = sender gone)
    static final int EV_CONN_RESET = 3;      // a = reason (RESET_NO_FEEDBACK: watchdog); Java calls disconnectAll
    static final int EV_MIRROR_ON = 4;
    static final int EV_MIRROR_OFF = 5;
    static final int EV_AUDIO_FORMAT = 6;    // a = ct (2 ALAC, 4 AAC-LC, 8 AAC-ELD), b = 1 if part of mirroring
    static final int EV_AUDIO_TEARDOWN = 7;
    static final int EV_VIDEO_SIZE = 8;      // a = width, b = height
    static final int EV_FIRST_FRAME = 9;
    static final int EV_VOLUME = 10;         // a = sender volume in 1/100 dB (-14400 = mute)
    static final int EV_PIN_LOCKOUT = 11;    // a = seconds PIN pairing stays locked (wrong codes)

    static final int RESET_NO_FEEDBACK = 100;  // EV_CONN_RESET reason: no /feedback for 15 s

    static final int PIN_OFF = 0;
    static final int PIN_RANDOM = 1;
    static final int PIN_FIXED = 2;

    static final int SERVICE_RAOP = 0;
    static final int SERVICE_AIRPLAY = 1;

    private static boolean sLoaded;
    private static int sLoadFailures;

    /**
     * Loads libz9xairplay.so; false (and logged) if it cannot be loaded. A failure is not
     * cached: AirPlayService retries the start with back-off, and every retry tries again
     * (a library whose JNI_OnLoad failed keeps failing, which is then just logged again).
     */
    static synchronized boolean load() {
        if (sLoaded) return true;
        try {
            System.loadLibrary("z9xairplay");
            sLoaded = true;
            if (sLoadFailures > 0) android.util.Log.i("Z9xAirPlay", "libz9xairplay loaded after " + sLoadFailures + " failure(s)");
        } catch (Throwable t) {
            sLoadFailures++;
            if (sLoadFailures == 1) android.util.Log.e("Z9xAirPlay", "cannot load libz9xairplay", t);
            else android.util.Log.e("Z9xAirPlay", "cannot load libz9xairplay (attempt " + sLoadFailures + "): " + t);
        }
        return sLoaded;
    }

    /**
     * Called on UxPlay / decoder threads. Implementations only post to a Handler (never block,
     * never call back into NativeBridge synchronously). The native side looks the methods up
     * on the object's class, so the implementing class declares every one of them.
     */
    interface Callback {
        void onEvent(int what, int a, int b);
        void onPin(String pin);
        void onMetadata(byte[] dmap);
        void onCoverArt(byte[] image);
        void onProgress(long start, long current, long end);
        void onDacp(String dacpId, String activeRemote);
        void onVideoPlay(String url, float startSec);
        void onVideoScrub(float sec);
        void onVideoRate(float rate);
        void onVideoStop();
        boolean isTrustedClient(String pk);
        void onTrustClient(String deviceId, String pk, String name);
    }

    static native long create(Callback cb, byte[] hwAddr6, String name, String keyFile,
                              int pinMode, int fixedPin, boolean nohold, boolean hevc4k,
                              boolean videoUrl, int width, int height, int fps, int latencyMs);

    /** Starts the RTSP/HTTP server; returns the bound port or -1. */
    static native int start(long h, int port);

    /** TXT pairs [k0, v0, k1, v1, ...] of SERVICE_RAOP or SERVICE_AIRPLAY. */
    static native String[] txt(long h, int service);

    /** "<12 hex>@<name>" for _raop._tcp. */
    static native String raopServiceName(long h);

    /** Display surface for mirroring; null detaches (the decoder keeps running). Blocks until applied. */
    static native void setSurface(long h, Surface s);

    static native void disconnectAll(long h);

    static native void setOutputMuted(long h, boolean muted);

    static native void updatePlaybackInfo(long h, float pos, float dur, float rate, boolean ready);

    /** False once the native RTSP/HTTP server thread has exited (it then accepts nothing). */
    static native boolean isRunning(long h);

    static native void stop(long h);

    static native void destroy(long h);
}
