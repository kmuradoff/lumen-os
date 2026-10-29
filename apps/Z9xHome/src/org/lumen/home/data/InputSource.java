package org.lumen.home.data;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import org.lumen.home.App;
import org.lumen.home.Props;
import org.lumen.home.R;
import org.lumen.home.proj.ProjectorBridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HDMI inputs, cast receivers and projector shortcuts (SPEC 5.5, 5.6; PLAN C15).
 *
 * C15: CEC child inputs (TvInputInfo.getParentId() != null) are grouped under their HDMI port, so a
 * console shows once as "PlayStation 5" with the subtitle "HDMI 1", never twice. Runs on io.
 */
public final class InputSource {
    private InputSource() {}

    /**
     * Device switch {@code ro.lumen.home.cec_names} (Props): 1 = the cards of CEC devices get a friendly name
     * and a mark (CecNames) instead of the raw CEC name, which is a placeholder such as "Playback_1" until
     * the device sent its OSD name; HomeActivity then also refreshes on TvInputInfo updates. Unset: as 1.0.2.
     */
    public static final boolean CEC_NAMES = Props.num("cec_names", 0) > 0;

    public static final String TVINPUT_PKG = "org.lumen.tvinput";
    public static final String PKG_CHROMECAST = "com.google.android.apps.mediashell";
    public static final String PKG_AIRPLAY = "org.lumen.airplay";
    private static final Pattern HW = Pattern.compile("/HW(\\d+)$");
    private static final Pattern CONSOLE = Pattern.compile("(?i)playstation|ps[345]|xbox|nintendo|switch|steam");

    public static List<Card> inputs(Context c, Map<String, String> customLabels) {
        ArrayList<Card> out = new ArrayList<>();
        TvInputManager tim = c.getSystemService(TvInputManager.class);
        if (tim == null) return out;
        List<TvInputInfo> all;
        try {
            all = tim.getTvInputList();
        } catch (Throwable t) {
            Log.w(App.TAG, "tv inputs: " + t);
            return out;
        }
        Map<Integer, HdmiDeviceInfo> cec = cecDevices(c);
        Bundle hdmi = hdmiState(c);
        int[] showingPorts = showingPorts(hdmi);
        Map<Integer, Bundle> devices = portDevices(hdmi);   // empty unless the TV input app reports them
        HashMap<String, List<TvInputInfo>> children = new HashMap<>();
        ArrayList<TvInputInfo> tops = new ArrayList<>();
        for (TvInputInfo ii : all) {
            if (isHidden(c, ii)) continue;
            if (ii.getParentId() != null) {
                children.computeIfAbsent(ii.getParentId(), k -> new ArrayList<>()).add(ii);
            } else if (ii.getType() != TvInputInfo.TYPE_TUNER || ii.isPassthroughInput()) {
                tops.add(ii);
            }
        }
        // ours first (HW1, HW2), then other passthrough inputs
        tops.sort((a, b) -> {
            boolean oa = TVINPUT_PKG.equals(a.getServiceInfo().packageName);
            boolean ob = TVINPUT_PKG.equals(b.getServiceInfo().packageName);
            if (oa != ob) return oa ? -1 : 1;
            return Integer.compare(port(a), port(b)) != 0 ? Integer.compare(port(a), port(b)) : a.getId().compareTo(b.getId());
        });
        for (TvInputInfo ii : tops) {
            Card k = new Card(Card.INPUT, ii.getId(), "");
            int port = port(ii);
            String portLabel = label(c, ii);
            List<TvInputInfo> kids = children.get(ii.getId());
            HdmiDeviceInfo dev = null;
            String target = ii.getId();
            if (kids != null && !kids.isEmpty()) {
                TvInputInfo kid = kids.get(0);
                dev = kid.getHdmiDeviceInfo();
                target = kid.getId();
            }
            if (dev == null && port > 0) dev = cec.get(port);
            String devName = dev != null ? dev.getDisplayName() : null;
            HdmiDeviceInfo live = null;   // cec_names: the system's CEC entry of the port
            CecNames.Device friendly = null;
            if (CEC_NAMES) { // the device's names, best first; none of them real: the port's label below
                if (port > 0) live = cec.get(port);
                String kidLabel = kids != null && !kids.isEmpty() ? childLabel(c, kids.get(0)) : null;
                int vendor = dev != null ? dev.getVendorId() : 0;
                if (vendor == 0 && live != null) vendor = live.getVendorId();
                friendly = CecNames.of(vendor, devName, live != null ? live.getDisplayName() : null, kidLabel);
                devName = friendly.name;
            }
            String custom = customLabels.get(ii.getId());
            if (custom != null && !custom.isEmpty()) {
                k.title = custom;
                k.desc = devName != null && !devName.isEmpty() ? devName : portLabel;
            } else if (devName != null && !devName.trim().isEmpty()) {
                k.title = devName.trim();
                k.desc = portLabel;
            } else {
                k.title = portLabel;
                k.desc = "";
            }
            int st;
            try {
                st = tim.getInputState(ii.getId());
            } catch (Throwable t) {
                st = TvInputManager.INPUT_STATE_DISCONNECTED;
            }
            k.state = st;
            k.showing = port > 0 && contains(showingPorts, port);
            k.icon = icon(dev);
            k.intent = TvContract.buildChannelUriForPassthroughInput(target).toString();
            k.pkg = ii.getServiceInfo().packageName;
            k.meta = stateText(c, st);
            k.color = 0;
            if (friendly != null) { // cec_names: the device's mark, tinted on the card by k.color (0 = paper)
                if (friendly.mark == CecNames.MARK_CONSOLE) k.icon = R.drawable.ic_gamepad;
                else if (friendly.mark == CecNames.MARK_PLAYER) k.icon = R.drawable.ic_player;
                k.color = friendly.tint;
            }
            Bundle reported = port > 0 ? devices.get(port) : null;   // the TV input app's view of the port
            if (reported != null) applyDevice(c, k, reported, customLabels.get(ii.getId()), portLabel);
            out.add(k);
        }
        return out;
    }

    public static String stateText(Context c, int st) {
        switch (st) {
            case TvInputManager.INPUT_STATE_CONNECTED:
                return c.getString(R.string.input_connected);
            case TvInputManager.INPUT_STATE_CONNECTED_STANDBY:
                return c.getString(R.string.input_standby);
            default:
                return c.getString(R.string.input_not_connected);
        }
    }

    private static boolean isHidden(Context c, TvInputInfo ii) {
        try {
            return ii.isHidden(c);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String label(Context c, TvInputInfo ii) {
        try {
            CharSequence l = ii.loadCustomLabel(c);
            if (l == null || l.length() == 0) l = ii.loadLabel(c);
            if (l != null && l.length() > 0) return l.toString();
        } catch (Throwable ignored) {
        }
        int p = port(ii);
        return p > 0 ? "HDMI " + p : ii.getId();
    }

    /** cec_names: the label the TV input gave a CEC device's input (it may carry the OSD name), or null. */
    private static String childLabel(Context c, TvInputInfo kid) {
        try {
            CharSequence l = kid.loadCustomLabel(c);
            if (l == null || l.length() == 0) l = kid.loadLabel(c);
            return l != null ? l.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static int port(TvInputInfo ii) {
        Matcher m = HW.matcher(ii.getId());
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        HdmiDeviceInfo d = ii.getHdmiDeviceInfo();
        return d != null ? d.getPortId() : 0;
    }

    private static int icon(HdmiDeviceInfo dev) {
        if (dev == null) return R.drawable.ic_hdmi;
        switch (dev.getDeviceType()) {
            case HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM:
                return R.drawable.ic_soundbar;
            case HdmiDeviceInfo.DEVICE_PLAYBACK:
                String n = dev.getDisplayName();
                return n != null && CONSOLE.matcher(n).find() ? R.drawable.ic_gamepad : R.drawable.ic_player;
            case HdmiDeviceInfo.DEVICE_RECORDER:
            case HdmiDeviceInfo.DEVICE_TUNER:
                return R.drawable.ic_player;
            default:
                return R.drawable.ic_hdmi;
        }
    }

    /** port -> CEC device (playback/audio preferred); empty when CEC is unavailable. */
    private static Map<Integer, HdmiDeviceInfo> cecDevices(Context c) {
        HashMap<Integer, HdmiDeviceInfo> m = new HashMap<>();
        try {
            HdmiControlManager hm = c.getSystemService(HdmiControlManager.class);
            if (hm == null) return m;
            List<HdmiDeviceInfo> l = hm.getConnectedDevices();
            if (l == null) return m;
            for (HdmiDeviceInfo d : l) {
                if (d == null || d.getPortId() <= 0) continue;
                HdmiDeviceInfo cur = m.get(d.getPortId());
                if (cur == null || rank(d) > rank(cur)) m.put(d.getPortId(), d);
            }
        } catch (Throwable t) {
            Log.i(App.TAG, "cec devices unavailable: " + t);
        }
        return m;
    }

    private static int rank(HdmiDeviceInfo d) {
        switch (d.getDeviceType()) {
            case HdmiDeviceInfo.DEVICE_PLAYBACK:
                return 3;
            case HdmiDeviceInfo.DEVICE_RECORDER:
            case HdmiDeviceInfo.DEVICE_TUNER:
                return 2;
            case HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM:
                return 1;
            default:
                return 0;
        }
    }

    /** org.lumen.tvinput's "hdmi_state" (HDMI_STATE perm), null when it cannot be read. */
    private static Bundle hdmiState(Context c) {
        try (ContentProviderClient cl = c.getContentResolver()
                .acquireUnstableContentProviderClient(Uri.parse("content://org.lumen.tvinput.hdmistate"))) {
            if (cl == null) return null;
            return cl.call("hdmi_state", null, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ports that currently have an open HDMI session ("ports" of hdmi_state). */
    private static int[] showingPorts(Bundle b) {
        try {
            int[] p = b != null ? b.getIntArray("ports") : null;
            return p != null ? p : new int[0];
        } catch (Throwable t) {
            return new int[0];
        }
    }

    // ------------------------------------------------------------------ the TV input app's device states

    /** Change notifications of the port states below (org.lumen.tvinput's provider), a content Uri. */
    public static final String PORTS_URI = "content://org.lumen.tvinput.hdmistate/ports";
    private static volatile boolean sPortStates;

    /** True once the TV input app reported per-port device states (then Home observes PORTS_URI). */
    public static boolean portStates() {
        return sPortStates;
    }

    /**
     * Port number -> the TV input app's bundle of the device behind it ("port_ids", "port_<n>": state, names,
     * glyph kind, accent, the input to open; docs of org.lumen.tvinput HdmiStateProvider). Empty when the app
     * does not report them (older TV input app): every card as before.
     */
    private static Map<Integer, Bundle> portDevices(Bundle b) {
        HashMap<Integer, Bundle> m = new HashMap<>();
        try {
            int[] ids = b != null ? b.getIntArray("port_ids") : null;
            if (ids == null) return m;
            sPortStates = true;
            for (int id : ids) {
                Bundle p = b.getBundle("port_" + id);
                if (p != null && p.getString("device_state") != null) m.put(id, p);
            }
        } catch (Throwable t) {
            m.clear();
        }
        return m;
    }

    /**
     * The card of a port the TV input app describes: its state (on, asleep, no signal, not connected; an
     * unknown state keeps the TIF one), the device's name over the port's label (a name the user gave the
     * input still wins), the glyph of its kind in a softened brand accent, and the input to open (the
     * device's own input routes to it, and on an asleep device the TV input app's viewer offers OK to wake).
     */
    private static void applyDevice(Context c, Card k, Bundle d, String custom, String portLabel) {
        int st = CecNames.deviceState(d.getString("device_state"));
        if (st < 0) return;
        String label = nonEmpty(d.getString("label"), portLabel);
        String title = d.getString("title");
        if (custom != null && !custom.isEmpty()) {
            k.title = custom;
            k.desc = nonEmpty(title, label);
        } else if (title != null && !title.trim().isEmpty()) {
            k.title = title.trim();
            k.desc = label;
        } else {
            k.title = label;
            k.desc = "";
        }
        k.state = st;
        k.meta = devText(c, st);
        String kind = d.getString("kind");
        if ("console".equals(kind)) k.icon = R.drawable.ic_gamepad;
        else if ("player".equals(kind)) k.icon = R.drawable.ic_player;
        int accent = d.getInt("accent", 0);
        k.color = accent == 0 || accent == NEUTRAL_ACCENT ? 0 : CecNames.softAccent(accent);
        String target = nonEmpty(d.getString("child_input_id"), d.getString("input_id"));
        if (target != null) k.intent = TvContract.buildChannelUriForPassthroughInput(target).toString();
    }

    /** The TV input app's colour for a device without a brand accent. */
    private static final int NEUTRAL_ACCENT = 0xFF3A3F47;

    public static String devText(Context c, int st) {
        switch (st) {
            case Card.DEV_ON: return c.getString(R.string.input_on);
            case Card.DEV_ASLEEP: return c.getString(R.string.input_asleep);
            case Card.DEV_NO_SIGNAL: return c.getString(R.string.input_no_signal);
            default: return c.getString(R.string.input_not_connected);
        }
    }

    private static String nonEmpty(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a.trim() : b;
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    // ------------------------------------------------------------------ cast + projector tiles

    public static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static String deviceName(Context c) {
        String n = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
        return n == null || n.isEmpty() ? "XGIMI Z9X" : n;
    }

    /**
     * Cast cards (Chromecast built-in, AirPlay). Not called since 1.0.1: their only place, the Inputs tab,
     * is gone and the owner wants no duplicate of it; kept with HomeActivity's cast how-to panel for an
     * entry point elsewhere, if one is ever wanted.
     */
    public static List<Card> casts(Context c) {
        ArrayList<Card> out = new ArrayList<>();
        String name = deviceName(c);
        if (installed(c, PKG_CHROMECAST)) {
            Card k = new Card(Card.CAST, "cast:chromecast", c.getString(R.string.cast_chromecast));
            k.icon = R.drawable.ic_cast;
            k.meta = c.getString(R.string.cast_ready, name);
            k.pkg = PKG_CHROMECAST;
            out.add(k);
        }
        if (installed(c, PKG_AIRPLAY)) {
            Card k = new Card(Card.CAST, "cast:airplay", c.getString(R.string.cast_airplay));
            k.icon = R.drawable.ic_airplay;
            k.meta = c.getString(R.string.cast_ready, name);
            k.pkg = PKG_AIRPLAY;
            out.add(k);
        }
        return out;
    }

    /**
     * Projector shortcuts (SPEC 5.6): intent = "section:x", "action:x" or "settings". A projector app with the
     * device-state provider may list tiles its projector does not support (ProjectorBridge.unsupportedTiles,
     * by intent or Card id): those are left out. Without the provider (the Z9X) every tile, as before.
     */
    public static List<Card> tiles(Context c) {
        ArrayList<Card> out = new ArrayList<>();
        tile(out, c, "action:autofocus", R.string.tile_autofocus, R.drawable.ic_autofocus);
        tile(out, c, "section:keystone", R.string.tile_keystone, R.drawable.ic_keystone);
        tile(out, c, "section:picture", R.string.tile_picture, R.drawable.ic_picture);
        tile(out, c, "section:sound", R.string.tile_sound, R.drawable.ic_sound);
        tile(out, c, "section:eye", R.string.tile_eye, R.drawable.ic_eye);
        // v1 power rules: the projector turns the lamp off and enters real sleep (STR); action id kept
        tile(out, c, "action:lamp_standby", R.string.tile_sleep, R.drawable.ic_power);
        tile(out, c, "action:sleep_timer", R.string.tile_sleep_timer, R.drawable.ic_timer);
        tile(out, c, "section:general", R.string.tile_projector_settings, R.drawable.ic_tune);
        tile(out, c, "settings", R.string.tile_android_settings, R.drawable.ic_settings);
        Set<String> off = ProjectorBridge.unsupportedTiles(c);
        if (!off.isEmpty()) out.removeIf(k -> off.contains(k.intent) || off.contains(k.id));
        return out;
    }

    private static void tile(List<Card> out, Context c, String intent, int label, int icon) {
        Card k = new Card(Card.TILE, "tile:" + intent, c.getString(label));
        k.intent = intent;
        k.icon = icon;
        out.add(k);
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
