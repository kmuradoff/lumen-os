package org.z9x.projector.dream;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * MODULE "screensaver", Lumen OS 1.0.1: which screensaver the one-shot default (DreamSettings
 * .applyDefaultsOnce, marker dream_defaults_v2) chooses. Plain Java (component names as "pkg/cls"
 * strings, no Android calls): host test test/dream/DreamDefaultsTest (sh test/dream/run.sh).
 *
 * The owner's default is Lumen Home's living sky. Projectors upgraded over the air keep what an
 * earlier image chose in Settings.Secure screensaver_components (1.0 / 1.0.0: our clock, set once
 * by the v1 default; before that an Android TV default). Only those values, or none at all, are
 * replaced; anything else is the user's choice and stays. A pick of the clock before 1.0.1 looks
 * the same as the v1 default and is replaced once (later picks are never touched again).
 */
final class DreamDefaults {
    private DreamDefaults() {}

    static final String SKY = "org.z9x.home/org.z9x.home.sky.SkyDreamService";
    static final String CLOCK = "org.z9x.projector/org.z9x.projector.dream.ClockDream";
    static final String IDLE = "org.z9x.projector/org.z9x.projector.dream.StandbyIdleDream";

    /** Defaults of earlier images: the v1 clock, and the dreams Android / Android TV images name. */
    private static final Set<String> PREVIOUS = new HashSet<>(Arrays.asList(
            CLOCK,
            "com.android.dreams.basic/com.android.dreams.basic.Colors",          // TvFrameworkOverlay
            "com.google.android.backdrop/com.google.android.backdrop.Backdrop",  // Android TV (Ambient)
            "com.google.android.deskclock/com.android.deskclock.Screensaver",    // AOSP framework default
            "com.android.deskclock/com.android.deskclock.Screensaver"));

    /** What happens to the active screensaver (screensaver on). */
    enum Active {
        /** unset or a previous default: the sky becomes the active screensaver */
        SKY,
        /** unset, and the sky is not an installed dream: the v1 default (our clock) */
        CLOCK,
        /** the sky already */
        ALREADY,
        /** the user's choice (or a previous default while the sky is missing): kept */
        KEEP
    }

    static Active active(String comps, boolean skyInstalled) {
        if (isUnset(comps)) return skyInstalled ? Active.SKY : Active.CLOCK;
        if (SKY.equals(first(comps))) return Active.ALREADY;
        if (!onlyPrevious(comps)) return Active.KEEP;
        return skyInstalled ? Active.SKY : Active.KEEP;
    }

    /**
     * Screensaver off (IdleOwner: the blank standby dream is active): the choice it returns to when it is
     * turned on again (IdleOwner's saved components) becomes the sky when it is unset, the standby dream
     * itself or a previous default. Screensaver_components is not written: IdleOwner would read that as a
     * screensaver chosen elsewhere and turn it on.
     */
    static boolean savedToSky(String saved, boolean skyInstalled) {
        if (!skyInstalled) return false;
        return isUnset(saved) || IDLE.equals(first(saved)) || (!SKY.equals(first(saved)) && onlyPrevious(saved));
    }

    /** Which of our two screensavers the quick panel's "Show" row marks (Lumen OS 1.0.1). */
    enum Shown {
        /** Lumen Home's living sky is the active screensaver */
        SKY,
        /** our clock is */
        CLOCK,
        /** another dream, none chosen, or the blank standby dream (screensaver off) */
        OTHER
    }

    static Shown shown(String comps) {
        String f = first(comps);
        if (SKY.equals(f)) return Shown.SKY;
        if (CLOCK.equals(f)) return Shown.CLOCK;
        return Shown.OTHER;
    }

    /** The blank standby dream is the active one (IdleOwner: screensaver off). */
    static boolean isIdle(String comps) {
        return IDLE.equals(first(comps));
    }

    private static boolean isUnset(String comps) {
        return comps == null || comps.trim().isEmpty();
    }

    /** Every entry of the comma-separated list is a previous default (or unreadable: no usable dream). */
    private static boolean onlyPrevious(String comps) {
        for (String e : comps.split(",")) {
            String n = normalize(e);
            if (n != null && !PREVIOUS.contains(n)) return false;
        }
        return true;
    }

    private static String first(String comps) {
        return isUnset(comps) ? null : normalize(comps.split(",")[0]);
    }

    /** "pkg/cls" as ComponentName.unflattenFromString reads it ("pkg/.Cls" = "pkg/pkg.Cls"); null if none. */
    static String normalize(String flat) {
        if (flat == null) return null;
        String s = flat.trim();
        int i = s.indexOf('/');
        if (i <= 0 || i == s.length() - 1) return null;
        String pkg = s.substring(0, i), cls = s.substring(i + 1);
        if (cls.startsWith(".")) cls = pkg + cls;
        return pkg + "/" + cls;
    }
}
