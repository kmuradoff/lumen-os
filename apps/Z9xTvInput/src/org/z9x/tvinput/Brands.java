/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.hardware.hdmi.HdmiDeviceInfo;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The brand of an HDMI-CEC source device, for the label of its child input (HdmiInputService) and the
 * viewer banner (PassthroughActivity). One small table by CEC vendor id (the IEEE OUI a device reports in
 * &lt;Device Vendor ID&gt;) and OSD name (&lt;Set OSD Name&gt;); no device checks. A brand is a plain word,
 * shown in our own type with our own glyph for its kind and an accent colour: no logo or brand artwork.
 *
 * <p>Matching: a brand word in the OSD name first (lower case, letters and digits only, so "Xbox Series X",
 * "XboxSeriesX" and "XBOX ONE" all hold "xbox"), then the vendor id. The framework's placeholder names
 * (Playback_1, Recorder_2, Tuner_1, Audio ...) never match and are never shown as a name: a device carries one
 * from its first &lt;Report Physical Address&gt; until its &lt;Set OSD Name&gt; arrives (about a second later),
 * or for good when it never sends one.
 *
 * <p>Vendor ids. A CEC vendor id is the maker's IEEE OUI, which only that maker can use, so a vendor match can
 * not name the wrong maker. Two kinds of source, named per row below:
 * <ul>
 *   <li>"CEC list": the id is in a public CEC vendor id list: libCEC include/cectypes.h (enum cec_vendor_id)
 *       and v4l-utils utils/libcecutil/cec-info.cpp (cec_vendor2s).</li>
 *   <li>"IEEE": the block is assigned to that maker in the IEEE MA-L registry (read through Wireshark's
 *       manuf file, which is generated from it, on 2026-10-13). No public CEC list names the block these
 *       makers' devices send, and they hold several blocks each, so the name decides first and the id is
 *       only a second chance.</li>
 * </ul>
 * Sony (0x080046: CEC list CEC_VENDOR_SONY; IEEE 08-00-46 Sony Corporation; a PlayStation 5 sends it) is not
 * a vendor row: Sony also makes Blu-ray players, receivers and TVs, so a PlayStation is found by its name
 * ("PlayStation 5", "PlayStation 4"). Amazon is name only: Amazon Technologies holds about 200 IEEE blocks
 * and none is known as the one a Fire TV sends.
 */
final class Brands {
    /** Kinds, one glyph each: HDMI plug, gamepad, streaming box. */
    static final int KIND_OTHER = 0;
    static final int KIND_CONSOLE = 1;
    static final int KIND_PLAYER = 2;

    /** Tile colour of everything without a brand accent: a neutral dark grey. */
    static final int ACCENT_NEUTRAL = 0xFF3A3F47;

    static final class Brand {
        final String name;
        final int kind;
        final int accent;

        Brand(String name, int kind, int accent) {
            this.name = name;
            this.kind = kind;
            this.accent = accent;
        }
    }

    private static final class Rule {
        final Brand brand;
        /** Found in the normalised OSD name (see {@link #norm}). */
        final Pattern name;
        final int[] vendors;

        Rule(Brand brand, String name, int... vendors) {
            this.brand = brand;
            this.name = Pattern.compile(name);
            this.vendors = vendors;
        }
    }

    private static final Rule[] RULES = {
            // PlayStation blue
            new Rule(new Brand("PlayStation", KIND_CONSOLE, 0xFF0070D1), "playstation"),
            // Xbox green. IEEE: 00-50-F2, 00-0D-3A, 00-22-48, 7C-1E-52, 98-5F-D3 Microsoft
            new Rule(new Brand("Xbox", KIND_CONSOLE, 0xFF107C10), "xbox",
                    0x0050F2, 0x000D3A, 0x002248, 0x7C1E52, 0x985FD3),
            // Nintendo red. The first Switch sends "NintendoSwitch", the Switch 2 "Switch 2" (a public
            // cec-client scan shows its vendor as unknown to libCEC). IEEE: 00-09-BF, 00-17-AB, 00-1F-32 Nintendo
            new Rule(new Brand("Nintendo Switch", KIND_CONSOLE, 0xFFE60012), "nintendo|^switch",
                    0x0009BF, 0x0017AB, 0x001F32),
            // CEC list CEC_VENDOR_APPLE; IEEE 00-10-FA Apple, Inc. A public cec-client scan shows an Apple TV
            // as vendor Apple, OSD name "Apple TV".
            new Rule(new Brand("Apple TV", KIND_PLAYER, ACCENT_NEUTRAL), "appletv", 0x0010FA),
            // IEEE 00-04-4B NVIDIA
            new Rule(new Brand("NVIDIA Shield", KIND_PLAYER, ACCENT_NEUTRAL), "shield|nvidia", 0x00044B),
            // CEC list CEC_VENDOR_GOOGLE; IEEE 00-1A-11 Google, Inc.
            new Rule(new Brand("Chromecast", KIND_PLAYER, ACCENT_NEUTRAL), "chromecast", 0x001A11),
            new Rule(new Brand("Fire TV", KIND_PLAYER, ACCENT_NEUTRAL), "firetv|amazon"),
            // IEEE 00-0D-4B Roku, Inc.
            new Rule(new Brand("Roku", KIND_PLAYER, ACCENT_NEUTRAL), "roku", 0x000D4B),
    };

    /** HdmiUtils default names of the framework's CEC device list (by logical address), any case. */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)(tv|audio|audiosystem|secondary_tv|(playback|recorder|tuner|audio|backup|reserved)_\\d+)");

    private Brands() {}

    /** The brand of a CEC device, or null. */
    static Brand of(HdmiDeviceInfo d) {
        if (d == null || !d.isCecDevice()) return null;
        return of(realName(d), d.getVendorId());
    }

    static Brand of(String name, int vendorId) {
        String n = norm(name);
        if (!n.isEmpty()) {
            for (Rule r : RULES) {
                if (r.name.matcher(n).find()) return r.brand;
            }
        }
        for (Rule r : RULES) {
            for (int v : r.vendors) {
                if (v == vendorId) return r.brand;
            }
        }
        return null;
    }

    /** The device's own OSD name, trimmed; null when it is empty or still the framework's placeholder. */
    static String realName(HdmiDeviceInfo d) {
        String n = d == null ? null : d.getDisplayName();
        if (n == null) return null;
        n = n.trim();
        return n.isEmpty() || isPlaceholder(n) ? null : n;
    }

    static boolean isPlaceholder(String name) {
        return name != null && PLACEHOLDER.matcher(name.trim()).matches();
    }

    /** What a device is called: its brand, else its own name, else null (the caller shows the port). */
    static String label(HdmiDeviceInfo d) {
        Brand b = of(d);
        return b != null ? b.name : realName(d);
    }

    private static String norm(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
