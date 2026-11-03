package org.lumen.sensors.core;

/**
 * Which device profile the app runs with, chosen once at start from system properties:
 *  - ro.lumen.device unset AND the XGIMI model code is G0082 (ro.boot.xgimi.modelname and
 *    ro.xgimi.modelname; every one that is set must say G0082) = {@link #Z9X}: generic sources plus the
 *    XGIMI HAL source (z9x package), thermal_zone0 is the SoC. The board (ro.boot.hardware /
 *    ro.hardware = mt9952) decides only when both model properties are empty: another XGIMI model on
 *    the same MT9952 board may number its IGmpf2 methods differently (the allowlist is keyed by number);
 *  - anything else, e.g. ro.lumen.device=play6 = {@link #GENERIC}: generic sources only, no XGIMI HAL at
 *    all (the Play 6 IGmpf2 code table differs; Play 6 support comes later in its own classes).
 * The Z9X sets both model properties to G0082 (property dumps of two units).
 * The decision itself is plain Java ({@link #decide}) so the host tests cover it.
 */
public final class DeviceProfile {
    private DeviceProfile() {}

    public static final int GENERIC = 0, Z9X = 1;

    static final String Z9X_BOARD = "mt9952";
    static final String Z9X_MODEL = "G0082";

    public static int decide(String lumenDevice, String bootHardware, String hardware, String bootModel, String model) {
        if (!empty(lumenDevice)) return GENERIC;
        String bm = trim(bootModel), m = trim(model);
        if (!bm.isEmpty() || !m.isEmpty()) {
            boolean z9x = (bm.isEmpty() || Z9X_MODEL.equals(bm)) && (m.isEmpty() || Z9X_MODEL.equals(m));
            return z9x ? Z9X : GENERIC;
        }
        if (Z9X_BOARD.equals(trim(bootHardware)) || Z9X_BOARD.equals(trim(hardware))) return Z9X;
        return GENERIC;
    }

    /**
     * The X50 (XGIMI G0111, X50 Ultra, mt9681): ro.lumen.device unset, ro.boot.xgimi.modelname = G0111 and
     * ro.xgimi.modelname G0111 or unset. Separate from {@link #decide}, which stays as it is (G0111 gives GENERIC
     * there): the profile classes ask this only when decide() did not say Z9X. No board fallback: the X50 shares
     * ro.product.device horse_popular and the mt5877 platform with the Z9X, so only the model code tells it.
     */
    public static boolean isX50(String lumenDevice, String bootModel, String model) {
        if (!empty(lumenDevice)) return false;
        String bm = trim(bootModel), m = trim(model);
        return X50_MODEL.equals(bm) && (m.isEmpty() || X50_MODEL.equals(m));
    }

    static final String X50_MODEL = "G0111";

    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
