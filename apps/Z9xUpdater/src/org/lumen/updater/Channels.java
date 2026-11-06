// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

/**
 * Which manifests one check reads and which of them it offers. With "Beta updates" on, a check reads both manifests
 * of the edition's channel pair, the stable one and the beta one, and offers the one with the higher version_code
 * (the stable one on a tie). A beta manifest that is missing (404), unreachable or does not verify is skipped, so
 * the stable channel keeps working while no beta is published. With beta off, or on an edition without a beta
 * channel, only the one manifest is read, as before. Shared by every device's updater: it uses only the Ota calls
 * each Ota fork keeps (channels, betaOn, manifestUrl, channelFile, imageManifestUrl). Plain Java, host test
 * BetaTest.
 */
final class Channels {
    private Channels() {}

    /** A manifest that was not found, not reachable or not verified: never offered. */
    static final int MISSING = Integer.MIN_VALUE;

    /** The manifest URLs a check reads, the stable one (or the only one) first. */
    static String[] read() { return read(Ota.imageManifestUrl(), Ota.betaOn()); }

    static String[] read(String image, boolean beta) {
        String[] c = Ota.channels(image);
        if (c != null && beta) return new String[] {c[0], c[1]};
        return new String[] {Ota.manifestUrl(image, beta)};
    }

    /** The manifest file names a check accepts, in read() order: the USB files to look for. */
    static String[] files() { return files(read()); }

    static String[] files(String[] urls) {
        String[] f = new String[urls.length];
        for (int i = 0; i < urls.length; i++) f[i] = Ota.channelFile(urls[i]);
        return f;
    }

    /**
     * True when a manifest of this channel may be offered: its file update-&lt;channel&gt;.json is one this check
     * reads. An edition never installs another edition's update, and a beta manifest counts only while beta
     * updates are on.
     */
    static boolean accepts(String channel) { return accepts(files(), channel); }

    static boolean accepts(String[] files, String channel) {
        String want = "update-" + channel + ".json";
        for (String f : files) {
            if (want.equals(f)) return true;
        }
        return false;
    }

    /**
     * The index of the manifest to offer among those read (their version codes in read() order, MISSING for one
     * that is not usable): the highest version_code, the earlier one on a tie (stable before beta); -1 when none.
     */
    static int pick(int[] codes) {
        int best = -1;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == MISSING) continue;
            if (best < 0 || codes[i] > codes[best]) best = i;
        }
        return best;
    }
}
