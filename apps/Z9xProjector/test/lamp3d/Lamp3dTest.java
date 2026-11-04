package org.lumen.projector.panel;

/**
 * Host check of the brightness 3D gate (Lamp3d): stock get3dMode() != 1 refuses, 192 unknown does not, both
 * slider directions are step keys. sh test/lamp3d/run.sh
 */
public final class Lamp3dTest {
    private static int failed, passed;

    private static void eq(String what, Object want, Object got) {
        if (want == got || (want != null && want.equals(got))) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    public static void main(String[] a) {
        // ---- 192 / 193 as the vendor reports them (ThreeD: 190(1) off, 190(2) SBS, 190(3) TB, 191 as 2D)
        eq("2D, 193 off", false, Lamp3d.blocks(1, false));
        eq("2D, 193 read failed", false, Lamp3d.blocks(1, null));
        eq("side by side", true, Lamp3d.blocks(2, false));
        eq("top and bottom", true, Lamp3d.blocks(3, false));
        eq("other 3D format, 193 failed", true, Lamp3d.blocks(4, null));
        eq("3D as 2D (193 on, 192 1)", true, Lamp3d.blocks(1, true));
        eq("3D as 2D (193 on, 192 2)", true, Lamp3d.blocks(2, true));
        // ---- 192 failed: no refusal, as the panel's other 3D gates (snap.fmt3d != null && ...)
        eq("192 unknown", false, Lamp3d.blocks(null, null));
        eq("192 unknown, 193 on", false, Lamp3d.blocks(null, true));

        // ---- step keys: LEFT 21 and RIGHT 22 both (RTL mirrors the slider; both are refused anyway)
        // ---- picture modes whose lumens mode locks brightness (stock colorModeCustom / Office / Performance)
        eq("custom 6", true, Lamp3d.modeLocks(6));
        eq("office 12", true, Lamp3d.modeLocks(12));
        eq("performance 15", true, Lamp3d.modeLocks(15));
        eq("D65 16", false, Lamp3d.modeLocks(16));
        eq("colour temp 1 17", false, Lamp3d.modeLocks(17));
        eq("175 failed", false, Lamp3d.modeLocks(null));
        eq("LEFT", true, Lamp3d.isStepKey(21));
        eq("RIGHT", true, Lamp3d.isStepKey(22));
        eq("UP", false, Lamp3d.isStepKey(19));
        eq("DOWN", false, Lamp3d.isStepKey(20));
        eq("OK", false, Lamp3d.isStepKey(23));
        eq("BACK", false, Lamp3d.isStepKey(4));

        System.out.println("lamp3d: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
