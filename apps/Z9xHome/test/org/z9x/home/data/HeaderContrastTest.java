package org.z9x.home.data;

import org.z9x.home.ui.Scrims;
import org.z9x.home.ui.UiScale;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The header stays readable over any art (1.0.1 follow-up, owner report: clock, date, search and
 * settings vanished over a white flag) without the follow-up's plate (owner: a dark box behind the clock
 * and the icons): the full-width header scrim alone, as the gradient draws it, over a pure white backdrop,
 * at every row of real pixels of the header items in each UI mode (1080p, 2K, 4K). Colours come from
 * res/values/colors_lumen.xml (-Dhome.dir, set by tools/run_tests.sh).
 */
public final class HeaderContrastTest {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DATE = 0xFFE6DFD3; // TopBar.ClockBlock.LINE_COLOR
    // header items, design px (TopBar: header line y 60-132): the clock block (time over date, also its
    // focus pill) y 54-138; tabs, search, settings, update badge: 52 px pills on the line, y 70-122
    private static final int CLOCK_TOP = 54, CLOCK_BOTTOM = 138, PILL_TOP = 70, PILL_BOTTOM = 122;

    public static void run() throws Exception {
        String dir = System.getProperty("home.dir", ".");
        String xml = new String(Files.readAllBytes(new File(dir, "res/values/colors_lumen.xml").toPath()), StandardCharsets.UTF_8);
        int ground = color(xml, "lumen_ground"), text = color(xml, "lumen_text"), text2 = color(xml, "lumen_text2"),
                focus = color(xml, "lumen_focus"), onFocus = color(xml, "lumen_on_focus");
        // the scrim's shape: flat behind the header, then eased out to nothing, never rising
        T.ok(Scrims.headDrawn(0) == Scrims.HEAD_ALPHA && Scrims.headDrawn(Scrims.HEAD_HOLD) == Scrims.HEAD_ALPHA,
                "header scrim holds " + Scrims.HEAD_ALPHA + " down to y " + Scrims.HEAD_HOLD);
        T.ok(Scrims.headDrawn(Scrims.HEAD_END) == 0f && Scrims.HEAD_A[Scrims.HEAD_A.length - 1] == 0f
                && Scrims.HEAD_POS[Scrims.HEAD_POS.length - 1] == 1f, "header scrim gone at y " + Scrims.HEAD_END);
        boolean mono = true, close = true;
        float maxStep = 0f;
        for (int y = 0; y < Scrims.HEAD_END + 20; y++) {
            float a = Scrims.headDrawn(y), b = Scrims.headDrawn(y + 1);
            mono &= b <= a + 1e-6f;
            close &= Math.abs(a - Scrims.headAlpha(y)) < 0.012f; // 10 linear pieces follow the smoothstep
            maxStep = Math.max(maxStep, a - b);
        }
        T.ok(mono, "header scrim never rises");
        T.ok(close, "header scrim gradient follows the eased curve (within 0.012)");
        // no edge: the steepest step is about 1.5 levels of 255 per design px (a linear 200 px fade: 0.94)
        T.ok(maxStep < 0.006f, "header scrim: no step over 0.006 per design px (got " + maxStep + ")");
        for (int[] m : UiScale.MODES) {
            float s = UiScale.scale(m[0], m[1]);
            String mode = m[0] + "x" + m[1];
            // the darkest the scrim leaves over white, over every real pixel row of each item
            int clockBg = worst(ground, UiScale.px(CLOCK_TOP, s), UiScale.px(CLOCK_BOTTOM, s), s);
            int pillBg = worst(ground, UiScale.px(PILL_TOP, s), UiScale.px(PILL_BOTTOM, s), s);
            check(mode + " time (text) over white", text, clockBg);
            check(mode + " date line over white", DATE, clockBg);
            check(mode + " stale date (text2) over white", text2, clockBg);
            check(mode + " search / settings icons (text) over white", text, pillBg);
            check(mode + " tab (text2) over white", text2, pillBg);
            check(mode + " selected tab (text on 15 % paper) over white", text, Scrims.over(text, 0.15f, pillBg));
            check(mode + " update badge (text on 14 % paper) over white", text, Scrims.over(text, 0.14f, pillBg));
        }
        // the stage's crossfade to or from no art: white art at alpha a over a white sky that has its own
        // header scrim, then the stage's header scrim at Scrims.headOverArt(a), never lighter than the hold
        boolean fadeOk = true;
        double fadeMin = 99;
        for (int i = 0; i <= 100; i++) {
            float a = i / 100f;
            int sky = Scrims.over(ground, Scrims.HEAD_ALPHA, WHITE);
            int art = Scrims.over(WHITE, a, sky);
            int bg = Scrims.over(ground, Scrims.HEAD_ALPHA * Scrims.headOverArt(a), art);
            double r = Scrims.contrast(text2, bg);
            fadeMin = Math.min(fadeMin, r);
            fadeOk &= r >= 4.5;
        }
        T.ok(fadeOk && Scrims.headOverArt(0f) == 0f && Scrims.headOverArt(1f) == 1f,
                String.format(java.util.Locale.ROOT, "tabs over white art fading to or from the sky >= 4.5:1 (lowest %.2f:1)", fadeMin));
        check("focused button / clock", onFocus, focus);
        // over the stage the start side also gets D_Home's side scrim: only darker
        T.ok(Math.abs(Scrims.sideAlpha(0.27f) - 0.8f) < 1e-4 && Scrims.sideAlpha(0.9f) == 0f, "side scrim stops");
    }

    /** The lightest backdrop between real rows y0..y1 (the scrim is weakest at the bottom row). */
    private static int worst(int ground, int y0, int y1, float scale) {
        float a = 1f;
        for (int y = y0; y <= y1; y++) a = Math.min(a, Scrims.headDrawn((y + 0.5f) / scale));
        return Scrims.over(ground, a, WHITE);
    }

    private static boolean ok(int fg, int bg) {
        return Scrims.contrast(fg, bg) >= 4.5;
    }

    private static String ratio(int fg, int bg) {
        return String.format(java.util.Locale.ROOT, "%.2f:1", Scrims.contrast(fg, bg));
    }

    private static void check(String what, int fg, int bg) {
        T.ok(ok(fg, bg), what + " >= 4.5:1 (got " + ratio(fg, bg) + ")");
        System.out.println("contrast " + what + ": " + ratio(fg, bg));
    }

    private static int color(String xml, String name) {
        Matcher m = Pattern.compile("name=\"" + name + "\">#([0-9A-Fa-f]{8})<").matcher(xml);
        if (!m.find()) throw new IllegalStateException("colour " + name + " not in colors_lumen.xml");
        return (int) Long.parseLong(m.group(1), 16);
    }
}
