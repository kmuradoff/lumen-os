// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

/** The two decisions of the one-time statistics notice (Notice), plain Java for the host test (NoticeTest). */
final class NoticeRule {
    private NoticeRule() {}

    /**
     * At boot: due after a version change (lastVc = the version of the previous boot, 0 before this updater recorded
     * one), when the first setup is finished (a fresh install gets the choice in its setup), this install never saw
     * the choice and the switch is on.
     */
    static boolean dueAtBoot(boolean setupDone, boolean seen, boolean statsOn, int lastVc, int vc) {
        return setupDone && !seen && statsOn && vc > 0 && lastVc != vc;
    }

    /**
     * May the notice show now: the home activity itself is in front (flattened component names: not the screensaver,
     * the HDMI input or a player of the same package), the screen on and nothing playing.
     */
    static boolean canShow(String home, String top, boolean interactive, boolean playing) {
        return home != null && !home.isEmpty() && home.equals(top) && interactive && !playing;
    }
}
