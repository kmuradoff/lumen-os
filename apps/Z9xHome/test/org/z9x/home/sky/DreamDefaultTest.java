package org.z9x.home.sky;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Host check of the 1.0.1 screensaver one-shot (sky/DreamDefault): which Settings.Secure values get the
 * living sky and which stay (an installed dream is never replaced). Run by tools/run_tests.sh.
 */
public final class DreamDefaultTest {
    private static int failed, passed;

    private static final String SKY = "org.z9x.home/org.z9x.home.sky.SkyDreamService";
    private static final String CLOCK = "org.z9x.projector/org.z9x.projector.dream.ClockDream";
    private static final String COLORS = "com.android.dreams.basic/com.android.dreams.basic.Colors";
    private static final String BACKDROP = "com.google.android.backdrop/com.google.android.backdrop.Backdrop";

    public static void main(String[] a) {
        // what this image has installed as dreams
        Set<String> dreams = new HashSet<>(Arrays.asList(SKY, CLOCK, "org.z9x.projector/org.z9x.projector.dream.StandbyIdleDream"));
        Predicate<String> installed = dreams::contains;

        // screensaver_default_component: unset or an old default -> sky; a usable dream stays
        ok(DreamDefault.newDefault(null, installed), "default unset -> sky");
        ok(DreamDefault.newDefault("", installed), "default empty -> sky");
        ok(DreamDefault.newDefault(COLORS, installed), "default BasicDreams Colors -> sky");
        ok(DreamDefault.newDefault("com.android.dreams.basic/.Colors", installed), "default short form -> sky");
        ok(DreamDefault.newDefault(BACKDROP, installed), "default Backdrop -> sky");
        ok(DreamDefault.newDefault("com.example.gone/.Dream", installed), "default missing dream -> sky");
        ok(DreamDefault.newDefault("garbage", installed), "default unreadable -> sky");
        ok(!DreamDefault.newDefault(SKY, installed), "default already the sky: kept");
        ok(!DreamDefault.newDefault(CLOCK, installed), "default an installed dream: kept");

        // screensaver_components: only old defaults / missing dreams are replaced; empty stays empty
        ok(!DreamDefault.newActive(null, installed), "active unset: left to the fallback and Z9xProjector");
        ok(!DreamDefault.newActive("  ", installed), "active blank: left alone");
        ok(DreamDefault.newActive(COLORS, installed), "active BasicDreams Colors -> sky");
        ok(DreamDefault.newActive(COLORS + "," + BACKDROP, installed), "active list of old defaults -> sky");
        ok(DreamDefault.newActive("com.example.gone/.Dream", installed), "active missing dream -> sky");
        ok(!DreamDefault.newActive(CLOCK, installed), "active projector clock (or the user's pick): kept");
        ok(!DreamDefault.newActive("org.z9x.projector/.dream.StandbyIdleDream", installed), "active screensaver-off dream: kept");
        ok(!DreamDefault.newActive(COLORS + "," + CLOCK, installed), "active list with an installed dream: kept");
        ok(!DreamDefault.newActive(SKY, installed), "active already the sky: kept");

        // the deskclock default is an old default even if some image had it installed
        Predicate<String> all = n -> true;
        ok(DreamDefault.newActive("com.google.android.deskclock/com.android.deskclock.Screensaver", all),
                "active deskclock default -> sky");

        eq(DreamDefault.normalize("a.b/.C"), "a.b/a.b.C", "normalize short class");
        eq(DreamDefault.normalize(" a.b/x.Y "), "a.b/x.Y", "normalize trims");
        eq(DreamDefault.normalize("/x.Y"), null, "normalize no package");
        eq(DreamDefault.normalize("a.b/"), null, "normalize no class");

        System.out.println("dream default tests passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void ok(boolean c, String what) {
        if (c) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    private static void eq(Object a, Object b, String what) {
        ok(a == null ? b == null : a.equals(b), what + " (got " + a + ", want " + b + ")");
    }
}
