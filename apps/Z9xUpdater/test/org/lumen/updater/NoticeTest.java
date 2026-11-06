// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

/** Host check of the one-time statistics notice: when it becomes due at boot, and when it may show. */
public final class NoticeTest {
    private static int fails, runs;

    private static void eq(String what, boolean want, boolean got) {
        runs++;
        if (want != got) {
            fails++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    public static void main(String[] a) {
        // dueAtBoot(setupDone, seen, statsOn, lastVc, vc)
        eq("update from a version without the notice", true, NoticeRule.dueAtBoot(true, false, true, 0, 10010));
        eq("update between notice versions", true, NoticeRule.dueAtBoot(true, false, true, 10009, 10010));
        eq("plain reboot, same version", false, NoticeRule.dueAtBoot(true, false, true, 10010, 10010));
        eq("fresh install: setup still running", false, NoticeRule.dueAtBoot(false, false, true, 0, 10010));
        eq("saw the setup's Done page", false, NoticeRule.dueAtBoot(true, true, true, 10009, 10010));
        eq("switch already off", false, NoticeRule.dueAtBoot(true, false, false, 10009, 10010));
        eq("no version code", false, NoticeRule.dueAtBoot(true, false, true, 0, 0));
        // canShow(home, top, interactive, playing)
        String home = "org.lumen.home/org.lumen.home.HomeActivity";
        eq("home in front", true, NoticeRule.canShow(home, home, true, false));
        eq("screensaver of the home package", false,
                NoticeRule.canShow(home, "org.lumen.home/android.service.dreams.DreamActivity", true, false));
        eq("HDMI input in front", false, NoticeRule.canShow(home, "org.lumen.tvinput/.HdmiActivity", true, false));
        eq("film in front", false, NoticeRule.canShow(home, "org.lumen.play/.PlayerActivity", true, false));
        eq("music playing over home", false, NoticeRule.canShow(home, home, true, true));
        eq("screen off", false, NoticeRule.canShow(home, home, false, false));
        eq("no home known", false, NoticeRule.canShow(null, null, true, false));
        eq("top unknown", false, NoticeRule.canShow(home, null, true, false));
        System.out.println(fails == 0 ? "notice tests passed=" + runs + " failed=0" : "notice tests failed=" + fails);
        if (fails != 0) System.exit(1);
    }
}
