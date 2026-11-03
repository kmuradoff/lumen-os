package org.lumen.projector.audio;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Host test of the HB sound output rows (audio/HbOutputRows), states taken from the hb-t12 log of 2026-11-01 16:47-16:52
 * (~/hb_session/audio/hb_full.txt on the builder): which outputs AudioManager listed when the panel read them.
 */
public final class HbOutputRowsTest {
    private static int fails, checks;

    private static void eq(String what, Object want, Object got) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            fails++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static List<String> l(String... k) { return Arrays.asList(k); }

    /** The rows AudioOutputChoice.options alone gave (the old base list before the HB soundbar row). */
    private static List<String> oldBase(List<String> connected) {
        return HbOutputRows.kinds(connected, false, null);
    }

    public static void main(String[] a) {
        List<String> none = Collections.emptyList();
        List<String> arc = l("ARC");

        // 16:48:08 panel opened: ARC connected, System Audio on, LG SOUND BAR (la 5) listed -> soundbar + format rows
        boolean known = HbOutputRows.soundbarKnown(true, true, false);
        eq("start: rows", l("AUTO", "SPEAKER", "ARC"), HbOutputRows.kinds(arc, known, "AUTO"));
        eq("start: row shown", true, HbOutputRows.rowVisible(HbOutputRows.kinds(arc, known, "AUTO")));
        eq("start: format row", true, HbOutputRows.formatRowVisible(true, known, "AUTO"));

        // Reproduction of the collapse: 16:48:47.99 Speaker -> System Audio off -> "ARC terminate" -> hdmi_arc removed;
        // 16:48:51.96 Soundbar chosen; the panel read at 16:48:52.98 saw no ARC device (back only at 16:48:55.84).
        // AudioManager alone: no soundbar row, the ARC choice shown as "Auto", no format row, while the soundbar
        // played from 16:48:56 on.
        eq("old: gap rows lose the soundbar", l("AUTO", "SPEAKER"), oldBase(none));
        eq("old: gap format row gone", false, HbOutputRows.formatRowVisible(false, false, "ARC"));

        // Fixed, CEC device list still lists the audio system in the gap
        known = HbOutputRows.soundbarKnown(false, true, true);
        eq("gap, CEC listed: soundbar row", l("AUTO", "SPEAKER", "ARC"), HbOutputRows.kinds(none, known, "ARC"));
        eq("gap: format row stays for the soundbar choice", true, HbOutputRows.formatRowVisible(false, known, "ARC"));
        // Fixed, CEC list momentarily without the audio system (hotplug / polling): seen earlier this boot
        known = HbOutputRows.soundbarKnown(false, false, true);
        eq("gap, CEC empty, seen: soundbar row", l("AUTO", "SPEAKER", "ARC"), HbOutputRows.kinds(none, known, "SPEAKER"));
        eq("gap, CEC empty, seen: row shown", true, HbOutputRows.rowVisible(HbOutputRows.kinds(none, known, "SPEAKER")));
        // Speaker chosen, ARC ended (System Audio off): soundbar must stay selectable to come back
        eq("Speaker chosen, no ARC: format row hidden", false, HbOutputRows.formatRowVisible(false, known, "SPEAKER"));
        // Auto chosen during the gap: format row waits for ARC (the live refresh shows it when hdmi_arc is added)
        eq("Auto in the gap: format row waits", false, HbOutputRows.formatRowVisible(false, known, "AUTO"));
        // 16:48:55.84 ARC back
        eq("ARC back: rows", l("AUTO", "SPEAKER", "ARC"), HbOutputRows.kinds(arc, true, "ARC"));
        eq("ARC back: format row", true, HbOutputRows.formatRowVisible(true, true, "ARC"));

        // Speaker -> Auto -> Speaker (hb-t12 16:52): ARC ended again with System Audio off, the soundbar played before;
        // seen this boot keeps Speaker + Soundbar listed and the row shown, with the Speaker choice selectable
        List<String> back = HbOutputRows.kinds(none, HbOutputRows.soundbarKnown(false, false, true), "SPEAKER");
        eq("Speaker -> Auto -> Speaker: rows", l("AUTO", "SPEAKER", "ARC"), back);
        eq("Speaker -> Auto -> Speaker: row shown", true, HbOutputRows.rowVisible(back));
        // the stored choice always has its row, even with its device missing and no soundbar known
        eq("ARC chosen, soundbar never seen", l("AUTO", "SPEAKER", "ARC"), HbOutputRows.kinds(none, false, "ARC"));
        eq("BT chosen, BT off", l("AUTO", "SPEAKER", "BT"), HbOutputRows.kinds(none, false, "BT"));
        eq("USB chosen, unplugged, soundbar known", l("AUTO", "SPEAKER", "ARC", "USB"), HbOutputRows.kinds(none, true, "USB"));
        eq("Speaker chosen counts once", l("AUTO", "SPEAKER"), HbOutputRows.kinds(none, false, "SPEAKER"));
        eq("null choice", l("AUTO", "SPEAKER"), HbOutputRows.kinds(null, false, null));
        // connected outputs, UI order ARC, BT, WIRED, USB; unknown kinds ignored
        eq("all connected", l("AUTO", "SPEAKER", "ARC", "BT", "WIRED", "USB"),
                HbOutputRows.kinds(l("USB", "BT", "WIRED", "ARC", "SPEAKER", "AUTO", "X"), false, "AUTO"));
        eq("BT only", l("AUTO", "SPEAKER", "BT"), HbOutputRows.kinds(l("BT"), false, "AUTO"));

        // visibility: hidden only with a single real output
        eq("speaker alone: hidden", false, HbOutputRows.rowVisible(l("AUTO", "SPEAKER")));
        eq("speaker + BT: shown", true, HbOutputRows.rowVisible(l("AUTO", "SPEAKER", "BT")));
        eq("speaker + soundbar: shown", true, HbOutputRows.rowVisible(l("AUTO", "SPEAKER", "ARC")));
        eq("never seen, nothing connected, Auto chosen: hidden", false,
                HbOutputRows.rowVisible(HbOutputRows.kinds(none, HbOutputRows.soundbarKnown(false, false, false), "AUTO")));

        // soundbar known: any one source
        eq("known: ARC now", true, HbOutputRows.soundbarKnown(true, false, false));
        eq("known: CEC", true, HbOutputRows.soundbarKnown(false, true, false));
        eq("known: seen", true, HbOutputRows.soundbarKnown(false, false, true));
        eq("unknown", false, HbOutputRows.soundbarKnown(false, false, false));

        System.out.println("HbOutputRowsTest: " + (checks - fails) + "/" + checks + " ok");
        if (fails > 0) System.exit(1);
    }
}
