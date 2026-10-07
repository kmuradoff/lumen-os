package org.z9x.projector.cec;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.hdmi.HdmiControlManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.util.Log;

import java.util.Calendar;

/**
 * MODULE "cec" (Lumen OS 1.0, cec spec 3.2 CecSettings, adapted to real STR): every HDMI-CEC setting
 * the projector shows in one place, each read from and written to its single owner.
 *
 * <ul>
 *   <li><b>Framework</b> (HdmiControlManager @SystemApi, HDMI_CEC; system_server's HdmiCecConfig, also
 *       shown by TvSettings): master switch hdmi_cec_enabled; "Turn on with an HDMI device" =
 *       tv_wake_on_one_touch_play (Lumen OS 1.0: user-configurable and ON by default in
 *       Z9xFrameworkKeysOverlay, so the vendor HAL's enableWakeupByOtp always agrees with the PM51 wake
 *       source cec0 that init writes from {@value #PROP_WAKE}); "Turn off HDMI devices with the
 *       projector" = tv_send_standby_on_sleep; soundbar = system_audio_control + volume_control_enabled.</li>
 *   <li><b>org.z9x.tvinput</b> (its HdmiStateProvider call get_cec_prefs / set_cec_pref, HDMI_STATE):
 *       switch to a device when it turns on, remote controls the device, internal source on exit.</li>
 *   <li><b>Ours</b> (prefs {@value #PREFS}): soundbar on with the projector, turn off with the HDMI
 *       device, night guard hours.</li>
 * </ul>
 * Getters return null when the value cannot be read (CEC service missing, binder error). Every method
 * that does binder or provider work must run off the main thread (CecPolicy's worker). Never throws.
 */
public final class CecSettings {
    private static final String TAG = "Z9xCec";

    static final String PREFS = "z9x_cec";
    static final String K_AVR_ON = "cec_avr_on";
    static final String K_FOLLOW = "cec_follow_standby";
    static final String K_NIGHT = "cec_night_choice";
    /** The framework OTP wake was switched off for a real power-off and is restored at the next start. */
    static final String K_RESTORE_OTP = "cec_restore_otp_after_poweroff";

    /** Night guard choices: {from hour, to hour} local time, {-1,-1} = off. Default 01:00-07:00 (PLAN Q13). */
    static final int[][] NIGHT_CHOICES = {{-1, -1}, {22, 7}, {23, 7}, {0, 7}, {1, 7}};
    static final int NIGHT_DEFAULT = 4;

    /** Boot default of PM51 cec0 (overlay/v1/z9x_power.rc block 2): 1 = CEC master on AND wake on. */
    static final String PROP_WAKE = "persist.z9x.cec_wake";
    /** Per-sleep arm decision (overlay/v1/z9x_power_cec.rc writes cec0 from it). */
    static final String PROP_ARM = "sys.z9x.cec_arm";

    /** org.z9x.tvinput's HdmiStateProvider. */
    static final Uri TVINPUT_URI = Uri.parse("content://org.z9x.tvinput.hdmistate");
    static final String TV_OTP = "cec_one_touch_play";
    static final String TV_CONTROL = "cec_control";
    static final String TV_INTERNAL_ON_EXIT = "cec_internal_on_exit";

    private CecSettings() {}

    static HdmiControlManager hm(Context c) {
        try {
            return c.getApplicationContext().getSystemService(HdmiControlManager.class);
        } catch (Throwable t) {
            return null;
        }
    }

    // =================================================================== framework values

    /** HDMI-CEC master switch, null = unknown / no CEC service. */
    static Boolean masterOn(Context c) {
        HdmiControlManager m = hm(c);
        if (m == null) return null;
        try {
            return m.getHdmiCecEnabled() == HdmiControlManager.HDMI_CEC_CONTROL_ENABLED;
        } catch (Throwable t) {
            Log.w(TAG, "getHdmiCecEnabled: " + t);
            return null;
        }
    }

    static boolean setMaster(Context c, boolean on) {
        HdmiControlManager m = hm(c);
        if (m == null) return false;
        try {
            m.setHdmiCecEnabled(on ? HdmiControlManager.HDMI_CEC_CONTROL_ENABLED
                    : HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
            Log.i(TAG, "setting: HDMI-CEC " + (on ? "on" : "off"));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setHdmiCecEnabled: " + t);
            return false;
        }
    }

    /** "Turn on with an HDMI device" (framework tv_wake_on_one_touch_play). */
    static Boolean wakeOn(Context c) {
        HdmiControlManager m = hm(c);
        if (m == null) return null;
        try {
            return m.getTvWakeOnOneTouchPlay() == HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_ENABLED;
        } catch (Throwable t) {
            Log.w(TAG, "getTvWakeOnOneTouchPlay: " + t);
            return null;
        }
    }

    static boolean setWake(Context c, boolean on) {
        HdmiControlManager m = hm(c);
        if (m == null) return false;
        try {
            m.setTvWakeOnOneTouchPlay(on ? HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_ENABLED
                    : HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_DISABLED);
            Log.i(TAG, "setting: turn on with an HDMI device " + (on ? "on" : "off"));
            return true;
        } catch (Throwable t) {
            // IllegalArgumentException "prohibited" = an image without the Lumen OS 1.0 RRO value
            Log.w(TAG, "setTvWakeOnOneTouchPlay: " + t);
            return false;
        }
    }

    /** "Turn off HDMI devices with the projector" (framework tv_send_standby_on_sleep). */
    static Boolean sendStandbyOn(Context c) {
        HdmiControlManager m = hm(c);
        if (m == null) return null;
        try {
            return m.getTvSendStandbyOnSleep() == HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_ENABLED;
        } catch (Throwable t) {
            Log.w(TAG, "getTvSendStandbyOnSleep: " + t);
            return null;
        }
    }

    static boolean setSendStandby(Context c, boolean on) {
        HdmiControlManager m = hm(c);
        if (m == null) return false;
        try {
            m.setTvSendStandbyOnSleep(on ? HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_ENABLED
                    : HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_DISABLED);
            Log.i(TAG, "setting: turn off devices with the projector " + (on ? "on" : "off"));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setTvSendStandbyOnSleep: " + t);
            return false;
        }
    }

    /** Soundbar / AV receiver: System Audio Control (sound over ARC + volume keys over CEC). */
    static Boolean soundbarOn(Context c) {
        HdmiControlManager m = hm(c);
        if (m == null) return null;
        try {
            return m.getSystemAudioControl() == HdmiControlManager.SYSTEM_AUDIO_CONTROL_ENABLED;
        } catch (Throwable t) {
            Log.w(TAG, "getSystemAudioControl: " + t);
            return null;
        }
    }

    static boolean setSoundbar(Context c, boolean on) {
        HdmiControlManager m = hm(c);
        if (m == null) return false;
        try {
            m.setSystemAudioControl(on ? HdmiControlManager.SYSTEM_AUDIO_CONTROL_ENABLED
                    : HdmiControlManager.SYSTEM_AUDIO_CONTROL_DISABLED);
            try {
                m.setHdmiCecVolumeControlEnabled(on ? HdmiControlManager.VOLUME_CONTROL_ENABLED
                        : HdmiControlManager.VOLUME_CONTROL_DISABLED);
            } catch (Throwable t) {
                Log.w(TAG, "setHdmiCecVolumeControlEnabled: " + t);
            }
            Log.i(TAG, "setting: soundbar / AV receiver " + (on ? "on" : "off"));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setSystemAudioControl: " + t);
            return false;
        }
    }

    // =================================================================== our values

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean avrOn(Context c) {
        try { return prefs(c).getBoolean(K_AVR_ON, true); } catch (Throwable t) { return true; }
    }

    static void setAvrOn(Context c, boolean on) {
        try { prefs(c).edit().putBoolean(K_AVR_ON, on).apply(); } catch (Throwable t) { Log.w(TAG, "prefs: " + t); }
        Log.i(TAG, "setting: soundbar on with the projector " + (on ? "on" : "off"));
    }

    /** Turn the projector off when the HDMI device being watched turns off (default on, PLAN Q13). */
    static boolean followStandby(Context c) {
        try { return prefs(c).getBoolean(K_FOLLOW, true); } catch (Throwable t) { return true; }
    }

    static void setFollowStandby(Context c, boolean on) {
        try { prefs(c).edit().putBoolean(K_FOLLOW, on).apply(); } catch (Throwable t) { Log.w(TAG, "prefs: " + t); }
        Log.i(TAG, "setting: turn off with the HDMI device " + (on ? "on" : "off"));
    }

    static int nightChoice(Context c) {
        int i;
        try { i = prefs(c).getInt(K_NIGHT, NIGHT_DEFAULT); } catch (Throwable t) { i = NIGHT_DEFAULT; }
        return i >= 0 && i < NIGHT_CHOICES.length ? i : NIGHT_DEFAULT;
    }

    static void setNightChoice(Context c, int i) {
        if (i < 0 || i >= NIGHT_CHOICES.length) return;
        try { prefs(c).edit().putInt(K_NIGHT, i).apply(); } catch (Throwable t) { Log.w(TAG, "prefs: " + t); }
        Log.i(TAG, "setting: night guard " + describeNight(i));
    }

    static String describeNight(int i) {
        int[] r = NIGHT_CHOICES[i];
        return r[0] < 0 ? "off" : r[0] + ":00-" + r[1] + ":00";
    }

    /** Inside the night guard hours right now (local time)? */
    static boolean inNight(Context c, long wallMs) {
        int[] r = NIGHT_CHOICES[nightChoice(c)];
        if (r[0] < 0) return false;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(wallMs);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        return r[0] < r[1] ? (h >= r[0] && h < r[1]) : (h >= r[0] || h < r[1]);
    }

    static boolean restoreOtpPending(Context c) {
        try { return prefs(c).getBoolean(K_RESTORE_OTP, false); } catch (Throwable t) { return false; }
    }

    static void setRestoreOtpPending(Context c, boolean v) {
        try { prefs(c).edit().putBoolean(K_RESTORE_OTP, v).commit(); } catch (Throwable t) { Log.w(TAG, "prefs: " + t); }
    }

    // =================================================================== org.z9x.tvinput prefs

    /** {cec_one_touch_play, cec_control, cec_internal_on_exit} from tvinput, null = not available. */
    static Bundle tvinputPrefs(Context c) {
        try {
            if (c.getPackageManager().resolveContentProvider(TVINPUT_URI.getAuthority(), 0) == null) return null;
            return c.getContentResolver().call(TVINPUT_URI, "get_cec_prefs", null, null);
        } catch (Throwable t) {
            Log.w(TAG, "tvinput get_cec_prefs: " + t);
            return null;
        }
    }

    static boolean setTvinputPref(Context c, String key, boolean value) {
        try {
            Bundle b = new Bundle();
            b.putBoolean("value", value);
            Bundle r = c.getContentResolver().call(TVINPUT_URI, "set_cec_pref", key, b);
            boolean ok = r != null && r.getBoolean("ok", false);
            Log.i(TAG, "setting: tvinput " + key + " = " + value + (ok ? "" : " FAILED"));
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "tvinput set_cec_pref " + key + ": " + t);
            return false;
        }
    }

    /** HDMI ports with an open, streaming tvinput session (a picture), null = unknown. */
    static int[] tvinputStreams(Context c) {
        try {
            if (c.getPackageManager().resolveContentProvider(TVINPUT_URI.getAuthority(), 0) == null) return null;
            Bundle b = c.getContentResolver().call(TVINPUT_URI, "hdmi_state", null, null);
            return b == null ? null : b.getIntArray("streams");
        } catch (Throwable t) {
            Log.w(TAG, "tvinput hdmi_state: " + t);
            return null;
        }
    }

    // =================================================================== PM51 cec0 (init properties)

    /** Boot default of cec0 = master && wake (persist, so init applies it at the next boot_completed). */
    static void syncWakeProp(Context c) {
        Boolean m = masterOn(c), w = wakeOn(c);
        if (m == null || w == null) return;
        String want = m && w ? "1" : "0";
        try {
            if (!want.equals(SystemProperties.get(PROP_WAKE, ""))) {
                SystemProperties.set(PROP_WAKE, want);
                Log.i(TAG, PROP_WAKE + " = " + want);
            }
        } catch (Throwable t) {
            Log.w(TAG, "set " + PROP_WAKE + ": " + t);
        }
    }

    /**
     * Arms or disarms the PM51 CEC wake source for the coming sleep. The property is cleared first so
     * init's trigger always fires, also after z9x_power.rc wrote cec0 from {@value #PROP_WAKE} meanwhile.
     */
    static void arm(boolean on, String why) {
        try {
            SystemProperties.set(PROP_ARM, "");
            SystemProperties.set(PROP_ARM, on ? "1" : "0");
            Log.i(TAG, "PM51 CEC wake " + (on ? "armed" : "off") + " (" + why + ")");
        } catch (Throwable t) {
            Log.w(TAG, "set " + PROP_ARM + ": " + t);
        }
    }
}
