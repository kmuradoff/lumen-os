package org.lumen.info.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Host tests of LumenInfo (plain Java, no android.jar):
 *  1. EDID decoder on the real HB built-in files (test/data/*.hex, copied from the HB) and on synthetic CTA blocks
 *     (Dolby Vision, HDR luminance, HDMI Forum with ALLM / FRL / VRR, Atmos audio, 4:2:0, speakers), broken input;
 *  2. dump and file parsers on real HB dumps (test/data) and synthetic lines;
 *  3. HB facts: EDID file mapping from the vendor ini;
 *  4. HAL allow list: every row of Hb.PLAN is a row of allowed.tsv or allowed_lumeninfo.tsv and of aidl_codes.tsv
 *     (same method) and not a BANNED / REVIEW row of banned.tsv; allowed_lumeninfo.tsv is exactly the PLAN rows
 *     that allowed.tsv lacks, each a getter with the signature of aidl_codes.tsv; the only setter is
 *     setEdidVersion; hal/HbGmpf.java has exactly these calls; every "wanted" getter is in neither list; the
 *     vendor call schedule stays within the budget (4 calls per second on HDMI, 3 otherwise) and reaches every
 *     scheduled read; the parcelable decoders (core.HalStructs) on synthetic words;
 *  5. static checks of src/: Binder transact only in hal/HbGmpf.java, no shell, no su, no writes to /sys or
 *     /proc, no vendor descriptors outside hal/.
 * Arguments: GSI root, app root.
 */
public final class InfoTest {
    private static int checks, failures;

    private static void ok(boolean c, String what) {
        checks++;
        if (!c) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    private static void eq(Object want, Object got, String what) {
        ok(want == null ? got == null : want.equals(got), what + ": want <" + want + "> got <" + got + ">");
    }

    public static void main(String[] a) throws Exception {
        Path gsi = Paths.get(a[0]), app = Paths.get(a[1]);
        Path data = app.resolve("test/data");
        edidReal(data);
        edidSynthetic(data);
        edidBroken(data);
        cecSads();
        vic();
        parsers(data);
        hbFacts();
        halStructs();
        schedule();
        plan(gsi, app);
        staticChecks(app);
        System.out.println("LumenInfo host tests: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    // ================================================================ EDID
    static byte[] hex(Path p) throws IOException {
        String h = new String(Files.readAllBytes(p), StandardCharsets.US_ASCII).replaceAll("\\s", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** CEC / AudioDescriptor SADs as the LG SOUND BAR on the HB's ARC port reported them (2026-11-03). */
    static void cecSads() {
        Edid.Sad p = Edid.sad(new byte[] {0x09, 0x7f, 0x07}, 0);
        eq(1, p.format, "sad pcm format");
        eq(2, p.channels, "sad pcm channels");
        eq("32/44.1/48/88.2/96/176.4/192", p.rates(), "sad pcm rates");
        eq("16/20/24", p.lpcmBits(), "sad pcm bits");
        Edid.Sad d = Edid.sad(new byte[] {0x15, 0x07, 0x50}, 0);
        eq(2, d.format, "sad ac3 format");
        eq(6, d.channels, "sad ac3 channels");
        eq("32/44.1/48", d.rates(), "sad ac3 rates");
        eq(640, d.maxKbps(), "sad ac3 kbps");
        ok(Edid.sad(new byte[] {0x00, 0x00, 0x00}, 0) == null, "sad reserved format 0 refused");
        ok(Edid.sad(new byte[] {0x09, 0x7f}, 0) == null, "sad short array refused");
    }

    static void edidReal(Path data) throws IOException {
        Edid e = Edid.parse(hex(data.resolve("Mstar_EDID2_v2.0_4K_2K_3D_HDR.hex")));
        ok(e.valid, "2.0 valid");
        eq("MTK", e.manufacturer, "2.0 maker");
        eq("MediaTek TV", e.monitorName, "2.0 name");
        eq(1, e.version, "2.0 edid version");
        eq(3, e.revision, "2.0 edid revision");
        eq(2, e.blockCount, "2.0 blocks");
        ok(e.badChecksumBlocks.isEmpty(), "2.0 checksums " + e.badChecksumBlocks);
        ok(e.hasCta, "2.0 cta");
        eq(3, e.ctaRevision, "2.0 cta rev");
        ok(e.hdmi != null, "2.0 hdmi vsdb");
        eq("2.0.0.0", e.hdmi.physicalAddress, "2.0 phys");
        eq(340, e.hdmi.maxTmdsMHz, "2.0 tmds 1.4 block");
        ok(e.hdmi.dc30 && e.hdmi.dc36 && e.hdmi.dc48 && e.hdmi.dcY444, "2.0 deep colour");
        eq(4, e.hdmi.hdmiVics.size(), "2.0 hdmi vics");
        ok(e.forum != null, "2.0 hf-vsdb");
        eq(600, e.forum.maxTmdsMHz, "2.0 forum tmds");
        ok(e.forum.scdc, "2.0 scdc");
        ok(e.forum.dc420_30 && e.forum.dc420_36 && e.forum.dc420_48, "2.0 dc 420");
        eq(0, e.forum.maxFrl, "2.0 no frl");
        eq("2.0", e.hdmiLevel(), "2.0 level");
        ok(e.hdrStatic != null && e.hdrStatic.sdr && e.hdrStatic.hdr && e.hdrStatic.pq && e.hdrStatic.hlg, "2.0 eotf");
        ok(e.hdrStatic.type1, "2.0 static type 1");
        ok(Double.isNaN(e.hdrStatic.maxNits), "2.0 no luminance");
        eq(1, e.hdr10PlusVersion, "2.0 hdr10+");
        ok(e.dolby == null, "2.0 no dolby vision");
        eq("HDR10/HLG/HDR10+", hdrKinds(e), "2.0 hdr kinds");
        eq(0x01ff, e.colorimetry, "2.0 colorimetry");
        ok(e.colorimetryNames().contains("BT2020YCC") && e.colorimetryNames().contains("BT2020RGB"), "2.0 colorimetry names");
        eq(3, e.audio.size(), "2.0 audio count");
        eq("LPCM", e.audio.get(0).formatName(), "2.0 sad0");
        eq(8, e.audio.get(0).channels, "2.0 sad0 ch");
        eq("32/44.1/48/88.2/96/176.4/192", e.audio.get(0).rates(), "2.0 sad0 rates");
        eq("16/20/24", e.audio.get(0).lpcmBits(), "2.0 sad0 bits");
        eq("AC-3", e.audio.get(1).formatName(), "2.0 sad1");
        eq(6, e.audio.get(1).channels, "2.0 sad1 ch");
        eq(640, e.audio.get(1).maxKbps(), "2.0 sad1 kbps");
        eq("E-AC-3", e.audio.get(2).formatName(), "2.0 sad2");
        eq("FL/FR", e.speakerNames(), "2.0 speakers");
        eq(27, e.svds.size(), "2.0 svd count");
        Vic best = e.bestVic();
        eq("4096x2160p60", best == null ? null : best.label(), "2.0 best vic");
        boolean native16 = false, native4 = false;
        for (Edid.Svd s : e.svds) {
            if (s.vic == 4 && s.nativeFormat) native4 = true;
            if (s.vic == 16 && s.nativeFormat) native16 = true;
        }
        ok(native4 && native16, "2.0 native vics 4 and 16");
        ok(e.ycc420Also.contains(96) && e.ycc420Also.contains(97), "2.0 420 map has 2160p50/60 " + e.ycc420Also);
        ok(e.videoCap != null && e.videoCap.qy && e.videoCap.qs, "2.0 video capability");
        ok(e.otherBlocks.toString().contains("00001A"), "2.0 FreeSync block noted " + e.otherBlocks);
        ok(!e.detailed.isEmpty(), "2.0 dtd");
        eq("3840x2160p60", e.detailed.get(0).label(), "2.0 preferred timing");

        Edid f = Edid.parse(hex(data.resolve("Mstar_EDID2_v1.4_3D_Frame_SideHalf_Top.hex")));
        ok(f.valid, "1.4 valid");
        eq("MST", f.manufacturer, "1.4 maker");
        eq("MStar Demo", f.monitorName, "1.4 name");
        ok(f.badChecksumBlocks.isEmpty(), "1.4 checksums");
        ok(f.hdmi != null && f.forum == null, "1.4 hdmi 1.4 only");
        eq("1.4", f.hdmiLevel(), "1.4 level");
        // the vendor's "1.4" file still announces HDR10, HLG and Dolby Vision (VSVDB version 2), no HDR10+
        eq("HDR10/HLG/Dolby Vision", hdrKinds(f), "1.4 hdr kinds");
        eq(2, f.dolby.version, "1.4 dolby vision version");
        eq("1280x720p60", f.detailed.get(0).label(), "1.4 preferred");
        eq(59, f.rangeMinV, "1.4 range min v");
        eq(60, f.rangeMaxV, "1.4 range max v");
        ok(!f.hdmi.threeD, "1.4 3D_present bit not set");

        Edid h = Edid.parse(hex(data.resolve("HDMI_EDID.hex")));
        ok(h.valid && h.badChecksumBlocks.isEmpty(), "HDMI_EDID valid");
        eq("MTK", h.manufacturer, "HDMI_EDID maker");
    }

    static String hdrKinds(Edid e) {
        List<String> k = new ArrayList<>();
        if (e.hdrStatic != null) {
            if (e.hdrStatic.pq) k.add("HDR10");
            if (e.hdrStatic.hlg) k.add("HLG");
        }
        if (e.hdr10PlusVersion >= 0) k.add("HDR10+");
        if (e.dolby != null) k.add("Dolby Vision");
        return String.join("/", k);
    }

    /** Base block of the 1.4 file with one extension: the given CTA data blocks. */
    static byte[] synth(Path data, int[]... blocks) throws IOException {
        byte[] base = hex(data.resolve("Mstar_EDID2_v1.4_3D_Frame_SideHalf_Top.hex"));
        byte[] out = new byte[256];
        System.arraycopy(base, 0, out, 0, 128);
        out[126] = 1;
        fix(out, 0);
        int p = 128;
        out[p++] = 2;
        out[p++] = 3;
        int dPos = p++;
        out[p++] = (byte) 0x70;
        for (int[] b : blocks) for (int x : b) out[p++] = (byte) x;
        out[dPos] = (byte) (p - 128);   // no DTDs after the blocks (zero padding)
        fix(out, 128);
        return out;
    }

    static void fix(byte[] b, int o) {
        int s = 0;
        for (int i = 0; i < 127; i++) s += b[o + i] & 0xff;
        b[o + 127] = (byte) ((256 - (s & 0xff)) & 0xff);
    }

    static void edidSynthetic(Path data) throws IOException {
        int[] dv = {0xEB, 0x01, 0x46, 0xD0, 0x00, 0x49, 0x04, 0x02, 0, 0, 0, 0};
        int[] hdr = {0xE6, 0x06, 0x0D, 0x01, 0x60, 0x40, 0x10};
        int[] hf = {0x6A, 0xD8, 0x5D, 0xC4, 0x01, 0x78, 0x80, 0x50, 0x02, 0x30, 0x78};
        int[] audio = {0x26, 0x57, 0x07, 0x01, 0x67, 0x06, 0x01};
        int[] video = {0x44, 0x90, 97, 118, 199};
        int[] spk = {0x83, 0x4F, 0x00, 0x00};
        int[] y420 = {0xE2, 0x0E, 97};
        int[] hdmi = {0x65, 0x03, 0x0C, 0x00, 0x10, 0x00};
        Edid e = Edid.parse(synth(data, hdmi, dv, hdr, hf, audio, video, spk, y420));
        ok(e.valid && e.badChecksumBlocks.isEmpty(), "synth valid " + e.badChecksumBlocks + " " + e.error);
        ok(e.dolby != null, "synth dolby");
        eq(2, e.dolby.version, "dv version");
        eq(4, e.dolby.dmVersion, "dv dm version");
        ok(e.dolby.yuv422_12, "dv 422 12 bit");
        ok(e.dolby.globalDimming, "dv global dimming");
        eq(2, e.dolby.interfaceCode, "dv interface");
        eq(400.0, Math.round(e.hdrStatic.maxNits * 10) / 10.0, "hdr max nits");
        eq(200.0, Math.round(e.hdrStatic.maxAvgNits * 10) / 10.0, "hdr avg nits");
        ok(Math.abs(e.hdrStatic.minNits - 400 * (16 / 255.0) * (16 / 255.0) / 100) < 1e-9, "hdr min nits");
        ok(e.hdrStatic.sdr && !e.hdrStatic.hdr && e.hdrStatic.pq && e.hdrStatic.hlg, "hdr eotf bits");
        ok(e.forum != null && e.forum.allm, "hf allm");
        eq(5, e.forum.maxFrl, "hf frl");
        eq("10G x4", Edid.frlName(e.forum.maxFrl), "frl name");
        eq("2.1", e.hdmiLevel(), "level 2.1");
        eq(48, e.forum.vrrMin, "vrr min");
        eq(120, e.forum.vrrMax, "vrr max");
        eq("1.0.0.0", e.hdmi.physicalAddress, "phys");
        eq(2, e.audio.size(), "synth audio");
        ok(e.audio.get(0).atmos() && e.audio.get(0).channels == 8, "eac3 atmos 8ch");
        eq("MAT (TrueHD)", e.audio.get(1).formatName(), "mat");
        eq(4, e.svds.size(), "synth svds");
        ok(e.svds.get(0).vic == 16 && e.svds.get(0).nativeFormat, "native 1080p60");
        eq("7680x4320p60", e.bestVic().label(), "best 8k");
        eq("FL/FR LFE FC RL/RR RLC/RRC", e.speakerNames(), "speakers");
        eq(List.of(97), e.ycc420Only, "420 only");
        eq("HDR10/HLG/Dolby Vision", hdrKinds(e), "kinds");
    }

    static void edidBroken(Path data) throws IOException {
        Edid z = Edid.parse(hex(data.resolve("MTK_EDID2_Persist.hex")));
        ok(!z.valid && "empty (all zero)".equals(z.error), "persist zeros: " + z.error);
        ok(!Edid.parse(new byte[10]).valid, "short");
        ok(!Edid.parse(null).valid, "null");
        byte[] bad = hex(data.resolve("Mstar_EDID2_v2.0_4K_2K_3D_HDR.hex"));
        bad[200] ^= 0x55;
        Edid b = Edid.parse(bad);
        ok(b.valid && b.badChecksumBlocks.contains(1), "bad checksum block 1");
        // random junk after a valid header never throws
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 300; i++) {
            byte[] j = new byte[256];
            r.nextBytes(j);
            System.arraycopy(new byte[] {0, -1, -1, -1, -1, -1, -1, 0}, 0, j, 0, 8);
            j[128] = 2;
            j[129] = 3;
            Edid x = Edid.parse(j);
            ok(x.error == null || !x.error.startsWith("decode"), "junk " + i + " " + x.error);
        }
    }

    static void vic() {
        eq("1920x1080p60", Vic.of(16).label(), "vic 16");
        eq("3840x2160p60", Vic.of(97).label(), "vic 97");
        eq("4096x2160p60", Vic.of(102).label(), "vic 102");
        eq("3840x2160p120", Vic.of(118).label(), "vic 118");
        eq("7680x4320p60", Vic.of(199).label(), "vic 199");
        eq("1920x1080i60", Vic.of(5).label(), "vic 5");
        ok(Vic.of(0) == null && Vic.of(128) == null && Vic.of(250) == null, "reserved vics");
        int n = 0;
        for (int i = 1; i < 256; i++) if (Vic.of(i) != null) n++;
        eq(127 + 27, n, "vic table size");
    }

    // ================================================================ parsers
    static void parsers(Path data) throws IOException {
        String af = new String(Files.readAllBytes(data.resolve("audio_flinger.txt")), StandardCharsets.UTF_8);
        List<Parse.AudioThread> t = Parse.audioThreads(af);
        ok(!t.isEmpty(), "audio threads");
        Parse.AudioThread d = t.get(0);
        eq("AudioOut_D", d.name, "thread name");
        eq("MIXER", d.type, "thread type");
        ok(d.standby, "standby");
        eq(48000, d.sampleRate, "rate");
        eq(2, d.channels, "channels");
        eq("PCM_16_BIT", d.halFormat, "format");
        eq("SPEAKER", d.devices, "devices");
        ok(d.flags.contains("PRIMARY"), "primary flag");
        ok(Parse.activeAudio(t) == null, "nothing active");
        String direct = "Output thread 0xae1a0dc1, name AudioOut_15, tid 1100, type 1 (DIRECT):\n  Standby: no\n"
                + "  Sample rate: 48000 Hz\n  HAL format: 0x9000000 (AUDIO_FORMAT_AC3)\n  Channel count: 2\n"
                + "  Output devices: 0x40000 (AUDIO_DEVICE_OUT_HDMI_ARC)\n";
        List<Parse.AudioThread> t2 = Parse.audioThreads(af + direct);
        Parse.AudioThread act = Parse.activeAudio(t2);
        ok(act != null && act.passthrough() && "AC3".equals(act.halFormat) && "HDMI_ARC".equals(act.devices),
                "direct ac3 passthrough");

        String rm = new String(Files.readAllBytes(data.resolve("resource_manager.txt")), StandardCharsets.UTF_8);
        ok(Parse.videoCodecs(rm).isEmpty(), "real resource_manager: all codecs removed");
        String live = "ResourceManagerService: 0x1\n  Processes:\n    Pid: 4242\n    Priority: 0\n      Client:\n"
                + "        Id: 77\n        Name: c2.mtk.hevc.decoder\n        Resources:\n"
                + "          non-secure-codec/hw-video-codec:[]:1\n  Process Pid override:\n";
        List<Parse.CodecClient> c = Parse.videoCodecs(live);
        ok(c.size() == 1 && c.get(0).pid == 4242 && "c2.mtk.hevc.decoder".equals(c.get(0).name) && c.get(0).hardware
                && !c.get(0).secure, "live client by name");
        String ev = "ResourceManagerService: 0x1\n  Processes:\n    Pid: 9\n  Events logs (most recent at top):\n"
                + "    10-31 20:49:00 addResource(pid 9, uid 10084 clientId 5, resources secure-codec/hw-video-codec:[]:1 )\n"
                + "    10-31 20:48:44 removeResource(pid 9, uid 10084 clientId 4)\n"
                + "    10-31 20:48:39 addResource(pid 9, uid 10084 clientId 4, resources non-secure-codec/hw-video-codec:[]:1 )\n";
        List<Parse.CodecClient> c2 = Parse.videoCodecs(ev);
        ok(c2.size() == 1 && c2.get(0).pid == 9 && c2.get(0).secure, "event log live secure codec");

        String mm = "Statsd atoms:\n  10-31 21:34:24.441 result:104 { mediametrics_codec_reported:184 codec:c2.mtk.avc.decoder "
                + "mime:video/avc mode:0 encoder:0 secure:0 width:1920 height:1080 frame_rate:-1 bitrate:-1 }\n"
                + "  10-31 21:40:00.000 result:105 { mediametrics_codec_reported:184 codec:c2.mtk.hevc.decoder "
                + "mime:video/hevc encoder:0 width:3840 height:2160 frame_rate:23.976 bitrate:-1 }\n"
                + "  10-31 21:41:00.000 result:106 { mediametrics_codec_reported:184 codec:c2.android.aac.decoder "
                + "mime:audio/mp4a-latm encoder:0 }\n";
        Parse.CodecRecord r = Parse.lastVideoDecoder(mm);
        ok(r != null && "c2.mtk.hevc.decoder".equals(r.codec) && r.width == 3840 && r.height == 2160, "metrics atom");
        eq("23.98", Fmt.hz(r.frameRate), "metrics fps");
        String item = "  12: {android.media.mediacodec, (10-31 21:42:00.000), (com.app, 1, 10084), "
                + "(android.media.mediacodec.codec=c2.mtk.vp9.decoder, android.media.mediacodec.mime=video/x-vnd.on2.vp9, "
                + "android.media.mediacodec.encoder=0, android.media.mediacodec.width=2560, android.media.mediacodec.height=1440)}\n";
        Parse.CodecRecord r2 = Parse.lastVideoDecoder(mm + item);
        ok(r2 != null && "c2.mtk.vp9.decoder".equals(r2.codec) && r2.width == 2560, "metrics item " + (r2 == null ? null : r2.codec));
        eq("VP9", Parse.mimeName(r2.mime), "mime name");

        String hwc = "Display 0 (active) HWC layers:\n-----\n Layer name\n           Z |  Window Type |  Comp Type |  Transform |   Disp Frame (LTRB) |          Source Crop (LTRB) |     Frame Rate (Explicit) (Seamlessness) [Focused]\n-----\n"
                + " SurfaceView[org.lumen.tvinput/org.lu[...]put.PassthroughActivity](BLAST)#2945\n"
                + "           4 |            0 |   SIDEBAND |          0 |    0    0 3840 2160 |    0.0    0.0    0.0    0.0 |                                              [*]\n"
                + " SurfaceView[com.app/com.app.Player](BLAST)#3001\n"
                + "           2 |            0 |     DEVICE |          0 |    0  270 1920 1350 |    0.0    0.0 1920.0 1080.0 |  23.98 Hz (ExactOrMultiple) (OnlySeamless)   [ ]\n"
                + "-----\n";
        List<Parse.HwcLayer> layers = Parse.hwcLayers(hwc);
        eq(2, layers.size(), "hwc layers");
        Parse.HwcLayer v = Parse.videoLayer(layers);
        ok(v != null && "SIDEBAND".equals(v.composition) && v.right == 3840 && v.focused, "video layer sideband");
        ok(Math.abs(layers.get(1).frameRate - 23.98) < 1e-9, "app frame rate vote");
        eq("org.lumen.tvinput", Parse.layerPackage(v.name), "layer package");

        long[] s1 = Parse.procStatCpu("cpu  100 0 100 800 0 0 0 0 0 0\ncpu0 1 2 3 4\n");
        long[] s2 = Parse.procStatCpu("cpu  150 0 150 900 0 0 0 0 0 0\n");
        eq(50.0, Parse.loadPercent(s1, s2), "cpu load");
        ok(Double.isNaN(Parse.loadPercent(null, s2)), "cpu load first tick");
        eq(3891200L, Parse.meminfo("MemTotal:        3891200 kB\nMemFree: 1 kB\n", "MemTotal"), "meminfo");
        eq("59.9", Fmt.centiHz(5990), "centi hz");
        eq("60", Fmt.hz(60.000004), "60 hz");
        eq("1.29 GHz", Fmt.khz(1290000), "khz");
    }

    // ================================================================ HB facts
    static final String INI = "[HDMI_EDID_1]\nHDMI_EDID_File   = \"/config/EDID_BIN/Mstar_EDID1_v2.0_4K_2K_3D_HDR.bin\";\n"
            + "HDMI_EDID_File_2_0   = \"/config/EDID_BIN/Mstar_EDID1_v2.0_4K_2K_3D_HDR.bin\";\n"
            + "HDMI_EDID_File_1_4   = \"/config/EDID_BIN/Mstar_EDID1_v1.4_3D_Frame_SideHalf_Top.bin\";\n"
            + "eEdidVersion     =  3;  #EDID_14:1 , EDID_20:2 , EDID_21:3\n\n"
            + "[HDMI_EDID_2]\nHDMI_EDID_File_2_0   = \"/config/EDID_BIN/Mstar_EDID2_v2.0_4K_2K_3D_HDR.bin\";\n"
            + "HDMI_EDID_File_1_4   = \"/config/EDID_BIN/../etc/passwd\";\n";

    static void hbFacts() {
        Map<String, Map<String, String>> ini = Parse.ini(INI);
        eq("3", ini.get("HDMI_EDID_1").get("eEdidVersion"), "ini comment and semicolon");
        eq(Hb.EDID_DIR + "/Mstar_EDID1_v1.4_3D_Frame_SideHalf_Top.bin", Hb.edidFile(ini, 1, Hb.EDID_14), "port 1 v1.4");
        eq(Hb.EDID_DIR + "/Mstar_EDID2_v2.0_4K_2K_3D_HDR.bin", Hb.edidFile(ini, 2, Hb.EDID_20), "port 2 v2.0");
        ok(Hb.edidFromIni(ini, 2, Hb.EDID_20), "port 2 v2.0 from ini");
        ok(!Hb.edidFromIni(ini, 2, Hb.EDID_14), "path with .. refused");
        eq(Hb.EDID_DIR + "/Mstar_EDID2_v1.4_3D_Frame_SideHalf_Top.bin", Hb.edidFile(ini, 2, Hb.EDID_14), "fallback name");
        eq(Hb.EDID_DIR + "/Mstar_EDID1_v2.0_4K_2K_3D_HDR.bin", Hb.edidFile(null, 1, Hb.EDID_20), "no ini");
        ok(Hb.isEdidFileName("Mstar_EDID1_v2.0_4K_2K.bin") && !Hb.isEdidFileName("../x.bin")
                && !Hb.isEdidFileName("a.txt"), "edid file names");
        eq("HLG", Hb.hdrTypeName(4), "hdr 4");
        eq("2.0", Hb.edidVersionName(2), "edid 2");
    }

    // ================================================================ allow list
    static List<String[]> tsv(Path p) throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (l.startsWith("#") || l.trim().isEmpty()) continue;
            out.add(l.split("\t"));
        }
        return out;
    }

    // ================================================================ vendor structs and schedule
    static void halStructs() {
        HalStructs.VideoInfo v = HalStructs.videoInfo(new int[] {3840, 2160, 5994, 1, 3});
        eq(3840, v.width, "video width");
        eq(2160, v.height, "video height");
        eq(5994, v.frameRate, "video rate");
        eq(1, v.hdmiOrDvi, "video hdmi");
        eq(3, v.videoType, "video type");
        ok(v.plausible(), "video plausible");
        HalStructs.VideoInfo shortV = HalStructs.videoInfo(new int[] {1920, 1080});
        eq(0, shortV.frameRate, "short struct leaves missing fields 0");
        ok(!HalStructs.videoInfo(new int[0]).plausible(), "empty video not plausible");

        HalStructs.HdmiHwCfg h = HalStructs.hdmiHwCfg(new int[] {3, 1, 0, 0x2000});
        eq(3, h.hwPortId, "hw port");
        ok(h.cec && !h.arc, "hw cec / arc");
        eq("2.0.0.0", HalStructs.physicalAddress(h.physicalAddr), "physical address");
        eq("1.2.3.4", HalStructs.physicalAddress(0x1234), "physical address digits");
        eq(null, HalStructs.physicalAddress(0), "no physical address");
        eq(null, HalStructs.physicalAddress(0x10000), "physical address over 16 bits");

        HalStructs.ScreenResolution sr = HalStructs.screenResolution(new int[] {50, -3, 0xFF});
        eq(50, sr.center, "zoom centre");
        eq(-3, sr.horizontal, "zoom horizontal signed byte");
        eq(-1, sr.vertical, "zoom vertical from 0xFF");

        HalStructs.OutputTiming t = HalStructs.outputTiming(new int[] {4, 3840, 2160, 2, 3840, 2160, 60, 1, 16, 0, 0, 0});
        ok(t.highFrameRate(), "user timing 4 = high frame rate");
        eq(3840, t.dispWidth, "panel width");
        eq(2160, t.currV, "output height");
        eq(60, t.vfreq, "vfreq");
        ok(t.plausible(), "timing plausible");
        ok(!HalStructs.outputTiming(new int[] {0, 0, 0, 0, 0, 0, 0}).plausible(), "zero timing not plausible");

        HalStructs.ColorSpaceInfo c = HalStructs.colorSpaceInfo(new int[] {1, 2});
        eq(1, c.type, "colour space type");
        eq(2, c.level, "colour space level");

        eq("60 Hz", HalStructs.rate(60), "rate in Hz");
        eq("59.94 Hz", HalStructs.rate(5994), "rate in 1/100 Hz");
        eq("23.98 Hz", HalStructs.rate(23976), "rate 23976 is 1/1000 Hz (nearest common rate)");
        eq("24 Hz", HalStructs.rate(2400), "rate 2400: tie keeps 1/100 Hz");
        eq("60 Hz", HalStructs.rate(600), "rate in 1/10 Hz");
        eq("70 Hz", HalStructs.rate(7000), "uncommon rate in 1/100 Hz");
        eq("60 Hz", HalStructs.rate(60000), "rate in 1/1000 Hz");
        eq(null, HalStructs.rate(0), "no rate");
        eq(null, HalStructs.rate(5), "implausible rate");
        eq(null, HalStructs.rate(4000000), "rate too large");
        eq("3840x2160", HalStructs.size(3840, 2160), "size");
        eq(null, HalStructs.size(0, 2160), "no size");

        eq(5, HalStructs.fieldWords(24, 20), "struct of 24 bytes has 5 field words");
        eq(-1, HalStructs.fieldWords(24, 16), "struct longer than the reply");
        eq(-1, HalStructs.fieldWords(2, 100), "struct smaller than its size word");
        eq(-1, HalStructs.fieldWords(22, 100), "struct size not a multiple of 4");
        eq(-1, HalStructs.fieldWords(4096, 8192), "struct size over the limit");
        eq(0, HalStructs.fieldWords(4, 0), "empty struct");
    }

    static void schedule() {
        Set<String> seenHdmiPorts = new HashSet<>(), seenOff = new HashSet<>();
        int callsHdmi = 0;
        for (long tick = 0; tick < 200; tick++) {
            List<String> on = Hb.schedule(tick, true, true), off = Hb.schedule(tick, false, false);
            ok(on.size() <= 4, "tick " + tick + " on HDMI: " + on.size() + " vendor calls (max 4)");
            ok(off.size() <= 3, "tick " + tick + " off HDMI: " + off.size() + " vendor calls (max 3)");
            eq(Hb.SRC, on.get(0), "source first");
            ok(!off.contains(Hb.HDMI_INFO) && !off.contains(Hb.RANGE) && !off.contains(Hb.ALLM), "no HDMI reads off HDMI");
            ok(!off.contains(Hb.CONNECT1) && !off.contains(Hb.HWCFG2), "no port reads without the HDMI page");
            seenHdmiPorts.addAll(on);
            seenOff.addAll(off);
            callsHdmi += on.size();
            ok(new HashSet<>(on).size() == on.size(), "no read twice in a tick");
        }
        ok(callsHdmi <= 200 * 4, "average on HDMI");
        for (String r : new String[] {Hb.SRC, Hb.HDMI_INFO, Hb.HDR_TYPE, Hb.VIDEO_INFO, Hb.HDR_VIVID, Hb.OUTPUT_TIMING,
                Hb.EDID_VERSION, Hb.RANGE, Hb.ALLM, Hb.PICTURE_MODE, Hb.COLOR_SPACE, Hb.SYS_TEMP, Hb.SIGNAL_STATUS,
                Hb.HDR_ENABLE, Hb.COLOR_SPACE_INFO, Hb.SCREEN_RES, Hb.ARC_TYPE, Hb.SPDIF_TYPE, Hb.ARC_CONNECTED,
                Hb.ARC_SUPPORT, Hb.AUDIO_DELAY, Hb.CONNECT1, Hb.CONNECT2, Hb.HWCFG1, Hb.HWCFG2}) {
            ok(seenHdmiPorts.contains(r), "schedule reaches " + r);
        }
        // fast group every 5 s, HDR type at most 3 s apart, slow group every 20 s
        eq(5, Hb.FAST_ROUND, "fast round");
        eq(20, Hb.SLOW_ROUND, "slow round");
        long last = -1;
        int maxGap = 0;
        for (long tick = 0; tick < 100; tick++) {
            if (Hb.schedule(tick, false, false).contains(Hb.HDR_TYPE)) {
                if (last >= 0) maxGap = Math.max(maxGap, (int) (tick - last));
                last = tick;
            }
        }
        ok(maxGap <= 4, "HDR type at most 4 s apart (" + maxGap + ")");
        ok(seenOff.contains(Hb.ARC_TYPE) && seenOff.contains(Hb.VIDEO_INFO), "audio and video reads also off HDMI");
    }

    static int code(String s) {
        return s.startsWith("0x") ? Integer.parseInt(s.substring(2), 16) : Integer.parseInt(s);
    }

    static void plan(Path gsi, Path app) throws IOException {
        Path r = gsi.resolve("devices/goat_elegant/research");
        Set<String> allowed = new HashSet<>();
        Map<String, String> allowedMethod = new HashMap<>();
        for (String[] f : tsv(r.resolve("allowed.tsv"))) {
            String k = f[0] + "/" + code(f[1]);
            allowed.add(k);
            allowedMethod.put(k, f[2]);
        }
        Map<String, String> codes = new HashMap<>(), sigs = new HashMap<>();
        for (String[] f : tsv(r.resolve("aidl_codes.tsv"))) {
            codes.put(f[0] + "/" + code(f[1]), f[2]);
            sigs.put(f[0] + "/" + code(f[1]), f[3]);
        }
        // allowed_lumeninfo.tsv: LumenInfo-only getters (2026-11-01)
        Set<String> extra = new HashSet<>();
        Set<String> planKeys = new HashSet<>();
        for (String[] p : Hb.PLAN) planKeys.add(p[0] + "/" + code(p[1]));
        for (String[] f : tsv(r.resolve("allowed_lumeninfo.tsv"))) {
            String k = f[0] + "/" + code(f[1]);
            ok(f.length >= 5 && !f[4].trim().isEmpty(), "allowed_lumeninfo.tsv row " + k + " has a reason");
            ok(!allowed.contains(k), "allowed_lumeninfo.tsv row " + k + " is not also in allowed.tsv");
            ok(planKeys.contains(k), "allowed_lumeninfo.tsv row " + k + " is a PLAN row (the list is exact)");
            eq(codes.get(k), f[2], "allowed_lumeninfo.tsv method of " + k);
            eq(sigs.get(k), f.length > 3 ? f[3] : null, "allowed_lumeninfo.tsv signature of " + k);
            ok(f[2].startsWith("get") || f[2].startsWith("is"), "allowed_lumeninfo.tsv row " + k + " is a getter");
            ok(sigs.get(k) != null && !sigs.get(k).startsWith("void"), "allowed_lumeninfo.tsv row " + k + " returns a value");
            extra.add(k);
            allowedMethod.put(k, f[2]);
        }
        Map<String, String> banned = new HashMap<>();
        for (String[] f : tsv(r.resolve("banned.tsv"))) if (f.length > 4) banned.put(f[0] + "/" + code(f[1]), f[4]);
        int setters = 0;
        for (String[] p : Hb.PLAN) {
            String k = p[0] + "/" + code(p[1]);
            ok(allowed.contains(k) || extra.contains(k), "plan row " + k + " " + p[2] + " is in allowed.tsv or allowed_lumeninfo.tsv");
            eq(p[2], allowedMethod.get(k), "allowed.tsv method of " + k);
            if (!p[0].equals("-")) eq(p[2], codes.get(k), "aidl_codes.tsv method of " + k);
            String b = banned.get(k);
            ok(b == null || b.equals("EXCEPTION"), "plan row " + k + " not banned (" + b + ")");
            if (p[3].equals("w")) {
                setters++;
                eq("setEdidVersion", p[2], "the only setter");
            } else {
                ok(p[2].startsWith("get") || p[2].startsWith("is"), "read-only row is a getter: " + p[2]);
            }
        }
        eq(1, setters, "setters in plan");
        for (String w : Hb.WANTED_NOT_ALLOWED) {
            String[] f = w.split(" ");
            ok(!allowed.contains(f[0] + "/" + f[1]) && !extra.contains(f[0] + "/" + f[1]), "wanted getter " + w + " is not allowed yet");
            ok(!planKeys.contains(f[0] + "/" + f[1]), "wanted getter " + w + " is not in PLAN");
            eq(f[2], codes.get(f[0] + "/" + f[1]), "wanted getter " + w + " matches aidl_codes.tsv");
        }
        // hal/HbGmpf.java has exactly the plan
        String src = new String(Files.readAllBytes(app.resolve("src/org/lumen/info/hal/HbGmpf.java")), StandardCharsets.UTF_8);
        Map<String, String> iface = Map.of("TV", "GmTvManager", "DISPLAY", "DisplayManager", "TEMPERATURE", "TemperatureManager",
                "AUDIO", "GmAudioManager");
        Matcher m = Pattern.compile("\\n\\s+[A-Z_]+\\((null|Iface\\.([A-Z]+)), (0x[0-9A-Fa-f]+|\\d+), \"(\\w+)\"\\)").matcher(src);
        List<String> got = new ArrayList<>();
        while (m.find()) got.add((m.group(2) == null ? "-" : iface.get(m.group(2))) + "/" + code(m.group(3)) + "/" + m.group(4));
        List<String> want = new ArrayList<>();
        for (String[] p : Hb.PLAN) want.add(p[0] + "/" + code(p[1]) + "/" + p[2]);
        eq(want, got, "HbGmpf.Call equals Hb.PLAN");
        ok(src.contains("if (v != 1 && v != 2) throw"), "setEdidVersion refuses other values");
        Matcher d = Pattern.compile("\"(xgimi\\.hardware\\.gmpf\\.I\\w+)\"").matcher(src);
        Set<String> desc = new HashSet<>();
        while (d.find()) desc.add(d.group(1));
        eq(Set.of("xgimi.hardware.gmpf.IDisplayManager", "xgimi.hardware.gmpf.IGmTvManager",
                "xgimi.hardware.gmpf.ITemperatureManager", "xgimi.hardware.gmpf.IGmAudioManager"), desc, "descriptors");
        // arguments of the new getters stay inside the stock choices
        ok(src.contains("if (port != 1 && port != 2) throw new IllegalArgumentException(\"port \" + port);\n        return call(Call.GET_HDMI_CONNECT_STATUS"),
                "TV 70 only for HDMI ports 1 / 2");
        ok(src.contains("if (port != 1 && port != 2) throw new IllegalArgumentException(\"port \" + port);\n        return call(Call.GET_HDMI_HW_CONFIG"),
                "TV 153 only for HDMI ports 1 / 2");
        ok(src.contains("p -> p.writeByte(Hb.AUDIO_DEVICE_ARC), Parcel::readBoolean") && src.contains("p -> p.writeByte(Hb.AUDIO_DEVICE_ARC), Parcel::readByte"),
                "GmAudio 33 / 34 only with HDMI ARC");
        eq(2, (int) Hb.AUDIO_DEVICE_ARC, "HDMI ARC device id (stock EN_AUDIO_ARC)");
        // every PLAN getter except the meta calls has a caller in the Collector schedule (no dead allow rows)
        String col = new String(Files.readAllBytes(app.resolve("src/org/lumen/info/data/Collector.java")), StandardCharsets.UTF_8);
        Matcher cm = Pattern.compile("\\n\\s+([A-Z_]+)\\((?:null|Iface\\.[A-Z]+), (?:0x[0-9A-Fa-f]+|\\d+), \"(\\w+)\"\\)").matcher(src);
        while (cm.find()) {
            String m2 = cm.group(2);
            if (m2.startsWith("getInterface")) continue;
            Matcher def = Pattern.compile("public static \\S+ (\\w+)\\([^)]*\\)[^{]*\\{[^}]*?Call\\." + cm.group(1) + "\\b").matcher(src);
            ok(def.find(), "a public wrapper sends Call." + cm.group(1));
        }
        for (String w2 : new String[] {"HbGmpf::hdmiInfo", "HbGmpf::videoInfo", "HbGmpf::outputTiming", "HbGmpf.colorSpaceInfo(",
                "HbGmpf::screenResolution", "HbGmpf::arcType", "HbGmpf::spdifType", "HbGmpf::arcConnected", "HbGmpf::arcSupportType",
                "HbGmpf::audioDelay", "HbGmpf.hdmiConnectStatus(1)", "HbGmpf.hdmiConnectStatus(2)", "HbGmpf.hdmiHwConfig(1)",
                "HbGmpf.hdmiHwConfig(2)", "HbGmpf::signalStatus", "HbGmpf::hdrEnable", "HbGmpf::hdrVividPlaying"}) {
            ok(col.contains(w2), "Collector reads " + w2);
        }
    }

    // ================================================================ static checks
    static void staticChecks(Path app) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(app.resolve("src"))) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        ok(!files.isEmpty(), "sources found");
        for (Path f : files) {
            String t = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            String n = app.relativize(f).toString();
            boolean hal = n.endsWith("hal/HbGmpf.java");
            ok(hal || !t.contains(".transact("), n + ": transact only in hal/HbGmpf.java");
            ok(hal || !t.contains("xgimi.hardware"), n + ": vendor names only in hal/");
            ok(!t.contains("Runtime.getRuntime().exec") && !t.contains("ProcessBuilder"), n + ": no shell");
            ok(!t.contains("\"su\"") && !t.contains("/system/xbin"), n + ": no su");
            ok(!t.contains("utopia_mdb"), n + ": no MStar debug nodes");
            ok(!Pattern.compile("FileOutputStream\\(\"?/(sys|proc|vendor|mnt)").matcher(t).find(), n + ": no writes to system paths");
            ok(!t.contains("Settings.Global.put") && !t.contains("Settings.System.put") && !t.contains("Settings.Secure.put"),
                    n + ": no settings writes");
        }
    }
}
