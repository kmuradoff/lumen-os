/*
 * Z9xAirPlay settings: keys, defaults and the immutable snapshot the server is built from.
 * Inspired by jqssun/android-airplay-server Prefs.kt (GPL-3.0).
 *
 * Copyright (C) jqssun / android-airplay-server contributors
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.z9x.airplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class Prefs {
    private Prefs() {}

    static final String FILE = "settings";

    static final String ENABLED = "enabled";
    static final String NAME = "device_name";
    static final String PIN_MODE = "pin_mode";        // NativeBridge.PIN_*
    static final String FIXED_PIN = "fixed_pin";      // "0000".."9999"
    static final String QUALITY_4K = "quality_4k";    // false = 1080p H.264, true = 4K HEVC
    static final String NOHOLD = "nohold";            // a new sender may take over
    static final String VIDEO_URL = "video_url";      // "AirPlay video" (YouTube HLS) via MediaPlayer
    static final String LATENCY_MS = "latency_ms";    // 150 / 250 / 500
    static final String MUSIC_SCREEN = "music_screen";
    static final String TRUSTED = "trusted_pks";      // StringSet of sender public keys
    static final String NOTIF_ASKED = "notif_asked";  // POST_NOTIFICATIONS was requested once

    static final boolean DEF_ENABLED = true;
    static final int DEF_PIN_MODE = NativeBridge.PIN_OFF;
    static final boolean DEF_QUALITY_4K = false;
    /* Off: the current sender keeps the session until it stops (or BACK on the projector),
       so another device on the network cannot take over a running session unasked. */
    static final boolean DEF_NOHOLD = false;
    /* Off until NuPlayer playback of UxPlay's YouTube HLS proxy is verified on the Z9X: with
       it off the YouTube app falls back to screen mirroring, which always works. */
    static final boolean DEF_VIDEO_URL = false;
    static final int DEF_LATENCY_MS = 250;
    static final boolean DEF_MUSIC_SCREEN = true;
    static final int[] LATENCIES = {150, 250, 500};

    /** mDNS instance names are at most 63 bytes; the RAOP one is "<12 hex>@" + name. */
    static final int MAX_NAME_BYTES = 50;

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String defaultName(Context c) {
        return c.getString(R.string.default_device_name);
    }

    /**
     * The advertised name: an AirPlay-only name the user saved in our settings; otherwise the projector's
     * name (Settings.Global device_name: Lumen Setup's Done step and Android Settings write it, Cast and
     * Bluetooth use it; PLAN C12); R.string.default_device_name only as the last fallback.
     */
    static String name(Context c) {
        String n = sanitizeName(get(c).getString(NAME, null));
        return n.isEmpty() ? fallbackName(c) : n;
    }

    /** The name without an AirPlay-only override: the system device name, else the built-in default. */
    static String fallbackName(Context c) {
        String n = sanitizeName(systemName(c));
        return n.isEmpty() ? defaultName(c) : n;
    }

    /** Settings.Global.DEVICE_NAME, null when unset or unreadable. */
    static String systemName(Context c) {
        try {
            return Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Trims, drops control characters and cuts to MAX_NAME_BYTES of UTF-8 on a code point boundary. */
    static String sanitizeName(String in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        String s = in.trim();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp)) continue;
            int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + n > MAX_NAME_BYTES) break;
            bytes += n;
            sb.appendCodePoint(cp);
        }
        return sb.toString().trim();
    }

    static boolean validPin(String p) {
        return p != null && p.length() == 4 && p.chars().allMatch(ch -> ch >= '0' && ch <= '9');
    }

    static int latencyMs(SharedPreferences p) {
        int v = p.getInt(LATENCY_MS, DEF_LATENCY_MS);
        for (int l : LATENCIES) if (l == v) return v;
        return DEF_LATENCY_MS;
    }

    static Set<String> trusted(SharedPreferences p) {
        Set<String> s = p.getStringSet(TRUSTED, null);
        return s == null ? Collections.emptySet() : new HashSet<>(s);
    }

    /**
     * Stable 6-byte AirPlay device id: SHA-256("org.z9x.airplay:" + ANDROID_ID), first 6 bytes,
     * made a locally administered unicast address. No MAC permission needed, stable across
     * Wi-Fi changes and reinstalls with the same signing key.
     */
    static byte[] deviceId(Context c) {
        String aid = null;
        try {
            aid = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
        }
        if (aid == null || aid.isEmpty()) aid = "z9x-fallback";
        byte[] id = new byte[6];
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(("org.z9x.airplay:" + aid).getBytes(StandardCharsets.UTF_8));
            System.arraycopy(h, 0, id, 0, 6);
        } catch (Exception e) {
            byte[] b = aid.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < b.length; i++) id[i % 6] ^= b[i];
        }
        id[0] = (byte) ((id[0] & 0xFC) | 0x02);
        return id;
    }

    /** Everything the native server is created from; a change means a server restart. */
    static final class Config {
        final String name;
        final int pinMode;
        final int fixedPin;
        final boolean hevc4k, nohold, videoUrl;
        final int latencyMs;

        Config(Context c) {
            SharedPreferences p = get(c);
            name = Prefs.name(c);
            int pm = p.getInt(PIN_MODE, DEF_PIN_MODE);
            String fp = p.getString(FIXED_PIN, null);
            if (pm == NativeBridge.PIN_FIXED && !validPin(fp)) pm = NativeBridge.PIN_RANDOM;
            if (pm != NativeBridge.PIN_RANDOM && pm != NativeBridge.PIN_FIXED) pm = NativeBridge.PIN_OFF;
            pinMode = pm;
            fixedPin = validPin(fp) ? Integer.parseInt(fp) : 0;
            hevc4k = p.getBoolean(QUALITY_4K, DEF_QUALITY_4K);
            nohold = p.getBoolean(NOHOLD, DEF_NOHOLD);
            videoUrl = p.getBoolean(VIDEO_URL, DEF_VIDEO_URL);
            latencyMs = latencyMs(p);
        }

        boolean sameAs(Config o) {
            return o != null && name.equals(o.name) && pinMode == o.pinMode && fixedPin == o.fixedPin
                    && hevc4k == o.hevc4k && nohold == o.nohold && videoUrl == o.videoUrl
                    && latencyMs == o.latencyMs;
        }
    }
}
