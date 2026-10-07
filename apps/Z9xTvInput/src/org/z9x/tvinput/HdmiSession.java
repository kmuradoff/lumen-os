/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.content.Context;
import android.media.tv.TvInputHardwareInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.media.tv.TvStreamConfig;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;

/**
 * One TvView <-> one HDMI port. Video goes through the vendor sideband stream:
 * Hardware.setSurface(surface, config) -> TvInputHardwareImpl -> TvInputHal.addOrUpdateStream ->
 * ITvInput.openStream() -> Surface.setSidebandStream(). TvInputHardwareImpl also creates the
 * "HDMI In" -> STREAM_MUSIC sinks audio patch while a stream is active (mActiveConfig != null).
 *
 * <p>Threading (checked against Lineage 21 TvInputManager / TvInputService / TvInputHardwareManager):
 * <ul>
 *   <li>Session callbacks (onSetSurface, onTune, onSetMain, onRelease, keys) arrive on the main thread.</li>
 *   <li>HardwareCallback (3-arg acquireTvInputHardware = inline executor) arrives on a binder thread.</li>
 *   <li>Every TvInputManager.Hardware call and all hardware state live on {@link HdmiInputService#hwHandler()}.</li>
 *   <li>notifyVideoAvailable/notifyVideoUnavailable/notifyTuned may be called from the worker: the
 *       base class posts them to the main thread (executeOrPostRunnableOnMainThread).</li>
 * </ul>
 * Every runnable and callback is crash-shielded (persistent process).
 */
final class HdmiSession extends TvInputService.Session {
    private static final String TAG = "Z9xHdmiSession";

    /** Main-thread only: the session that the framework last made "main" (TvView.setMain). */
    private static HdmiSession sMainSession;

    private final Context mContext;
    private final String mInputId;
    private final TvInputInfo mInfo;
    private final int mDeviceId;
    private final int mPortId;
    /** Lumen OS 1.0: HdmiDeviceInfo id of the CEC device of a child input (routing by deviceSelect), -1 = port input. */
    private final int mCecDeviceId;
    private final Handler mWorker = HdmiInputService.hwHandler();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    // ---- worker-thread state ----
    private TvInputManager.Hardware mHardware;
    private TvStreamConfig[] mConfigs = new TvStreamConfig[0];
    private Surface mSurface;
    private float mVolume = 1.0f;
    private boolean mReleased;
    private boolean mTuned;
    private boolean mStreaming;
    /** This session is counted as streaming in HdmiStateProvider (worker thread). */
    private boolean mCountedStreaming;
    /** Another acquirer took the device (no TunerResourceManager on this build: latest acquire wins). */
    private boolean mLostToOtherClient;
    /** This session is counted in HdmiStateProvider (holds the hardware, not released). */
    private boolean mCounted;

    // ---- main-thread state ----
    private boolean mKeyForwarded;

    /** Callbacks arrive on a system_server binder thread (oneway ITvInputHardwareCallback). */
    private final TvInputManager.HardwareCallback mHardwareCallback =
            new TvInputManager.HardwareCallback() {
                @Override
                public void onReleased() {
                    // Our own releaseTvInputHardware() also lands here (mReleased is already set
                    // then); otherwise a newer acquirer of the same deviceId took it. Do not fight.
                    post("onReleased", () -> {
                        if (mReleased) return;
                        Log.w(TAG, mInputId + ": hardware taken by another client");
                        mHardware = null;
                        setStreaming(false);
                        mLostToOtherClient = true;
                        updateCounted();
                        notifyState();
                    });
                }

                @Override
                public void onStreamConfigChanged(TvStreamConfig[] configs) {
                    final TvStreamConfig[] copy =
                            configs == null ? new TvStreamConfig[0] : configs.clone();
                    post("onStreamConfigChanged", () -> {
                        if (mReleased) return;
                        Log.i(TAG, mInputId + ": stream configs " + describe(copy));
                        mConfigs = copy;
                        // JNI already closed every open stream of this device and bumped the config
                        // generation (JTvInputHal onStreamConfigurationsChanged); reopen below.
                        setStreaming(false);
                        apply("configs");
                    });
                }
            };

    /**
     * @param info the PORT input's info (hardware acquisition); for a CEC child input {@code inputId} is
     *             the child's id and {@code cecDeviceId} its HdmiDeviceInfo id, else -1.
     */
    HdmiSession(Context context, String inputId, TvInputInfo info, TvInputHardwareInfo hw, int cecDeviceId) {
        super(context);
        mContext = context.getApplicationContext();
        mInputId = inputId;
        mInfo = info;
        mDeviceId = hw.getDeviceId();
        mPortId = hw.getHdmiPortId();
        mCecDeviceId = cecDeviceId;
        post("acquire", this::acquire);
    }

    private void post(String what, Runnable r) {
        try {
            mWorker.post(Safe.wrap(TAG, mInputId + " " + what, r));
        } catch (Throwable t) {
            Log.e(TAG, mInputId + ": post " + what + " failed", t);
        }
    }

    // ------------------------------------------------------------------ worker thread

    private void acquire() {
        if (mReleased || mHardware != null) return;
        TvInputManager tim = HdmiInputs.tim(mContext);
        if (tim == null) return;
        try {
            // 3-arg SystemApi variant: inline executor, PRIORITY_HINT_USE_CASE_TYPE_LIVE, no session id
            // (only TunerResourceManager would look at it, and TRM is not started without FEATURE_TUNER).
            // Connection.resetLocked() immediately replays the current configs (onStreamConfigChanged).
            mHardware = tim.acquireTvInputHardware(mDeviceId, mInfo, mHardwareCallback);
        } catch (Throwable t) {
            Log.e(TAG, mInputId + ": acquireTvInputHardware failed", t);
            mHardware = null;
        }
        Log.i(TAG, mInputId + ": acquired hardware deviceId=" + mDeviceId + " -> " + mHardware);
        updateCounted();
        if (mHardware == null) {
            notifyState();
            return;
        }
        mLostToOtherClient = false;
        // TvInputHardwareImpl starts with mSourceVolume = 0.0f and the "HDMI In" port has a
        // MODE_JOINT gain, so without this the HDMI audio patch would be silent.
        setVolumeSafe(mVolume);
        apply("acquired");
    }

    /**
     * Worker thread: mStreaming plus HdmiStateProvider's per-port "streams" count (Lumen OS 1.0: the
     * projector's CEC unattended check asks whether an HDMI picture is up).
     */
    private void setStreaming(boolean on) {
        mStreaming = on;
        boolean want = on && !mReleased;
        if (want == mCountedStreaming) return;
        mCountedStreaming = want;
        HdmiStateProvider.onSessionStreaming(mPortId, want);
    }

    /** Worker thread: keep HdmiStateProvider's open-session count in line with mHardware. */
    private void updateCounted() {
        boolean want = mHardware != null && !mReleased;
        if (want == mCounted) return;
        mCounted = want;
        HdmiStateProvider.onSessionHardware(mPortId, want);
        ProjectorEvents.send(mContext, mPortId, want, "session");   // v6.2 game module (org.z9x.projector)
    }

    private static TvStreamConfig pickConfig(TvStreamConfig[] configs) {
        if (configs == null || configs.length == 0) return null;
        for (TvStreamConfig c : configs) {
            if (c != null && c.getType() == TvStreamConfig.STREAM_TYPE_INDEPENDENT_VIDEO_SOURCE) {
                return c;
            }
        }
        for (TvStreamConfig c : configs) {
            if (c != null) return c;
        }
        return null;
    }

    private boolean setSurfaceSafe(Surface surface, TvStreamConfig config) {
        if (mHardware == null) return false;
        try {
            return mHardware.setSurface(surface, config);
        } catch (IllegalStateException e) {
            // "Device already released." after another client took it.
            Log.w(TAG, mInputId + ": setSurface: " + e.getMessage());
            mHardware = null;
            mLostToOtherClient = true;
            updateCounted();
            return false;
        } catch (Throwable t) {
            Log.w(TAG, mInputId + ": setSurface failed: " + t);
            return false;
        }
    }

    private void setVolumeSafe(float volume) {
        if (mHardware == null) return;
        try {
            mHardware.setStreamVolume(volume);
        } catch (Throwable t) {
            Log.w(TAG, mInputId + ": setStreamVolume failed: " + t);
        }
    }

    /** Bring the hardware stream in line with (surface, configs). Idempotent. */
    private void apply(String why) {
        if (mReleased || mHardware == null) {
            notifyState();
            return;
        }
        if (mSurface == null) {
            if (mStreaming) {
                setSurfaceSafe(null, null);
                setStreaming(false);
            }
            notifyState();
            return;
        }
        if (!mSurface.isValid()) {
            // The framework releases the previous Surface right after onSetSurface returns; a
            // newer setSurface is already queued behind us and will apply the live one.
            Log.i(TAG, mInputId + ": apply(" + why + ") skipped: surface already released");
            return;
        }
        TvStreamConfig config = pickConfig(mConfigs);
        if (config == null) {
            // Cable out (the XGIMI HAL reports 0 configs while disconnected). setSurface(null)
            // also clears a stale TvInputHardwareImpl.mActiveConfig from the old generation
            // (returns true at once when there is none).
            setSurfaceSafe(null, null);
            setStreaming(false);
            notifyState();
            return;
        }
        setVolumeSafe(mVolume);
        boolean ok = setSurfaceSafe(mSurface, config);
        if (!ok && mHardware != null) {
            // After a hotplug the first call fails with ERROR_STALE_CONFIG while it drops the old
            // mActiveConfig (TvInputHardwareImpl.setSurface); the second call opens the stream.
            ok = setSurfaceSafe(mSurface, config);
        }
        setStreaming(ok);
        Log.i(TAG, mInputId + ": apply(" + why + ") stream=" + config.getStreamId()
                + " " + config.getMaxWidth() + "x" + config.getMaxHeight()
                + " gen=" + config.getGeneration() + " -> " + ok);
        notifyState();
    }

    private void notifyState() {
        if (!mTuned || mReleased) return;
        if (mStreaming) {
            notifyVideoAvailable();
        } else if (mHardware == null) {
            notifyVideoUnavailable(mLostToOtherClient
                    ? TvInputManager.VIDEO_UNAVAILABLE_REASON_INSUFFICIENT_RESOURCE
                    : TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
        } else if (pickConfig(mConfigs) == null) {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_NOT_CONNECTED);
        } else if (mSurface == null) {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
        } else {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
        }
    }

    private void releaseOnWorker() {
        if (mReleased) return;
        mReleased = true;
        TvInputManager.Hardware hw = mHardware;
        if (hw != null) {
            // Close the HAL stream first: TvInputHardwareImpl.release() only drops the audio patch
            // and leaves the stream open in TvInputHal/JNI.
            try {
                hw.setSurface(null, null);
            } catch (Throwable t) {
                Log.w(TAG, mInputId + ": setSurface(null) on release failed: " + t);
            }
            try {
                TvInputManager tim = HdmiInputs.tim(mContext);
                if (tim != null) tim.releaseTvInputHardware(mDeviceId, hw);
            } catch (Throwable t) {
                Log.w(TAG, mInputId + ": releaseTvInputHardware failed: " + t);
            }
        }
        mHardware = null;
        mSurface = null;
        setStreaming(false);
        updateCounted();
        Log.i(TAG, mInputId + ": released");
    }

    private static String describe(TvStreamConfig[] configs) {
        StringBuilder sb = new StringBuilder("[");
        for (TvStreamConfig c : configs) {
            if (c == null) continue;
            sb.append("{id=").append(c.getStreamId()).append(" type=").append(c.getType())
                    .append(' ').append(c.getMaxWidth()).append('x').append(c.getMaxHeight())
                    .append(" gen=").append(c.getGeneration()).append('}');
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ framework callbacks (main thread)

    @Override
    public boolean onSetSurface(Surface surface) {
        post("setSurface", () -> {
            mSurface = surface;
            apply(surface == null ? "surface-null" : "surface");
        });
        return true;
    }

    @Override
    public boolean onTune(Uri channelUri) {
        post("tune", () -> {
            if (mReleased) return;
            mTuned = true;
            if (mHardware == null && !mLostToOtherClient) {
                acquire(); // first acquire failed (e.g. HAL device not ready yet): try once more
            } else if (!mStreaming) {
                apply("tune");
            } else {
                notifyState();
            }
            if (channelUri != null) notifyTuned(channelUri);
        });
        return true;
    }

    @Override
    public void onSetStreamVolume(float volume) {
        post("setStreamVolume", () -> {
            mVolume = volume;
            setVolumeSafe(volume);
        });
    }

    @Override
    public void onSetCaptionEnabled(boolean enabled) {
        // HDMI passthrough has no caption tracks.
    }

    @Override
    public void onSetMain(boolean isMain) {
        Safe.run(TAG, mInputId + " onSetMain", () -> {
            if (isMain) {
                sMainSession = this;
                mMain.removeCallbacks(mInternalSelect);
                if (Prefs.get(mContext, Prefs.CEC_CONTROL)) {
                    final int port = mPortId;
                    final int dev = mCecDeviceId;
                    final Context c = mContext;
                    if (dev >= 0) {
                        // CEC child input: route to that device (<Set Stream Path>, also behind an AVR)
                        post("deviceSelect", () -> CecHelper.deviceSelect(c, dev, port));
                    } else {
                        // CEC routing to this port (<Routing Change>): wakes/activates the source.
                        post("portSelect", () -> CecHelper.portSelect(c, port));
                    }
                }
            } else if (sMainSession == this) {
                sMainSession = null;
                scheduleInternalSelect();
            }
        });
    }

    @Override
    public void onRelease() {
        Safe.run(TAG, mInputId + " onRelease", () -> {
            if (sMainSession == this) {
                sMainSession = null;
                scheduleInternalSelect();
            }
        });
        post("release", this::releaseOnWorker);
    }

    private final Runnable mInternalSelect = Safe.wrap(TAG, "internalSelect", this::runInternalSelect);

    private void runInternalSelect() {
        // Only if none of our HDMI sessions became main meanwhile (e.g. switch HDMI 1 -> HDMI 2).
        if (sMainSession == null) {
            final Context c = mContext;
            post("selectInternal", () -> CecHelper.selectInternal(c));
        }
    }

    private void scheduleInternalSelect() {
        if (!Prefs.get(mContext, Prefs.CEC_INTERNAL_ON_EXIT)) return;
        mMain.removeCallbacks(mInternalSelect);
        mMain.postDelayed(mInternalSelect, 1000);
    }

    // ------------------------------------------------------------------ keys (main thread)

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        try {
            if (event == null || !shouldForward(keyCode)) return false;
            if (event.getRepeatCount() == 0) {
                // HdmiCecLocalDevice SendKeyAction repeats <User Control Pressed> itself until released.
                mKeyForwarded = CecHelper.sendKey(mContext, keyCode, true);
            }
            return mKeyForwarded;
        } catch (Throwable t) {
            Log.w(TAG, "onKeyDown failed: " + t);
            return false;
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        try {
            if (!shouldForward(keyCode)) return false;
            boolean sent = CecHelper.sendKey(mContext, keyCode, false);
            mKeyForwarded = false;
            return sent;
        } catch (Throwable t) {
            Log.w(TAG, "onKeyUp failed: " + t);
            return false;
        }
    }

    private boolean shouldForward(int keyCode) {
        return CecHelper.isForwardableKey(keyCode)
                && Prefs.get(mContext, Prefs.CEC_CONTROL)
                && CecHelper.hasCecSourceOnPort(mContext, mPortId);
    }
}
