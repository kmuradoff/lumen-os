package org.z9x.projector.audio;

/**
 * v6.4 equalizer maths (pure Java, no android.*: unit-testable on the Mac). Mirrors
 * research/v64/peq/peq_model.py: the SoC PEQ of the speaker path (IGmpf 50/51, MTK MI_AOUT basic-sound
 * PEQ) runs peaking biquads only, modelled as RBJ "Audio EQ Cookbook" peaking filters at 48 kHz.
 *
 * Bands: 60 / 150 / 400 / 1k / 2.4k / 6k / 15k Hz, all Q 1.0 (qTenth 10). Gains are kept in half-dB
 * steps (int, 1 = 0.5 dB). Slider range -6..+6 dB, band 1 (60 Hz) boost capped at +3 dB (the UI says
 * so when UP hits it, string eq_bass_limit; a deviation from the +-6 dB request): every
 * G0082 TAS5805M amp table already cuts 60 Hz by 13-21 dB (research/v63/ampeq), so a 60 Hz boost
 * would mostly eat headroom (peq_spec.json bands[0].note).
 *
 * Limits (peq_spec.json auto_preamp):
 *  - the COMBINED user curve (sum of the 7 filters on a 600-point log grid 20 Hz..20 kHz) must stay
 *    <= +6 dB ({@link #withinCap}); per-slider +6 alone is not enough (7 x +6 dB = +8.74 dB);
 *  - automatic preamp: the only flat "preamp" the PEQ offers is a uniform downward shift of all 7
 *    bands (IGmpf 48 SetPreScale goes through SetVolumeDb and is not used). {@link #preampShift}
 *    lowers all bands in 0.5 dB steps until the combined curve is <= HEADROOM (+3 dB, the vendor's
 *    own DTS curve peaks at +5.5 dB). Presets: Bass -2.0 dB, Voice -0.5 dB, Night 0 (model results).
 */
public final class EqCurve {
    public static final int BANDS = 7;
    public static final int[] FC_HZ = {60, 150, 400, 1000, 2400, 6000, 15000};
    public static final int Q_TENTH = 10;
    /** Per-band limits in half-dB (-6 dB .. +6 dB; band 1 boost +3 dB). */
    public static final int[] MIN_HALF = {-12, -12, -12, -12, -12, -12, -12};
    public static final int[] MAX_HALF = {6, 12, 12, 12, 12, 12, 12};
    /** Combined user curve limit, dB. */
    public static final double CAP_DB = 6.0;
    /** Target peak of the combined SENT curve after the automatic preamp shift, dB. */
    public static final double HEADROOM_DB = 3.0;
    /** Largest automatic shift (half-dB): -6 dB. A cut of -6 dB then goes out as -12 dB at most. */
    public static final int MAX_SHIFT_HALF = 12;

    public static final int PRESET_FLAT = 0, PRESET_BASS = 1, PRESET_VOICE = 2, PRESET_NIGHT = 3, PRESET_CUSTOM = 4;
    /** Presets in half-dB (peq_spec.json presets_dB x 2): Flat, Bass, Voice, Night. */
    static final int[][] PRESETS = {
            {0, 0, 0, 0, 0, 0, 0},
            {4, 9, 3, 0, 0, 0, 1},          // Bass  [2, 4.5, 1.5, 0, 0, 0, 0.5]
            {-4, -4, -1, 3, 6, 3, 0},       // Voice [-2, -2, -0.5, 1.5, 3, 1.5, 0]
            {-8, -6, -2, 0, 3, 2, 0},       // Night [-4, -3, -1, 0, 1.5, 1, 0]: less bass, slight presence
    };

    private static final double FS = 48_000.0;
    private static final int GRID = 600;
    private static final double[] COS1 = new double[GRID], SIN1 = new double[GRID];
    private static final double[] COS2 = new double[GRID], SIN2 = new double[GRID];

    static {
        double lo = Math.log(20.0), hi = Math.log(20_000.0);
        for (int i = 0; i < GRID; i++) {
            double f = Math.exp(lo + (hi - lo) * i / (GRID - 1));
            double w = 2 * Math.PI * f / FS;
            COS1[i] = Math.cos(w);
            SIN1[i] = Math.sin(w);
            COS2[i] = Math.cos(2 * w);
            SIN2[i] = Math.sin(2 * w);
        }
    }

    private EqCurve() {}

    /** A copy of preset {@code p} (0..3), or of Flat. */
    public static int[] preset(int p) {
        return (p >= 0 && p < PRESETS.length ? PRESETS[p] : PRESETS[PRESET_FLAT]).clone();
    }

    /** Index of the preset equal to {@code halfDb}, or {@link #PRESET_CUSTOM}. */
    public static int matchPreset(int[] halfDb) {
        for (int p = 0; p < PRESETS.length; p++) if (java.util.Arrays.equals(PRESETS[p], halfDb)) return p;
        return PRESET_CUSTOM;
    }

    public static boolean isFlat(int[] halfDb) {
        for (int v : halfDb) if (v != 0) return false;
        return true;
    }

    /** Clamped to the per-band limits (a corrupt pref never reaches the HAL out of range). */
    public static int[] sanitize(int[] halfDb) {
        int[] out = new int[BANDS];
        if (halfDb == null) return out;
        for (int i = 0; i < BANDS && i < halfDb.length; i++) out[i] = Math.max(MIN_HALF[i], Math.min(MAX_HALF[i], halfDb[i]));
        return out;
    }

    /** Peak (max over 20 Hz..20 kHz) of the combined curve, dB; {@code shiftHalf} is added to every band. */
    public static double peakDb(int[] halfDb, int shiftHalf) {
        double[] sum = new double[GRID];
        for (int b = 0; b < BANDS; b++) {
            double g = (halfDb[b] + shiftHalf) / 2.0;
            if (Math.abs(g) < 1e-9) continue;
            addPeaking(sum, FC_HZ[b], g, Q_TENTH / 10.0);
        }
        double max = -1e9;
        for (double v : sum) max = Math.max(max, v);
        return max;
    }

    /** The user curve obeys the combined +6 dB cap (same 0.05 dB tolerance as peq_model.py). */
    public static boolean withinCap(int[] halfDb) {
        return peakDb(halfDb, 0) <= CAP_DB + 0.05;
    }

    /**
     * Automatic preamp (half-dB, <= 0): peq_model.autopreamp(cap = HEADROOM_DB, step 0.5 dB), i.e.
     * the smallest uniform cut that brings the combined sent curve to <= +3 dB, at most -6 dB.
     */
    public static int preampShift(int[] halfDb) {
        int shift = 0;
        while (peakDb(halfDb, shift) > HEADROOM_DB + 0.05 && shift > -MAX_SHIFT_HALF) shift--;
        return shift;
    }

    /** Gains as sent to IGmpf 50, in 0.1 dB: (user + preamp) x 5. */
    public static int[] wireTenths(int[] halfDb) {
        int shift = preampShift(halfDb);
        int[] out = new int[BANDS];
        for (int b = 0; b < BANDS; b++) out[b] = (halfDb[b] + shift) * 5;
        return out;
    }

    /** "+1.5 dB" style label of a half-dB value. */
    public static String label(int half) {
        String n = (half % 2 == 0) ? Integer.toString(Math.abs(half) / 2) : (Math.abs(half) / 2) + "." + 5;
        return (half > 0 ? "+" : half < 0 ? "−" : "") + n;
    }

    /** Short frequency label: 60, 150, 400, 1k, 2.4k, 6k, 15k. */
    public static String freqLabel(int b) {
        int f = FC_HZ[b];
        if (f < 1000) return Integer.toString(f);
        return (f % 1000 == 0 ? Integer.toString(f / 1000) : (f / 1000) + "." + (f % 1000) / 100) + "k";
    }

    /** Adds the dB response of one RBJ peaking filter (f0, gain, Q) at 48 kHz to {@code acc}. */
    private static void addPeaking(double[] acc, double f0, double gainDb, double q) {
        double a = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2 * Math.PI * f0 / FS;
        double al = Math.sin(w0) / (2 * q);
        double b0 = 1 + al * a, b1 = -2 * Math.cos(w0), b2 = 1 - al * a;
        double a0 = 1 + al / a, a1 = b1, a2 = 1 - al / a;
        for (int i = 0; i < GRID; i++) {
            // H(e^jw) with z^-1 = cos w - j sin w
            double nr = b0 + b1 * COS1[i] + b2 * COS2[i], ni = -(b1 * SIN1[i] + b2 * SIN2[i]);
            double dr = a0 + a1 * COS1[i] + a2 * COS2[i], di = -(a1 * SIN1[i] + a2 * SIN2[i]);
            acc[i] += 10.0 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di));
        }
    }
}
