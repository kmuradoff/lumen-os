package org.lumen.projector.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioProductStrategy;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Lumen OS 1.0.4 (proposal, NOT device-tested): the manual sound output of stock Sound > Output > Sound output
 * (voice_output: Auto, Internal speaker, ARC, Headphones, Bluetooth, USB; research/z9x_stock_settings/tree_annot.txt).
 *
 * Stock did it in the XGIMI service (GimiAudioService.setAudioDeviceOn: AudioSystem.setForceUse(FOR_MEDIA, c) with
 * c = 1 speaker, 2 headphones, 4 BT A2DP and XGIMI-only 16 ARC, 17 SPDIF, 18 USB, which only XGIMI's patched
 * audioserver accepts; Lumen's AOSP AudioSystem.NUM_FORCE_CONFIG is 16). Lumen uses the AOSP 14 way instead:
 * AudioManager.setPreferredDeviceForStrategy(media strategy, device) (@SystemApi, MODIFY_AUDIO_ROUTING, held: the
 * platform signature grants it from /system/app). The engine gives a preferred device that is available priority over
 * every other rule (also over the speaker + Spdif media pair Lumen routes to today), so getDevicesForAttributes(MEDIA)
 * changes and AudioPathReporter sends the stock switch (59(100), 1 s, 59(P)): the HDMI input sound follows the choice
 * through the vendor path, and HdmiVolume follows path 0.
 *
 * Rows: Auto and Speaker always; ARC / eARC, Bluetooth A2DP, wired and USB only while such an output is connected.
 * Never Spdif (attached port in the Z9X policy, always "available", no jack) and never the XGIMI wireless speaker
 * ports (BUS, ECHO_CANCELLER). Bluetooth and USB are categories, like stock: the chosen address first, else the
 * first connected device of that kind.
 *
 * The choice is stored (device-protected prefs "audio_output"); the role lives only in AudioService memory, so it is
 * applied at process start, at BOOT_COMPLETED, on every device add / remove and on every media routing change
 * (idempotent: the current preferred device is read first). The chosen output missing: the role is removed (Auto
 * until it is back), the choice stays.
 *
 * Audio format (stock voice_arc_format_auto_sound_v5, Auto / PCM / Passthrough): XGIMI service MtkAudio sent
 * setParameters("digital_output_format=pcm|bypass|auto"). Sent only after the user chose one (Lumen's HAL runs in
 * "auto" without it), again at boot and when ARC appears. Row shown only with ARC / eARC connected.
 *
 * Thread "z9x-audioout" for every AudioManager write. Log tag Z9xAudioOut.
 */
public final class AudioOutputChoice {
    private static final String TAG = "Z9xAudioOut";
    static final String PREFS = "audio_output";
    static final String KEY_KIND = "kind";
    static final String KEY_ADDR = "addr";
    static final String KEY_FORMAT = "digital_format";

    public enum Kind { AUTO, SPEAKER, ARC, BT, WIRED, USB }

    /** Stock wire values of digital_output_format (MtkAudio.setAudioDataFormatAndNoSave). UI order. */
    public enum Format {
        AUTO("auto"), PCM("pcm"), PASSTHROUGH("bypass");
        public final String wire;
        Format(String w) { wire = w; }
    }

    /** One row: a connected output (label = the device name for Bluetooth / USB). */
    public static final class Option {
        public final Kind kind;
        public final String addr;
        public final CharSequence label;
        Option(Kind k, String a, CharSequence l) { kind = k; addr = a == null ? "" : a; label = l; }
    }

    private static final AudioAttributes MEDIA = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).build();

    private static Handler sH;
    private static Context sApp;
    private static boolean sInstalled;
    private static boolean sArcSeen;

    private AudioOutputChoice() {}

    /** Application.onCreate. Idempotent, never throws. */
    public static synchronized void install(Context ctx) {
        if (sInstalled) return;
        try {
            sInstalled = true;
            sApp = ctx.getApplicationContext();
            HandlerThread t = new HandlerThread("z9x-audioout");
            t.start();
            sH = new Handler(t.getLooper());
            AudioManager am = sApp.getSystemService(AudioManager.class);
            if (am != null) {
                am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d) { apply("devices added"); arcFormat(); }
                    @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d) { apply("devices removed"); arcFormat(); }
                }, sH);
                try {
                    am.addOnDevicesForAttributesChangedListener(MEDIA, sH::post,
                            (attrs, devices) -> apply("media routing " + devices));
                } catch (Throwable e) {
                    Log.w(TAG, "routing listener not available: " + e);
                }
            }
            sH.post(() -> apply("process start"));
            Log.i(TAG, "installed, choice " + kind(sApp));
        } catch (Throwable t) {
            Log.e(TAG, "install", t);
        }
    }

    /** BOOT_COMPLETED receiver. */
    public static void onBootCompleted(Context ctx) {
        install(ctx);
        Handler h = sH;
        if (h == null) return;
        h.post(() -> apply("boot completed"));
        h.post(() -> sendFormat("boot completed"));
    }

    // ------------------------------------------------------------------ UI (main thread)
    /** Store the user's choice and apply it. */
    public static void choose(Context ctx, Kind k, String addr) {
        prefs(ctx).edit().putString(KEY_KIND, k.name()).putString(KEY_ADDR, addr == null ? "" : addr).apply();
        Log.i(TAG, "user chose " + k + (addr == null || addr.isEmpty() ? "" : " " + addr));
        install(ctx);
        Handler h = sH;
        if (h != null) h.post(() -> apply("user"));
    }

    public static void chooseFormat(Context ctx, Format f) {
        prefs(ctx).edit().putString(KEY_FORMAT, f.name()).apply();
        install(ctx);
        Handler h = sH;
        if (h != null) h.post(() -> sendFormat("user"));
    }

    /** The stored choice (AUTO when none or unreadable). */
    public static Kind kind(Context ctx) {
        try {
            return Kind.valueOf(prefs(ctx).getString(KEY_KIND, Kind.AUTO.name()));
        } catch (Throwable t) {
            return Kind.AUTO;
        }
    }

    /** UI index of the stored format; Auto when never chosen (Lumen's HAL default). */
    public static int formatIndex(Context ctx) {
        Format f = format(ctx);
        return f == null ? Format.AUTO.ordinal() : f.ordinal();
    }

    /** Rows to show now: Auto, Speaker, then each connected kind once (owner rule: hide, never disable). */
    public static List<Option> options(Context ctx) {
        List<Option> out = new ArrayList<>();
        out.add(new Option(Kind.AUTO, "", ctx.getString(org.lumen.projector.R.string.sound_output_auto)));
        out.add(new Option(Kind.SPEAKER, "", ctx.getString(org.lumen.projector.R.string.sound_output_speaker)));
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) return out;
        String addr = prefs(ctx).getString(KEY_ADDR, "");
        for (Kind k : new Kind[] {Kind.ARC, Kind.BT, Kind.WIRED, Kind.USB}) {
            AudioDeviceInfo d = find(am, k, k == kind(ctx) ? addr : "");
            if (d == null) continue;
            CharSequence label;
            switch (k) {
                case ARC: label = ctx.getString(d.getType() == AudioDeviceInfo.TYPE_HDMI_EARC
                        ? org.lumen.projector.R.string.sound_output_earc : org.lumen.projector.R.string.sound_output_arc); break;
                case WIRED: label = ctx.getString(org.lumen.projector.R.string.sound_output_wired); break;
                default: {
                    CharSequence n = d.getProductName();
                    label = n != null && n.length() > 0 ? n : ctx.getString(k == Kind.BT
                            ? org.lumen.projector.R.string.sound_output_bt : org.lumen.projector.R.string.sound_output_usb);
                }
            }
            out.add(new Option(k, d.getAddress(), label));
        }
        return out;
    }

    /** Selected row: the stored kind while it is listed, else Auto (the choice stays stored). */
    public static int selectedIndex(Context ctx, List<Option> opts) {
        Kind k = kind(ctx);
        for (int i = 0; i < opts.size(); i++) if (opts.get(i).kind == k) return i;
        return 0;
    }

    /** An HDMI ARC / eARC output is connected now (the Audio format row is shown only then). */
    public static boolean arcConnected(Context ctx) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        return am != null && find(am, Kind.ARC, "") != null;
    }

    // ------------------------------------------------------------------ z9x-audioout thread
    private static void apply(String why) {
        try {
            Context app = sApp;
            AudioManager am = app == null ? null : app.getSystemService(AudioManager.class);
            if (am == null) return;
            AudioProductStrategy media = mediaStrategy();
            if (media == null) {
                Log.w(TAG, why + ": no product strategy for USAGE_MEDIA");
                return;
            }
            Kind k = kind(app);
            AudioDeviceInfo d = k == Kind.AUTO ? null : find(am, k, prefs(app).getString(KEY_ADDR, ""));
            List<AudioDeviceAttributes> cur = am.getPreferredDevicesForStrategy(media);
            if (d == null) {
                if (cur != null && !cur.isEmpty() && ours(cur)) {
                    boolean ok = am.removePreferredDeviceForStrategy(media);
                    Log.i(TAG, why + ": " + (k == Kind.AUTO ? "auto" : k + " not connected, auto until it is back")
                            + ", preferred " + cur + " removed" + (ok ? "" : " (REFUSED)"));
                }
                return;
            }
            AudioDeviceAttributes want = new AudioDeviceAttributes(d);
            if (cur != null && cur.size() == 1 && same(cur.get(0), want)) return;    // already set
            boolean ok = am.setPreferredDeviceForStrategy(media, want);
            Log.i(TAG, why + ": media -> " + k + " (type " + want.getType() + " '" + want.getAddress() + "')"
                    + (ok ? "" : " REFUSED"));
        } catch (Throwable t) {
            Log.w(TAG, why + ": " + t);
        }
    }

    /** ARC appeared: send the stored format again (the HAL may have reset it). */
    private static void arcFormat() {
        Context app = sApp;
        if (app == null) return;
        boolean arc = arcConnected(app);
        if (arc && !sArcSeen) sendFormat("ARC connected");
        sArcSeen = arc;
    }

    private static void sendFormat(String why) {
        Context app = sApp;
        Format f = app == null ? null : format(app);
        if (f == null) return;                                   // never chosen: leave the HAL's own mode
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            if (am == null) return;
            am.setParameters("digital_output_format=" + f.wire);
            Log.i(TAG, "digital_output_format=" + f.wire + " (" + why + ")");
        } catch (Throwable t) {
            Log.w(TAG, "digital_output_format: " + t);
        }
    }

    // ------------------------------------------------------------------ helpers
    private static AudioProductStrategy mediaStrategy() {
        for (AudioProductStrategy s : AudioManager.getAudioProductStrategies()) {
            if (s.supportsAudioAttributes(MEDIA)) return s;
        }
        return null;
    }

    /** A connected output of this kind: the given address first, else the first one. */
    private static AudioDeviceInfo find(AudioManager am, Kind k, String addr) {
        AudioDeviceInfo first = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!is(k, d.getType())) continue;
            if (k == Kind.ARC && d.getType() == AudioDeviceInfo.TYPE_HDMI_EARC) return d;   // eARC before ARC
            if (addr != null && !addr.isEmpty() && addr.equals(d.getAddress())) return d;
            if (first == null) first = d;
        }
        return first;
    }

    private static boolean is(Kind k, int type) {
        switch (k) {
            case SPEAKER: return type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
            case ARC: return type == AudioDeviceInfo.TYPE_HDMI_ARC || type == AudioDeviceInfo.TYPE_HDMI_EARC;
            case BT: return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
            case WIRED: return type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET;
            case USB: return type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET;
            default: return false;
        }
    }

    /** Only remove a role of a kind this class sets (never an internal role of another output). */
    private static boolean ours(List<AudioDeviceAttributes> cur) {
        for (AudioDeviceAttributes a : cur) {
            for (Kind k : Kind.values()) if (k != Kind.AUTO && is(k, a.getType())) return true;
        }
        return false;
    }

    private static boolean same(AudioDeviceAttributes a, AudioDeviceAttributes b) {
        return a.getType() == b.getType() && String.valueOf(a.getAddress()).equals(String.valueOf(b.getAddress()));
    }

    private static Format format(Context ctx) {
        try {
            String v = prefs(ctx).getString(KEY_FORMAT, null);
            return v == null ? null : Format.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        Context c = ctx.getApplicationContext();
        try {
            return c.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }
}
