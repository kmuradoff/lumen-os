package org.lumen.projector.panel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Host check of the colour temperature row (parity picture item 13, {@link ColorTempRule}): Office (12) and
 * Performance (15) keep the row with nothing marked, only a failed 175 hides it. sh test/picture/run.sh
 */
public final class ColorTempRuleTest {
    private static int failed, passed;

    private static void eq(String what, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    public static void main(String[] a) throws Exception {
        // ---- listed temperatures (stock map 16, 17)
        eq("D65", 0, ColorTempRule.index(16));
        eq("Colour temp 1", 1, ColorTempRule.index(17));
        // ---- Office / Performance / custom / others: row stays, nothing marked, selectable
        for (int raw : new int[] {12, 15, 6, 0, 7, 8}) {
            eq("index " + raw, -1, ColorTempRule.index(raw));
            eq("known " + raw, true, ColorTempRule.known(true, raw));
            eq("shown " + raw, false, ColorTempRule.hidden(true, raw));
        }
        // ---- 175 failed alone: hidden; whole read failed: kept, not selectable
        eq("175 failed hidden", true, ColorTempRule.hidden(true, null));
        eq("175 failed not known", false, ColorTempRule.known(true, null));
        eq("read failed kept", false, ColorTempRule.hidden(false, null));
        eq("read failed not known", false, ColorTempRule.known(false, null));
        eq("no read index", -1, ColorTempRule.index(null));
        // ---- the wire list matches GmpfClient.ColorTemp (row order = CT_ORDER)
        String src = new String(Files.readAllBytes(Paths.get(a[0], "src/org/lumen/projector/hal/GmpfClient.java")),
                StandardCharsets.UTF_8);
        eq("enum order", true, src.contains("D65(16), COLOR_TEMP_1(17);"));
        eq("2 wires", 2, ColorTempRule.WIRES.length);
        // ---- Office label (stock "Colour temp 3" for lumens mode 12 only)
        eq("office 12", true, ColorTempRule.isOffice(12));
        eq("performance 15", false, ColorTempRule.isOffice(15));
        eq("D65 not office", false, ColorTempRule.isOffice(16));
        eq("no read not office", false, ColorTempRule.isOffice(null));

        System.out.println("ColorTempRuleTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
