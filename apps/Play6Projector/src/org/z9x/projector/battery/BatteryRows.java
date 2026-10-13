package org.z9x.projector.battery;

/**
 * Was the battery in the quick panel of the XGIMI Play 6 (plan 3.4): the Battery page (level with a bar,
 * status, power source, the warning levels), the Battery tile value and the battery part of the info line.
 * All three are gone since t13 (docs/play6/research/battery_sound_features.md 6.1 B8, owner decision: the
 * battery moves to Lumen Home, which reads it from device/DeviceStateProvider). Nothing references this
 * class; it stays only because the t13 lane does not delete files it did not create. It can be deleted
 * together with res/drawable/ic_tile_battery.xml and res/drawable/ic_batt_bolt.xml (both unused too).
 */
final class BatteryRows {
    private BatteryRows() {}
}
