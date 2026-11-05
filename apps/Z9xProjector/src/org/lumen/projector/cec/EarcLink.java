/*
 * EarcLink: eARC the stock way on the Z9X and the Z9X HB (2026-11-04, devices/goat_elegant/stock analysis). Both
 * vendors run the same libxgimi flow and both stock frameworks carry the same patch. Calls: setEARCEnable = HB TV 65 /
 * Z9X IGmpf 633, getEARCEnableState = TV 66 / 632, getEARCStatus = TV 79 / 619 (GmpfClient setEarc / getEarc /
 * getEarcStatus in each fork).
 *
 * Stock end to end (G0130 release 838; the Z9X stock services.jar has the same seteARCStatus / geteArcStatus):
 * <ul>
 *   <li>The vendor does the whole link. setEARCEnable(true) sets a runtime flag of libxgimi Msrv_Tv_Control; its CEC
 *       thread then sends &lt;Request ARC Termination&gt; to the soundbar, re-pulls the HPD of the ARC port, sets the MI
 *       eARC mode 3 and reads the link after 600 ms. A link change resets the audio output caps and the EDID from the
 *       soundbar's eARC capability data and raises the GmTv event 11 "eARCstate:1|0". getEARCStatus reads the link
 *       (3 = eARC).</li>
 *   <li>XRMService (SignalChangeReceiver) turns event 11, and getEARCStatus == 3 at its start, into
 *       AudioManager.seteARCStatus(true|false); the stock AudioService then connects (or disconnects) the plain HDMI ARC
 *       output, and the stock HdmiCecLocalDeviceTv skips the ARC disconnect "while eARC mode" when the soundbar ends
 *       CEC ARC. The audio policy has no HDMI eARC device: eARC audio leaves through the ARC output and the vendor's ARC
 *       path.</li>
 * </ul>
 * Lumen has the same vendor and the AOSP framework (no eARC AIDL HAL, no seteARCStatus), so this module plays the
 * XRMService and framework part:
 * <ul>
 *   <li>No setEARCEnable of its own. getEARCEnableState reads the vendor database (the runtime flag is a separate
 *       field), but the link comes up without one: live on the HB 2026-11-04 19:10 (beta3, none sent since its boot)
 *       getEARCStatus read 3 and the MI kernel read the soundbar's eARC capability data (MAT 2.x, DTS-HD). Every earlier
 *       HB failure (10-31, 11-03) followed a toggle (forced &lt;Request ARC Termination&gt; and HPD re-pull), so a call
 *       at boot could break a working link. Only the panel row sends it, as stock.</li>
 *   <li>The link is read in a short watch (1 s steps) after the HAL connect, the panel row and every ARC output change
 *       (the vendor's link change always moves CEC ARC). While it reads 3 and no HDMI ARC output is listed, one is
 *       connected (AudioManager.setWiredDeviceConnectionState, no descriptors, as stock), e.g. after the framework
 *       removed CEC ARC's on &lt;Terminate ARC&gt;; a listed one is never touched.</li>
 *   <li>When the link ends, the output this module connected is removed; if no ARC output comes back within
 *       ARC_RESTART_OWN_MS (ARC_RESTART_MS after a failed attempt) the framework is asked for ARC again (IHdmiControlService.setArcMode(true), the AOSP
 *       RequestArcInitiationAction), except with a local output chosen (EarcRules.arcRestart). The same restart
 *       follows a failed attempt (the vendor's "Amp Not Support EARC": status 1 and no ARC output).</li>
 * </ul>
 * AudioOutputChoice labels the soundbar "HDMI eARC" while linked(). Decisions in EarcRules (host test test/earc). HAL
 * calls on "z9x-hal" (Hal.run), the rest on "z9x-earc". Log tag Z9xEarc.
 */
package org.lumen.projector.cec;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiTvClient;
import android.hardware.hdmi.IHdmiControlService;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import org.lumen.projector.Hal;
import org.lumen.projector.audio.AudioOutputChoice;

public final class EarcLink {
    private static final String TAG = "Z9xEarc";
    /** Watch length after the HAL connect, the panel row or an ARC output change (the vendor decides 600 ms after its HPD pull). */
    private static final long WATCH_MS = 15_000L;
    private static final long STEP_MS = 1_000L;
    /** Without an ARC output this long after the link ended, ask the framework for ARC. */
    private static final long ARC_RESTART_MS = 6_000L;
    /**
     * After this module removed its own output. CEC ARC may have re-attached to that output meanwhile (AOSP connects
     * an output that is already listed without a device event), so the framework can still count ARC as up with no
     * output left: ask at once. Seen live on the HB 2026-11-04 20:10 (setArcMode off / on while eARC was linked).
     */
    private static final long ARC_RESTART_OWN_MS = 1_500L;
    private static final Object WATCH_TOKEN = new Object();
    private static final Object RESTART_TOKEN = new Object();
    private static Handler sH;
    private static Context sApp;
    /** EarcLink connected the HDMI ARC output and has not removed it ("z9x-earc"). */
    private static boolean sOurs;
    private static volatile int sLastStatus = -1;
    private static long sWatchUntil;

    private EarcLink() {}

    /** App.startModules (main thread, user unlocked); idempotent. */
    public static synchronized void install(Context ctx) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        Handler h = handler();
        if (h == null) return;
        try {
            AudioManager am = sApp.getSystemService(AudioManager.class);
            if (am != null) {
                am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d) {
                        if (hasArc(d)) watch("ARC output added");
                    }
                    @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d) {
                        if (hasArc(d)) watch("ARC output removed");
                    }
                }, h);
            }
        } catch (Throwable t) {
            Log.w(TAG, "device callback: " + t);
        }
        Hal.addConnectedListener(EarcLink::onHalConnected);
    }

    /** "z9x-hal", after every (re)connect: one watch (the link may be up before any ARC output change). */
    private static void onHalConnected() {
        watch("HAL connected");
    }

    /** The last getEARCStatus read was 3. Any thread. */
    public static boolean linked() {
        return sLastStatus == EarcRules.EARC_LINKED;
    }

    /** The panel's eARC row (stock "eARC Mode" Auto / Off). "z9x-hal" thread with its client. */
    public static boolean setUser(org.lumen.projector.hal.GmpfClient g, boolean on) throws Exception {
        boolean ok = g.setEarc(on);
        Log.i(TAG, "user: setEARCEnable(" + on + ") -> " + ok);
        watch(on ? "user Auto" : "user Off");
        return ok;
    }

    /** Starts (or extends) the getEARCStatus watch. Any thread. */
    private static void watch(String why) {
        Handler h = handler();
        if (h == null) return;
        h.post(() -> {
            boolean running = SystemClock.uptimeMillis() < sWatchUntil;
            sWatchUntil = SystemClock.uptimeMillis() + WATCH_MS;
            Log.i(TAG, "watch (" + why + ")" + (running ? ", extended" : ""));
            if (!running) {
                h.removeCallbacksAndMessages(WATCH_TOKEN);
                step();
            }
        });
    }

    /** "z9x-earc": one getEARCStatus read, the next step while the watch runs. */
    private static void step() {
        Handler h = handler();
        if (h == null) return;
        Hal.run((g, g2) -> {
            int s = g.getEarcStatus();
            h.post(() -> onStatus(s));
        });
        if (SystemClock.uptimeMillis() < sWatchUntil) {
            h.postAtTime(EarcLink::step, WATCH_TOKEN, SystemClock.uptimeMillis() + STEP_MS);
        }
    }

    /** "z9x-earc". */
    private static void onStatus(int status) {
        boolean present = arcPresent();
        if (status != sLastStatus) {
            Log.i(TAG, "eARC status " + sLastStatus + " -> " + status + " (ARC output " + present
                    + ", ours " + sOurs + ")");
        }
        sLastStatus = status;
        switch (EarcRules.step(status, sOurs, present)) {
            case CONNECT:
                if (connect(true)) sOurs = true;
                handler().removeCallbacksAndMessages(RESTART_TOKEN);
                break;
            case DISCONNECT:
                connect(false);
                sOurs = false;
                restartLater("eARC ended", ARC_RESTART_OWN_MS);
                break;
            default:
                if (status != EarcRules.EARC_LINKED && !present) restartLater("no eARC, no ARC", ARC_RESTART_MS);
        }
    }

    /** One pending ARC restart at a time. "z9x-earc". */
    private static void restartLater(String why, long delayMs) {
        Handler h = handler();
        if (h == null || h.hasCallbacks(RESTART_RUNNABLE)) return;
        h.postAtTime(RESTART_RUNNABLE, RESTART_TOKEN, SystemClock.uptimeMillis() + delayMs);
        Log.i(TAG, why + ": ARC restart check in " + delayMs + " ms");
    }

    private static final Runnable RESTART_RUNNABLE = () -> {
        try {
            if (sLastStatus == EarcRules.EARC_LINKED) return;
            Context app = sApp;
            AudioOutputChoice.Kind k = AudioOutputChoice.kind(app);
            boolean present = arcPresent();
            boolean avr = CecDevices.audioSystem(app) != null;
            if (!EarcRules.arcRestart(k == null ? null : k.name(), present, avr)) {
                Log.i(TAG, "ARC restart: not needed (" + k + ", ARC output " + present + ", audio system " + avr + ")");
                return;
            }
            IHdmiControlService s = hdmiService(app);
            if (s == null) return;
            s.setArcMode(true);
            Log.i(TAG, "ARC restart: setArcMode(true) sent (" + k + ")");
        } catch (Throwable t) {
            Log.w(TAG, "ARC restart: " + t);
        }
    };

    /**
     * The framework's IHdmiControlService behind HdmiTvClient (HdmiClient.mService): setArcMode has no manager method,
     * and the HAL gate keeps ServiceManager out of this app. Null without a TV client.
     */
    private static IHdmiControlService hdmiService(Context app) throws ReflectiveOperationException {
        HdmiControlManager m = CecSettings.hm(app);
        HdmiTvClient tv = m == null ? null : m.getTvClient();
        if (tv == null) return null;
        java.lang.reflect.Field f = tv.getClass().getSuperclass().getDeclaredField("mService");
        f.setAccessible(true);
        return (IHdmiControlService) f.get(tv);
    }

    /** Connects or removes the HDMI ARC output (no name, no descriptors: the stock AudioService call). */
    private static boolean connect(boolean on) {
        try {
            AudioManager am = sApp.getSystemService(AudioManager.class);
            AudioDeviceAttributes arc = new AudioDeviceAttributes(AudioDeviceAttributes.ROLE_OUTPUT,
                    AudioDeviceInfo.TYPE_HDMI_ARC, "");
            am.setWiredDeviceConnectionState(arc, on ? 1 : 0);
            Log.i(TAG, "HDMI ARC output " + (on ? "connected (eARC linked)" : "removed (eARC ended)"));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "HDMI ARC output " + on + ": " + t);
            return false;
        }
    }

    private static boolean arcPresent() {
        try {
            AudioManager am = sApp.getSystemService(AudioManager.class);
            return hasArc(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasArc(AudioDeviceInfo[] d) {
        if (d == null) return false;
        for (AudioDeviceInfo i : d) {
            if (i != null && i.getType() == AudioDeviceInfo.TYPE_HDMI_ARC) return true;
        }
        return false;
    }

    private static synchronized Handler handler() {
        if (sH == null) {
            try {
                HandlerThread t = new HandlerThread("z9x-earc");
                t.start();
                sH = new Handler(t.getLooper());
            } catch (Throwable e) {
                Log.w(TAG, "thread: " + e);
            }
        }
        return sH;
    }
}
