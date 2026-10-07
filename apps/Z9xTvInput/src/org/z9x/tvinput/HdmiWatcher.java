/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiTvClient;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lives in the persistent app process and turns HDMI events into "open the HDMI viewer".
 * It only registers passive callbacks with system_server and never calls a HAL itself; the viewer
 * it opens is what acquires the hardware.
 *
 * <ul>
 *   <li><b>Plug</b>: TvInputManager.TvInputCallback.onInputStateChanged() for our port inputs
 *       (state from TvInputHardwareManager: TV HAL cable status and HdmiControlService hotplug).
 *       A change from anything else to INPUT_STATE_CONNECTED that is still CONNECTED after
 *       {@value #PLUG_DEBOUNCE_MS} ms opens the viewer.</li>
 *   <li><b>CEC One Touch Play</b>: HdmiTvClient.InputChangeListener (&lt;Active Source&gt;).</li>
 *   <li><b>Open on boot</b> (setting, off by default): once per boot, after the settle window.</li>
 * </ul>
 * Rules (all paths): nothing during the first {@value #START_SETTLE_MS} ms after process start;
 * nothing while the screen is off; v6.5: nothing while org.z9x.projector is in its lamp-only standby
 * (lamp off, Android awake: HdmiInputs.isProjectorStandby asks its StandbyStateProvider at decision
 * time, and its STANDBY_CHANGED broadcast cancels pending switches at once), because the viewer and
 * its TIF hardware audio patch would play behind the black standby screen, and an AVR / console
 * re-asserting HPD must never light the projector (Lumen OS 1.0: a CEC source's &lt;Active Source&gt;
 * may, only through the projector's own decision, see handleCecInputChange); nothing
 * before setup is complete; never re-open the input that is already on screen. Plug only: ignored within {@value #SCREEN_ON_GRACE_MS} ms after SCREEN_ON
 * (HPD bounces on wake) and when the only CEC device on the port is an audio system (soundbar).
 * CEC only: ignored within {@value #OWN_ROUTING_GUARD_MS} ms after our own portSelect().
 * Unplug handling (return to home) is done by {@link PassthroughActivity}.
 *
 * <p>Threading: everything runs on the main thread (callbacks are registered with a main-thread
 * Handler; the CEC listener, which arrives on a binder thread, posts to it). Every entry point is
 * crash-shielded.
 */
final class HdmiWatcher {
    private static final String TAG = "Z9xHdmiWatcher";

    /** A plug must stay CONNECTED this long before we switch (HPD bounces during EDID/HDCP). */
    static final long PLUG_DEBOUNCE_MS = 2000;
    /** Ignore everything right after the process (= boot) starts: HAL and CEC states settle. */
    static final long START_SETTLE_MS = 30000;
    /** HPD may bounce when the projector leaves standby; that is not a user "plug". */
    static final long SCREEN_ON_GRACE_MS = 10000;
    /** CEC &lt;Active Source&gt; bursts are coalesced; the screen may still be waking up. */
    static final long CEC_DEBOUNCE_MS = 2000;
    /** Routing that our own portSelect() triggered comes back through the same listener. */
    static final long OWN_ROUTING_GUARD_MS = 10000;
    private static final long BOOT_CHECK_DELAY_MS = START_SETTLE_MS + 2000;

    private static final String REASON_PLUG = "plug";
    private static final String REASON_CEC = "cec";
    /** Lumen OS 1.0: the projector left its standby for this CEC source (CecPolicy accepted the wake). */
    private static final String REASON_CEC_WAKE = "cec_wake";
    private static final String REASON_BOOT = "boot";

    private static HdmiWatcher sInstance;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final long mStartedAt = SystemClock.uptimeMillis();
    // ---- main-thread state ----
    private final Map<String, Integer> mStates = new ArrayMap<>();
    private final Map<String, Runnable> mPendingPlug = new ArrayMap<>();
    private Runnable mPendingCec;
    private long mScreenOnAt;
    private boolean mCecRegistered;
    private TvInputManager mTim;

    /** v6.5: org.z9x.projector entered / left its lamp-only standby (signature-permission broadcast). */
    private final BroadcastReceiver mStandbyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Safe.run(TAG, "standby broadcast", () -> {
                boolean on = intent != null && intent.getBooleanExtra("standby", false);
                Log.i(TAG, "projector standby " + (on ? "entered" : "left"));
                if (on) cancelAllPending("projector standby");
            });
        }
    };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Safe.run(TAG, "screen broadcast", () -> {
                String action = intent == null ? null : intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    mScreenOnAt = SystemClock.uptimeMillis();
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    cancelAllPending("screen off");
                }
            });
        }
    };

    private final TvInputManager.TvInputCallback mCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            Safe.run(TAG, "onInputAdded", () -> {
                if (!HdmiInputs.isOurPortInput(mContext, inputId)) return;
                // TvInputManager caches CONNECTED for a just-added input; the real state follows via
                // onInputStateChanged (TvInputHardwareManager.addHardwareInput posts it). A source
                // already plugged in at boot therefore does NOT count as a plug.
                mStates.put(inputId, HdmiInputs.stateOf(mContext, inputId));
                Log.i(TAG, "input added " + inputId + " state=" + mStates.get(inputId));
            });
        }

        @Override
        public void onInputRemoved(String inputId) {
            Safe.run(TAG, "onInputRemoved", () -> {
                mStates.remove(inputId);
                cancelPlug(inputId);
            });
        }

        @Override
        public void onInputStateChanged(String inputId, int state) {
            Safe.run(TAG, "onInputStateChanged", () -> handleState(inputId, state));
        }
    };

    /** Invoked by HdmiControlService on a binder thread. */
    private final HdmiTvClient.InputChangeListener mCecListener = info -> {
        try {
            mHandler.post(Safe.wrap(TAG, "CEC input change", () -> handleCecInputChange(info)));
        } catch (Throwable t) {
            Log.w(TAG, "CEC listener post failed: " + t);
        }
    };

    static synchronized void start(Context appContext) {
        if (sInstance != null) return;
        sInstance = new HdmiWatcher(appContext);
        sInstance.init();
    }

    private HdmiWatcher(Context c) {
        mContext = c.getApplicationContext();
    }

    private void init() {
        mTim = HdmiInputs.tim(mContext);
        if (mTim == null) {
            Log.w(TAG, "no TvInputManager (android.software.live_tv missing?)");
            return;
        }
        try {
            mTim.registerCallback(mCallback, mHandler);
        } catch (Throwable t) {
            Log.e(TAG, "registerCallback failed", t);
            return;
        }
        for (TvInputInfo info : HdmiInputs.portInputs(mContext)) {
            mStates.put(info.getId(), HdmiInputs.stateOf(mContext, info.getId()));
        }
        Log.i(TAG, "started, inputs=" + mStates);
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            mContext.registerReceiver(mScreenReceiver, f, null, mHandler, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "screen receiver failed: " + t);
        }
        try {
            // only org.z9x.projector (holder of the signature permission) can send it
            mContext.registerReceiver(mStandbyReceiver, new IntentFilter(HdmiInputs.ACTION_PROJECTOR_STANDBY_CHANGED),
                    HdmiInputs.PERMISSION_PROJECTOR_STANDBY, mHandler, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "standby receiver failed: " + t);
        }
        // setInputChangeListener only stores the listener in system_server (no CEC traffic).
        // HdmiControlManager may have no TV client very early; retry a couple of times.
        mHandler.post(Safe.wrap(TAG, "registerCec", this::registerCec));
        mHandler.postDelayed(Safe.wrap(TAG, "registerCec", this::registerCec), 15000);
        mHandler.postDelayed(Safe.wrap(TAG, "registerCec", this::registerCec), 60000);
        mHandler.postDelayed(Safe.wrap(TAG, "bootCheck", this::bootCheck), BOOT_CHECK_DELAY_MS);
    }

    private void registerCec() {
        if (mCecRegistered) return;
        mCecRegistered = CecHelper.setInputChangeListener(mContext, mCecListener);
        Log.i(TAG, "CEC input change listener registered=" + mCecRegistered);
    }

    // ------------------------------------------------------------------ plug

    private void handleState(String inputId, int state) {
        if (!HdmiInputs.isOurPortInput(mContext, inputId)) return;
        Integer prev = mStates.put(inputId, state);
        Log.i(TAG, "state " + inputId + ": " + prev + " -> " + state);
        if (state != TvInputManager.INPUT_STATE_CONNECTED) {
            cancelPlug(inputId);
            return;
        }
        if (prev != null && prev == TvInputManager.INPUT_STATE_CONNECTED) return;
        // Rules are applied at event time (a plug inside a window is dropped, not postponed) and
        // again when the debounce expires.
        String skip = commonSkipReason(true);
        if (skip != null) {
            Log.i(TAG, "ignore plug of " + inputId + ": " + skip);
            return;
        }
        if (!Prefs.get(mContext, Prefs.AUTO_SWITCH)) {
            Log.i(TAG, "ignore plug of " + inputId + ": auto-switch is off");
            return;
        }
        cancelPlug(inputId);
        Runnable r = Safe.wrap(TAG, "plug " + inputId, () -> {
            mPendingPlug.remove(inputId);
            tryOpen(inputId, REASON_PLUG);
        });
        mPendingPlug.put(inputId, r);
        mHandler.postDelayed(r, PLUG_DEBOUNCE_MS);
    }

    private void cancelPlug(String inputId) {
        Runnable r = mPendingPlug.remove(inputId);
        if (r != null) mHandler.removeCallbacks(r);
    }

    private void cancelAllPending(String why) {
        if (!mPendingPlug.isEmpty() || mPendingCec != null) {
            Log.i(TAG, "cancel pending switches: " + why);
        }
        List<String> ids = new ArrayList<>(mPendingPlug.keySet());
        for (String id : ids) cancelPlug(id);
        if (mPendingCec != null) {
            mHandler.removeCallbacks(mPendingCec);
            mPendingCec = null;
        }
    }

    /** Null when an automatic switch is allowed right now. */
    private String commonSkipReason(boolean screenOnGrace) {
        long now = SystemClock.uptimeMillis();
        if (now - mStartedAt < START_SETTLE_MS) return "first 30 s after process start";
        if (!HdmiInputs.isInteractive(mContext)) return "screen is off";
        if (HdmiInputs.isProjectorStandby(mContext)) return "projector in standby (lamp off)";
        if (screenOnGrace && mScreenOnAt != 0 && now - mScreenOnAt < SCREEN_ON_GRACE_MS) {
            return "within 10 s after screen on";
        }
        if (!HdmiInputs.isSetupComplete(mContext)) return "setup not complete";
        return null;
    }

    private void tryOpen(String inputId, String reason) {
        int state = HdmiInputs.stateOf(mContext, HdmiInputs.stateInputId(mContext, inputId));
        if (REASON_CEC.equals(reason) || REASON_CEC_WAKE.equals(reason)) {
            // A source that just sent <Active Source> is on; accept STANDBY (HPD-less sources).
            if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
                Log.i(TAG, "skip " + reason + " " + inputId + ": input disconnected");
                return;
            }
        } else if (state != TvInputManager.INPUT_STATE_CONNECTED) {
            Log.i(TAG, "skip " + reason + " " + inputId + ": not connected any more");
            return;
        }
        String skip = commonSkipReason(REASON_PLUG.equals(reason));
        if (skip != null) {
            Log.i(TAG, "skip " + reason + " " + inputId + ": " + skip);
            return;
        }
        if (REASON_PLUG.equals(reason)) {
            int port = HdmiInputs.portOf(HdmiInputs.info(mContext, inputId));
            if (port > 0 && CecHelper.isAudioSystemOnlyPort(mContext, port)) {
                Log.i(TAG, "skip plug: only a CEC audio system (soundbar/AVR) on port " + port);
                return;
            }
        }
        String showing = PassthroughActivity.sShowingInputId;
        if (inputId.equals(showing)) {
            Log.i(TAG, "skip " + reason + ": already showing " + inputId);
            return;
        }
        if (showing != null && !REASON_PLUG.equals(reason)
                && HdmiInputs.stateInputId(mContext, inputId).equals(HdmiInputs.stateInputId(mContext, showing))) {
            // Lumen OS 1.0: the port input vs. its CEC child input: the same picture, no re-tune
            Log.i(TAG, "skip " + reason + ": already showing that HDMI port (" + showing + ")");
            return;
        }
        HdmiInputs.openViewer(mContext, inputId, reason);
    }

    // ------------------------------------------------------------------ CEC One Touch Play

    /**
     * HdmiCecLocalDeviceTv.updateActiveInput -> HdmiControlService.invokeInputChangeListener.
     * Lumen OS 1.0 (cec spec 3.1, PLAN C14): nothing before setup is complete; own routing ignored;
     * with the projector in its lamp-only standby (STR blocked, Android awake) a CEC SOURCE asks the
     * projector (StandbyStateProvider "cec_wake": setting, night guard, storm limit) and its input opens
     * only when the projector accepted; otherwise (projector on) the v6.x One Touch Play switch, now to the
     * device's CEC child input. Audio systems and unregistered sources never wake or switch. From STR the
     * projector wakes through PM51 (cec0) and this listener only sees the source's &lt;Active Source&gt;
     * after Android is up.
     */
    private void handleCecInputChange(HdmiDeviceInfo info) {
        Log.i(TAG, "CEC input change: " + info);
        if (info == null) return;
        // INACTIVE_DEVICE / internal source (port <= 0): we never yank the user away.
        int port = info.getPortId();
        if (port <= 0) return;
        long since = SystemClock.uptimeMillis() - CecHelper.lastPortSelectAt();
        if (CecHelper.lastPortSelectAt() != 0 && since < OWN_ROUTING_GUARD_MS) {
            Log.i(TAG, "ignore CEC input change: caused by our own routing " + since + " ms ago");
            return;
        }
        if (!HdmiInputs.isSetupComplete(mContext)) {
            Log.i(TAG, "ignore CEC input change: setup not complete");
            return;
        }
        boolean source = info.isCecDevice() && HdmiInputService.isSourceType(info.getDeviceType())
                && info.getLogicalAddress() > 0 && info.getLogicalAddress() < 15;
        String portInput = HdmiInputs.findInputIdForPort(mContext, port);
        if (portInput == null) return;
        String child = source ? HdmiInputs.findChildInputId(mContext, info.getId()) : null;
        final String inputId = child != null ? child : portInput;
        String reason = REASON_CEC;
        if (HdmiInputs.isProjectorStandby(mContext)) {
            if (!source) {
                Log.i(TAG, "ignore CEC input change during projector standby: not a source device");
                return;
            }
            if (HdmiInputs.stateOf(mContext, portInput) == TvInputManager.INPUT_STATE_DISCONNECTED) {
                Log.i(TAG, "ignore CEC input change during projector standby: HDMI " + port + " disconnected");
                return;
            }
            if (CrashGuard.isSafeMode()) {
                Log.i(TAG, "ignore CEC input change during projector standby: safe mode");
                return;
            }
            if (!HdmiInputs.projectorCecWake(mContext, info)) {
                Log.i(TAG, "CEC wake refused by the projector");
                return;
            }
            Log.i(TAG, "CEC wake accepted by the projector: opening " + inputId);
            reason = REASON_CEC_WAKE;
        } else if (!Prefs.get(mContext, Prefs.CEC_ONE_TOUCH_PLAY)) {
            return;
        }
        if (mPendingCec != null) mHandler.removeCallbacks(mPendingCec);
        final String why = reason;
        final Runnable r = Safe.wrap(TAG, "cec " + inputId, () -> {
            mPendingCec = null;
            tryOpen(inputId, why);
        });
        mPendingCec = r;
        // the projector's lamp / screen may still be waking up
        mHandler.postDelayed(r, CEC_DEBOUNCE_MS);
    }

    // ------------------------------------------------------------------ open on boot

    /** Optional: once per boot, open the connected HDMI input (last used one first). */
    private void bootCheck() {
        if (!Prefs.get(mContext, Prefs.OPEN_ON_BOOT)) return;
        String bootId = CrashGuard.bootId();
        if (!"unknown".equals(bootId)
                && bootId.equals(Prefs.getString(mContext, Prefs.BOOT_OPENED_FOR))) {
            return; // the process restarted within the same boot
        }
        String pick = null;
        String last = Prefs.getString(mContext, Prefs.LAST_INPUT);
        if (last != null && HdmiInputs.isOurPortInput(mContext, last)
                && HdmiInputs.stateOf(mContext, last) == TvInputManager.INPUT_STATE_CONNECTED) {
            pick = last;
        } else {
            for (TvInputInfo i : HdmiInputs.portInputs(mContext)) {
                if (HdmiInputs.stateOf(mContext, i.getId()) == TvInputManager.INPUT_STATE_CONNECTED) {
                    pick = i.getId();
                    break;
                }
            }
        }
        if (!"unknown".equals(bootId)) Prefs.setString(mContext, Prefs.BOOT_OPENED_FOR, bootId);
        if (pick != null) tryOpen(pick, REASON_BOOT);
    }
}
