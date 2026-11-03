/*
 * HbOutputRows: which rows the HB panel's "Sound output" row lists and when the row is shown (2026-11-01, hb-t12
 * tester 16:48-16:50: the output rows collapsed while the soundbar kept playing). Free of Android classes so
 * test/audio/run.sh checks it on the host. HB-only file, no HAL access. Used by audio/HbOutputOptions.
 *
 * Kinds are AudioOutputChoice.Kind names. What AudioManager reports is not stable on the HB: "Speaker" turns CEC System
 * Audio off, which ends ARC ("ARC terminate, Reset Aout Capability", hdmi_arc removed); "Soundbar" / "Auto" turn it on
 * again and hdmi_arc comes back about 4 s later (16:48:51.96 chose ARC, 16:48:55.84 media routing -> hdmi_arc). A panel
 * read inside that gap (16:48:52.98) saw no ARC device, so rows built from AudioManager alone dropped the soundbar
 * (and the "Audio format" row) while the soundbar was coming back and then played.
 *
 * Rules:
 *   Auto first, Speaker always.
 *   Soundbar (ARC) while the soundbar is known: an ARC / eARC output now, a CEC audio system in the HDMI-CEC device
 *     list, or either of them seen earlier in this process (the app is persistent: one process per boot).
 *   Bluetooth, wired, USB while connected.
 *   The stored choice always gets its row, even while its device is momentarily missing (never a hidden selection).
 *   The row is shown only with more than one real output (Auto is not an output): Speaker alone = hidden.
 */
package org.lumen.projector.audio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class HbOutputRows {
    public static final String AUTO = "AUTO", SPEAKER = "SPEAKER", ARC = "ARC", BT = "BT", WIRED = "WIRED", USB = "USB";
    /** Row order after Auto and Speaker (AudioOutputChoice.options order). */
    private static final String[] EXTRA = {ARC, BT, WIRED, USB};

    private HbOutputRows() {}

    /** The soundbar is known: ARC / eARC output now, a CEC audio system listed now, or one of them seen this boot. */
    public static boolean soundbarKnown(boolean arcNow, boolean cecAudioSystem, boolean seenThisBoot) {
        return arcNow || cecAudioSystem || seenThisBoot;
    }

    /**
     * The row kinds in UI order. connected: the kinds AudioManager lists now (ARC, BT, WIRED, USB; others ignored).
     * chosen: the stored kind (null = AUTO).
     */
    public static List<String> kinds(Collection<String> connected, boolean soundbarKnown, String chosen) {
        List<String> out = new ArrayList<>();
        out.add(AUTO);
        out.add(SPEAKER);
        for (String k : EXTRA) {
            boolean listed = connected != null && connected.contains(k);
            if (listed || (ARC.equals(k) && soundbarKnown) || k.equals(chosen)) out.add(k);
        }
        return out;
    }

    /** Show the row: more than one real output among the kinds (Auto does not count). */
    public static boolean rowVisible(List<String> kinds) {
        int outputs = 0;
        for (String k : kinds) if (!AUTO.equals(k)) outputs++;
        return outputs > 1;
    }

    /**
     * The "Audio format" row (digital_output_format): with ARC / eARC connected now, and also while the soundbar is the
     * stored choice and known (the gap while System Audio brings ARC back; the format is stored and sent again when
     * ARC connects, AudioOutputChoice.arcFormat).
     */
    public static boolean formatRowVisible(boolean arcNow, boolean soundbarKnown, String chosen) {
        return arcNow || (soundbarKnown && ARC.equals(chosen));
    }
}
