package org.z9x.projector.audio;

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
 * Play 6 fork of the Z9X class (tools/play6/forks.tsv), Lumen OS 1.0.6, owner 2026-10-24 ("in the settings there is
 * no way to choose where the sound goes, as on the Z9X"): the manual sound output of stock Sound > Sound output,
 * done the AOSP 14 way (AudioManager.setPreferredDeviceForStrategy on the media strategy, MODIFY_AUDIO_ROUTING held
 * through the platform signature). The engine gives an available preferred device priority over every other rule;
 * BtAudio then moves the vendor sink (xgimi_audiopath_switch_end) with the media route, as for Auto.
 *
 * Rows: Auto and Speaker always; HDMI ARC / eARC, Bluetooth A2DP, wired and USB only while such an output is
 * connected (the Play 6 policy has no attached Spdif, dumpsys media.audio_policy 2026-10-24). Bluetooth and USB are
 * categories, like stock: the chosen address first, else the first connected device of that kind. Not ported: the
 * Z9X "Audio output format" (digital_output_format is a parameter of the Z9X HAL, not checked on the mt5889).
 *
 * The choice is stored (device-protected prefs "audio_output"); the role lives only in AudioService memory, so it is
 * applied at process start, at BOOT_COMPLETED, on every device add / remove and on every media routing change
 * (idempotent: the current preferred device is read first). The chosen output missing: the role is removed (Auto
 * until it is back), the choice stays. Thread "p6-audioout" for every AudioManager write. Log tag P6AudioOut.
 */
public final class AudioOutputChoice {
    private static final String TAG = "P6AudioOut";
    static final String PREFS = "audio_output";
    static final String KEY_KIND = "kind";
    static final String KEY_ADDR = "addr";

    public enum Kind { AUTO, SPEAKER, ARC, BT, WIRED, USB }

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

    private AudioOutputChoice() {}

    /** Application.onCreate. Idempotent, never throws. */
    public static synchronized void install(Context ctx) {
        if (sInstalled) return;
        try {
            sInstalled = true;
            sApp = ctx.getApplicationContext();
            HandlerThread t = new HandlerThread("p6-audioout");
            t.start();
            sH = new Handler(t.getLooper());
            AudioManager am = sApp.getSystemService(AudioManager.class);
            if (am != null) {
                am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d) { apply("devices added"); }
                    @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d) { apply("devices removed"); }
                }, sH);
                try {
                    am.addOnDevicesForAttributesChangedListener(MEDIA, sH::post,
                            (attrs, devices) -> apply("media routing " + devices));
                } catch (Throwable e) {
                    Log.w(TAG, "routing listener not available: " + e);
                }
            }
            sH.post(() -> apply("process start"));
            // the Play 6 boot receiver is frozen (check_handshake): the boot-time re-apply is timed instead
            sH.postDelayed(() -> apply("after boot"), 30_000L);
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

    /** The stored choice (AUTO when none or unreadable). */
    public static Kind kind(Context ctx) {
        try {
            return Kind.valueOf(prefs(ctx).getString(KEY_KIND, Kind.AUTO.name()));
        } catch (Throwable t) {
            return Kind.AUTO;
        }
    }

    /** Rows to show now: Auto, Speaker, then each connected kind once (owner rule: hide, never disable). */
    public static List<Option> options(Context ctx) {
        List<Option> out = new ArrayList<>();
        out.add(new Option(Kind.AUTO, "", ctx.getString(org.z9x.projector.R.string.sound_output_auto)));
        out.add(new Option(Kind.SPEAKER, "", ctx.getString(org.z9x.projector.R.string.sound_output_speaker)));
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) return out;
        String addr = prefs(ctx).getString(KEY_ADDR, "");
        for (Kind k : new Kind[] {Kind.ARC, Kind.BT, Kind.WIRED, Kind.USB}) {
            AudioDeviceInfo d = find(am, k, k == kind(ctx) ? addr : "");
            if (d == null) continue;
            CharSequence label;
            switch (k) {
                case ARC: label = ctx.getString(d.getType() == AudioDeviceInfo.TYPE_HDMI_EARC
                        ? org.z9x.projector.R.string.sound_output_earc : org.z9x.projector.R.string.sound_output_arc); break;
                case WIRED: label = ctx.getString(org.z9x.projector.R.string.sound_output_wired); break;
                default: {
                    CharSequence n = d.getProductName();
                    label = n != null && n.length() > 0 ? n : ctx.getString(k == Kind.BT
                            ? org.z9x.projector.R.string.sound_output_bt : org.z9x.projector.R.string.sound_output_usb);
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

    private static SharedPreferences prefs(Context ctx) {
        Context c = ctx.getApplicationContext();
        try {
            return c.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }
}
