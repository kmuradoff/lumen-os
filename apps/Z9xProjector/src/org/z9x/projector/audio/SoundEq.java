package org.z9x.projector.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.hal.GmpfClient;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v6.4 equalizer (not in the stock firmware): 7 bands on the SoC PEQ of the built-in speaker path,
 * IGmpf 50 SetPEQ / 51 SetPEQEnable. Curve maths and limits: {@link EqCurve}.
 *
 * Where it works (research/v64/peq, libporting + mik.ko disassembly): PlatformAudio::SetPEQ /
 * SetPEQEnable make one MI_AOUT_SetAttr(0x1001) on MI AOUT path 4 = EN_AUDIO_BUILD_IN_SPEAKER (the
 * output HDMI-in is connected to and that feeds the TAS5825 amp over I2S), so it also covers
 * offload and HDMI audio. The PEQ is independent of the DTS/DAP block (_MI_AOUT_EnableAdvSnd never
 * touches it), so it is in the speaker path in AMP (Harman) mode. In DTS Virtual:X the vendor
 * itself loads its [DTS_*] tuning into the same 13 bands (setPeqForINI): a user EQ would overwrite
 * it, so the EQ is offered in AMP mode only (26 == 2, 43 off) and shown disabled in DTS. Built-in
 * speaker only (BT / ARC / USB outputs do not pass this block).
 *
 * State: per sound mode (AI, Movie, Music, Sports, Karaoke = the 62 value), device-protected prefs
 * "z9x_eq": the chosen preset (Flat / Bass / Voice / Night / Custom) and the user's custom curve.
 * Flat = PEQ bypassed (51(false)).
 *
 * Apply (HAL thread, idempotent): re-read 43 / 26 / 62; DTS or unknown -> nothing. Flat -> 51(false)
 * only (and only when we turned it on, except for a user action). Otherwise 50(1..7, (user +
 * preamp) x 5 tenths, fc, Q 1.0), 50(8..13, 0, 1000, 10) (after DTS the struct still holds DTS
 * bands there), then 51(true); stop at the first reply != 1, never 51(true) after a failed 50.
 * No getter exists, so the vendor resetting it is covered by re-apply triggers:
 *  - PlatformAudio::setAdvSnd turns the PEQ off whenever it was on. Msrv::_setSoundEffectsMode calls
 *    it only when (flag != 0 || process changes) (evidence_auto_sound.ann @0x159c100..0x159c124):
 *    our 27 / 44 (process change) -> re-applied in the same HAL task (SoundProfiles.apply); our 61
 *    (flag 0 / AUTO store, same process: no setAdvSnd) -> the new mode's curve is written in full
 *    (afterUserModeChange: the mode switch already mutes the amp ~60 ms, and a full write repairs a
 *    PEQ the vendor turned off without our knowing). 63 sub-mode changes (flag 0, same process) do
 *    NOT reset it.
 *  - vendor restores with flag 1: boot (aud2_effect restore), Resume (wake), SetOutputType back to
 *    the speaker in AI mode. Primary trigger: {@link VendorSoundWatch} sees the vendor's
 *    _setSoundEffectsMode run itself (serial of vendor.xgimi.audio.switch.effect, not caused by our
 *    own call) -> re-apply once it settles. Backup schedules, for when the watch is off: boot: 3 s
 *    after vendor.xgimi.audio.current.effect_process is set plus BOOT_COMPLETED +20 / +130 / +180 s;
 *    SCREEN_ON +3 / +10 / +20 / +30 / +60 s (the Resume restore delay is the countdown
 *    Msrv_AudioManage_Control::m_s_u16InitEffetCountDown = u16 of AudioInfo_t+0x0c (this+0x93,
 *    Resume @0x159f168), x 100 ms; its ini value is not resolved); a confirmed 59 output report
 *    +600 ms; a HAL reconnect (gmpf_main restart zeroes libporting's struct) +3 s.
 * Every trigger re-applies only a non-flat curve (a flat one needs nothing: the vendor leaves the
 * PEQ off in AMP mode), and the same curve is not rewritten within {@link #DEDUP_MS} (each write
 * rewrites the DSP coefficients and may click during playback). Never IGmpf 48 SetPreScale (it goes
 * through SetVolumeDb) or 46/47 DRC; MTK AIAQ untouched.
 *
 * Threads: HAL calls on "z9x-hal" (Hal.run / runDelayed); timers on "z9x-eq". Never throws.
 */
public final class SoundEq {
    private static final String TAG = "Z9xEq";
    private static final String PREFS = "z9x_eq";
    private static final String PROP_EFFECT_PROCESS = "vendor.xgimi.audio.current.effect_process";

    /** Unused bands 8..13: 0 dB at 1 kHz, Q 1.0 (peq_spec.json unused_bands_8_13). */
    private static final int UNUSED_FC = 1000;
    /** Same curve (same wire values) not rewritten by a trigger within this window. */
    private static final long DEDUP_MS = 1_500;
    /** User changes: one apply per burst. */
    private static final long USER_DEBOUNCE_MS = 200;
    private static final long[] BOOT_BLIND_MS = {20_000, 130_000, 180_000};
    private static final long[] SCREEN_ON_MS = {3_000, 10_000, 20_000, 30_000, 60_000};
    private static final long PROP_POLL_MS = 1_000, PROP_WAIT_MAX_MS = 180_000, PROP_SETTLE_MS = 3_000;
    private static final long PATH_DELAY_MS = 600, RECONNECT_DELAY_MS = 3_000;

    private static Context sApp;
    private static Handler sH;
    private static boolean sInstalled;
    private static int sConnects;
    private static final AtomicBoolean sUserQueued = new AtomicBoolean();

    // HAL thread only
    /** We sent 51(true) last (the vendor may have turned it off since; no getter). */
    private static boolean sOnByUs;
    private static int[] sLastWire;
    private static long sLastWireAt;

    private SoundEq() {}

    /** One sound mode's equalizer setting. */
    public static final class Setting {
        public final int preset;          // EqCurve.PRESET_*
        public final int[] custom;        // half-dB, the user's own curve
        Setting(int preset, int[] custom) { this.preset = preset; this.custom = custom; }
        /** The curve in effect. */
        public int[] gains() {
            return preset == EqCurve.PRESET_CUSTOM ? custom.clone() : EqCurve.preset(preset);
        }
    }

    // ------------------------------------------------------------------ install / triggers
    /** Application.onCreate. Idempotent, never throws, no HAL call. */
    public static synchronized void install(Context ctx) {
        if (sInstalled) return;
        try {
            sInstalled = true;
            sApp = ctx.getApplicationContext();
            HandlerThread t = new HandlerThread("z9x-eq");
            t.start();
            sH = new Handler(t.getLooper());
            Hal.addConnectedListener(() -> {                       // "z9x-hal" thread
                if (++sConnects > 1) {
                    sOnByUs = false;                               // gmpf_main restarted: struct zeroed, PEQ state unknown
                    sLastWire = null;
                    reapply("HAL reconnected", RECONNECT_DELAY_MS);
                }
            });
            AudioPathReporter.addPathListener(path -> reapply("output path " + path + " confirmed", PATH_DELAY_MS));
            VendorSoundWatch.addListener(why -> reapply(why, 0));
            if ("1".equals(prop("sys.boot_completed"))) bootTriggers();   // persistent process restarted after boot
            Log.i(TAG, "installed");
        } catch (Throwable t) {
            Log.e(TAG, "install", t);
        }
    }

    /** BOOT_COMPLETED receiver. */
    public static void onBootCompleted(Context ctx) {
        install(ctx);
        bootTriggers();
    }

    /** SCREEN_ON (main thread): the vendor Resume restore (flag 1) may have turned the PEQ off. */
    public static void onScreenOn() {
        for (long d : SCREEN_ON_MS) reapply("screen on", d);
    }

    private static void bootTriggers() {
        Handler h = sH;
        if (h == null) return;
        final long start = SystemClock.elapsedRealtime();
        h.post(new Runnable() {
            @Override public void run() {
                if (!prop(PROP_EFFECT_PROCESS).isEmpty()) {
                    Log.i(TAG, PROP_EFFECT_PROCESS + "=" + prop(PROP_EFFECT_PROCESS) + ": re-apply in " + PROP_SETTLE_MS + " ms");
                    reapply("vendor effect restore", PROP_SETTLE_MS);
                    return;
                }
                if (SystemClock.elapsedRealtime() - start < PROP_WAIT_MAX_MS) sH.postDelayed(this, PROP_POLL_MS);
            }
        });
        for (long d : BOOT_BLIND_MS) reapply("boot +" + d / 1000 + " s", d);
    }

    /** Any thread: a trigger re-apply after {@code delayMs} (non-flat curves only, deduplicated). */
    public static void reapply(final String why, long delayMs) {
        Handler h = sH;
        if (h == null) return;
        h.postDelayed(() -> {
            if (!Hal.isHandshakeDone()) {
                Log.i(TAG, why + ": boot handshake not done, skipped");
                return;
            }
            Hal.run((g, g2) -> apply(g, why, false));
        }, Math.max(0, delayMs));
    }

    /** Main thread, user change of the curve / preset: one apply ~200 ms after the last change. */
    public static void requestUserApply() {
        Handler h = sH;
        if (h == null) return;
        if (!sUserQueued.compareAndSet(false, true)) return;
        h.postDelayed(() -> {
            sUserQueued.set(false);
            Hal.run((g, g2) -> apply(g, "user", true));
        }, USER_DEBOUNCE_MS);
    }

    // ------------------------------------------------------------------ HAL thread
    /** Automatic trigger re-apply: non-flat curves only, same curve skipped within {@link #DEDUP_MS}. */
    private static final int KIND_TRIGGER = 0;
    /** A user action (61, 27 / 44, an edit): always write, flat included. */
    private static final int KIND_FORCE = 2;

    /**
     * HAL thread: called by the sound setters right after our own 27 / 44 (process change: vendor
     * setAdvSnd turned the PEQ off). Never throws.
     */
    public static void afterVendorCall(GmpfClient g, String why) {
        apply(g, why, KIND_FORCE);
    }

    /**
     * HAL thread: called right after the user's own 61 (sound mode change). 61(non-3) runs
     * _setSoundEffectsMode(mode, 0) with the same process (no setAdvSnd) and 61(3) only stores the
     * AUTO flag (evidence_auto_sound.ann @0x159c100..0x159c124, @0x159ccc4..0x159cce4), so the PEQ
     * normally keeps what we wrote. Still a full write (KIND_FORCE): without a getter our "on" state
     * can be stale (a vendor restore, a gmpf_main restart), the mode switch is already audible (amp
     * table write, ~60 ms mute), and a user action is the natural moment to repair it. Never throws.
     */
    public static void afterUserModeChange(GmpfClient g, String why) {
        apply(g, why, KIND_FORCE);
    }

    static boolean apply(GmpfClient g, String why, boolean force) {
        return apply(g, why, force ? KIND_FORCE : KIND_TRIGGER);
    }

    /**
     * HAL thread. Applies the curve of the current sound mode. {@code kind}: KIND_FORCE also writes a
     * flat curve (51(false)) and ignores the dedup window; KIND_TRIGGER see {@link #reapply}. Returns
     * true when the PEQ is in the wanted state.
     */
    private static boolean apply(GmpfClient g, String why, int kind) {
        final boolean force = kind == KIND_FORCE;
        try {
            Boolean dts = null;
            Integer proc = null;
            int mode = -1;
            try { dts = g.getDtsEffects(); } catch (Throwable t) { Log.w(TAG, why + ": 43 " + t); }
            try { proc = g.getSoundProcessRaw(); } catch (Throwable t) { Log.w(TAG, why + ": 26 " + t); }
            try { mode = g.getSoundEffectRaw(); } catch (Throwable t) { Log.w(TAG, why + ": 62 " + t); }
            if (!Boolean.FALSE.equals(dts) || proc == null || proc != GmpfClient.SoundProcess.HARMAN.wire) {
                Log.i(TAG, why + ": not the AMP (Harman) process (26=" + proc + " 43=" + dts + "): the vendor owns the PEQ, nothing sent");
                sOnByUs = false;
                return false;
            }
            if (!validMode(mode)) {
                Log.i(TAG, why + ": sound mode 62=" + mode + " has no equalizer profile, nothing sent");
                return false;
            }
            int[] gains = EqCurve.sanitize(load(sApp, mode).gains());
            if (EqCurve.isFlat(gains)) {
                if (!force && !sOnByUs) return true;               // vendor default in AMP mode: off
                int r = g.setPeqEnabled(false);                     // 51(false)
                Log.i(TAG, why + ": mode " + mode + " flat: 51(false) -> " + r);
                sOnByUs = false;
                sLastWire = null;
                return r == 1;
            }
            int[] wire = EqCurve.wireTenths(gains);
            long now = SystemClock.elapsedRealtime();
            if (kind == KIND_TRIGGER && sOnByUs && sLastWire != null && Arrays.equals(sLastWire, wire) && now - sLastWireAt < DEDUP_MS) {
                Log.d(TAG, why + ": same curve written " + (now - sLastWireAt) + " ms ago, skipped");
                return true;
            }
            for (int b = 0; b < EqCurve.BANDS; b++) {
                int r = g.setPeqBand(b + 1, wire[b], EqCurve.FC_HZ[b], EqCurve.Q_TENTH);   // 50
                if (r != 1) {
                    Log.w(TAG, why + ": 50(" + (b + 1) + "," + wire[b] + "," + EqCurve.FC_HZ[b] + ",10) -> " + r + ": stopped, 51(true) not sent");
                    return false;
                }
            }
            for (int b = EqCurve.BANDS + 1; b <= GmpfClient.PEQ_BANDS; b++) {
                int r = g.setPeqBand(b, 0, UNUSED_FC, EqCurve.Q_TENTH);
                if (r != 1) {
                    Log.w(TAG, why + ": 50(" + b + ",0," + UNUSED_FC + ",10) -> " + r + ": stopped, 51(true) not sent");
                    return false;
                }
            }
            int r = g.setPeqEnabled(true);                          // 51(true): pushes all 13 bands
            sOnByUs = r == 1;
            sLastWire = sOnByUs ? wire : null;
            sLastWireAt = now;
            Log.i(TAG, why + ": mode " + mode + " EQ " + Arrays.toString(gains) + " (half-dB), preamp "
                    + EqCurve.label(EqCurve.preampShift(gains)) + " dB, sent " + Arrays.toString(wire) + " (0.1 dB); 51(true) -> " + r);
            return sOnByUs;
        } catch (Throwable t) {
            Log.w(TAG, why + ": " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ persistence
    /** The sound modes with their own equalizer setting (62 values: AI, Movie, Music, Sports, Karaoke). */
    public static boolean validMode(int mode) {
        return mode == GmpfClient.SoundEffect.AI.wire || mode == GmpfClient.SoundEffect.MOVIE.wire
                || mode == GmpfClient.SoundEffect.MUSIC.wire || mode == GmpfClient.SoundEffect.SPORTS.wire
                || mode == GmpfClient.SoundEffect.KARAOKE.wire;
    }

    private static SharedPreferences prefs(Context app) {
        try {
            return app.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    /** Any thread. Default: Flat, custom curve flat. */
    public static Setting load(Context app, int mode) {
        int preset = EqCurve.PRESET_FLAT;
        int[] custom = new int[EqCurve.BANDS];
        try {
            SharedPreferences p = prefs(app);
            preset = p.getInt("preset_" + mode, EqCurve.PRESET_FLAT);
            String c = p.getString("custom_" + mode, null);
            if (c != null) {
                String[] parts = c.split(",");
                for (int i = 0; i < EqCurve.BANDS && i < parts.length; i++) custom[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (Throwable t) {
            Log.w(TAG, "load " + mode + ": " + t);
        }
        if (preset < EqCurve.PRESET_FLAT || preset > EqCurve.PRESET_CUSTOM) preset = EqCurve.PRESET_FLAT;
        custom = EqCurve.sanitize(custom);
        if (!EqCurve.withinCap(custom)) custom = new int[EqCurve.BANDS];   // never apply an over-cap curve
        return new Setting(preset, custom);
    }

    /** Main thread: preset chosen (Custom = back to the saved custom curve). */
    public static void savePreset(Context app, int mode, int preset) {
        if (!validMode(mode)) return;
        try { prefs(app).edit().putInt("preset_" + mode, preset).apply(); } catch (Throwable t) { Log.w(TAG, "save preset: " + t); }
    }

    /** Main thread: the user's own curve (already within the limits); selects Custom (or the equal preset). */
    public static void saveCustom(Context app, int mode, int[] halfDb) {
        if (!validMode(mode)) return;
        int[] c = EqCurve.sanitize(halfDb);
        if (!EqCurve.withinCap(c)) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.length; i++) { if (i > 0) sb.append(','); sb.append(c[i]); }
        try {
            prefs(app).edit().putString("custom_" + mode, sb.toString())
                    .putInt("preset_" + mode, EqCurve.matchPreset(c)).apply();   // a curve equal to a preset shows that preset
        } catch (Throwable t) {
            Log.w(TAG, "save custom: " + t);
        }
    }

    private static String prop(String k) {
        try { return SystemProperties.get(k, ""); } catch (Throwable t) { return ""; }
    }
}
