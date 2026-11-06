// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

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
        // the channel tag z9x (images from 1.0.8 on, tools/lumen_v1.sh MANIFEST_BASE)
        String b = "https://github.com/kmuradoff/lumen-os/releases/download/z9x/";
        // what images up to 1.0.7 read: the same file names on the Latest release
        String old = "https://github.com/kmuradoff/lumen-os/releases/latest/download/";
        eq("1.0.7 private", "update-stable.json", Ota.channelFile(old + "update-stable.json"));
        eq("1.0.7 no-Google", "update-public-nogms.json", Ota.channelFile(old + "update-public-nogms.json"));
        eq("private", "update-stable.json", Ota.channelFile(b + "update-stable.json"));
        eq("public", "update-public.json", Ota.channelFile(b + "update-public.json"));
        eq("no-Google", "update-public-nogms.json", Ota.channelFile(b + "update-public-nogms.json"));
        eq("query string", "update-public-nogms.json", Ota.channelFile(b + "update-public-nogms.json?x=1"));
        eq("not a manifest file", "update-stable.json", Ota.channelFile(b + "latest.json"));
        eq("empty", "update-stable.json", Ota.channelFile(""));
        eq("null", "update-stable.json", Ota.channelFile(null));
        // the fallback when the image has no manifest prop: the channel tag z9x too (1.0.9), never releases/latest
        // the published edition's file: the tag z9x holds no update-stable.json
        eq("fallback on the tag z9x", b + "update-public-nogms.json", Ota.DEFAULT_MANIFEST_URL);
        eq("fallback file", "update-public-nogms.json", Ota.channelFile(Ota.DEFAULT_MANIFEST_URL));
        // Preflight's guard: "update-" + channel + ".json" must be this file
        eq("guard nogms", true, ("update-" + "public-nogms" + ".json").equals(Ota.channelFile(b + "update-public-nogms.json")));
        eq("guard crossed", false, ("update-" + "public" + ".json").equals(Ota.channelFile(b + "update-public-nogms.json")));
        // the Z9X guide links: docs/install/z9x/<lang>.md with the anchors <a name="vbmeta"> and <a name="rescue">
        String g = "https://github.com/kmuradoff/lumen-os/blob/main/docs/install/z9x/";
        eq("guide vbmeta en", g + "en.md#vbmeta", Ota.GUIDE_VBMETA_EN);
        eq("guide vbmeta ru", g + "ru.md#vbmeta", Ota.GUIDE_VBMETA_RU);
        eq("guide rescue en", g + "en.md#rescue", Ota.GUIDE_RESCUE_EN);
        eq("guide rescue ru", g + "ru.md#rescue", Ota.GUIDE_RESCUE_RU);
        String root = System.getProperty("lumen.gsi");
        if (root != null) {
            for (String lang : new String[] {"en", "ru"}) {
                String md;
                try {
                    md = new String(java.nio.file.Files.readAllBytes(
                            java.nio.file.Paths.get(root, "docs/install/z9x", lang + ".md")), "UTF-8");
                } catch (java.io.IOException e) {
                    md = "";
                }
                eq("anchor vbmeta in " + lang + ".md", true, md.contains("<a name=\"vbmeta\"></a>"));
                eq("anchor rescue in " + lang + ".md", true, md.contains("<a name=\"rescue\"></a>"));
            }
        }
        System.out.println("channel tests passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
