package org.lumen.sensors.core;

/**
 * Value levels (normal / warm / hot) and THE threshold table: every threshold of the app lives here.
 * A level only colours the value (text colour, warm = amber, hot = red); nothing acts on it.
 *
 * Temperature kinds (degrees C, a value at or above "warm" is warm, at or above "hot" is hot):
 *   SOC    SoC / CPU die (thermal_zone0 on the Z9X)        75 / 85
 *   DMD    DLP chip (XGIMI sensor index 2)                  65 / 75
 *   LED    LED board (XGIMI sensor index 5, 4, 8)           60 / 70
 *   AIR    air inside the case (XGIMI sensor index 1)       40 / 45
 *   BOARD  system board and any other sensor or zone        60 / 70
 * Other kinds:
 *   THROTTLE  a cooling device step above 0 is warm (the kernel is throttling something)
 *   FLAG      a set flag (over-temperature) is hot
 * Sample values from the research (Z9X, Lumen 1.0.1 logs): SoC 49..79, DMD 39..64, LED 33..46,
 * air 27..36; the Projector's Boost refuses above 85 (QuickPanelController).
 */
public final class Levels {
    private Levels() {}

    public static final int NORMAL = 0, WARM = 1, HOT = 2;

    public static final int K_NONE = 0, K_SOC = 1, K_DMD = 2, K_LED = 3, K_AIR = 4, K_BOARD = 5,
            K_THROTTLE = 6, K_FLAG = 7;

    private static final float NEVER = Float.MAX_VALUE;

    /** kind -> {warm, hot}. */
    private static final float[][] TABLE = {
            /* K_NONE     */ {NEVER, NEVER},
            /* K_SOC      */ {75, 85},
            /* K_DMD      */ {65, 75},
            /* K_LED      */ {60, 70},
            /* K_AIR      */ {40, 45},
            /* K_BOARD    */ {60, 70},
            /* K_THROTTLE */ {1, NEVER},
            /* K_FLAG     */ {NEVER, 1},
    };

    public static int level(int kind, double v) {
        if (kind <= 0 || kind >= TABLE.length || Double.isNaN(v)) return NORMAL;
        float[] t = TABLE[kind];
        if (v >= t[1]) return HOT;
        if (v >= t[0]) return WARM;
        return NORMAL;
    }

    /** Warm threshold of a kind (graphs draw it as a faint line); NaN when the kind has none. */
    public static float warmAt(int kind) {
        if (kind <= 0 || kind >= TABLE.length || TABLE[kind][0] == NEVER) return Float.NaN;
        return TABLE[kind][0];
    }
}
