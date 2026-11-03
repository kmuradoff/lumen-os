package org.lumen.sensors.x50;

/**
 * X50 ONLY: the gmpf read plan of the X50 HAL source, plain Java so the host test checks it. The same budget as the
 * Z9X (z9x/Z9xSchedule, not shared: device separation): the X50 vendor also serves every gmpf call of every app in
 * one process (xgimi.hardware.gmpf-service, 12 % CPU idle at the survey), so at most 2 calls per second after the
 * probes, no bursts, and the light gate first.
 *
 * Periods ({@link #kindOf}, by service and code of research/aidl_codes.tsv):
 *   ROUND  max(4 s, refresh)  DISPLAY 28 (the light gate), TEMPERATURE 6, 7 per sensor, 3, 5 per fan
 *   SLOW   10 s               DISPLAY 7, 5, 152, 178 (light); TV 4, 78, 38 or 122, DISPLAY 57, 179 (picture);
 *                             MOTION 2, FOCUS 59 (motion, focus); TEMPERATURE 12
 *   ONCE   once per start     DISPLAY 176 (light usage); a failed read is tried again after 10 s
 * No ToF, no supply voltage, no laser currents, no ambient light on the X50 (not in the allow list).
 *
 * Steady calls per second (refresh 2 s so ROUND = 4 s; HDMI input; the survey's 7 sensors and 2 fans):
 *   detailed  asks for ROUND 28 + 6 + 7 x 7 + 3 + 2 x 5 = 12 calls / 4 s = 3.0
 *                      SLOW  7 5 152 178 4 78 38 57 179 2 59 12 = 12 calls / 10 s = 1.2   together 4.2;
 *             gets 2 at most (the steady gap): every read waits a few seconds more (nextDue)
 *   compact   28 + DMD + red laser + air + fan 1 = 5 calls / 4 s = 1.25
 *   graphs    28 + 6 + 7 sensors = 9 calls / 4 s = 2.25, so the graph points come about every 4.5 s
 */
public final class X50Schedule {
    private X50Schedule() {}

    public static final long GAP_MS = 200;
    public static final long STEADY_GAP_MS = 500;
    public static final long LATE_MS = 2_000;
    public static final long ROUND_MIN_MS = 4_000;
    public static final long SLOW_MS = 10_000;

    public static final int P_ROUND = 0, P_SLOW = 1, P_ONCE = 4;

    public static long periodMs(int kind, long refreshMs) {
        return kind == P_ROUND ? Math.max(ROUND_MIN_MS, refreshMs) : SLOW_MS;
    }

    public static long gapMs(boolean probe) {
        return probe ? GAP_MS : STEADY_GAP_MS;
    }

    public static double maxSteadyPerSecond() {
        return 1000.0 / STEADY_GAP_MS;
    }

    /** As the Z9X plan's nextDue: on the beat when about on time, else a full period after the call. */
    public static long nextDue(long due, long end, long period) {
        long beat = due + period;
        return end - due <= LATE_MS && beat >= end ? beat : end + period;
    }

    /** {service, code, period kind} of every read of X50Gmpf.Call (the meta calls are not planned). */
    private static final Object[][] PLAN = {
            {"DisplayManager", 28, P_ROUND},
            {"TemperatureManager", 6, P_ROUND},
            {"TemperatureManager", 7, P_ROUND},
            {"TemperatureManager", 3, P_ROUND},
            {"TemperatureManager", 5, P_ROUND},
            {"TemperatureManager", 12, P_SLOW},
            {"DisplayManager", 7, P_SLOW},
            {"DisplayManager", 5, P_SLOW},
            {"DisplayManager", 152, P_SLOW},
            {"DisplayManager", 178, P_SLOW},
            {"DisplayManager", 176, P_ONCE},
            {"DisplayManager", 57, P_SLOW},
            {"DisplayManager", 179, P_SLOW},
            {"GmTvManager", 4, P_SLOW},
            {"GmTvManager", 78, P_SLOW},
            {"GmTvManager", 38, P_SLOW},
            {"GmTvManager", 122, P_SLOW},
            {"MotionDetectionManager", 2, P_SLOW},
            {"ProjectorFocusManager", 59, P_SLOW},
    };

    /** Period kind of a read, or -1 for a call this plan does not read. */
    public static int kindOf(String service, int code) {
        for (Object[] r : PLAN) if (r[0].equals(service) && (Integer) r[1] == code) return (Integer) r[2];
        return -1;
    }

    /** {service, code} of every planned read (tests). */
    public static String[] planned() {
        String[] s = new String[PLAN.length];
        for (int i = 0; i < PLAN.length; i++) s[i] = PLAN[i][0] + "\t" + PLAN[i][1];
        return s;
    }

    public static double perSecond(String service, int code, long refreshMs) {
        int k = kindOf(service, code);
        if (k < 0 || k == P_ONCE) return 0;
        return 1000.0 / periodMs(k, refreshMs);
    }

    // ------------------------------------------------------------------ input source (TV 4)
    public static final int INPUT_UNKNOWN = -1, INPUT_MEDIA = 0, INPUT_HDMI1 = 1, INPUT_HDMI2 = 2;

    public static int input(int raw) {
        return raw == INPUT_MEDIA || raw == INPUT_HDMI1 || raw == INPUT_HDMI2 ? raw : INPUT_UNKNOWN;
    }

    /** TV 38 only on an HDMI input. */
    public static boolean readsHdmiInfo(int input) {
        return input == INPUT_HDMI1 || input == INPUT_HDMI2;
    }

    /** TV 122 only on the media input (on HDMI it repeats TV 38: the survey read 3840x2160 60 Hz from both). */
    public static boolean readsVideoInfo(int input) {
        return input == INPUT_MEDIA;
    }
}
