package org.lumen.projector.panel;

/**
 * Host check of the manual keystone game lock (stock isGameKeystoneMutex, STATUS_1.0.8 "Запрет трапеции в Турбо"):
 * refused only while a top-speed game really runs on HDMI. sh test/kstgame/run.sh
 */
public final class KstGameLockTest {
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
        for (int opt : new int[] {2, 3}) {
            // the bug: Turbo stored, game off (55 {1,0,opt}) -> allowed, as stock
            eq("top " + opt + " game off", false, KstGameLock.refuse(opt, true, false, null));
            eq("top " + opt + " game off, 666 AI", false, KstGameLock.refuse(opt, true, false, 10));
            eq("top " + opt + " game off, 666 game picture", true, KstGameLock.refuse(opt, true, false, 4));
            eq("top " + opt + " game on", true, KstGameLock.refuse(opt, true, true, null));
            eq("top " + opt + " 55 unknown (as 1.0.7)", true, KstGameLock.refuse(opt, true, null, null));
            eq("top " + opt + " not on HDMI, game on", false, KstGameLock.refuse(opt, false, true, 4));
        }
        for (int opt : new int[] {0, 1}) {             // Basic: support_Trapezoid_GAME=true
            eq("basic " + opt + " game on", false, KstGameLock.refuse(opt, true, true, 4));
            eq("basic " + opt + " unknown", false, KstGameLock.refuse(opt, true, null, null));
        }
        eq("647 out of range", false, KstGameLock.refuse(7, true, true, 4));
        eq("isTopSpeed 2", true, KstGameLock.isTopSpeed(2));
        eq("isTopSpeed 1", false, KstGameLock.isTopSpeed(1));

        System.out.println("KstGameLockTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
