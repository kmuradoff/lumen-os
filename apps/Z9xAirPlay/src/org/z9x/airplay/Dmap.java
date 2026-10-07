/*
 * DMAP (iTunes metadata) parser for AirPlay audio "now playing" data.
 * Ported to Java from jqssun/android-airplay-server v0.0.31 audio/DmapParser.kt and
 * audio/TrackInfo.kt (GPL-3.0): containers are flattened, the tags we show are kept.
 *
 * Copyright (C) jqssun / android-airplay-server contributors
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.z9x.airplay;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class Dmap {
    private Dmap() {}

    static final class Track {
        final String title, artist, album;
        final long durationMs;

        Track(String title, String artist, String album, long durationMs) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.durationMs = durationMs;
        }
    }

    private static boolean isContainer(String tag) {
        return tag.equals("mlit") || tag.equals("mcon") || tag.equals("mlcl");
    }

    private static boolean isInt(String tag) {
        switch (tag) {
            case "astm": case "astn": case "asdk": case "asts": case "miid": case "mcti":
            case "mper": case "asai": case "asri": case "asci": case "asgi":
                return true;
            default:
                return false;
        }
    }

    /** Flattened tag -> String or Long map. Malformed input stops the walk, never throws. */
    static Map<String, Object> parse(byte[] data) {
        Map<String, Object> out = new HashMap<>();
        if (data != null) walk(data, 0, data.length, out, 0);
        return out;
    }

    private static void walk(byte[] d, int off, int end, Map<String, Object> out, int depth) {
        while (end - off >= 8 && depth < 8) {
            String tag = new String(d, off, 4, StandardCharsets.US_ASCII);
            long len = ((d[off + 4] & 0xFFL) << 24) | ((d[off + 5] & 0xFFL) << 16)
                    | ((d[off + 6] & 0xFFL) << 8) | (d[off + 7] & 0xFFL);
            off += 8;
            if (len > end - off) return;
            int n = (int) len;
            if (isContainer(tag)) {
                walk(d, off, off + n, out, depth + 1);
            } else if (isInt(tag)) {
                long v = 0;
                for (int i = 0; i < n && i < 8; i++) v = (v << 8) | (d[off + i] & 0xFF);
                out.put(tag, v);
            } else {
                out.put(tag, new String(d, off, n, StandardCharsets.UTF_8));
            }
            off += n;
        }
    }

    static Track track(byte[] data) {
        Map<String, Object> m = parse(data);
        Object dur = m.get("astm");
        return new Track(str(m, "minm"), str(m, "asar"), str(m, "asal"),
                dur instanceof Long ? (Long) dur : 0L);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof String ? ((String) v).trim() : "";
    }
}
