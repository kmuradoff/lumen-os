// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

import android.os.SystemProperties;

import java.util.Collections;
import java.util.List;

/**
 * Host test of the "Beta updates" switch (sh test/run.sh): which manifest is read (Ota.channels, betaOn,
 * manifestUrl, channelFile) and that turning beta off never downgrades (Preflight.applicable). Only the no-Google
 * edition (update-public-nogms.json) has a beta channel, update-public-beta.json on the fixed pre-release tag beta;
 * the private and the Google editions show no switch. Properties come from the SystemProperties stand-in of
 * test/stubs.
 */
public final class BetaTest {
    private static int failed, passed;
    private static final String LATEST = "https://github.com/kmuradoff/lumen-os/releases/latest/download/";
    private static final String NOGMS = LATEST + "update-public-nogms.json";
    private static final String BETA = "https://github.com/kmuradoff/lumen-os/releases/download/beta/update-public-beta.json";
    private static final List<String> ANY = Collections.emptyList();

    private static void eq(String what, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static String res(Preflight.Block b) { return b == null ? "ok" : b.res; }

    private static Preflight.Block app(String channel, int vc) {
        return Preflight.applicable(1, "z9x", channel, ANY, vc, 0);
    }

    /** A no-Google image (manifest URL of its edition) running version code {@code vc}. */
    private static void image(String url, int vc) {
        SystemProperties.VALUES.clear();
        SystemProperties.VALUES.put("ro.z9x.ota.manifest_url", url);
        SystemProperties.VALUES.put("ro.z9x.version_code", String.valueOf(vc));
        SystemProperties.VALUES.put("ro.vendor.build.version.incremental", "v6.15.58");
    }

    public static void main(String[] a) {
        // pure: the channel pair of each edition
        String[] c = Ota.channels(NOGMS);
        eq("nogms stable", NOGMS, c == null ? null : c[0]);
        eq("nogms beta", BETA, c == null ? null : c[1]);
        eq("private: no beta", null, Ota.channels(LATEST + "update-stable.json"));
        eq("Google edition: no beta", null, Ota.channels(LATEST + "update-public.json"));
        eq("never chosen = the image's channel", false, Ota.betaOn(NOGMS, null));
        eq("chosen on", true, Ota.betaOn(NOGMS, true));
        eq("chosen off", false, Ota.betaOn(NOGMS, false));
        eq("on without a beta channel", false, Ota.betaOn(LATEST + "update-stable.json", true));
        eq("url default", NOGMS, Ota.manifestUrl(NOGMS, null));
        eq("url on", BETA, Ota.manifestUrl(NOGMS, true));
        eq("url off", NOGMS, Ota.manifestUrl(NOGMS, false));
        eq("url private on", LATEST + "update-stable.json", Ota.manifestUrl(LATEST + "update-stable.json", true));
        eq("beta file", "update-public-beta.json", Ota.channelFile(BETA));

        // the running updater: no choice yet = stable, as before the switch existed
        image(NOGMS, 10009);
        Ota.setBeta(null);
        eq("switch shown", true, Ota.betaAvailable());
        eq("off by default", false, Ota.betaOn());
        eq("reads stable", NOGMS, Ota.manifestUrl());
        eq("stable newer", "ok", res(app("public-nogms", 10010)));
        eq("beta manifest while off", "blk_channel", res(app("public-beta", 10010)));

        // on: the beta channel is read, its manifest counts, the stable one is refused
        Ota.setBeta(true);
        eq("reads beta", BETA, Ota.manifestUrl());
        eq("USB file", "update-public-beta.json", Ota.channelFile());
        eq("beta newer", "ok", res(app("public-beta", 10010)));
        eq("stable manifest while on", "blk_channel", res(app("public-nogms", 10010)));

        // a beta is installed (10010), then beta is turned off: never a downgrade
        image(NOGMS, 10010);
        Ota.setBeta(false);
        eq("reads stable again", NOGMS, Ota.manifestUrl());
        eq("older stable", "uptodate", res(app("public-nogms", 10009)));
        eq("same version stable", "uptodate", res(app("public-nogms", 10010)));
        eq("newer stable", "ok", res(app("public-nogms", 10011)));
        eq("newer beta while off", "blk_channel", res(app("public-beta", 10012)));

        // the private and the Google editions: no switch, the choice changes nothing
        image(LATEST + "update-stable.json", 10009);
        Ota.setBeta(true);
        eq("private: no switch", false, Ota.betaAvailable());
        eq("private: stays", LATEST + "update-stable.json", Ota.manifestUrl());
        eq("private: own channel", "ok", res(app("stable", 10010)));
        eq("private: beta refused", "blk_channel", res(app("public-beta", 10010)));
        image(LATEST + "update-public.json", 10009);
        eq("Google edition: no switch", false, Ota.betaAvailable());
        eq("Google edition: stays", LATEST + "update-public.json", Ota.manifestUrl());

        System.out.println("beta tests passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
