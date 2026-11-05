// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

import android.os.SystemProperties;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Host test of the device-neutral Preflight and Ota (sh test/ota/run.sh)
 * with the properties of an HB image (wire id hb, channel hb-nogms on the fixed pre-release tag z9x-hb). The
 * properties come from the SystemProperties stand-in of test/ota/stubs. Cases: the device must be hb; an empty
 * device prop fails closed; the gate blocks (hal not ok, slot not marked, merge not none, pending, another slot
 * starts next); version codes of a project decision; the guide links. The probe and the build checks: PreflightGateTest.
 */
public final class PreflightHbTest {
    private static int failed, passed;
    private static final String TAG_URL = "https://github.com/kmuradoff/lumen-os/releases/download/z9x-hb/";
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

    private static String arg(Preflight.Block b) { return b == null ? "" : b.arg; }

    private static void hbImage() {
        SystemProperties.VALUES.clear();
        set("ro.lumen.ota.device", "hb");
        set("ro.lumen.ota.manifest_url", TAG_URL + "update-hb-nogms.json");
        set("ro.lumen.version", "1.0.8-beta1");
        set("ro.lumen.version_code", "1000801");
        set("ro.lumen.build_id", "lumen-hb-1.0.8-beta1-20261104");
        set("ro.vendor.build.version.incremental", "V1");
        // the gate after a normal boot of a marked slot (lumen_ota.sh decide)
        set("sys.z9x.ota", "none");
        set("sys.z9x.ota.hal", "ok");
        set("sys.z9x.ota.marked", "1");
        set("sys.z9x.ota.merge", "none");
        set("ro.boot.slot_suffix", "_a");
        set("sys.z9x.ota.active", "_a");
    }

    private static void set(String k, String v) {
        if (v == null) SystemProperties.VALUES.remove(k);
        else SystemProperties.VALUES.put(k, v);
    }

    private static Preflight.Block app(String device, String channel, int vc) {
        return Preflight.applicable(1, device, channel, ANY, vc, 0);
    }

    public static void main(String[] a) {
        hbImage();

        // device hb: the manifest's device must be this image's wire id
        eq("device", "hb", Ota.device());
        eq("hb final over beta1", "ok", res(app("hb", "hb-nogms", 1000899)));
        eq("same version", "uptodate", res(app("hb", "hb-nogms", 1000801)));
        eq("older", "uptodate", res(app("hb", "hb-nogms", 1000799)));
        eq("Z9X manifest", "err_signature", res(app("z9x", "hb-nogms", 1000899)));
        eq("Play 6 manifest", "err_signature", res(app("play6", "hb-nogms", 1000899)));
        eq("schema 2", "err_signature", res(Preflight.applicable(2, "hb", "hb-nogms", ANY, 1000899, 0)));
        eq("test channel on a public image", "blk_channel", res(app("hb", "hb-test", 1000899)));
        eq("test channel arg", "hb-test", arg(app("hb", "hb-test", 1000899)));
        eq("Z9X edition channel", "blk_channel", res(app("hb", "public-nogms", 1000899)));
        eq("vendor match", "ok", res(Preflight.applicable(1, "hb", "hb-nogms", Arrays.asList("V0", "V1"), 1000899, 0)));
        eq("vendor mismatch", "blk_vendor", res(Preflight.applicable(1, "hb", "hb-nogms", Arrays.asList("V2"), 1000899, 0)));
        eq("vendor arg", "V1", arg(Preflight.applicable(1, "hb", "hb-nogms", Arrays.asList("V2"), 1000899, 0)));
        Preflight.Block min = Preflight.applicable(1, "hb", "hb-nogms", ANY, 1000901, 1000899);
        eq("min version", "blk_min_version", res(min));
        eq("min version name", "1.0.8", arg(min));
        eq("min version beta name", "1.0.8-beta2",
                arg(Preflight.applicable(1, "hb", "hb-nogms", ANY, 1000901, 1000802)));
        set("ro.lumen.ota.manifest_url", TAG_URL + "update-hb-test.json");
        eq("test image takes hb-test", "ok", res(app("hb", "hb-test", 1000899)));
        eq("test image refuses hb-nogms", "blk_channel", res(app("hb", "hb-nogms", 1000899)));
        eq("USB file of a test image", "update-hb-test.json", Ota.channelFile());

        // an empty device prop fails closed: no manifest URL, no USB file, nothing applicable
        hbImage();
        set("ro.lumen.ota.device", null);
        eq("no device: url", "", Ota.manifestUrl());
        eq("no device: USB file", "", Ota.channelFile());
        eq("no device: hb manifest", "err_signature", res(app("hb", "hb-nogms", 1000899)));
        eq("no device: empty device manifest", "err_signature", res(app("", "hb-nogms", 1000899)));
        set("ro.lumen.ota.device", "  ");
        eq("blank device", "err_signature", res(app("hb", "hb-nogms", 1000899)));
        hbImage();
        set("ro.lumen.ota.manifest_url", "http://example.org/update-hb-nogms.json");
        eq("no https: url", "", Ota.manifestUrl());
        eq("no https: refused", "blk_channel", res(app("hb", "hb-nogms", 1000899)));
        set("ro.lumen.ota.manifest_url", null);
        eq("no url: refused", "blk_channel", res(app("hb", "hb-nogms", 1000899)));

        // the gate's state of the running slot (Preflight.install, after the vbmeta check)
        hbImage();
        eq("gate ok", "ok", res(Preflight.slotState()));
        set("sys.z9x.ota", "pending");
        eq("pending", "blk_pending", res(Preflight.slotState()));
        hbImage();
        set("sys.z9x.ota.hal", null);
        eq("hal never published", "blk_hal", res(Preflight.slotState()));
        eq("hal never published arg", "?", arg(Preflight.slotState()));
        set("sys.z9x.ota.hal", "bad:conf");
        eq("hal bad", "blk_hal", res(Preflight.slotState()));
        eq("hal bad arg", "conf", arg(Preflight.slotState()));
        set("sys.z9x.ota.hal", "bad:");
        eq("hal bad, no reason", "?", arg(Preflight.slotState()));
        set("sys.z9x.ota.hal", "OK");
        eq("hal not exactly ok", "blk_hal", res(Preflight.slotState()));
        hbImage();
        set("sys.z9x.ota.marked", "0");
        eq("slot not marked", "blk_not_marked", res(Preflight.slotState()));
        set("sys.z9x.ota.marked", null);
        eq("slot mark unknown", "blk_not_marked", res(Preflight.slotState()));
        hbImage();
        set("sys.z9x.ota.merge", "merging");
        eq("merge running", "blk_merge", res(Preflight.slotState()));
        set("sys.z9x.ota.merge", "snapshotted");
        eq("merge pending", "blk_merge", res(Preflight.slotState()));
        set("sys.z9x.ota.merge", null);
        eq("merge unknown", "blk_hal", res(Preflight.slotState()));
        eq("merge unknown arg", "merge", arg(Preflight.slotState()));
        hbImage();
        set("sys.z9x.ota.active", "_b");
        eq("other slot starts next", "blk_active", res(Preflight.slotState()));
        eq("other slot starts next arg", "B", arg(Preflight.slotState()));
        set("sys.z9x.ota.active", null);
        eq("active slot unknown", "blk_active", res(Preflight.slotState()));
        eq("active slot unknown arg", "?", arg(Preflight.slotState()));
        set("sys.z9x.ota.active", "_a");
        set("ro.boot.slot_suffix", null);
        eq("no slot suffix", "blk_active", res(Preflight.slotState()));
        hbImage();
        set("ro.boot.slot_suffix", "_b");
        set("sys.z9x.ota.active", "_b");
        eq("slot b running and next", "ok", res(Preflight.slotState()));

        // version codes
        eq("beta1", 1000801, Ota.versionCode("1.0.8-beta1"));
        eq("beta12", 1000812, Ota.versionCode("1.0.8-beta12"));
        eq("rc2", 1000852, Ota.versionCode("1.0.8-rc2"));
        eq("final", 1000899, Ota.versionCode("1.0.8"));
        eq("two parts", 1010099, Ota.versionCode("1.1"));
        eq("major 2", 2000099, Ota.versionCode("2.0"));
        eq("beta0", 0, Ota.versionCode("1.0.8-beta0"));
        eq("beta50", 0, Ota.versionCode("1.0.8-beta50"));
        eq("rc49", 0, Ota.versionCode("1.0.8-rc49"));
        eq("diag build", 0, Ota.versionCode("1.0.8-diag1"));
        eq("test build", 0, Ota.versionCode("hb-t12"));
        eq("minor 100", 0, Ota.versionCode("1.100.0"));
        eq("null", 0, Ota.versionCode(null));
        eq("name final", "1.0.8", Ota.versionName(1000899));
        eq("name beta", "1.0.8-beta1", Ota.versionName(1000801));
        eq("name rc", "1.0.8-rc2", Ota.versionName(1000852));
        eq("name unknown", "?", Ota.versionName(0));
        for (String v : new String[] {"1.0.8-beta1", "1.0.8-beta49", "1.0.8-rc1", "1.0.8-rc48", "1.0.8", "12.34.56"}) {
            eq("round trip " + v, v, Ota.versionName(Ota.versionCode(v)));
        }
        eq("final newer than rc", true, Ota.versionCode("1.0.8") > Ota.versionCode("1.0.8-rc9"));
        eq("rc newer than beta", true, Ota.versionCode("1.0.8-rc1") > Ota.versionCode("1.0.8-beta49"));
        eq("next beta newer than final", true, Ota.versionCode("1.0.9-beta1") > Ota.versionCode("1.0.8"));
        eq("old formula image is older", true, 10007 < Ota.versionCode("1.0.8-beta1"));
        hbImage();
        set("ro.lumen.version_code", null);
        set("ro.lumen.version", "1.0.7-beta1");
        eq("version code from the version", 1000701, Ota.currentVersionCode());
        set("ro.lumen.version_code", "x");
        eq("unreadable version code", 1000701, Ota.currentVersionCode());

        // guide links (UpdaterActivity: vbmeta step and rescue)
        hbImage();
        eq("guide of the device", Ota.PROJECT_URL + "/blob/main/docs/install/hb/en.md#vbmeta", Ota.guide("en", "vbmeta"));
        set("ro.lumen.ota.guide", "https://example.org/guide/");
        eq("guide prop", "https://example.org/guide/ru.md#rescue", Ota.guide("ru", "rescue"));
        set("ro.lumen.ota.guide", "http://example.org/guide");
        eq("guide prop not https", Ota.PROJECT_URL + "/blob/main/docs/install/hb/ru.md#rescue", Ota.guide("ru", "rescue"));
        set("ro.lumen.ota.device", null);
        set("ro.lumen.ota.guide", null);
        eq("guide without a device", Ota.PROJECT_URL + "/blob/main/docs/install/en.md#vbmeta", Ota.guide("en", "vbmeta"));

        eq("reboot reason", "z9x-ota", Ota.REBOOT_REASON);
        System.out.println("preflight hb tests passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
