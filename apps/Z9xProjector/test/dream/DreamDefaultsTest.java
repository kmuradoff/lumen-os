package org.z9x.projector.dream;

/**
 * Host check of the Lumen OS 1.0.1 screensaver one-shot (DreamSettings.applyDefaultsOnce, marker
 * dream_defaults_v2; decisions in {@link DreamDefaults}): which screensaver_components values become the
 * living sky and which stay the user's choice. sh test/dream/run.sh
 */
public final class DreamDefaultsTest {
    private static int failed, passed;

    private static final String SKY = DreamDefaults.SKY, CLOCK = DreamDefaults.CLOCK, IDLE = DreamDefaults.IDLE;
    private static final String COLORS = "com.android.dreams.basic/com.android.dreams.basic.Colors";
    private static final String BACKDROP = "com.google.android.backdrop/com.google.android.backdrop.Backdrop";
    private static final String DESKCLOCK = "com.google.android.deskclock/com.android.deskclock.Screensaver";
    private static final String USERS = "com.example.photos/com.example.photos.PhotoDream";

    private static void eq(String what, Object want, Object got) {
        if (want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    public static void main(String[] a) {
        // ---- screensaver on: unset or an earlier default -> the sky
        eq("unset", DreamDefaults.Active.SKY, DreamDefaults.active(null, true));
        eq("blank", DreamDefaults.Active.SKY, DreamDefaults.active("  ", true));
        eq("v1 clock", DreamDefaults.Active.SKY, DreamDefaults.active(CLOCK, true));
        eq("v1 clock, short form", DreamDefaults.Active.SKY,
                DreamDefaults.active("org.z9x.projector/.dream.ClockDream", true));
        eq("BasicDreams Colors", DreamDefaults.Active.SKY, DreamDefaults.active(COLORS, true));
        eq("Colors, short form", DreamDefaults.Active.SKY, DreamDefaults.active("com.android.dreams.basic/.Colors", true));
        eq("Backdrop", DreamDefaults.Active.SKY, DreamDefaults.active(BACKDROP, true));
        eq("DeskClock", DreamDefaults.Active.SKY, DreamDefaults.active(DESKCLOCK, true));
        eq("list of earlier defaults", DreamDefaults.Active.SKY, DreamDefaults.active(COLORS + "," + CLOCK, true));
        eq("unreadable", DreamDefaults.Active.SKY, DreamDefaults.active("garbage", true));
        // ---- already the sky
        eq("sky", DreamDefaults.Active.ALREADY, DreamDefaults.active(SKY, true));
        eq("sky, short form", DreamDefaults.Active.ALREADY, DreamDefaults.active("org.z9x.home/.sky.SkyDreamService", true));
        eq("sky first in a list", DreamDefaults.Active.ALREADY, DreamDefaults.active(SKY + "," + CLOCK, true));
        // ---- the user's own choice is never replaced
        eq("user's dream", DreamDefaults.Active.KEEP, DreamDefaults.active(USERS, true));
        eq("user's dream in a list", DreamDefaults.Active.KEEP, DreamDefaults.active(COLORS + "," + USERS, true));
        eq("standby dream (off) is not an earlier default", DreamDefaults.Active.KEEP, DreamDefaults.active(IDLE, true));
        // ---- the sky not installed: v1 rule for unset, everything else stays
        eq("unset, no sky", DreamDefaults.Active.CLOCK, DreamDefaults.active(null, false));
        eq("v1 clock, no sky", DreamDefaults.Active.KEEP, DreamDefaults.active(CLOCK, false));
        eq("Colors, no sky", DreamDefaults.Active.KEEP, DreamDefaults.active(COLORS, false));
        eq("user's dream, no sky", DreamDefaults.Active.KEEP, DreamDefaults.active(USERS, false));

        // ---- screensaver off (blank standby dream active): what "on" brings back
        eq("off, nothing saved", true, DreamDefaults.savedToSky("", true));
        eq("off, saved null", true, DreamDefaults.savedToSky(null, true));
        eq("off, saved v1 clock", true, DreamDefaults.savedToSky(CLOCK, true));
        eq("off, saved Colors", true, DreamDefaults.savedToSky(COLORS, true));
        eq("off, saved standby dream", true, DreamDefaults.savedToSky(IDLE, true));
        eq("off, saved sky", false, DreamDefaults.savedToSky(SKY, true));
        eq("off, saved user's dream", false, DreamDefaults.savedToSky(USERS, true));
        eq("off, no sky", false, DreamDefaults.savedToSky("", false));
        eq("standby dream active", true, DreamDefaults.isIdle(IDLE));
        eq("standby dream, short form", true, DreamDefaults.isIdle("org.z9x.projector/.dream.StandbyIdleDream"));
        eq("sky is not the standby dream", false, DreamDefaults.isIdle(SKY));
        eq("unset is not the standby dream", false, DreamDefaults.isIdle(null));

        // ---- 1.0.1 quick panel "Show" row: which of the sky / the clock is active
        eq("show sky", DreamDefaults.Shown.SKY, DreamDefaults.shown(SKY));
        eq("show sky, short form", DreamDefaults.Shown.SKY, DreamDefaults.shown("org.z9x.home/.sky.SkyDreamService"));
        eq("show clock", DreamDefaults.Shown.CLOCK, DreamDefaults.shown(CLOCK));
        eq("show clock first in a list", DreamDefaults.Shown.CLOCK, DreamDefaults.shown(CLOCK + "," + SKY));
        eq("show user's dream", DreamDefaults.Shown.OTHER, DreamDefaults.shown(USERS));
        eq("show standby dream (off)", DreamDefaults.Shown.OTHER, DreamDefaults.shown(IDLE));
        eq("show unset", DreamDefaults.Shown.OTHER, DreamDefaults.shown(null));

        // ---- component names
        eq("normalize short", SKY, DreamDefaults.normalize(" org.z9x.home/.sky.SkyDreamService "));
        eq("normalize full", CLOCK, DreamDefaults.normalize(CLOCK));
        eq("normalize no class", "null", String.valueOf(DreamDefaults.normalize("org.z9x.home/")));
        eq("normalize no package", "null", String.valueOf(DreamDefaults.normalize("/.Dream")));

        System.out.println((failed == 0 ? "OK" : "FAILED") + ": " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
