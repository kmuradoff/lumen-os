package org.lumen.sensors.core;

/**
 * The value groups of the HUD, in display order. Each group can be switched off in the settings
 * (Prefs groups bit mask, bit = group number); a group without one readable value is never shown,
 * neither in the HUD nor in the settings list. Plain Java (host tests).
 */
public final class Group {
    private Group() {}

    public static final int TEMPS = 0;
    public static final int COOLING = 1;
    public static final int LIGHT = 2;
    public static final int CPU = 3;        // processor and graphics
    public static final int MEMORY = 4;
    public static final int NETWORK = 5;
    public static final int PICTURE = 6;    // picture and signal
    public static final int MOTION = 7;
    public static final int SYSTEM = 8;

    public static final int COUNT = 9;
    /** Every group on (the default of the settings). */
    public static final int ALL = (1 << COUNT) - 1;

    public static boolean in(int mask, int group) {
        return (mask & (1 << group)) != 0;
    }
}
