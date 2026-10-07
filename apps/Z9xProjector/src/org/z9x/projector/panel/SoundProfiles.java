package org.z9x.projector.panel;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.z9x.projector.audio.AudioPathReporter;
import org.z9x.projector.hal.GmpfClient;

/**
 * v6.3.1 "Sound enhancement" row of the quick panel: Harman Kardon / DTS Virtual:X (stock values),
 * plus the sound-mode list and the boot safety check. research/v63/ampeq (decoded TAS5805M tables,
 * TI SLOA263A map) is binding:
 *
 *  - The v6.3 "Z9X" profile is removed. It left the amp on its INIT table only, and INIT is the
 *    thinnest speaker table (4th-order high-pass at 110-115 Hz; 13-15 dB less than Harman MOVIE at
 *    80-100 Hz). Harman MOVIE is the bassiest protected table and the default here.
 *  - Sound modes = the 61 values with a real G0082 *_5805 table: 1 Movie (movie_5805), 2 Music,
 *    12 Sports (sport_5805), 4 Karaoke (ktv_5805), and v6.4 AI (3) first, as stock: the vendor AUTO
 *    path over those 4 tables, with the sub-mode per foreground app from audio.AiSoundEngine
 *    (63 + xgimi.pkg.effect.mod; v6.3.x hid it because nothing chose the sub-mode, so it was always
 *    Movie). Standard (20) is "TI58XX mode err" (nothing written; after a reboot the amp keeps INIT
 *    only). Never sent: 61 = 5, 6, 7, 8, 10, 11, 13, 14, 15, 16, 20, 50-53, 194, 200; 39; the
 *    persist.vendor.xgimi.amptest hook. 61(3) on a user action only.
 *  - 27 setSoundProcessType / 44 apply only while the vendor output path is 0 (speaker). Our GSI
 *    reports the path with {@link AudioPathReporter} (IGmpf 59, as stock audioserver did); before
 *    that, the vendor path is 100, every effect is forced to the AMP (Harman) process and 27 only
 *    writes the DB.
 *
 * State mapping (UI): 43 == true -> unknown ("—", choosing re-applies); 26 == 2 -> Harman;
 * 26 == 5 -> DTS only when path reporting is active ({@link AudioPathReporter#isActive}: 59 sent,
 * vender.xgimi.audio.ready == 1 and 60 getAudioOutput == that path), otherwise Harman (that is what
 * plays: the amp runs the Harman process) with a hint; anything else -> unknown.
 * DTS sub-options (TruBass 24/25, Dialog Clarity 18/19, TruSurround 22/23, TruVolume 20/21) are not
 * offered: the stock Z9X sound menu (FEATURE_SPEC 4) shows none, so they are unverified as stock UI.
 *
 * Boot safety ({@link #bootCheckOnce}): once per process after the boot handshake (and before the
 * first 59 report), 62 is read; when it is 20 or any value that is not one of the 5 modes above,
 * 61(1) Movie is sent (3 = AI is valid since v6.4: the vendor restores AUTO itself). That fixes this unit's current DB (mode 20 from the v6.3 Z9X sequence), which would load the
 * INIT table only at the next boot or wake.
 *
 * Volume levelling (41/42): the stock default is off (this unit's DB read enable:0 before our app
 * ever sent 42; volume_balance_enable is not in CustomerEnvTbl). A sound-enhancement choice sends
 * 42(false) only while 41 reads true (42 mutes the amp ~750 ms on the speaker path).
 *
 * v6.4 equalizer: every 27 / 44 here is followed by audio.SoundEq.afterVendorCall (the vendor's
 * setAdvSnd turns the SoC PEQ off on a process change); that forced apply also covers a 61 sent by
 * ensureMode in the same task.
 *
 * All HAL work runs on the "z9x-hal" thread; setters on a user action, except the boot check.
 */
public final class SoundProfiles {
    private static final String TAG = "Z9xSound";

    enum Profile { HARMAN, DTS }

    /** Outcome of {@link #apply}. */
    enum Result {
        /** The read-back shows the profile and it is running. */
        APPLIED,
        /** DTS stored; the vendor applies DTS on the built-in speaker only (current path is not 0). */
        SPEAKER_ONLY,
        /** DTS stored; output-path reporting is not active yet, so the amp still runs Harman. */
        OUTPUT_NOT_READY,
        /** The read-back does not show the profile (or is unknown). */
        NOT_CONFIRMED
    }

    /** Order of the "Sound enhancement" choices (labels: Harman Kardon, DTS Virtual:X). */
    static final Profile[] ORDER = {Profile.HARMAN, Profile.DTS};

    /** Sound modes offered: AI first (as stock), then the 4 real amp tables; Movie = default. */
    static final GmpfClient.SoundEffect[] MODES = {
            GmpfClient.SoundEffect.AI, GmpfClient.SoundEffect.MOVIE, GmpfClient.SoundEffect.MUSIC,
            GmpfClient.SoundEffect.SPORTS, GmpfClient.SoundEffect.KARAOKE};

    /** Device-protected prefs (readable before the first unlock). */
    private static final String PREFS = "z9x_sound";
    /** int, SoundEffect wire: the sound mode chosen last (restored when 62 is not a valid mode). */
    private static final String KEY_MODE = "sound_mode";

    /** HAL thread only: the boot check ran in this process. */
    private static boolean sBootChecked;

    private SoundProfiles() {}

    /**
     * The profile to show, or null (unknown / 43 on / a getter failed). {@code pathActive}: output
     * path reporting is active ({@link AudioPathReporter#isActive}).
     */
    static Profile fromState(Integer process, Boolean dtsEffects, boolean pathActive) {
        if (dtsEffects == null || dtsEffects) return null;        // 43 true: neither option (QS)
        if (process == null) return null;
        if (process == GmpfClient.SoundProcess.DTS_VIRTUAL_X.wire) return pathActive ? Profile.DTS : Profile.HARMAN;
        if (process == GmpfClient.SoundProcess.HARMAN.wire) return Profile.HARMAN;
        return null;
    }

    /** True when the vendor DB holds DTS Virtual:X (26 == 5, 43 off), whether or not it runs. */
    static boolean dtsStored(Integer process, Boolean dtsEffects) {
        return Boolean.FALSE.equals(dtsEffects) && process != null
                && process == GmpfClient.SoundProcess.DTS_VIRTUAL_X.wire;
    }

    static int indexOf(Profile p) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == p) return i;
        return -1;
    }

    static int modeIndex(Integer wire) {
        if (wire == null) return -1;
        for (int i = 0; i < MODES.length; i++) if (MODES[i].wire == wire) return i;
        return -1;
    }

    private static SharedPreferences prefs(Context app) {
        try {
            return app.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    /** The sound mode to restore when 62 is not a valid mode (default Movie). */
    static GmpfClient.SoundEffect lastMode(Context app) {
        int w = GmpfClient.SoundEffect.MOVIE.wire;
        try { w = prefs(app).getInt(KEY_MODE, w); } catch (Throwable ignored) { }
        int i = modeIndex(w);
        return i >= 0 ? MODES[i] : GmpfClient.SoundEffect.MOVIE;
    }

    static void rememberMode(Context app, GmpfClient.SoundEffect e) {
        if (e == null || modeIndex(e.wire) < 0) return;
        try { prefs(app).edit().putInt(KEY_MODE, e.wire).apply(); } catch (Throwable ignored) { }
    }

    /**
     * Boot safety, HAL thread, once per process (callers: AudioPathReporter after the boot handshake,
     * right before its first 59 report). 62 == 20 or a value that is not one of {@link #MODES} ->
     * 61(1) Movie. 62 == 3 (AI) is valid and starts audio.AiSoundEngine. A failed read is retried at
     * the next call. Never throws.
     */
    public static void bootCheckOnce(Context app, GmpfClient g) {
        if (sBootChecked) return;
        int raw;
        try {
            raw = g.getSoundEffectRaw();
        } catch (Throwable t) {
            Log.w(TAG, "boot check: 62 " + t);
            return;
        }
        sBootChecked = true;
        if (modeIndex(raw) >= 0) {
            Log.i(TAG, "boot check: 62=" + raw + " ok");
            org.z9x.projector.audio.AiSoundEngine.noteMode(raw, "boot check");
            return;
        }
        try {
            g.setSoundEffect(GmpfClient.SoundEffect.MOVIE);                        // 61(u8 1)
            if (app != null) rememberMode(app, GmpfClient.SoundEffect.MOVIE);
            int after = -1;
            try { after = g.getSoundEffectRaw(); } catch (Throwable ignored) { }
            org.z9x.projector.audio.AiSoundEngine.noteMode(after, "boot check");
            Log.w(TAG, "boot check: 62=" + raw + " has no amp table (20 = INIT only after a reboot): 61(1) Movie -> 62=" + after);
        } catch (Throwable t) {
            Log.w(TAG, "boot check: 61(1) " + t);
        }
    }

    /**
     * HAL thread, user action only. Applies {@code p} and says honestly what it achieved. Throws on
     * a transport error (the caller shows "Could not do that").
     */
    static Result apply(Context app, GmpfClient g, Profile p) throws Exception {
        // 41/42: back to the stock default "off" only when it is on.
        Boolean vb = null;
        try { vb = g.getVolumeBalance(); } catch (Throwable t) { Log.w(TAG, "41: " + t); }
        if (Boolean.TRUE.equals(vb)) {
            boolean r = g.disableVolumeBalance();                                  // 42(false)
            Log.i(TAG, "volume levelling was on: 42(false) -> " + r);
        }
        GmpfClient.SoundProcess proc = p == Profile.DTS
                ? GmpfClient.SoundProcess.DTS_VIRTUAL_X : GmpfClient.SoundProcess.HARMAN;
        boolean ok = g.setSoundEnhancement(proc);                                  // 44(false), 27(2|5)
        ensureMode(app, g);
        // v6.4: the process change ran setAdvSnd (SoC PEQ off); the EQ of the mode is re-applied in AMP
        // mode, nothing is sent in DTS (the vendor's own DTS bands live there).
        org.z9x.projector.audio.SoundEq.afterVendorCall(g, "27(" + proc.wire + ")");
        Integer process = null;
        Boolean dts = null;
        try { process = g.getSoundProcessRaw(); } catch (Throwable t) { Log.w(TAG, "26: " + t); }
        try { dts = g.getDtsEffects(); } catch (Throwable t) { Log.w(TAG, "43: " + t); }
        boolean active = AudioPathReporter.isActive(g);
        int path = AudioPathReporter.confirmedPath();
        Log.i(TAG, "apply " + p + ": 27(" + proc.wire + ") -> " + ok + ", read-back 26=" + process
                + " 43=" + dts + ", path active=" + active + " (" + path + ")");
        if (!ok || process == null || process != proc.wire || !Boolean.FALSE.equals(dts)) return Result.NOT_CONFIRMED;
        if (p == Profile.HARMAN) return Result.APPLIED;          // Harman plays with or without the path
        if (!active) return Result.OUTPUT_NOT_READY;
        if (path != GmpfClient.AudioPath.SPEAKER.wire) return Result.SPEAKER_ONLY;
        return Result.APPLIED;
    }

    /** 62 not a valid mode (20, a read failure) -> 61(last mode; AI through AiSoundEngine). HAL thread. */
    private static void ensureMode(Context app, GmpfClient g) {
        int raw = -1;
        try { raw = g.getSoundEffectRaw(); } catch (Throwable t) { Log.w(TAG, "62: " + t); }
        if (modeIndex(raw) >= 0) return;
        GmpfClient.SoundEffect m = lastMode(app);
        try {
            if (m == GmpfClient.SoundEffect.AI) org.z9x.projector.audio.AiSoundEngine.userChoseAi(g);   // prop + 61(3)
            else g.setSoundEffect(m);                                              // 61
            Log.i(TAG, "62=" + raw + " is not a valid mode: 61(" + m.wire + ")");
        } catch (Throwable t) {
            Log.w(TAG, "61(" + m.wire + "): " + t);
        }
    }
}
