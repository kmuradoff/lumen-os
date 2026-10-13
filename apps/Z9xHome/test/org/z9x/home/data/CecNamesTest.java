package org.z9x.home.data;

import org.z9x.home.ui.Scrims;

/**
 * The friendly names and marks of CEC devices on the Inputs cards (CecNames, switch cec_names): placeholders
 * never shown, known devices by name or by an unambiguous vendor id, their own model names kept, the
 * console accents readable on the card.
 */
final class CecNamesTest {
    private static final int SURFACE2 = 0xFF26221E; // res/values/colors_lumen.xml lumen_surface2, the card

    static void run() {
        for (String g : new String[]{"Playback_1", "Recorder_2", "Tuner_1", "TV", "Audio_System", "playback 3",
                "Backup_1", "Secondary_TV", "HDMI 1", "hdmi2", "", "  ", null}) {
            T.ok(CecNames.generic(g), "placeholder: " + g);
        }
        for (String n : new String[]{"PlayStation 5", "Apple TV", "Blu-ray Player", "SHIELD"}) {
            T.ok(!CecNames.generic(n), "real name: " + n);
        }
        check(CecNames.of(0, "Playback_1", null, null), null, CecNames.MARK_NONE, 0, "placeholder only: the port's label");
        check(CecNames.of(0x080046, "Playback_1", "PlayStation 5", null), "PlayStation 5", CecNames.MARK_CONSOLE,
                0xFF5A9BEA, "PlayStation 5 from the system's CEC list (vendor Sony)");
        check(CecNames.of(0x080046, "Playback_1"), null, CecNames.MARK_NONE, 0, "Sony vendor alone: not enough");
        check(CecNames.of(0, "Playback_1", null, "PS5"), "PlayStation", CecNames.MARK_CONSOLE, 0xFF5A9BEA,
                "PS5 from the TV input's label");
        check(CecNames.of(0, "Xbox Series X"), "Xbox Series X", CecNames.MARK_CONSOLE, 0xFF52B043, "Xbox model kept");
        check(CecNames.of(0, "NintendoSwitch"), "Nintendo Switch", CecNames.MARK_CONSOLE, 0xFFE9505A, "Nintendo");
        check(CecNames.of(0x0010FA, "Playback_1"), "Apple TV", CecNames.MARK_PLAYER, 0, "Apple by vendor id");
        check(CecNames.of(0, "SHIELD"), "NVIDIA Shield", CecNames.MARK_PLAYER, 0, "Shield");
        check(CecNames.of(0x001A11, "Playback_2"), "Chromecast", CecNames.MARK_PLAYER, 0, "Google by vendor id");
        check(CecNames.of(0, "AFTMM"), "Fire TV", CecNames.MARK_PLAYER, 0, "Fire TV model code");
        check(CecNames.of(0, "Fire TV Stick 4K"), "Fire TV Stick 4K", CecNames.MARK_PLAYER, 0, "Fire TV name kept");
        check(CecNames.of(0, "Roku Ultra"), "Roku Ultra", CecNames.MARK_PLAYER, 0, "Roku");
        check(CecNames.of(0, "Blu-ray Player"), "Blu-ray Player", CecNames.MARK_NONE, 0, "unknown real name kept");
        check(CecNames.of(0, "Recorder_1", "Tuner_1", "HDMI 1"), null, CecNames.MARK_NONE, 0, "no real name");
        check(CecNames.of(0, "HDMI Switch"), "HDMI Switch", CecNames.MARK_NONE, 0, "a switch is not a Nintendo");
        // the TV input app's device states and brand accents (InputSource, its provider's port bundles)
        T.eq(CecNames.deviceState("on"), Card.DEV_ON, "device state on");
        T.eq(CecNames.deviceState("asleep"), Card.DEV_ASLEEP, "device state asleep");
        T.eq(CecNames.deviceState("no_signal"), Card.DEV_NO_SIGNAL, "device state no signal");
        T.eq(CecNames.deviceState("disconnected"), Card.DEV_DISCONNECTED, "device state disconnected");
        T.eq(CecNames.deviceState("unknown"), -1, "unknown: the TIF state stays");
        T.eq(CecNames.deviceState(null), -1, "no state: the TIF state stays");
        for (int accent : new int[]{0xFF0070D1, 0xFF107C10, 0xFFE60012}) {
            double r = Scrims.contrast(CecNames.softAccent(accent), SURFACE2);
            T.ok(r >= 4.0, String.format(java.util.Locale.ROOT, "brand accent %08X softened on the card >= 4:1 (got %.2f:1)", accent, r));
        }
        for (int tint : new int[]{0xFF5A9BEA, 0xFF52B043, 0xFFE9505A}) {
            double r = Scrims.contrast(tint, SURFACE2);
            T.ok(r >= 4.0, String.format(java.util.Locale.ROOT, "accent %08X on the card >= 4:1 (got %.2f:1)", tint, r));
        }
    }

    private static void check(CecNames.Device d, String name, int mark, int tint, String what) {
        T.eq(d.name, name, what + ": name");
        T.eq(d.mark, mark, what + ": mark");
        T.eq(d.tint, tint, what + ": tint");
    }
}
