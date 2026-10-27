package org.lumen.sensors.z9x;

import org.lumen.sensors.hal.GmpfAllowlist;

/**
 * Z9X ONLY: the XGIMI HAL read plan of the Z9X HAL source, plain Java so the host test checks it: the
 * period of every read, the gap between two calls, the input rule of the two signal reads and the
 * ToF rule.
 *
 * Why so slow (device test of 0.1, 2026-10-27): the vendor serves every gmpf call of every app on ONE
 * thread (gmpf_main), each call writes vendor log lines, and Projector's own calls (keystone, focus,
 * brightness) wait behind ours. 0.1 asked for 6.3 calls per second in detailed mode and got about 4.8
 * (its cap was 5) for values that change slowly. Periods now ({@link #kindOf}):
 *   ROUND    max(4 s, refresh)   196 (the light gate), 438, 439 per sensor, 435, 437 per fan
 *   SLOW     10 s                177, 175, IGmpf2 51, 25, 252 (light); 696, 620, 157, 662 or IGmpf2 319,
 *                                IGmpf2 22 (picture); 584, 266 (motion, focus); IGmpf2 241
 *   VOLTAGE  30 s                IGmpf2 68
 *   TOF      60 s                279 ({@link #TOF_MS})
 *   ONCE     once per start      IGmpf2 27 (light usage), 339 (inside the sensor probe); a failed
 *                                read is tried again after 10 s
 * These are the shortest periods. The first reads of a start go out at least {@link #GAP_MS} apart,
 * every later read at least {@link #STEADY_GAP_MS} apart: after the probes never more than 2 calls in a
 * second, under half of 0.1's 4.8. When the wanted reads ask for more than that, every period stretches by the same
 * few seconds ({@link #nextDue}). The sysfs values are not here: they follow the refresh setting (cheap).
 *
 * Steady calls per second (every group on, refresh 2 s so ROUND = 4 s, HDMI input, 4 temperature
 * sensors and 1 fan as on the owner's Z9X, 68 absent on mains; the host test repeats these sums and
 * runs a model of the pump):
 *   detailed  asks for ROUND 196 + 438 + 4 x 439 + 435 + 437 = 8 calls / 4 s = 2.0
 *                      SLOW  177 175 51 25 252 696 620 157 662 22 584 266 241 = 13 calls / 10 s = 1.3
 *                      TOF   1 call / 60 s = 0.017                   together about 3.3 per second;
 *             gets 2 per second at most: 196 every 4 s, the other round reads about every 8 s, the
 *             10 s reads about every 14 s, ToF about every 65 s
 *   compact   196 + 3 x 439 (air, DMD, LED) + 437 = 5 calls / 4 s = 1.25 per second (1.0 at refresh 5 s)
 *   graphs    196 + 438 + 4 x 439 = 6 calls / 4 s = 1.5 per second
 * Compact and graphs ask for less than the cap, so their periods are kept exactly.
 */
public final class Z9xSchedule {
    private Z9xSchedule() {}

    /**
     * At least this long between the end of one call and the start of a first read (probe step) of a
     * start: 5 per second at most, for the few seconds the probes take.
     */
    public static final long GAP_MS = 200;
    /**
     * At least this long between the end of one call and the start of any later read, ToF included (a
     * 196 goes at the pace of the read it comes before): 2 per second at most, and no bursts.
     */
    public static final long STEADY_GAP_MS = 500;
    /** A read that went out later than this after its due time is next due a period after its call. */
    public static final long LATE_MS = 2_000;
    public static final long ROUND_MIN_MS = 4_000;
    public static final long SLOW_MS = 10_000;
    public static final long VOLTAGE_MS = 30_000;
    /**
     * 279 getTofDistance is NOT a cached read: each call makes the vendor (ProjectorFocusManagerService
     * in gmpf_main, whose single thread serves every gmpf call) take an active 5-sample ToF measurement
     * (AF_TOF getTofDeviceDistance x5, about 0.57 s; the next gmpf call of anyone came about 0.8 s
     * later, device test 2026-10-27). So at most once per 60 s, only while its row is drawn (detailed
     * mode, Motion group on), and never together with the first reads of a start.
     */
    public static final long TOF_MS = 60_000;
    /** ToF waits this long after every other first read of a start, and after a view change. */
    public static final long TOF_AFTER_MS = 5_000;

    /** Period kinds. */
    public static final int P_ROUND = 0, P_SLOW = 1, P_VOLTAGE = 2, P_TOF = 3, P_ONCE = 4;

    /** Period of a kind; for P_ONCE the wait before a failed read is tried again. */
    public static long periodMs(int kind, long refreshMs) {
        switch (kind) {
            case P_ROUND: return Math.max(ROUND_MIN_MS, refreshMs);
            case P_VOLTAGE: return VOLTAGE_MS;
            case P_TOF: return TOF_MS;
            default: return SLOW_MS;
        }
    }

    /** Gap before a call: a probe step at the start pace, everything else at the steady pace. */
    public static long gapMs(boolean probe) {
        return probe ? GAP_MS : STEADY_GAP_MS;
    }

    /** Steady calls per second at most (the calls' own time makes it a little less). */
    public static double maxSteadyPerSecond() {
        return 1000.0 / STEADY_GAP_MS;
    }

    /**
     * Next due time of a read that was due at {@code due} and whose call ended at {@code end}: on its
     * beat (due + period) when it went out about on time, else a full period after the call. Keeping the
     * beat for every late read would let the 10 s reads keep theirs while the 4 s round reads wait
     * behind them (round reads about every 11 s in detailed mode); this way every read waits the same
     * few seconds more.
     */
    public static long nextDue(long due, long end, long period) {
        long beat = due + period;
        return end - due <= LATE_MS && beat >= end ? beat : end + period;
    }

    private static final int I1 = GmpfAllowlist.IGMPF, I2 = GmpfAllowlist.IGMPF2;

    /** {interface, code, period kind} of every read of the allowlist. */
    private static final int[][] PLAN = {
            {I1, GmpfAllowlist.SCREEN_ON, P_ROUND},
            {I1, GmpfAllowlist.SYSTEM_TEMPERATURE, P_ROUND},
            {I1, GmpfAllowlist.SENSOR_TEMPERATURE, P_ROUND},
            {I1, GmpfAllowlist.FAN_MODE, P_ROUND},
            {I1, GmpfAllowlist.FAN_SPEED, P_ROUND},
            {I1, GmpfAllowlist.LAMP_LEVEL, P_SLOW},
            {I1, GmpfAllowlist.LUMENS_MODE, P_SLOW},
            {I1, GmpfAllowlist.INPUT_SOURCE, P_SLOW},
            {I1, GmpfAllowlist.HDR_TYPE, P_SLOW},
            {I1, GmpfAllowlist.HDMI_INFO, P_SLOW},
            {I1, GmpfAllowlist.OUTPUT_TIMING, P_SLOW},
            {I1, GmpfAllowlist.MOTION, P_SLOW},
            {I1, GmpfAllowlist.FOCUS_STEP, P_SLOW},
            {I1, GmpfAllowlist.TOF_DISTANCE, P_TOF},
            {I1, GmpfAllowlist.SENSOR_STATUS, P_ONCE},
            {I2, GmpfAllowlist.LENS_DISP_MODE, P_SLOW},
            {I2, GmpfAllowlist.LED_LEVEL, P_SLOW},
            {I2, GmpfAllowlist.LED_CURRENTS, P_SLOW},
            {I2, GmpfAllowlist.OVER_TEMPERATURE, P_SLOW},
            {I2, GmpfAllowlist.VIDEO_INFO, P_SLOW},
            {I2, GmpfAllowlist.OUTPUT_FPS, P_SLOW},
            {I2, GmpfAllowlist.SUPPLY_VOLTAGE, P_VOLTAGE},
            {I2, GmpfAllowlist.LIGHT_USAGE, P_ONCE},
    };

    /** Period kind of a read, or -1 for a code this plan does not read. */
    public static int kindOf(int iface, int code) {
        for (int[] r : PLAN) if (r[0] == iface && r[1] == code) return r[2];
        return -1;
    }

    /** Steady calls per second of one value read with this code (0 for a once-per-start read). */
    public static double perSecond(int iface, int code, long refreshMs) {
        int k = kindOf(iface, code);
        if (k < 0 || k == P_ONCE) return 0;
        return 1000.0 / periodMs(k, refreshMs);
    }

    // ------------------------------------------------------------------ input source (696)
    /** 696 getCurrentInputSource: 0 media (Android), 1 HDMI 1, 2 HDMI 2; anything else is unknown. */
    public static final int INPUT_UNKNOWN = -1, INPUT_MEDIA = 0, INPUT_HDMI1 = 1, INPUT_HDMI2 = 2;

    public static int input(int raw) {
        return raw == INPUT_MEDIA || raw == INPUT_HDMI1 || raw == INPUT_HDMI2 ? raw : INPUT_UNKNOWN;
    }

    /** 662 getHdmiInfo only on an HDMI input. */
    public static boolean readsHdmiInfo(int input) {
        return input == INPUT_HDMI1 || input == INPUT_HDMI2;
    }

    /**
     * IGmpf2 319 getVideoInfo only on the media input: on HDMI it reports the same signal as 662 (the
     * device test showed "3840×2160p 59.9 Hz" twice). An unknown input reads neither.
     */
    public static boolean readsVideoInfo(int input) {
        return input == INPUT_MEDIA;
    }
}
