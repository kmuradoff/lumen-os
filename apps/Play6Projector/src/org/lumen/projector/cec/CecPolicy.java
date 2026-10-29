package org.lumen.projector.cec;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import org.lumen.projector.Ui;
import org.lumen.projector.power.StandbyController;

import java.util.ArrayDeque;

/**
 * The XGIMI Play 6 class behind the HDMI-CEC seams of the shared code (docs/play6/SETTINGS_PLAN.md 1.5, 2.4:
 * RemotePrompt, StandbyStateProvider). Not a copy of the Z9X CecPolicy (PM51 cec0 arming, night guard,
 * unattended return): the Play 6 has no STR by default since beta 2 (power/PowerKey), so a CEC wake here only
 * ends the lamp-only standby; an Android sleep is woken by the framework itself (tv_wake_on_one_touch_play).
 *
 * Stock parity 2026-10-21 ("HDMI device controls the projector", stock signal_auto_startup): one switch, the
 * framework's "Turn on with an HDMI device" (HdmiControlManager tv_wake_on_one_touch_play, shown in Projector
 * settings), the same value the framework uses while Android sleeps. IGmpf 645 / 646 (the vendor MCU wake
 * source) are not used: they only matter for STR, which stays off.
 */
public final class CecPolicy {
    private static final String TAG = "P6Cec";
    /** At most this many CEC wakes in {@link #WINDOW_MS}; more are ignored until the window passes (storm guard). */
    private static final int MAX_WAKES = 3;
    private static final long WINDOW_MS = 10 * 60_000L;
    private static final ArrayDeque<Long> sWakes = new ArrayDeque<>();
    private static volatile boolean sCecWake;

    private CecPolicy() {}

    /** This wake was accepted from an HDMI device over CEC (until the next standby or user key). Main thread. */
    public static boolean cecWakeActive() {
        return sCecWake;
    }

    /** A remote key or a new standby: the current wake is the user's. Main thread. */
    public static void onUserKey() {
        sCecWake = false;
    }

    /** "Turn on with an HDMI device", null = unknown (no CEC service). Off the main thread. */
    public static Boolean wakeOn(Context c) {
        try {
            HdmiControlManager m = c.getApplicationContext().getSystemService(HdmiControlManager.class);
            if (m == null) return null;
            return m.getTvWakeOnOneTouchPlay() == HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_ENABLED;
        } catch (Throwable t) {
            Log.w(TAG, "getTvWakeOnOneTouchPlay: " + t);
            return null;
        }
    }

    /** Off the main thread. False when the framework refused it. */
    public static boolean setWake(Context c, boolean on) {
        try {
            HdmiControlManager m = c.getApplicationContext().getSystemService(HdmiControlManager.class);
            if (m == null) return false;
            m.setTvWakeOnOneTouchPlay(on ? HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_ENABLED
                    : HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_DISABLED);
            Log.i(TAG, "turn on with an HDMI device: " + (on ? "on" : "off"));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setTvWakeOnOneTouchPlay: " + t);
            return false;
        }
    }

    private static boolean isSourceType(int type) {
        return type == HdmiDeviceInfo.DEVICE_PLAYBACK || type == HdmiDeviceInfo.DEVICE_TUNER
                || type == HdmiDeviceInfo.DEVICE_RECORDER;
    }

    private static synchronized boolean takeWakeSlot() {
        long now = SystemClock.elapsedRealtime();
        while (!sWakes.isEmpty() && now - sWakes.peekFirst() > WINDOW_MS) sWakes.pollFirst();
        if (sWakes.size() >= MAX_WAKES) return false;
        sWakes.addLast(now);
        return true;
    }

    /**
     * StandbyStateProvider "cec_wake" from org.lumen.tvinput (binder thread), asked only during the lamp-only
     * standby: a CEC source became the active source (extras port, la, type, osd). {standby, accepted}:
     * accepted = the lamp comes on for this device, so tvinput opens its input.
     */
    public static Bundle onWakeRequest(Context ctx, Bundle ex) {
        Bundle r = new Bundle();
        boolean standby = StandbyController.isActive();
        r.putBoolean("standby", standby);
        r.putBoolean("accepted", false);
        try {
            int la = ex == null ? -1 : ex.getInt("la", -1);
            int type = ex == null ? -1 : ex.getInt("type", -1);
            String osd = ex == null ? null : ex.getString("osd");
            final String who = TextUtils.isEmpty(osd) ? "src " + la : osd.trim();
            String block = !standby ? "not in standby"
                    : !isSourceType(type) || la <= 0 || la >= 15 ? "not a source device"
                    : !Boolean.TRUE.equals(wakeOn(ctx)) ? "turn on with an HDMI device is off"
                    : StandbyController.shutdownInFlight() ? "power-off in progress"
                    : !takeWakeSlot() ? MAX_WAKES + " wake-ups in " + WINDOW_MS / 60_000 + " min" : null;
            if (block != null) {
                Log.i(TAG, "cec_wake " + who + " ignored: " + block);
                return r;
            }
            Log.i(TAG, "cec_wake " + who + " accepted: leaving standby");
            Ui.main().post(() -> {
                sCecWake = true;
                StandbyController.exitForCec(ctx, "cec_otp:" + who);
            });
            r.putBoolean("accepted", true);
        } catch (Throwable t) {
            Log.w(TAG, "wake request: " + t);
        }
        return r;
    }
}
