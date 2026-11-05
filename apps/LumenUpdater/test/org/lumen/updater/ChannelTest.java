// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

/**
 * Host test of {@link Ota#channelFile(String)} (sh test/ota/run.sh): the manifest file of each HB edition on the
 * fixed pre-release tag z9x-hb, which is the USB file the updater looks
 * for and the channel a manifest must name. Fork of the Z9X ChannelTest: a URL that names no manifest file gives
 * "" (fail closed), never the Z9X update-stable.json.
 */
public final class ChannelTest {
    private static int failed, passed;

    private static void eq(String what, Object want, Object got) {
        if (want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    public static void main(String[] a) {
        String b = "https://github.com/kmuradoff/lumen-os/releases/download/z9x-hb/";
        eq("hb public", "update-hb-nogms.json", Ota.channelFile(b + "update-hb-nogms.json"));
        eq("hb test units", "update-hb-test.json", Ota.channelFile(b + "update-hb-test.json"));
        eq("query string", "update-hb-nogms.json", Ota.channelFile(b + "update-hb-nogms.json?x=1"));
        eq("not a manifest file", "", Ota.channelFile(b + "latest.json"));
        eq("no channel", "", Ota.channelFile(b + "update-.json"));
        eq("empty", "", Ota.channelFile(""));
        eq("null", "", Ota.channelFile(null));
        // Preflight's guard: "update-" + channel + ".json" must be this file
        eq("guard nogms", true, ("update-" + "hb-nogms" + ".json").equals(Ota.channelFile(b + "update-hb-nogms.json")));
        eq("guard test", true, ("update-" + "hb-test" + ".json").equals(Ota.channelFile(b + "update-hb-test.json")));
        eq("guard crossed", false, ("update-" + "hb-test" + ".json").equals(Ota.channelFile(b + "update-hb-nogms.json")));
        eq("guard Z9X edition", false,
                ("update-" + "public-nogms" + ".json").equals(Ota.channelFile(b + "update-hb-nogms.json")));
        System.out.println("channel tests passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
