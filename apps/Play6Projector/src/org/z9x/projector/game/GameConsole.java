package org.z9x.projector.game;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Is a game console on the HDMI port?" for game mode Auto on the XGIMI Play 6 (t15; research
 * hdmi_volume_game.md 3.4 and 3.5). Stock's HDMI player decides by the device name (the CEC OSD name of the
 * CEC child input): a console when it contains, ignoring case, one of playstation, xbox, switch, steam deck
 * (its game_devices list). Lumen adds the CEC vendor id 0x080046 (Sony; the PS5 reports 524358 with OSD name
 * "PlayStation 5"). Only CEC source devices count (recorder, tuner, playback); the TV itself and an audio
 * system (soundbar, AVR on the ARC port) never do. The Play 6 has one HDMI port, so the port is not checked.
 *
 * Devices: HdmiControlManager.getConnectedDevices (HDMI_CEC, held), plus the HdmiDeviceInfo of the CEC child
 * inputs org.z9x.tvinput publishes (TvInputManager), which carry the OSD name after its update (a PS5 first
 * appears as "Playback_1" and is renamed about 0.4 s later). Read only; worker threads (binder calls).
 */
public final class GameConsole {
    private static final String TAG = "P6Game";
    /** Stock xhplayer res/values/arrays.xml game_devices. */
    private static final String[] NAMES = {"playstation", "xbox", "switch", "steam deck"};
    /** CEC vendor id of Sony (the PlayStation family). */
    static final int VENDOR_SONY = 0x080046;

    /**
     * The result: the console's name (null when none), whether every CEC source already has its real name
     * (false while one still shows the framework's placeholder "Playback_1" without a vendor id), and the
     * source devices seen, for the log.
     */
    public static final class Result {
        public final String console;
        public final boolean settled;
        final List<String> seen;

        Result(String console, boolean settled, List<String> seen) {
            this.console = console;
            this.settled = settled;
            this.seen = seen;
        }

        public String describe() {
            return console != null ? "console '" + console + "'"
                    : (settled ? "no console" : "no console yet, names not settled") + " (CEC sources " + seen + ")";
        }
    }

    private GameConsole() {}

    /** Never throws. */
    public static Result find(Context app) {
        List<HdmiDeviceInfo> devices = new ArrayList<>();
        try {
            HdmiControlManager hcm = app.getSystemService(HdmiControlManager.class);
            if (hcm != null) devices.addAll(hcm.getConnectedDevices());
        } catch (Throwable t) {
            Log.w(TAG, "CEC devices: " + t);
        }
        try {
            TvInputManager tim = app.getSystemService(TvInputManager.class);
            if (tim != null) {
                for (TvInputInfo in : tim.getTvInputList()) {
                    HdmiDeviceInfo d = in.getHdmiDeviceInfo();
                    if (d != null) devices.add(d);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "TV inputs: " + t);
        }
        List<String> seen = new ArrayList<>();
        boolean settled = true;
        for (HdmiDeviceInfo d : devices) {
            try {
                if (!d.isCecDevice() || !isSource(d.getDeviceType())) continue;
                String name = d.getDisplayName();
                int vendor = d.getVendorId();
                String tag = name + " (type " + d.getDeviceType() + ", vendor 0x" + Integer.toHexString(vendor) + ")";
                if (!seen.contains(tag)) seen.add(tag);
                if (vendor == VENDOR_SONY || isConsoleName(name)) return new Result(name, true, seen);
                if (isPlaceholder(name) && (vendor == 0 || vendor == HdmiDeviceInfo.VENDOR_ID_UNKNOWN)) settled = false;
            } catch (Throwable t) {
                Log.w(TAG, "device: " + t);
            }
        }
        return new Result(null, settled, seen);
    }

    /**
     * The console's name for a notice (t18): its CEC OSD name, or null when there is none yet (empty, or still
     * the framework's placeholder, as for a Sony found by its vendor id before the rename).
     */
    static String knownName(String name) {
        if (name == null) return null;
        String n = name.trim();
        return n.isEmpty() || isPlaceholder(n) ? null : n;
    }

    /** The framework's name before the device's own OSD name arrives ("Playback_1", "Recorder_1", "Tuner_1"). */
    private static boolean isPlaceholder(String name) {
        return name == null || name.matches("(?i)(playback|recorder|tuner)_\\d+");
    }

    /** CEC recorder (1), tuner (3) or playback (4): the devices that send a picture. */
    private static boolean isSource(int type) {
        return type == HdmiDeviceInfo.DEVICE_RECORDER || type == HdmiDeviceInfo.DEVICE_TUNER
                || type == HdmiDeviceInfo.DEVICE_PLAYBACK;
    }

    static boolean isConsoleName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String s : NAMES) if (n.contains(s)) return true;
        return false;
    }
}
