// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

/**
 * Host test of {@link Ota#channelFile(String)} (sh test/run.sh, 1.0.1): the manifest file of each edition
 * (docs/ota.md "Variants"), which is the USB file the updater looks for and the channel a manifest must name.
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
        String b = "https://github.com/kmuradoff/lumen-os/releases/latest/download/";
        eq("private", "update-stable.json", Ota.channelFile(b + "update-stable.json"));
        eq("public", "update-public.json", Ota.channelFile(b + "update-public.json"));
        eq("no-Google", "update-public-nogms.json", Ota.channelFile(b + "update-public-nogms.json"));
        eq("query string", "update-public-nogms.json", Ota.channelFile(b + "update-public-nogms.json?x=1"));
        eq("not a manifest file", "update-stable.json", Ota.channelFile(b + "latest.json"));
        eq("empty", "update-stable.json", Ota.channelFile(""));
        eq("null", "update-stable.json", Ota.channelFile(null));
        // Preflight's guard: "update-" + channel + ".json" must be this file
        eq("guard nogms", true, ("update-" + "public-nogms" + ".json").equals(Ota.channelFile(b + "update-public-nogms.json")));
        eq("guard crossed", false, ("update-" + "public" + ".json").equals(Ota.channelFile(b + "update-public-nogms.json")));
        System.out.println("channel tests passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
