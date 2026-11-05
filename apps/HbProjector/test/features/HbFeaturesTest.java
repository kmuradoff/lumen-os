package org.lumen.projector.hal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Host test of HbFeatures (sh test/features/run.sh): the compile-time table against the stock G0130 feature.xml,
 * "off wins", require() for every state. Plain Java, android.util.Log and AidlCaller stubbed.
 */
public final class HbFeaturesTest {
    private static int failed;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    private static void reset() {
        for (HbFeatures.F f : HbFeatures.F.values()) f.offByFile = false;
    }

    private static HbFeatures.State[] snapshot() {
        HbFeatures.F[] v = HbFeatures.F.values();
        HbFeatures.State[] s = new HbFeatures.State[v.length];
        for (int i = 0; i < v.length; i++) s[i] = HbFeatures.state(v[i]);
        return s;
    }

    public static void main(String[] a) throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.UTF_8);
        // 1. the stock file changes nothing: every switch agrees with the table
        reset();
        HbFeatures.State[] before = snapshot();
        HbFeatures.apply(xml);
        HbFeatures.State[] after = snapshot();
        for (int i = 0; i < before.length; i++) {
            check(before[i] == after[i], "stock feature.xml changed " + HbFeatures.F.values()[i] + " " + before[i] + " -> " + after[i]);
        }
        check(android.util.Log.warnings == 0, "stock feature.xml logged " + android.util.Log.warnings + " off switches");
        // 2. the states the image relies on
        check(HbFeatures.on(HbFeatures.F.AUTO_KEYSTONE), "AUTO_KEYSTONE ON");
        check(HbFeatures.on(HbFeatures.F.MOVE_AK), "MOVE_AK ON");
        check(HbFeatures.on(HbFeatures.F.MANUAL_FOCUS), "MANUAL_FOCUS ON");
        check(HbFeatures.on(HbFeatures.F.PICTURE), "PICTURE ON");
        check(HbFeatures.on(HbFeatures.F.BOOST), "BOOST ON");
        // AREA picture (2026-10-30): the stock colour temperature list 0 / 16 (image_mode_4_color_temp_dapengse)
        check(HbFeatures.on(HbFeatures.F.COLOR_TEMP), "COLOR_TEMP ON");
        // 9.1 (2026-10-29): fit to screen with newAutoKst(11), and obstacle avoidance it switches on
        check(HbFeatures.on(HbFeatures.F.CURTAIN_FIT), "CURTAIN_FIT ON");
        check(HbFeatures.on(HbFeatures.F.OBSTACLE_AVOID), "OBSTACLE_AVOID ON");
        // hb-t8c: manual keystone after the 16 read / 64 + 15 one-step check on the device
        check(HbFeatures.on(HbFeatures.F.MANUAL_KEYSTONE), "MANUAL_KEYSTONE ON");
        // 2026-11-04: game mode with the stock HB options 1 / 2 (display_gamemode_version=3)
        check(HbFeatures.on(HbFeatures.F.GAME_MODE), "GAME_MODE ON");
        check(HbFeatures.on(HbFeatures.F.EYE_PROTECTION), "EYE_PROTECTION ON");
        for (HbFeatures.F f : new HbFeatures.F[] {HbFeatures.F.ZOOM_SHIFT, HbFeatures.F.ROTATION,
                HbFeatures.F.EQUALIZER, HbFeatures.F.UI_RESOLUTION, HbFeatures.F.FIT_SCREEN}) {
            check(HbFeatures.state(f) == HbFeatures.State.PENDING, f + " PENDING");
            check(!HbFeatures.on(f), f + " not on");
        }
        // hb-t5: optical zoom (9.3 zoom case) and the stock keystone reset (9.7)
        check(HbFeatures.on(HbFeatures.F.OPTICAL_ZOOM), "OPTICAL_ZOOM ON");
        check(HbFeatures.on(HbFeatures.F.KEYSTONE_RESET), "KEYSTONE_RESET ON");
        check(HbFeatures.state(HbFeatures.F.THREE_D) == HbFeatures.State.OFF, "THREE_D OFF");
        // 3. require(): only ON passes
        for (HbFeatures.F f : HbFeatures.F.values()) {
            boolean threw = false;
            try {
                HbFeatures.require(f, "test");
            } catch (AidlCaller.NotOnHbException e) {
                threw = true;
            }
            check(threw == !HbFeatures.on(f), "require(" + f + ") threw=" + threw);
        }
        // 4. off wins: a file that switches auto keystone off turns AUTO_KEYSTONE and MOVE_AK OFF, nothing turns ON
        reset();
        HbFeatures.apply(xml.replace("support_kst_adjust_type\" value=\"2\"", "support_kst_adjust_type\" value=\"0\"")
                .replace("support_optical_zoom\" value=\"true\"", "support_optical_zoom\" value=\"false\"")
                .replace("display_gamemode_version\" value=\"3\"", "display_gamemode_version\" value=\"0\""));
        check(HbFeatures.state(HbFeatures.F.AUTO_KEYSTONE) == HbFeatures.State.OFF, "AUTO_KEYSTONE off by file");
        check(HbFeatures.state(HbFeatures.F.MOVE_AK) == HbFeatures.State.OFF, "MOVE_AK off by file");
        check(HbFeatures.state(HbFeatures.F.GAME_MODE) == HbFeatures.State.OFF, "GAME_MODE off by file");
        check(HbFeatures.state(HbFeatures.F.OPTICAL_ZOOM) == HbFeatures.State.OFF, "OPTICAL_ZOOM off by file");
        // 4b. support_Adaptived_AK=0 (no adaptive AK) turns fit to screen and obstacle avoidance OFF
        reset();
        HbFeatures.apply(xml.replace("support_Adaptived_AK\" value=\"5\"", "support_Adaptived_AK\" value=\"0\""));
        check(HbFeatures.state(HbFeatures.F.CURTAIN_FIT) == HbFeatures.State.OFF, "CURTAIN_FIT off by file");
        check(HbFeatures.state(HbFeatures.F.OBSTACLE_AVOID) == HbFeatures.State.OFF, "OBSTACLE_AVOID off by file");
        check(HbFeatures.on(HbFeatures.F.AUTO_KEYSTONE), "AUTO_KEYSTONE untouched by support_Adaptived_AK");
        // 5. a file without the keys changes nothing
        reset();
        HbFeatures.apply("<features/>");
        HbFeatures.State[] empty = snapshot();
        for (int i = 0; i < before.length; i++) check(before[i] == empty[i], "empty file changed " + HbFeatures.F.values()[i]);
        reset();
        if (failed > 0) {
            System.out.println("HbFeaturesTest: " + failed + " failed");
            System.exit(1);
        }
        System.out.println("HbFeaturesTest: ok (" + HbFeatures.F.values().length + " features)");
    }
}
