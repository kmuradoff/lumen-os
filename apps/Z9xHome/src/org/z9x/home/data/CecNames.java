package org.z9x.home.data;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A friendly name and a mark for an HDMI-CEC device on the Inputs cards (plain Java, host-testable;
 * InputSource uses it with the {@code cec_names} switch). Sources, best first: the device names a CEC
 * device has (its OSD name in HdmiDeviceInfo, the system's CEC list, the TV input's own label for it),
 * its CEC vendor id and its device type.
 * <ul>
 *   <li>A known device (by name, or by vendor id where the vendor makes only that kind of box): its own
 *   name when that already says what it is ("PlayStation 5", "Xbox Series X"), else the product name
 *   ("PlayStation", "Nintendo Switch"); a console gets the gamepad, a streaming box the player glyph, three
 *   consoles a soft accent of their colour for it.</li>
 *   <li>Else the first device name that is not a CEC placeholder ("Playback_1" and the like: what Android
 *   calls a device before it sent its OSD name).</li>
 *   <li>Else nothing: the card keeps the port's label ("HDMI 1").</li>
 * </ul>
 * Product names are shown as the devices call themselves; the marks are our own glyphs, no logos.
 */
public final class CecNames {
    private CecNames() {}

    /** Mark of the card: none (the type's icon), a gamepad, a streaming box. */
    public static final int MARK_NONE = 0, MARK_CONSOLE = 1, MARK_PLAYER = 2;

    // the system's names for a CEC device before its OSD name (HdmiUtils default names), and other non-names
    private static final Pattern GENERIC = Pattern.compile(
            "(?i)(tv|secondary[ _]?tv|audio[ _]?system|(playback|recorder|tuner|backup)([ _]?\\d+)?"
                    + "|free[ _]?use|reserved|unregistered|broadcast|specific[ _]?use|hdmi[ _]?\\d*|unknown)");

    /** CEC vendor ids (IEEE OUI) of vendors whose only CEC source on a projector is one kind of box. */
    private static final int VENDOR_APPLE = 0x0010FA, VENDOR_GOOGLE = 0x001A11;

    private static final class Brand {
        final String name;      // product name, also what an own name must contain to be kept
        final Pattern match;    // in any of the device's names
        final int vendor;       // 0 = none
        final int mark, tint;

        Brand(String name, String match, int vendor, int mark, int tint) {
            this.name = name;
            this.match = Pattern.compile(match, Pattern.CASE_INSENSITIVE);
            this.vendor = vendor;
            this.mark = mark;
            this.tint = tint;
        }
    }

    // tints: the brand colours softened for a glyph on the dark card (SURFACE2), at least 4:1 there
    private static final Brand[] BRANDS = {
            new Brand("PlayStation", "playstation|\\bps ?[345]\\b", 0, MARK_CONSOLE, 0xFF5A9BEA),
            new Brand("Xbox", "xbox", 0, MARK_CONSOLE, 0xFF52B043),
            new Brand("Nintendo Switch", "nintendo", 0, MARK_CONSOLE, 0xFFE9505A),
            new Brand("Apple TV", "apple ?tv", VENDOR_APPLE, MARK_PLAYER, 0),
            new Brand("NVIDIA Shield", "shield|nvidia", 0, MARK_PLAYER, 0),
            new Brand("Chromecast", "chromecast|google ?tv", VENDOR_GOOGLE, MARK_PLAYER, 0),
            new Brand("Fire TV", "fire ?tv|\\baft[a-z0-9]*\\b|amazon", 0, MARK_PLAYER, 0),
            new Brand("Roku", "roku", 0, MARK_PLAYER, 0),
    };

    /** What a card shows for a CEC device. Immutable. */
    public static final class Device {
        /** Friendly name, null = none (the port's label stays). */
        public final String name;
        public final int mark;
        /** Accent colour for the mark (ARGB), 0 = the usual paper white. */
        public final int tint;

        Device(String name, int mark, int tint) {
            this.name = name;
            this.mark = mark;
            this.tint = tint;
        }
    }

    /** Card.DEV_* of the TV input app's "device_state", -1 for "unknown" or none (the TIF state stays). */
    public static int deviceState(String s) {
        if (s == null) return -1;
        switch (s) {
            case "on": return Card.DEV_ON;
            case "asleep": return Card.DEV_ASLEEP;
            case "no_signal": return Card.DEV_NO_SIGNAL;
            case "disconnected": return Card.DEV_DISCONNECTED;
            default: return -1;
        }
    }

    /** A brand accent softened towards the paper colour, readable as a glyph on the dark card (>= 4:1). */
    public static int softAccent(int accent) {
        return mix(accent, 0xFFF4EFE6, 0.35f);
    }

    /** Linear mix of two ARGB colours (alpha opaque), t in 0..1. */
    public static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** True for an empty name or a CEC placeholder ("Playback_1", "Recorder_1", "Tuner_1", "TV", ...). */
    public static boolean generic(String name) {
        return name == null || name.trim().isEmpty() || GENERIC.matcher(name.trim()).matches();
    }

    /**
     * The friendly name and mark of a CEC device: {@code names} in order of trust (null entries are
     * skipped), its CEC vendor id (0 = unknown).
     */
    public static Device of(int vendorId, String... names) {
        String own = null;   // the first real name
        if (names != null) {
            for (String n : names) {
                if (!generic(n)) {
                    own = n.trim();
                    break;
                }
            }
        }
        for (Brand b : BRANDS) {
            boolean byName = false;
            if (names != null) {
                for (String n : names) if (n != null && b.match.matcher(n).find()) byName = true;
            }
            if (!byName && (b.vendor == 0 || vendorId != b.vendor)) continue;
            String lower = b.name.toLowerCase(Locale.ROOT);
            String name = own != null && own.toLowerCase(Locale.ROOT).contains(lower) ? own : b.name;
            return new Device(name, b.mark, b.tint);
        }
        return new Device(own, MARK_NONE, 0);
    }
}
