/*
 * HbSystemAudio: CEC System Audio follows the panel's sound output choice on the Z9X HB (2026-11-01, ARC volume spike,
 * device tests, cause 3). HB-only file, no HAL access.
 *
 * Why: with System Audio on, AOSP AudioService.adjustStreamVolume forwards STREAM_MUSIC volume keys over CEC to the
 * soundbar whenever the routed device is not an absolute volume device (tvConditions; the stock HB services.jar
 * AudioService has the same condition), so with "Speaker" chosen the keys still queued up for the soundbar
 * (audio_switch.txt:4689-4932, MI_CEC_SendTxPolling fail; the soundbar reached 0x64 while Android went down,
 * audio/cec_raw.txt:25-29; System Audio still on, hdmi_control.txt:5, 8).
 *
 * Stock: the stock HB settings (release_838_newsettings VoiceRepository / VoiceHelper -> GmAudioManager.setAudioDeviceOn
 * -> XgimiService GimiAudioService.setAudioDeviceOn) only call AudioSystem.setForceUse(FOR_MEDIA, 1 speaker / 16 ARC)
 * and never change System Audio (no app of the stock image calls setSystemAudioMode, the factory app aside). This is
 * therefore a Lumen addition over stock, asked for by the device test; it uses the AOSP call CecPolicy already uses
 * to turn the soundbar on (HdmiTvClient.setSystemAudioMode), and never the persistent setting system_audio_control
 * (the CEC page's soundbar switch stays the user's).
 *
 * Rules (HbAudioRules.systemAudioFor / systemAudioChange): "Speaker" -> System Audio off; "Soundbar" (ARC) or "Auto"
 * -> on; other choices unchanged. Only with CEC on, System Audio Control on, an audio system known, and a mode that
 * differs. System Audio off ends ARC (AOSP HdmiCecLocalDeviceTv.setSystemAudioMode -> startArcAction(false)), so the
 * panel keeps a soundbar row while the soundbar is known (audio/HbOutputOptions, audio/HbOutputRows).
 *
 * CEC volume control (hb-t12 tester, 2026-11-01 15:53): "Speaker" was the stored choice since the boot, but nothing
 * turned System Audio off at the boot (the framework's auto initiation turned it on at 15:38:22, FOR_HDMI_SYSTEM_AUDIO
 * ENFORCED), so every volume key went to the LG soundbar as &lt;User Control Pressed&gt; Volume while the speaker
 * played; only choosing "Speaker" again at 15:54:23 sent &lt;System Audio Mode Request&gt; off. Now:
 * <ul>
 *   <li>CEC volume control (HdmiControlManager.setHdmiCecVolumeControlEnabled, the AOSP condition
 *       mHdmiCecVolumeControlEnabled of AudioService.adjustStreamVolume) follows the choice (HbAudioRules.cecVolumeFor):
 *       off for a local output, the CEC page's soundbar switch for "Soundbar" / "Auto" (CecSettings.setSoundbar keeps
 *       the two equal). With it off no volume key leaves over CEC, whoever turns System Audio on. No own setting: it is
 *       derived from the stored choice every time.</li>
 *   <li>Applied on a choice, 10 s and 40 s after the start (CEC discovery and the auto initiation of the boot),
 *       10 s after SCREEN_ON (after CecPolicy's soundbar on, 4 s + 4 s fallback), when an ARC / eARC output appears
 *       (the soundbar or CecPolicy turned System Audio on), and when anyone changes volume control.</li>
 *   <li>With "Speaker" also System Audio off again on those events (HbAudioRules.autoSystemAudioOff, at most once in
 *       30 s): this covers CecPolicy's "turn on the soundbar with the projector" after a wake (shared Z9X file,
 *       unchanged).</li>
 * </ul>
 *
 * Binder work on the thread "z9x-hbsam". Log tag Z9xHbSam.
 */
package org.lumen.projector.cec;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiTvClient;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import org.lumen.projector.audio.AudioOutputChoice;
import org.lumen.projector.audio.HbAudioRules;

public final class HbSystemAudio {
    private static final String TAG = "Z9xHbSam";
    private static final long START_CHECK_MS = 10_000L;
    private static final long START_RECHECK_MS = 40_000L;
    private static final long SCREEN_ON_CHECK_MS = 10_000L;
    private static final long ARC_CHECK_MS = 1_500L;
    private static final Object CHECK_TOKEN = new Object();
    private static Handler sH;
    private static Context sApp;
    /** uptime of the last automatic System Audio switch-off, -1 = never (z9x-hbsam only). */
    private static long sAutoOffAt = -1;

    private HbSystemAudio() {}

    /** App.startModules (main thread, user unlocked); idempotent. */
    public static synchronized void install(Context ctx) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        final Handler h = handler();
        if (h == null) return;
        try {
            AudioManager am = sApp.getSystemService(AudioManager.class);
            if (am != null) {
                am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                        if (!hasArc(added)) return;
                        org.lumen.projector.audio.HbOutputOptions.noteSoundbar();
                        check("ARC output added", ARC_CHECK_MS);
                    }
                }, h);
            }
        } catch (Throwable t) {
            Log.w(TAG, "device callback: " + t);
        }
        h.post(() -> {
            try {
                HdmiControlManager m = CecSettings.hm(sApp);
                if (m != null) {
                    m.addHdmiCecVolumeControlFeatureListener(HbSystemAudio::runOnWorker,
                            v -> enforce("volume control " + v));
                }
            } catch (Throwable t) {
                Log.w(TAG, "volume control listener: " + t);
            }
        });
        check("start", START_CHECK_MS);
        h.postDelayed(() -> enforce("start recheck"), START_RECHECK_MS);
    }

    /** SCREEN_ON (main thread): check after CecPolicy's soundbar on. */
    public static void onScreenOn() {
        check("screen on", SCREEN_ON_CHECK_MS);
    }

    private static void runOnWorker(Runnable r) {
        Handler h = handler();
        if (h != null) h.post(r);
    }

    private static boolean hasArc(AudioDeviceInfo[] d) {
        if (d == null) return false;
        for (AudioDeviceInfo i : d) {
            if (i != null && (i.getType() == AudioDeviceInfo.TYPE_HDMI_ARC || i.getType() == AudioDeviceInfo.TYPE_HDMI_EARC)) return true;
        }
        return false;
    }

    /** One pending check at a time (the last request wins). */
    private static void check(final String why, long delayMs) {
        Handler h = handler();
        if (h == null || sApp == null) return;
        h.removeCallbacksAndMessages(CHECK_TOKEN);
        h.postAtTime(() -> enforce(why), CHECK_TOKEN, SystemClock.uptimeMillis() + Math.max(0, delayMs));
    }

    /** z9x-hbsam: CEC volume control from the stored choice; with "Speaker" System Audio off again if on. */
    private static void enforce(String why) {
        try {
            Context app = sApp;
            if (app == null) return;
            AudioOutputChoice.Kind k = AudioOutputChoice.kind(app);
            String kind = k == null ? null : k.name();
            HdmiControlManager m = CecSettings.hm(app);
            if (m == null) return;
            applyVolumeControl(m, app, kind, why);
            boolean cecOn = Boolean.TRUE.equals(CecSettings.masterOn(app));
            boolean controlOn = Boolean.TRUE.equals(CecSettings.soundbarOn(app));
            boolean avr = CecDevices.audioSystem(app) != null;
            boolean modeOn = m.getSystemAudioMode();
            long now = SystemClock.uptimeMillis();
            long since = sAutoOffAt < 0 ? -1 : now - sAutoOffAt;
            if (!HbAudioRules.autoSystemAudioOff(kind, cecOn, controlOn, avr, modeOn, since)) return;
            HdmiTvClient tv = m.getTvClient();
            if (tv == null) return;
            sAutoOffAt = now;
            Log.i(TAG, why + ": " + kind + " chosen, system audio on -> off");
            tv.setSystemAudioMode(false, result -> Log.i(TAG, "system audio off (" + why + ") -> " + result));
        } catch (Throwable t) {
            Log.w(TAG, "enforce (" + why + "): " + t);
        }
    }

    private static void applyVolumeControl(HdmiControlManager m, Context app, String kind, String why) {
        try {
            Boolean want = HbAudioRules.cecVolumeFor(kind, CecSettings.soundbarOn(app));
            int v = m.getHdmiCecVolumeControlEnabled();
            Boolean cur = v == HdmiControlManager.VOLUME_CONTROL_ENABLED ? Boolean.TRUE
                    : v == HdmiControlManager.VOLUME_CONTROL_DISABLED ? Boolean.FALSE : null;
            if (!HbAudioRules.cecVolumeChange(want, cur)) return;
            Log.i(TAG, why + ": " + kind + " chosen, CEC volume control -> " + (want ? "on" : "off"));
            m.setHdmiCecVolumeControlEnabled(want ? HdmiControlManager.VOLUME_CONTROL_ENABLED
                    : HdmiControlManager.VOLUME_CONTROL_DISABLED);
        } catch (Throwable t) {
            Log.w(TAG, "volume control (" + why + "): " + t);
        }
    }

    /** The user chose a sound output (AudioOutputChoice.Kind name). Main thread; the CEC work runs on z9x-hbsam. */
    public static void onChoice(Context ctx, String kind) {
        final Context app = ctx.getApplicationContext();
        final Boolean want = HbAudioRules.systemAudioFor(kind);
        Handler h = handler();
        if (h == null) return;
        if (want == null) {
            // BT / wired / USB: System Audio unchanged, CEC volume control off (HbAudioRules.cecVolumeFor)
            h.post(() -> {
                HdmiControlManager m = CecSettings.hm(app);
                if (m != null) applyVolumeControl(m, app, kind, "choice");
            });
            return;
        }
        h.post(() -> apply(app, want, kind));
    }

    /**
     * A CEC audio system is in the HDMI-CEC device list (the panel keeps its soundbar row then, audio/HbOutputOptions).
     * Main thread (framework read only).
     */
    public static boolean audioSystemListed(Context ctx) {
        try {
            return CecDevices.audioSystem(ctx.getApplicationContext()) != null;
        } catch (Throwable t) {
            Log.w(TAG, "audioSystemListed: " + t);
            return false;
        }
    }

    private static void apply(Context app, boolean want, String kind) {
        try {
            HdmiControlManager m = CecSettings.hm(app);
            if (m == null) return;
            if (kind != null) applyVolumeControl(m, app, kind, "choice");
            boolean cecOn = Boolean.TRUE.equals(CecSettings.masterOn(app));
            boolean controlOn = Boolean.TRUE.equals(CecSettings.soundbarOn(app));
            boolean avr = CecDevices.audioSystem(app) != null;
            boolean modeOn = m.getSystemAudioMode();
            if (!HbAudioRules.systemAudioChange(want, cecOn, controlOn, avr, modeOn)) {
                Log.i(TAG, "choice " + kind + ": system audio stays " + (modeOn ? "on" : "off") + " (cec " + cecOn
                        + ", control " + controlOn + ", audio system " + avr + ")");
                return;
            }
            HdmiTvClient tv = m.getTvClient();
            if (tv == null) return;
            Log.i(TAG, "choice " + kind + ": system audio -> " + (want ? "on" : "off"));
            tv.setSystemAudioMode(want, result -> Log.i(TAG, "system audio " + (want ? "on" : "off") + " -> " + result));
        } catch (Throwable t) {
            Log.w(TAG, "system audio for " + kind + ": " + t);
        }
    }

    private static synchronized Handler handler() {
        if (sH == null) {
            try {
                HandlerThread t = new HandlerThread("z9x-hbsam");
                t.start();
                sH = new Handler(t.getLooper());
            } catch (Throwable e) {
                Log.w(TAG, "thread: " + e);
            }
        }
        return sH;
    }
}
