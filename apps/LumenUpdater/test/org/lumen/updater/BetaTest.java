// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

import android.os.SystemProperties;

import java.util.Collections;
import java.util.List;

/**
 * Host test of the "Beta updates" switch of the device-neutral updater (sh test/ota/run.sh). Fork of the Z9X
 * BetaTest with the HB facts: the channel pair is update-hb-nogms.json (stable) and update-hb-test.json (beta) on
 * the fixed pre-release tag z9x-hb; a public image starts on stable, a test image on beta, and the user's choice wins
 * either way. With beta on a check reads both manifests and offers the higher version (the shared Channels of the
 * Z9X updater, which the HB recipe takes unchanged). Turning beta off never downgrades (Preflight.applicable,
 * version codes of decision). Properties come from the SystemProperties stand-in of test/ota/stubs.
 */
public final class BetaTest {
    private static int failed, passed;
    private static final String TAG_URL = "https://github.com/Project-Lumen-OS/lumen-os/releases/download/z9x-hb/";
    private static final String NOGMS = TAG_URL + "update-hb-nogms.json";
    private static final String TEST = TAG_URL + "update-hb-test.json";
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
        return Preflight.applicable(1, "hb", channel, ANY, vc, 0);
    }

    /** An HB image reading {@code url}, running {@code version}. */
    private static void image(String url, String version) {
        SystemProperties.VALUES.clear();
        SystemProperties.VALUES.put("ro.lumen.ota.device", "hb");
        if (url != null) SystemProperties.VALUES.put("ro.lumen.ota.manifest_url", url);
        SystemProperties.VALUES.put("ro.lumen.version", version);
        SystemProperties.VALUES.put("ro.lumen.version_code", String.valueOf(Ota.versionCode(version)));
        SystemProperties.VALUES.put("ro.vendor.build.version.incremental", "V1");
    }

    public static void main(String[] a) {
        image(NOGMS, "1.0.8-beta3");
        String[] c = Ota.channels(NOGMS);
        eq("pair from stable: stable", NOGMS, c == null ? null : c[0]);
        eq("pair from stable: beta", TEST, c == null ? null : c[1]);
        c = Ota.channels(TEST + "?x=1");
        eq("pair from test: stable", NOGMS, c == null ? null : c[0]);
        eq("pair from test: beta", TEST, c == null ? null : c[1]);
        eq("another file: no beta", null, Ota.channels(TAG_URL + "update-hb-lab.json"));
        eq("another device's file: no beta", null, Ota.channels(TAG_URL + "update-x50-nogms.json"));
        eq("public image: off by default", false, Ota.betaOn(NOGMS, null));
        eq("test image: on by default", true, Ota.betaOn(TEST, null));
        eq("test image turned off", NOGMS, Ota.manifestUrl(TEST, false));
        eq("public image turned on", TEST, Ota.manifestUrl(NOGMS, true));

        // a public image, never chosen: stable as before
        Ota.setBeta(null);
        eq("switch shown", true, Ota.betaAvailable());
        eq("reads stable", NOGMS, Ota.manifestUrl());
        eq("test manifest while off", "blk_channel", res(app("hb-test", 1000899)));
        Ota.setBeta(true);
        eq("reads beta", TEST, Ota.manifestUrl());
        eq("USB file", "update-hb-test.json", Ota.channelFile());
        eq("beta newer", "ok", res(app("hb-test", 1000804)));
        eq("stable manifest while on (both read, the higher offered)", "ok", res(app("hb-nogms", 1000899)));
        eq("reads both", NOGMS + " " + TEST, String.join(" ", Channels.read()));
        eq("USB files", "update-hb-nogms.json update-hb-test.json", String.join(" ", Channels.files()));
        eq("another device's channel while on", "blk_channel", res(app("x50-nogms", 1000899)));

        // the tester's unit: a test image on 1.0.9-beta2 turns beta off, never a downgrade
        image(TEST, "1.0.9-beta2");
        Ota.setBeta(null);
        eq("test image reads beta", TEST, Ota.manifestUrl());
        Ota.setBeta(false);
        eq("turned off reads stable", NOGMS, Ota.manifestUrl());
        eq("older final", "uptodate", res(app("hb-nogms", Ota.versionCode("1.0.8"))));
        eq("older beta of the same version", "uptodate", res(app("hb-nogms", Ota.versionCode("1.0.9-beta1"))));
        eq("same build", "uptodate", res(app("hb-nogms", Ota.versionCode("1.0.9-beta2"))));
        eq("rc of the same version", "ok", res(app("hb-nogms", Ota.versionCode("1.0.9-rc1"))));
        eq("final of the same version", "ok", res(app("hb-nogms", Ota.versionCode("1.0.9"))));
        eq("newer beta while off", "blk_channel", res(app("hb-test", Ota.versionCode("1.0.9-beta3"))));

        // fail closed: no device or no manifest URL = no switch, nothing read
        image(null, "1.0.8");
        eq("no URL: no switch", false, Ota.betaAvailable());
        eq("no URL: nothing read", "", Ota.manifestUrl());
        SystemProperties.VALUES.put("ro.lumen.ota.manifest_url", NOGMS);
        SystemProperties.VALUES.remove("ro.lumen.ota.device");
        eq("no device: no switch", false, Ota.betaAvailable());
        eq("no device: nothing read", "", Ota.manifestUrl());

        System.out.println("beta tests passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
