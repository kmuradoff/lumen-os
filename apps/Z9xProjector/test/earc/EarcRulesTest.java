package org.lumen.projector.cec;

/** Host test of cec/EarcRules (eARC the stock way). */
public final class EarcRulesTest {
    private static int fails, checks;

    private static void eq(String what, Object want, Object got) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            fails++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    public static void main(String[] a) {
        EarcRules.Step none = EarcRules.Step.NONE, con = EarcRules.Step.CONNECT, dis = EarcRules.Step.DISCONNECT;
        eq("eARC linked, nothing listed", con, EarcRules.step(3, false, false));
        eq("eARC linked, CEC ARC output listed", none, EarcRules.step(3, false, true));
        eq("eARC linked, ours listed", none, EarcRules.step(3, true, true));
        eq("eARC linked, ours removed by <Terminate ARC>", con, EarcRules.step(3, true, false));
        eq("eARC ended, ours", dis, EarcRules.step(1, true, true));
        eq("eARC off, ours already gone", dis, EarcRules.step(0, true, false));
        eq("plain ARC, never ours", none, EarcRules.step(1, false, true));
        eq("eARC pending (2), nothing listed", none, EarcRules.step(2, false, false));
        eq("read failed (-1)", none, EarcRules.step(-1, false, false));
        eq("restart: auto", true, EarcRules.arcRestart("AUTO", false, true));
        eq("restart: soundbar chosen", true, EarcRules.arcRestart("ARC", false, true));
        eq("restart: nothing stored", true, EarcRules.arcRestart(null, false, true));
        eq("restart: ARC back", false, EarcRules.arcRestart("AUTO", true, true));
        eq("restart: speaker", false, EarcRules.arcRestart("SPEAKER", false, true));
        eq("restart: BT", false, EarcRules.arcRestart("BT", false, true));
        eq("restart: wired", false, EarcRules.arcRestart("WIRED", false, true));
        eq("restart: no audio system", false, EarcRules.arcRestart("AUTO", false, false));
        // ---- 2026-11-04 (A1): restart only after an eARC event of this process
        eq("allowed: link 3 seen", true, EarcRules.restartAllowed(999_999, false, true, 30_000));
        eq("allowed: row pressed 5 s ago", true, EarcRules.restartAllowed(5_000, true, false, 30_000));
        eq("refused: row pressed 31 s ago", false, EarcRules.restartAllowed(31_000, true, false, 30_000));
        eq("refused: plain ARC soundbar off, no row press", false, EarcRules.restartAllowed(1_000, false, false, 30_000));
        eq("refused: clock went back", false, EarcRules.restartAllowed(-1, true, false, 30_000));
        eq("awake: screen on, soundbar on", true, EarcRules.restartAwake(true, false));
        eq("awake: screen off", false, EarcRules.restartAwake(false, false));
        eq("awake: soundbar standby", false, EarcRules.restartAwake(true, true));
        if (fails > 0) {
            System.out.println("EarcRulesTest: " + fails + " of " + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("EarcRulesTest: " + checks + " checks passed");
    }
}
