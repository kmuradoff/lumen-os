package org.lumen.projector.audio;

import static org.lumen.projector.audio.HbAudioRules.VolumeStep.FOLLOW;
import static org.lumen.projector.audio.HbAudioRules.VolumeStep.HOLD;

/** Host test of the HB ARC volume spike decisions (device tests, "ARC volume spike"). */
public final class HbAudioRulesTest {
    private static int fails, checks;

    private static void eq(String what, Object want, Object got) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            fails++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    /**
     * The HdmiVolume decisions over the 2026-11-01 hb-t12 sequence (hb_full.txt 16:47-16:53): a model of what goes to
     * the HAL as spk_volume. follow() = HdmiVolume.follow, sink() = HdmiVolume.selectSpeakerSink.
     */
    private static final class Sim {
        int target = -1, confirmed = -1, music = 10, sent = -1;
        boolean following;
        final java.util.List<Integer> hal = new java.util.ArrayList<>();
        void follow(boolean force) {
            boolean speaker = HbAudioRules.volumeStep(target, confirmed) == FOLLOW;
            if (!speaker) { following = false; return; }
            boolean entering = !following;
            following = true;
            int v = HbAudioRules.speakerVolume(true, music, sent, force || entering);
            if (v >= 0) { hal.add(v); sent = v; }
        }
        void sink() {
            int v = HbAudioRules.speakerSinkStore(sent, music);
            if (v >= 0) { hal.add(v); sent = v; }
        }
        void path(int t, int c) { target = t; confirmed = c; follow(false); }
        void key(int m) { music = m; follow(false); }
        int last() { return hal.isEmpty() ? -1 : hal.get(hal.size() - 1); }
    }

    private static void speakerScenario() {
        Sim s = new Sim();
        s.path(0, -1);
        eq("boot: nothing before the vendor confirms", 0, s.hal.size());
        s.path(0, 0);
        eq("boot: speaker confirmed sends the current index", 10, s.last());
        s.key(9); s.key(8);
        eq("speaker: every key step sent", java.util.Arrays.asList(10, 9, 8), s.hal);
        // Soundbar chosen: routing leaves at once, the vendor confirms ARC later; keys on ARC send nothing
        int before = s.hal.size();
        s.path(2, 0); s.key(30); s.path(2, 2); s.key(40); s.key(100);
        eq("ARC: nothing sent (no 100, no index)", before, s.hal.size());
        // Speaker chosen again (16:52:10): Android's speaker index is 10 again; switch_end stores it, confirm resends
        s.music = 10;
        s.path(0, 2); s.key(10);
        eq("speaker pending: nothing yet", before, s.hal.size());
        s.sink();
        eq("speaker -> ARC -> speaker: switch_end stores the current index, not 100", 10, s.last());
        s.path(0, 0);
        eq("speaker confirmed again: the current index", 10, s.last());
        s.key(9); s.key(8); s.key(7); s.key(4);
        eq("speaker again: the keys reach the HAL (16:52:29-16:53:35)", 4, s.last());
        boolean no100 = true;
        for (int v : s.hal) no100 &= v != 100;
        eq("never 100 on the speaker in the whole sequence", true, no100);
        // mute and wake
        s.key(0);
        eq("mute = 0", 0, s.last());
        s.music = 4; s.follow(true);
        eq("wake / HDMI start: forced current index", 4, s.last());
    }

    public static void main(String[] a) {
        // 1. spk_volume only while the speaker is routed AND confirmed
        eq("speaker confirmed", true, HbAudioRules.speakerConfirmed(0, 0));
        eq("routed to ARC, vendor still 0", false, HbAudioRules.speakerConfirmed(2, 0));
        eq("routed to speaker, not confirmed yet", false, HbAudioRules.speakerConfirmed(0, -1));
        eq("nothing known", false, HbAudioRules.speakerConfirmed(-1, -1));
        eq("speaker = follow", FOLLOW, HbAudioRules.volumeStep(0, 0));
        eq("routing left for ARC = hold", HOLD, HbAudioRules.volumeStep(2, 0));
        eq("ARC confirmed = hold", HOLD, HbAudioRules.volumeStep(2, 2));
        eq("switch to the speaker pending = hold", HOLD, HbAudioRules.volumeStep(0, 2));
        eq("no path known = hold", HOLD, HbAudioRules.volumeStep(-1, -1));
        eq("BT = hold", HOLD, HbAudioRules.volumeStep(3, 3));
        eq("speaker: changed level sent", 7, HbAudioRules.speakerVolume(true, 7, 10, false));
        eq("speaker: same level not repeated", -1, HbAudioRules.speakerVolume(true, 7, 7, false));
        eq("speaker: forced resend", 7, HbAudioRules.speakerVolume(true, 7, 7, true));
        eq("speaker: muted = 0", 0, HbAudioRules.speakerVolume(true, 0, 7, false));
        eq("speaker: music unknown = nothing", -1, HbAudioRules.speakerVolume(true, -1, 7, true));
        eq("not the speaker: nothing even forced", -1, HbAudioRules.speakerVolume(false, 7, 100, true));
        eq("sink store: music level, never 100", 10, HbAudioRules.speakerSinkStore(-1, 10));
        eq("sink store: over an old 100", 10, HbAudioRules.speakerSinkStore(100, 10));
        eq("sink store: same level kept", -1, HbAudioRules.speakerSinkStore(10, 10));
        eq("sink store: music unknown kept", -1, HbAudioRules.speakerSinkStore(7, -1));
        speakerScenario();
        // 2. stock mute around an ARC switch, stock 1 s
        eq("speaker -> ARC mutes", true, HbAudioRules.halMute(0, 2));
        eq("ARC -> speaker mutes", true, HbAudioRules.halMute(2, 0));
        eq("BT -> ARC mutes", true, HbAudioRules.halMute(3, 2));
        eq("ARC -> BT: BT has its own switch_end=3", false, HbAudioRules.halMute(2, 3));
        eq("ARC -> wired: no start without an end", false, HbAudioRules.halMute(2, 4));
        eq("ARC -> USB", false, HbAudioRules.halMute(2, 6));
        eq("speaker -> BT", false, HbAudioRules.halMute(0, 3));
        eq("first report of a boot", false, HbAudioRules.halMute(-1, 2));
        eq("same path", false, HbAudioRules.halMute(2, 2));
        eq("ARC switch waits 1 s (stock 50 x 20 ms)", 1000L, HbAudioRules.switchDelayMs(0, 2));
        eq("other switch keeps 300 ms", 300L, HbAudioRules.switchDelayMs(3, 0));
        eq("speaker end stores spk_volume first", true, HbAudioRules.storeSpeakerBeforeEnd(0));
        eq("ARC end stores nothing", false, HbAudioRules.storeSpeakerBeforeEnd(2));
        eq("safety end comes after the 1 s wait", true, HbAudioRules.SWITCH_END_SAFETY_MS > HbAudioRules.STOCK_SWITCH_MS + 1500L);
        // 3. CEC System Audio follows the choice
        eq("Speaker = off", Boolean.FALSE, HbAudioRules.systemAudioFor("SPEAKER"));
        eq("Soundbar = on", Boolean.TRUE, HbAudioRules.systemAudioFor("ARC"));
        eq("Auto = on", Boolean.TRUE, HbAudioRules.systemAudioFor("AUTO"));
        eq("BT = unchanged", null, HbAudioRules.systemAudioFor("BT"));
        eq("wired = unchanged", null, HbAudioRules.systemAudioFor("WIRED"));
        eq("off when on", true, HbAudioRules.systemAudioChange(false, true, true, true, true));
        eq("off when off", false, HbAudioRules.systemAudioChange(false, true, true, true, false));
        eq("on when off", true, HbAudioRules.systemAudioChange(true, true, true, true, false));
        eq("CEC off", false, HbAudioRules.systemAudioChange(false, false, true, true, true));
        eq("control setting off: never touched", false, HbAudioRules.systemAudioChange(false, true, false, true, true));
        eq("no audio system", false, HbAudioRules.systemAudioChange(true, true, true, false, false));
        eq("unchanged choice", false, HbAudioRules.systemAudioChange(null, true, true, true, true));
        // 4. CEC volume control follows the output choice (tester 2026-11-01: Speaker, keys still went to the soundbar)
        eq("Speaker = CEC volume off", Boolean.FALSE, HbAudioRules.cecVolumeFor("SPEAKER", Boolean.TRUE));
        eq("BT = CEC volume off", Boolean.FALSE, HbAudioRules.cecVolumeFor("BT", Boolean.TRUE));
        eq("wired = CEC volume off", Boolean.FALSE, HbAudioRules.cecVolumeFor("WIRED", Boolean.TRUE));
        eq("USB = CEC volume off", Boolean.FALSE, HbAudioRules.cecVolumeFor("USB", null));
        eq("Soundbar = soundbar switch on", Boolean.TRUE, HbAudioRules.cecVolumeFor("ARC", Boolean.TRUE));
        eq("Soundbar, switch off = off", Boolean.FALSE, HbAudioRules.cecVolumeFor("ARC", Boolean.FALSE));
        eq("Auto = soundbar switch", Boolean.TRUE, HbAudioRules.cecVolumeFor("AUTO", Boolean.TRUE));
        eq("Auto, switch unknown = leave", null, HbAudioRules.cecVolumeFor("AUTO", null));
        eq("no choice = leave", null, HbAudioRules.cecVolumeFor(null, Boolean.TRUE));
        eq("volume off when on", true, HbAudioRules.cecVolumeChange(false, true));
        eq("volume off when off", false, HbAudioRules.cecVolumeChange(false, false));
        eq("volume on when off", true, HbAudioRules.cecVolumeChange(true, false));
        eq("volume current unknown", false, HbAudioRules.cecVolumeChange(false, null));
        eq("volume wanted unknown", false, HbAudioRules.cecVolumeChange(null, true));
        // 5. System Audio off again without a choice (boot / wake / ARC back) only for Speaker, rate limited
        eq("auto off: speaker, never before", true, HbAudioRules.autoSystemAudioOff("SPEAKER", true, true, true, true, -1));
        eq("auto off: speaker, 31 s later", true, HbAudioRules.autoSystemAudioOff("SPEAKER", true, true, true, true, 31_000));
        eq("auto off: speaker, 5 s later", false, HbAudioRules.autoSystemAudioOff("SPEAKER", true, true, true, true, 5_000));
        eq("auto off: already off", false, HbAudioRules.autoSystemAudioOff("SPEAKER", true, true, true, false, -1));
        eq("auto off: soundbar chosen", false, HbAudioRules.autoSystemAudioOff("ARC", true, true, true, true, -1));
        eq("auto off: auto chosen", false, HbAudioRules.autoSystemAudioOff("AUTO", true, true, true, true, -1));
        eq("auto off: BT keeps System Audio", false, HbAudioRules.autoSystemAudioOff("BT", true, true, true, true, -1));
        eq("auto off: CEC off", false, HbAudioRules.autoSystemAudioOff("SPEAKER", false, true, true, true, -1));
        eq("auto off: soundbar switch off", false, HbAudioRules.autoSystemAudioOff("SPEAKER", true, false, true, true, -1));
        eq("auto off: no audio system", false, HbAudioRules.autoSystemAudioOff("SPEAKER", true, true, false, true, -1));
        if (fails > 0) {
            System.out.println("HbAudioRulesTest: " + fails + " of " + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("HbAudioRulesTest: " + checks + " checks passed");
    }
}
