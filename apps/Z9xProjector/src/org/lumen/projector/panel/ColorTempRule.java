package org.lumen.projector.panel;

/**
 * Lumen 1.0.8, parity picture item 13: the colour temperature row of the Picture page. IGmpf 175 is the DLP lumens
 * mode, read raw (Snapshot.lumensMode). The stock G0082 node image_mode_3_color_temp_dapeng lists D65 = 16 and Colour
 * temp 1 = 17 (picture.json); Office (12) and Performance (15) are neither, and stock still shows the row with no
 * option marked (BrightFactory.setColorTemp): Office with the label "Colour temp 3", Performance empty (Lumen: the
 * dash); D65 is one key away. Hidden only when 175 itself failed (rule: hide, never disable). Plain Java:
 * sh test/picture/run.sh
 */
final class ColorTempRule {
    /** Row order = GmpfClient.ColorTemp order: D65, Colour temp 1. */
    static final int[] WIRES = {16, 17};
    /** Lumens mode of the Office picture mode (stock DisplayHelper). */
    static final int OFFICE = 12;

    private ColorTempRule() {}

    /** Option index of a raw 175 value; -1 = none marked (12, 15, anything not listed, or no read). */
    static int index(Integer raw) {
        if (raw == null) return -1;
        for (int i = 0; i < WIRES.length; i++) if (WIRES[i] == raw) return i;
        return -1;
    }

    /** Selectable whenever 175 answered, whatever the lumens mode. */
    static boolean known(boolean ok, Integer raw) {
        return ok && raw != null;
    }

    /** GONE only when the read worked but 175 failed; while the whole read failed the row stays (disabled). */
    static boolean hidden(boolean ok, Integer raw) {
        return ok && raw == null;
    }

    /** The Office lumens mode: stock labels the row "Colour temp 3". */
    static boolean isOffice(Integer raw) {
        return raw != null && raw == OFFICE;
    }
}
