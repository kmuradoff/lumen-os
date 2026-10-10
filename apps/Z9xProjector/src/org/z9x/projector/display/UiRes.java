package org.z9x.projector.display;

import java.util.Locale;

/**
 * Lumen OS 1.0.1: the interface resolution choice (4K, the default, or 1080p; 2K only for testing) as
 * plain Java, so every decision has a host test (test/uires/UiResTest, sh test/uires/run.sh). Android
 * side: {@link UiResolution}.
 *
 * Owner decision (2026-10-09, after the device tests): 4K is the default UI, 1:1 on the 3840 x 2160 DLP
 * panel (the image overrides the panel ini's OSD region to 3840 x 2160 before the display stack starts,
 * so the GOP destination is the panel); 1080p (the XGIMI stock UI, stretched 2x by the GOP) stays
 * selectable; 2K is offered only with the hidden debug property persist.z9x.ui_res.dev=1, because the
 * GOP's 1.5x scaling of it is not verified ({@link #offered}).
 *
 * Contract with the image's uires lane (init / boot script; it applies the value at boot and owns the
 * automatic fallback):
 *  - persist.z9x.ui_res: 1080 | 1440 | 2160, unset = 2160 (4K, the owner's default); the app still
 *    parses 1440 (the hidden 2K) but only offers it with the debug property;
 *  - persist.z9x.ui_res.dev: 1 = the chooser also offers 2K (read by this app only; whether a 2K pick
 *    then works is up to the lane, see .want below);
 *  - sys.z9x.ui_res.active: the resolution this boot really runs;
 *  - sys.z9x.ui_res.why: why (a fallback says so);
 *  - sys.z9x.ui_res.want / .failed: what the next start reads, set at 'on fs' and by the lane's "pick"
 *    after every write of persist.z9x.ui_res it accepts (a restart waits for it, {@link #pickDone}; the
 *    setting's wanted value follows it, {@link #effectiveWant}). The lane takes 1080 | 2160 from the app;
 *    2K runs only from its debug file, so a 2K pick that the lane does not take over changes nothing.
 * A resolution is named by its height. Every choice keeps the same 960 x 540 dp layout: 1920 x 1080 at
 * 320 dpi, 2560 x 1440 at 427 dpi, 3840 x 2160 at 640 dpi (the DLP panel is 3840 x 2160).
 */
public final class UiRes {
    private UiRes() {}

    public static final int P1080 = 1080, P1440 = 1440, P2160 = 2160;
    /** The owner's default (persist.z9x.ui_res unset): 4K, 1 UI px = 1 panel px. */
    public static final int DEFAULT = P2160;
    /** Every valid value, highest first (the order of the chooser; see {@link #offered}). */
    static final int[] CHOICES = {P2160, P1440, P1080};
    /** "Keep this resolution?" counts down this long while it is on screen. */
    static final int KEEP_SECONDS = 15;
    /** Closed this often without an answer (HOME, screen off, another window): no answer = back. */
    static final int KEEP_MAX_INTERRUPTS = 5;

    /** What the first start in a new resolution does with the change marker. */
    enum Keep {
        /** no change pending */
        NONE,
        /** still the boot that asked for it (the restart has not happened): keep the marker */
        SAME_BOOT,
        /** the new resolution runs: ask "Keep this resolution?" */
        ASK,
        /** another resolution runs (the boot fallback, or the image cannot apply it): drop, no question */
        DROP
    }

    /**
     * A property value -> height, or 0 when it names none of the choices. Accepts "1080", "1440",
     * "2160", "1440p", "2560x1440" (the height wins), "2k" / "4k" (any case, spaces ignored).
     */
    public static int parse(String v) {
        if (v == null) return 0;
        String s = v.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.isEmpty()) return 0;
        if (s.equals("2k")) return P1440;
        if (s.equals("4k") || s.equals("uhd")) return P2160;
        int x = s.indexOf('x');
        if (x >= 0) s = s.substring(x + 1);
        if (s.endsWith("p")) s = s.substring(0, s.length() - 1);
        int h;
        try {
            h = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
        return isChoice(h) ? h : 0;
    }

    static boolean isChoice(int h) {
        for (int c : CHOICES) if (c == h) return true;
        return false;
    }

    /** The wanted resolution from persist.z9x.ui_res (unset or not a choice = {@link #DEFAULT}). */
    public static int wanted(String persist) {
        int p = parse(persist);
        return p == 0 ? DEFAULT : p;
    }

    /**
     * The value the setting treats as wanted: the uires lane's sys.z9x.ui_res.want ({@code laneWant}) when
     * it names a choice, else persist.z9x.ui_res. The lane publishes what the next start really asks for:
     * it ignores a persist value it does not take from the app (a 1440 left by the 20261009b build, or set
     * with the debug property: 2K is its debug file only) and its debug file overrides the choice. Taken
     * from persist, such a value would show "2K" in the rows and a false "2K did not start properly" note
     * while the lane runs what it wants. Without the lane (want unset) persist as before.
     */
    public static String effectiveWant(String persist, String laneWant) {
        return parse(laneWant) != 0 ? laneWant : persist;
    }

    /**
     * The chooser's rows, highest first: 4K (default) and 1080p; 2K too when the debug property
     * persist.z9x.ui_res.dev is on, or when 2K is what is wanted ({@link #effectiveWant}: the lane's debug
     * file) or what runs: the user sees where he is and can leave it; it disappears once he does.
     */
    static int[] offered(boolean dev, int wanted, int active) {
        return dev || wanted == P1440 || active == P1440 ? new int[] {P2160, P1440, P1080} : new int[] {P2160, P1080};
    }

    /** A boolean system property value as SystemProperties.getBoolean reads it ("1", "true", "yes", "on", "y"). */
    static boolean isOn(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("on") || s.equals("y");
    }

    /** Width in px of a 16:9 resolution: 1920, 2560, 3840. */
    public static int width(int h) {
        return h * 16 / 9;
    }

    /** The density that keeps the 960 x 540 dp layout: 320, 427, 640 (0 for an unknown height). */
    public static int density(int h) {
        switch (h) {
            case P1080: return 320;
            case P1440: return 427;
            case P2160: return 640;
            default: return 0;
        }
    }

    /** The resolution of a window / display of {@code w} x {@code h} px (any orientation), or 0. */
    public static int fromSize(int w, int h) {
        int lo = Math.min(w, h), hi = Math.max(w, h);
        return isChoice(lo) && width(lo) == hi ? lo : 0;
    }

    /** "1080p", "2K", "4K": the same in every language. */
    public static String label(int h) {
        switch (h) {
            case P1440: return "2K";
            case P2160: return "4K";
            case P1080: return "1080p";
            default: return h > 0 ? h + "p" : "?";
        }
    }

    /** "2560 × 1440". */
    public static String size(int h) {
        return width(h) + " × " + h;
    }

    /** {@link #status(String, String, int, String, int, int)} without a remembered fallback. */
    public static Status status(String persist, String activeProp, int displayH, String why) {
        return status(persist, activeProp, displayH, why, 0, 0);
    }

    /**
     * Current state for the setting's summary. activeProp = sys.z9x.ui_res.active, displayH = the
     * resolution of the app's display (fromSize; 0 unknown). A fallback is reported from the uires
     * lane's own properties (active differs from the wanted value, or, active unset, why names a
     * fallback), else from our own memory of the last change: fellFrom was asked for and fellTo ran
     * instead (UiResolution keeps it until the next choice; it also covers a lane that rewrote the
     * persist value to the fallback). The display alone never claims one (an image without the uires lane
     * runs 1080p while the unset value means 4K).
     */
    public static Status status(String persist, String activeProp, int displayH, String why, int fellFrom, int fellTo) {
        int w = wanted(persist);
        int ap = parse(activeProp);
        int a = ap != 0 ? ap : displayH;
        int from = 0;
        if (ap != 0 ? ap != w : isFallbackWhy(why)) {
            from = w;
        } else if (isChoice(fellFrom) && a != 0 && a == fellTo && fellFrom != a) {
            from = fellFrom;
        }
        return new Status(w, a, why == null ? "" : why.trim(), from);
    }

    static boolean isFallbackWhy(String why) {
        return why != null && why.toLowerCase(Locale.ROOT).contains("fallback");
    }

    /** The resolution in effect, from the uires lane's property, else from the display (0 unknown). */
    public static int active(String activeProp, int displayH) {
        int a = parse(activeProp);
        return a != 0 ? a : (isChoice(displayH) ? displayH : 0);
    }

    /** Changing to {@code target} needs a restart unless it already runs ({@code active} known). */
    public static boolean needsRestart(int target, int active) {
        return active == 0 || target != active;
    }

    /**
     * The first start after a user's change. pendingNew = the marker's resolution (0 none),
     * pendingBoot / bootNow = boot ids (the boot of the request, this boot; null unknown),
     * active = {@link #active}.
     */
    static Keep keep(int pendingNew, String pendingBoot, String bootNow, int active) {
        if (pendingNew == 0) return Keep.NONE;
        if (bootNow == null || bootNow.isEmpty() || bootNow.equals(pendingBoot)) return Keep.SAME_BOOT;
        return active == pendingNew ? Keep.ASK : Keep.DROP;
    }

    /** What "Back to ..." restores: the resolution that ran when the change was asked for. */
    static int previous(int active, int wanted) {
        return active != 0 ? active : wanted;
    }

    /**
     * The uires lane took over persist.z9x.ui_res = {@code h} for the next start: its "pick" service
     * (run by init on every write) publishes sys.z9x.ui_res.want = h once /metadata holds it, and clears
     * h from sys.z9x.ui_res.failed (a fallback the user picks again). laneWant / laneFailed = those two
     * properties. A restart before that would start with the old choice.
     */
    static boolean pickDone(String laneWant, String laneFailed, int h) {
        return parse(laneWant) == h && !listed(laneFailed, h);
    }

    /** {@code h} is in a space separated list of heights (sys.z9x.ui_res.failed). */
    static boolean listed(String list, int h) {
        if (list == null) return false;
        for (String e : list.trim().split("[\\s,]+")) {
            if (!e.isEmpty() && parse(e) == h) return true;
        }
        return false;
    }

    /** Seconds shown by the countdown for {@code leftMs} ms left (rounded up, at least 0). */
    static int secondsLeft(long leftMs) {
        return leftMs <= 0 ? 0 : (int) ((leftMs + 999) / 1000);
    }

    /**
     * The values the setting shows: wanted (persist), active (0 unknown), why, and after a fallback the
     * resolution that did not come up ({@code fellFrom}, 0 none; {@code fallback} = it is set).
     */
    public static final class Status {
        public final int wanted, active, fellFrom;
        public final String why;
        public final boolean fallback;

        Status(int wanted, int active, String why, int fellFrom) {
            this.wanted = wanted;
            this.active = active;
            this.why = why;
            this.fellFrom = fellFrom;
            this.fallback = fellFrom != 0;
        }

        @Override
        public String toString() {
            return "wanted " + label(wanted) + ", active " + (active == 0 ? "?" : label(active))
                    + (why.isEmpty() ? "" : " (" + why + ")") + (fallback ? ", FALLBACK from " + label(fellFrom) : "");
        }
    }
}
