package org.lumen.projector.panel;

/**
 * Lumen OS 1.0.8 (parity brightness item 13): no brightness change in 3D, as stock. Stock common.json rule list
 * "dlpLumensLevel", rule iRISDeviceNotThreeDimension (DisplayHelper.java:150-152), action 2 (DispatchFun.java:123-125,
 * 222-225): on a model without IRIS (G0082: support_ambient_light_version 3) the brightness page does not open and
 * a slider step does nothing; the press shows "Turn off 3D and try again"; the row is neither greyed nor hidden.
 * 3D = stock get3dMode() != 1: IGmpf 192 != 1, or 193 (3D as 2D) on, the same test as Snapshot.is3dActive. A failed
 * 192 read does not block, like the panel's other 3D gates (snap.fmt3d != null && ...). Also the picture mode lock of
 * the same rule list ({@link #modeLocks}). Plain Java: sh test/lamp3d/run.sh
 */
final class Lamp3d {
    /** KeyEvent.KEYCODE_DPAD_LEFT / KEYCODE_DPAD_RIGHT (plain Java, no android.view import). */
    static final int KEY_LEFT = 21, KEY_RIGHT = 22;

    private Lamp3d() {}

    /** True when a brightness change must be refused (fmt3d = IGmpf 192, is3dTo2d = IGmpf 193). */
    static boolean blocks(Integer fmt3d, Boolean is3dTo2d) {
        return fmt3d != null && (fmt3d != 1 || Boolean.TRUE.equals(is3dTo2d));
    }

    /**
     * The other dlpLumensLevel rules of stock common.json (colorModeCustom / colorModeOffice / colorModePerformance,
     * action 2): no brightness change while IGmpf 175 (DLP lumens mode) is 6, 12 or 15 (stock DisplayHelper:47-57).
     * The vendor itself runs the 5-segment wheel there (IGmpf2 51 reads 1 and 52(0) is refused), so a slider would
     * show Boost it cannot leave. A failed 175 read does not block.
     */
    static boolean modeLocks(Integer lumensMode) {
        return lumensMode != null && (lumensMode == 6 || lumensMode == 12 || lumensMode == 15);
    }

    /** A step key of the slider. Both directions are refused, so RTL mirroring does not matter. */
    static boolean isStepKey(int keyCode) {
        return keyCode == KEY_LEFT || keyCode == KEY_RIGHT;
    }
}
