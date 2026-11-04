package org.lumen.projector.panel;

/**
 * Stock "gameModeFast" lock of the manual keystone (common.json manualKeystone; condition
 * DisplayHelper / KeyStoneManager.isGameKeystoneMutex): refused only while a game really runs (IGmpf2 55 state
 * on, or the vendor's game picture 666 == 4) AND 647 is top speed (2 / 3). Basic (0 / 1) is never locked
 * (support_Trapezoid_GAME=true). Plain Java, no Android, no HAL: ManualKeystonePanel.enter reads the values,
 * this class only decides (sh test/kstgame/run.sh).
 */
final class KstGameLock {
    /** IGmpf 666 value of the vendor's game picture mode (stock getPictureMode() == 4). */
    static final int PICTURE_MODE_GAME = 4;

    private KstGameLock() {}

    /** 647 wire value is top speed: 2 (top speed + HFR) or 3 (top speed). */
    static boolean isTopSpeed(int opt647) { return opt647 == 2 || opt647 == 3; }

    /**
     * True = refuse the manual keystone.
     * opt647: 647 wire value. hdmi: HDMI on screen (696 1 / 2 or a tvinput HDMI session; off HDMI the vendor's
     * 55 says off anyway, probe.md). game55: 55 state on (field 0 == 0) when the reply fits 647
     * (Gmpf2Client.gameModeOf >= 0), null = unknown. pic666: 666 raw, null = not read.
     * 55 unknown: the option alone, as 1.0.7 and Snapshot.isTopSpeedGame.
     */
    static boolean refuse(int opt647, boolean hdmi, Boolean game55, Integer pic666) {
        if (!isTopSpeed(opt647) || !hdmi) return false;
        if (pic666 != null && pic666 == PICTURE_MODE_GAME) return true;
        return !Boolean.FALSE.equals(game55);
    }
}
