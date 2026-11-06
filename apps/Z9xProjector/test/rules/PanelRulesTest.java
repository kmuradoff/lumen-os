package org.lumen.projector.panel;

import org.lumen.projector.panel.PanelRules.In;
import org.lumen.projector.panel.PanelRules.Why;

/** Host check of panel/PanelRules (stock Z9X SettingMutexManager order). sh test/rules/run.sh */
public final class PanelRulesTest {
    private static int failed, passed;

    private static void eq(String what, Object want, Object got) {
        if (want == got || (want != null && want.equals(got))) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    /** 2D, SDR, Standard, no game, HDMI, nothing else on. */
    private static In base() {
        return new In().fmt(1).to2d(false).hdr(0).pic(1).game(false).opt(0).lumens(16).boost(false)
                .nativeFps(false).u120(false).video(true).hdmi(true).fps(6000);
    }

    public static void main(String[] a) {
        // ---- basic / professional picture: game, AI, Performance (stock S 0x016b / 0x036e)
        eq("basic: free", Why.NONE, PanelRules.basicOrPro(base()));
        eq("basic: game running", Why.GAME, PanelRules.basicOrPro(base().game(true)));
        eq("basic: game before AI", Why.GAME, PanelRules.basicOrPro(base().game(true).pic(10)));
        eq("basic: AI 10", Why.AI, PanelRules.basicOrPro(base().pic(10)));
        eq("basic: AI scene 16 is not 10 (stock tests 10 only)", Why.NONE, PanelRules.basicOrPro(base().pic(16)));
        eq("basic: AI with Dolby Vision", Why.NONE, PanelRules.basicOrPro(base().pic(10).hdr(2)));
        eq("basic: Performance 5", Why.PERFORMANCE, PanelRules.basicOrPro(base().pic(5)));
        eq("basic: Performance with DV", Why.NONE, PanelRules.basicOrPro(base().pic(5).hdr(2)));
        eq("basic: 55 unknown", Why.NONE, PanelRules.basicOrPro(base().game(null)));
        eq("basic: 666 unknown", Why.NONE, PanelRules.basicOrPro(base().pic(null)));

        // ---- colour temperature: Office (666 25), then 3D
        eq("ct: free", Why.NONE, PanelRules.colorTemp(base()));
        eq("ct: Office 25", Why.OFFICE, PanelRules.colorTemp(base().pic(25)));
        eq("ct: Office before 3D", Why.OFFICE, PanelRules.colorTemp(base().pic(25).fmt(2)));
        eq("ct: 3D SBS", Why.THREE_D, PanelRules.colorTemp(base().fmt(2)));
        eq("ct: 3D as 2D", Why.THREE_D, PanelRules.colorTemp(base().to2d(true)));
        eq("ct: 175 Office 12 alone does not refuse", Why.NONE, PanelRules.colorTemp(base().lumens(12)));
        eq("ct: 192 unknown", Why.NONE, PanelRules.colorTemp(base().fmt(null)));

        // ---- keystone family: top speed game, then 3D
        eq("kst: free", Why.NONE, PanelRules.keystone(base()));
        eq("kst: top speed + game", Why.TOP_SPEED, PanelRules.keystone(base().opt(3).game(true)));
        eq("kst: top speed HFR + game", Why.TOP_SPEED, PanelRules.keystone(base().opt(2).game(true)));
        eq("kst: top speed, game off", Why.NONE, PanelRules.keystone(base().opt(3).game(false)));
        eq("kst: top speed, game picture 4", Why.TOP_SPEED, PanelRules.keystone(base().opt(3).game(false).pic(4)));
        eq("kst: basic speed + game", Why.NONE, PanelRules.keystone(base().opt(1).game(true)));
        eq("kst: top speed off HDMI", Why.NONE, PanelRules.keystone(base().opt(3).game(true).hdmi(false)));
        eq("kst: 3D", Why.THREE_D, PanelRules.keystone(base().fmt(3)));
        eq("kst: top speed before 3D", Why.TOP_SPEED, PanelRules.keystone(base().fmt(3).opt(3).game(true)));
        eq("kst: 647 unknown", Why.NONE, PanelRules.keystone(base().opt(null).game(true)));
        eq("rt kst: 3D", Why.THREE_D, PanelRules.realtimeKeystone(base().fmt(2)));
        eq("rt kst: top speed is no rule", Why.NONE, PanelRules.realtimeKeystone(base().opt(3).game(true)));

        // ---- 3D on: stock order
        eq("3d: free", Why.NONE, PanelRules.threeDOn(base()));
        eq("3d: Ultra 120 first", Why.ULTRA_120, PanelRules.threeDOn(base().u120(true).nativeFps(true).game(true)));
        eq("3d: original frame rate", Why.NATIVE_FPS, PanelRules.threeDOn(base().nativeFps(true).game(true)));
        eq("3d: game 55", Why.GAME, PanelRules.threeDOn(base().game(true).opt(3)));
        eq("3d: game picture 4", Why.GAME, PanelRules.threeDOn(base().pic(4)));
        eq("3d: top speed, 55 unknown", Why.TOP_SPEED, PanelRules.threeDOn(base().game(null).opt(3)));
        eq("3d: Performance lumens 15", Why.PERFORMANCE, PanelRules.threeDOn(base().lumens(15)));
        eq("3d: no video", Why.NO_VIDEO, PanelRules.threeDOn(base().video(false).hdr(1)));
        eq("3d: video unknown is no refusal", Why.NONE, PanelRules.threeDOn(base().video(null)));
        eq("3d: HDR10", Why.HDR, PanelRules.threeDOn(base().hdr(1)));
        eq("3d: HDR unknown", Why.NONE, PanelRules.threeDOn(base().hdr(null)));
        eq("3d: HDMI 120 Hz", Why.HIGH_FRAME_RATE, PanelRules.threeDOn(base().fps(12000)));
        eq("3d: HDMI 119.88 Hz", Why.HIGH_FRAME_RATE, PanelRules.threeDOn(base().fps(11988)));
        eq("3d: HDMI 60 Hz", Why.NONE, PanelRules.threeDOn(base().fps(6000)));
        eq("3d: 662 off HDMI ignored", Why.NONE, PanelRules.threeDOn(base().hdmi(false).fps(12000)));

        // ---- HDMI mode: game at a high-frame-rate speed
        eq("edid: free", Why.NONE, PanelRules.hdmiMode(base()));
        eq("edid: game + basic HFR", Why.CLOSE_HFR, PanelRules.hdmiMode(base().game(true).opt(1)));
        eq("edid: game + top speed HFR", Why.CLOSE_HFR, PanelRules.hdmiMode(base().game(true).opt(2)));
        eq("edid: game + top speed", Why.NONE, PanelRules.hdmiMode(base().game(true).opt(3)));
        eq("edid: game off + HFR option", Why.NONE, PanelRules.hdmiMode(base().game(false).opt(1)));

        // ---- aspect: game, 3D
        eq("aspect: free", Why.NONE, PanelRules.aspect(base(), false));
        eq("aspect: game", Why.GAME, PanelRules.aspect(base().fmt(2), true));
        eq("aspect: 3D", Why.THREE_D, PanelRules.aspect(base().fmt(2), false));

        // ---- professional rows
        eq("cs: free", Why.NONE, PanelRules.colorSpace(base()));
        eq("cs: Office before Boost", Why.OFFICE, PanelRules.colorSpace(base().pic(25).boost(true)));
        eq("cs: Boost", Why.BOOST, PanelRules.colorSpace(base().boost(true)));
        eq("db: Office 25", Why.OFFICE, PanelRules.dynamicBlack(base().pic(25)));
        eq("db: Office 6", Why.OFFICE, PanelRules.dynamicBlack(base().pic(6)));
        eq("db: Standard", Why.NONE, PanelRules.dynamicBlack(base()));
        eq("native on: Ultra 120", Why.ULTRA_120, PanelRules.nativeFpsOn(base().u120(true).fmt(2)));
        eq("native on: 3D", Why.THREE_D, PanelRules.nativeFpsOn(base().fmt(2)));
        eq("native on: free", Why.NONE, PanelRules.nativeFpsOn(base()));
        eq("u120 on: original frame rate", Why.NATIVE_FPS, PanelRules.ultra120On(base().nativeFps(true).fmt(2)));
        eq("u120 on: 3D", Why.THREE_D, PanelRules.ultra120On(base().fmt(2)));
        eq("u120 on: free", Why.NONE, PanelRules.ultra120On(base()));

        // ---- picture mode: game, Dolby Vision, 3D
        eq("pm: free", Why.NONE, PanelRules.pictureMode(base(), false));
        eq("pm: game before 3D", Why.GAME, PanelRules.pictureMode(base().fmt(2), true));
        eq("pm: DV before 3D", Why.DOLBY_VISION, PanelRules.pictureMode(base().hdr(2).fmt(2), false));
        eq("pm: 3D", Why.THREE_D, PanelRules.pictureMode(base().fmt(2), false));

        // ---- brightness: lumens lock first, named from 175, then 3D
        eq("lamp: free", PanelRules.LAMP_FREE, PanelRules.brightness(base()));
        eq("lamp: Custom before 3D", 0, PanelRules.brightness(base().lumens(6).fmt(2)));
        eq("lamp: Office", 1, PanelRules.brightness(base().lumens(12)));
        eq("lamp: Performance", 2, PanelRules.brightness(base().lumens(15)));
        eq("lamp: 3D", PanelRules.LAMP_3D, PanelRules.brightness(base().fmt(2)));
        eq("lamp: 175 unknown, 3D", PanelRules.LAMP_3D, PanelRules.brightness(base().lumens(null).fmt(2)));

        // ---- game page: DV, 3D, not HDMI
        eq("game page: free", Why.NONE, PanelRules.gamePage(base()));
        eq("game page: DV first", Why.DOLBY_VISION, PanelRules.gamePage(base().hdr(2).fmt(2).hdmi(false)));
        eq("game page: 3D before HDMI", Why.THREE_D, PanelRules.gamePage(base().fmt(2).hdmi(false)));
        eq("game page: not HDMI", Why.NOT_HDMI, PanelRules.gamePage(base().hdmi(false)));

        // ---- wires
        eq("edid wire compat", 0, PanelRules.edidWire(0));
        eq("edid wire enhanced", 1, PanelRules.edidWire(1));
        eq("edid read 1 = Enhanced", 1, PanelRules.edidIndex(1));
        eq("edid read 0 = Compatible", 0, PanelRules.edidIndex(0));
        eq("edid read 2 = Compatible", 0, PanelRules.edidIndex(2));
        eq("edid read 3 = Compatible", 0, PanelRules.edidIndex(3));
        eq("aspect order", "[2, 0, 1, 4]", java.util.Arrays.toString(PanelRules.ASPECT_WIRES));
        eq("57 after 648 true, On", true, PanelRules.resendGameState(true, true));
        eq("no 57 after 648 false", false, PanelRules.resendGameState(false, true));
        eq("no 57 outside On", false, PanelRules.resendGameState(true, false));

        // ---- AI scene re-assert (stock AIPQ.reset)
        eq("ai scene: 10", true, PanelRules.aiSceneReassert(10));
        eq("ai scene: 16", true, PanelRules.aiSceneReassert(16));
        eq("ai scene: 17", true, PanelRules.aiSceneReassert(17));
        eq("ai scene: Standard 1", false, PanelRules.aiSceneReassert(1));
        eq("ai scene: 18", false, PanelRules.aiSceneReassert(18));
        eq("ai scene: unknown", false, PanelRules.aiSceneReassert(null));

        System.out.println("rules: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
