package org.z9x.projector.display;

import org.z9x.projector.sys.BootReason;

/**
 * Host check of the Lumen OS 1.0.1 interface resolution decisions ({@link UiRes}: property values, the 4K
 * default, the chooser's rows (2K only with the debug property), sizes, the setting's fallback note,
 * restart or not, the "Keep this resolution?" question) and of the unattended-restart boot reasons
 * ({@link BootReason}, RemoteAutoPair's remote-lost prompt). The 109 corner units of the auto keystone
 * are checked by org.z9x.projector.ak.AkUnitsTest (same run).
 * sh test/uires/run.sh
 */
public final class UiResTest {
    private static int failed, passed;

    private static void eq(String what, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    public static void main(String[] a) {
        // ---- property values
        eq("1080", 1080, UiRes.parse("1080"));
        eq("1440", 1440, UiRes.parse("1440"));
        eq("2160", 2160, UiRes.parse("2160"));
        eq("spaces", 1440, UiRes.parse(" 1440 "));
        eq("1440p", 1440, UiRes.parse("1440p"));
        eq("WxH", 2160, UiRes.parse("3840x2160"));
        eq("2k", 1440, UiRes.parse("2K"));
        eq("4k", 2160, UiRes.parse("4k"));
        eq("720 is no choice", 0, UiRes.parse("720"));
        eq("garbage", 0, UiRes.parse("auto"));
        eq("empty", 0, UiRes.parse(""));
        eq("null", 0, UiRes.parse(null));
        eq("unset wants the default 4K", 2160, UiRes.wanted(""));
        eq("null wants the default 4K", 2160, UiRes.wanted(null));
        eq("garbage wants the default", 2160, UiRes.wanted("8k"));
        eq("1080 wanted", 1080, UiRes.wanted("1080"));
        eq("4K wanted", 2160, UiRes.wanted("2160"));
        eq("2K stays a valid (hidden) value", 1440, UiRes.wanted("1440"));

        // ---- the 4K default: 1 UI px = 1 panel px (3840 x 2160 DLP panel), 960 x 540 dp
        eq("default is 4K", "4K", UiRes.label(UiRes.DEFAULT));
        eq("default width = panel width", 3840, UiRes.width(UiRes.DEFAULT));
        eq("default density", 640, UiRes.density(UiRes.DEFAULT));

        // ---- the chooser's rows: 4K (default) and 1080p; 2K only for testing
        eq("rows", "[2160, 1080]", java.util.Arrays.toString(UiRes.offered(false, 2160, 2160)));
        eq("rows: default first", UiRes.DEFAULT, UiRes.offered(false, 1080, 1080)[0]);
        eq("rows after a 4K fallback", "[2160, 1080]", java.util.Arrays.toString(UiRes.offered(false, 2160, 1080)));
        eq("rows with the debug property", "[2160, 1440, 1080]", java.util.Arrays.toString(UiRes.offered(true, 2160, 2160)));
        eq("rows while 2K is set (adb, old test build)", "[2160, 1440, 1080]", java.util.Arrays.toString(UiRes.offered(false, 1440, 2160)));
        eq("rows while 2K runs", "[2160, 1440, 1080]", java.util.Arrays.toString(UiRes.offered(false, 2160, 1440)));
        eq("rows, active unknown", "[2160, 1080]", java.util.Arrays.toString(UiRes.offered(false, 2160, 0)));
        eq("dev 1", true, UiRes.isOn("1"));
        eq("dev true", true, UiRes.isOn(" TRUE "));
        eq("dev on", true, UiRes.isOn("on"));
        eq("dev 0", false, UiRes.isOn("0"));
        eq("dev empty", false, UiRes.isOn(""));
        eq("dev null", false, UiRes.isOn(null));
        eq("dev 2", false, UiRes.isOn("2"));

        // ---- sizes, densities (same 960 x 540 dp), labels
        eq("width 1080", 1920, UiRes.width(1080));
        eq("width 1440", 2560, UiRes.width(1440));
        eq("width 2160", 3840, UiRes.width(2160));
        eq("dp width 1080", 960, UiRes.width(1080) * 160 / UiRes.density(1080));
        eq("dp width 1440", 959, UiRes.width(1440) * 160 / UiRes.density(1440));   // 427 dpi: 959.25 dp
        eq("dp width 2160", 960, UiRes.width(2160) * 160 / UiRes.density(2160));
        eq("from 1920x1080", 1080, UiRes.fromSize(1920, 1080));
        eq("from portrait", 1440, UiRes.fromSize(1440, 2560));
        eq("from 3840x2160", 2160, UiRes.fromSize(3840, 2160));
        eq("from 1280x720", 0, UiRes.fromSize(1280, 720));
        eq("from 2560x1600", 0, UiRes.fromSize(2560, 1600));
        eq("label 1080", "1080p", UiRes.label(1080));
        eq("label 1440", "2K", UiRes.label(1440));
        eq("label 2160", "4K", UiRes.label(2160));
        eq("size 1440", "2560 × 1440", UiRes.size(1440));

        // ---- the setting's summary: a fallback only from the uires lane's own properties
        UiRes.Status s = UiRes.status("", "2160", 2160, "ok");
        eq("default 4K running", "2160/2160/false", s.wanted + "/" + s.active + "/" + s.fallback);
        s = UiRes.status("", "1080", 1080, "2160 fallback: GOP 1920x1080->3840x2160");
        eq("default 4K fell back to 1080p", "2160/1080/true/2160", s.wanted + "/" + s.active + "/" + s.fallback + "/" + s.fellFrom);
        s = UiRes.status("", "", 2160, "");
        eq("4K by the display, no lane property", "2160/2160/false", s.wanted + "/" + s.active + "/" + s.fallback);
        s = UiRes.status("1440", "1440", 1440, "ok");
        eq("hidden 2K set and running", "1440/1440/false", s.wanted + "/" + s.active + "/" + s.fallback);
        s = UiRes.status("2160", "1080", 1080, "fallback: gop size 5120x2880");
        eq("4K fell back to 1080p", "2160/1080/true", s.wanted + "/" + s.active + "/" + s.fallback);
        s = UiRes.status("", "", 1080, "");
        eq("image without the uires lane: no false fallback", "2160/1080/false", s.wanted + "/" + s.active + "/" + s.fallback);
        s = UiRes.status("2160", "", 1080, "fallback (sf cap)");
        eq("active unset, why says fallback", "2160/1080/true", s.wanted + "/" + s.active + "/" + s.fallback);
        s = UiRes.status("1080", "1080", 1080, "fallback earlier");
        eq("the fallback value chosen: no note", false, s.fallback);
        s = UiRes.status("2160", "1080", 1080, "fallback");
        eq("fell back from the wanted value", 2160, s.fellFrom);
        s = UiRes.status("1080", "1080", 1080, "fallback", 2160, 1080);
        eq("lane rewrote persist: our memory tells", "true/2160", s.fallback + "/" + s.fellFrom);
        s = UiRes.status("", "", 1080, "", 1440, 1080);
        eq("no uires lane: our memory tells", "true/1440", s.fallback + "/" + s.fellFrom);
        s = UiRes.status("2160", "2160", 2160, "ok", 2160, 1080);
        eq("memory of an older fallback, now running", false, s.fallback);
        s = UiRes.status("1440", "1440", 1440, "", 0, 0);
        eq("nothing remembered", "false/0", s.fallback + "/" + s.fellFrom);
        // ---- wanted = the lane's sys.z9x.ui_res.want when published (it ignores 1440 from the app: debug file only)
        eq("lane want wins", "2160", UiRes.effectiveWant("1440", "2160"));
        eq("lane want = persist", "1080", UiRes.effectiveWant("1080", "1080"));
        eq("no lane: persist", "1080", UiRes.effectiveWant("1080", ""));
        eq("no lane, unset", "", UiRes.effectiveWant("", null));
        eq("lane want garbage: persist", "1440", UiRes.effectiveWant("1440", "auto"));
        s = UiRes.status(UiRes.effectiveWant("1440", "2160"), "2160", 2160, "");
        eq("stale 1440 from 20261009b, lane runs 4K: no false 2K note", "2160/2160/false",
                s.wanted + "/" + s.active + "/" + s.fallback);
        eq("stale 1440: no 2K row", "[2160, 1080]", java.util.Arrays.toString(UiRes.offered(false, s.wanted, s.active)));
        s = UiRes.status(UiRes.effectiveWant("", "1440"), "1440", 1440, "");
        eq("lane debug file 2K: no false 4K note", "1440/1440/false", s.wanted + "/" + s.active + "/" + s.fallback);
        eq("lane debug file 2K: 2K row", "[2160, 1440, 1080]", java.util.Arrays.toString(UiRes.offered(false, s.wanted, s.active)));
        s = UiRes.status(UiRes.effectiveWant("", "2160"), "1080", 1080, "2160 fallback: check: region 1920x1080");
        eq("lane fallback still told", "true/2160", s.fallback + "/" + s.fellFrom);
        s = UiRes.status(UiRes.effectiveWant("1080", "1080"), "1080", 1080, "");
        eq("1080p chosen and running", "1080/1080/false", s.wanted + "/" + s.active + "/" + s.fallback);

        eq("active from the property", 2160, UiRes.active("2160", 1080));
        eq("active from the display", 1080, UiRes.active("", 1080));
        eq("active unknown", 0, UiRes.active("", 0));

        // ---- restart or not
        eq("2K -> 4K restarts", true, UiRes.needsRestart(2160, 1440));
        eq("default 4K -> 1080p restarts", true, UiRes.needsRestart(1080, 2160));
        eq("4K chosen while 4K runs: no restart", false, UiRes.needsRestart(2160, 2160));
        eq("way back from 1080p = the 4K that ran", 2160, UiRes.previous(2160, 2160));
        eq("fallback 1080p chosen: no restart", false, UiRes.needsRestart(1080, 1080));
        eq("active unknown: restart", true, UiRes.needsRestart(1080, 0));
        eq("previous = what ran", 1080, UiRes.previous(1080, 2160));
        eq("previous unknown = wanted", 1440, UiRes.previous(0, 1440));

        // ---- the restart waits for the uires lane's "pick" (sys.z9x.ui_res.want / .failed)
        eq("pick not run yet", false, UiRes.pickDone("1440", "", 2160));
        eq("pick done", true, UiRes.pickDone("2160", "", 2160));
        eq("re-picked fallback not cleared yet", false, UiRes.pickDone("2160", "2160", 2160));
        eq("re-picked fallback cleared", true, UiRes.pickDone("2160", "1440", 2160));
        eq("back to 1080p", true, UiRes.pickDone("1080", "2160", 1080));
        eq("failed list", true, UiRes.listed("1440 2160", 2160));
        eq("failed list, other mode", false, UiRes.listed("1440", 2160));
        eq("failed list empty", false, UiRes.listed("", 1440));
        eq("failed list null", false, UiRes.listed(null, 1440));

        // ---- "Keep this resolution?"
        eq("no marker (the 4K default after the update): never asked", UiRes.Keep.NONE, UiRes.keep(0, "", "b2", 2160));
        eq("no marker", UiRes.Keep.NONE, UiRes.keep(0, "", "b2", 1440));
        eq("user chose 1080p, it runs: ask", UiRes.Keep.ASK, UiRes.keep(1080, "b1", "b2", 1080));
        eq("same boot: restart pending", UiRes.Keep.SAME_BOOT, UiRes.keep(2160, "b1", "b1", 1440));
        eq("boot id unreadable", UiRes.Keep.SAME_BOOT, UiRes.keep(2160, "b1", null, 2160));
        eq("new mode runs: ask", UiRes.Keep.ASK, UiRes.keep(2160, "b1", "b2", 2160));
        eq("boot fallback ran: drop", UiRes.Keep.DROP, UiRes.keep(2160, "b1", "b2", 1080));
        eq("active unknown: drop", UiRes.Keep.DROP, UiRes.keep(1440, "b1", "b2", 0));
        eq("countdown 15 s", 15, UiRes.secondsLeft(15_000));
        eq("countdown rounds up", 15, UiRes.secondsLeft(14_001));
        eq("countdown last second", 1, UiRes.secondsLeft(1));
        eq("countdown over", 0, UiRes.secondsLeft(-5));

        // ---- unattended restarts (no remote-lost prompt)
        eq("ota", "reboot,z9x-ota", BootReason.unattended("reboot,z9x-ota", "", "", ""));
        eq("ota rollback", "reboot,z9x-ota-rollback", BootReason.unattended("", "reboot,z9x-ota-rollback"));
        eq("ota stuck", "reboot,z9x-ota-stuck", BootReason.unattended(null, " reboot,z9x-ota-stuck "));
        eq("ui resolution", "reboot,z9x-uires", BootReason.unattended("reboot,z9x-uires"));
        eq("ui resolution fallback", "reboot,z9x-uires-fallback", BootReason.unattended("reboot,z9x-uires-fallback"));
        eq("boot rescue", "reboot,fastboot", BootReason.unattended("reboot,fastboot"));
        eq("after fastbootd", "reboot,from_fastboot", BootReason.unattended("reboot,from_fastboot"));
        eq("bootloader", "bootloader", BootReason.unattended("", "", "", "bootloader"));
        eq("RescueParty", "reboot,RescueParty", BootReason.unattended("reboot,RescueParty"));
        eq("power menu restart", "null", BootReason.unattended("reboot,userrequested", "reboot,userrequested", "", "reboot"));
        eq("cold boot", "null", BootReason.unattended("cold", "", "", ""));
        eq("watchdog", "null", BootReason.unattended("watchdog", "kernel_panic"));
        eq("all empty", "null", BootReason.unattended("", null, " "));
        eq("no array", "null", BootReason.unattended((String[]) null));

        System.out.println((failed == 0 ? "OK" : "FAILED") + ": " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
