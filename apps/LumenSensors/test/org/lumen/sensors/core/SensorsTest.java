// SPDX-License-Identifier: Apache-2.0
package org.lumen.sensors.core;

import org.lumen.sensors.hal.GmpfAllowlist;
import org.lumen.sensors.hal.GmpfStructs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Host test of Lumen Sensors' plain-Java parts (sh test/run.sh): /proc/stat, /proc/meminfo, zram mm_stat
 * and thermal parsing, the XGIMI HAL struct decoders (584 motion, IGmpf2 252 LED currents, 662 HDMI info,
 * IGmpf2 319 video info), the HAL allowlist (banned codes refused), value formatting, level thresholds,
 * the device profile decision, the graph history and the registry order. Arguments: the reader source,
 * the HidlCaller copy, apps/common/hal/HidlCaller.java and the app's src/ root (static checks of the HAL
 * path and of the device separation over every source file).
 */
public final class SensorsTest {
    private static int failed, passed;

    private static void eq(String what, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static void near(String what, double want, double got, double eps) {
        if (Math.abs(want - got) <= eps) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static void yes(String what, boolean v) { eq(what, true, v); }

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    public static void main(String[] a) throws Exception {
        procStat();
        meminfo();
        zram();
        thermal();
        clocks();
        structs();
        allowlist();
        if (a.length >= 4) {
            halSources(a[0], a[1], a[2]);
            separation(a[3]);
        } else {
            failed++;
            System.out.println("FAIL static HAL checks: run through test/run.sh");
        }
        format();
        levels();
        profile();
        history();
        registry();

        System.out.println((failed == 0 ? "OK" : "FAILED") + ": " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }

    // ------------------------------------------------------------------ /proc/stat
    private static void procStat() {
        String s1 = "cpu  100 0 50 800 50 0 0 0 0 0\n"
                + "cpu0 25 0 10 200 15 0 0 0 0 0\n"
                + "cpu1 25 0 15 200 10 0 0 0 0 0\n"
                + "cpu2 25 0 15 200 10 0 0 0 0 0\n"
                + "cpu3 25 0 10 200 15 0 0 0 0 0\n"
                + "intr 12345 1 2 3 4 5 6 7 8 9\nctxt 999\nbtime 1\n";
        String s2 = "cpu  160 0 70 830 60 0 0 0 0 0\n"
                + "cpu0 45 0 20 205 15 0 0 0 0 0\n"
                + "cpu1 30 0 15 230 10 0 0 0 0 0\n"
                + "cpu3 40 0 15 210 15 0 0 0 0 0\n"
                + "intr 12399 1 2 3\n";
        long[] busy0 = new long[17], total0 = new long[17], busy1 = new long[17], total1 = new long[17];
        byte[] x1 = b(s1), x2 = b(s2);
        eq("stat entries", 5, Parse.procStat(x1, x1.length, busy0, total0));
        eq("stat busy all", 150L, busy0[0]);
        eq("stat total all", 1000L, total0[0]);
        eq("stat busy cpu0", 35L, busy0[1]);
        eq("stat total cpu0", 250L, total0[1]);
        eq("stat entries 2 (cpu2 offline)", 5, Parse.procStat(x2, x2.length, busy1, total1));
        near("load all", 80.0 * 100 / 120, Parse.loadPercent(busy0[0], total0[0], busy1[0], total1[0]), 1e-9);
        near("load cpu0 (busy 30 of 35)", 30.0 * 100 / 35, Parse.loadPercent(busy0[1], total0[1], busy1[1], total1[1]), 1e-9);
        near("load cpu1 (busy 5 of 35)", 5.0 * 100 / 35, Parse.loadPercent(busy0[2], total0[2], busy1[2], total1[2]), 1e-9);
        eq("offline core has no line", -1L, total1[3]);
        near("offline core load", -1, Parse.loadPercent(busy0[3], total0[3], busy1[3], total1[3]), 0);
        near("no time passed", -1, Parse.loadPercent(10, 100, 10, 100), 0);
        near("counters went back", -1, Parse.loadPercent(50, 200, 40, 210), 0);
        byte[] g = b("cpu  10 0 10 80 0 0 0 0 5 5\n");
        Parse.procStat(g, g.length, busy0, total0);
        eq("guest time not counted twice", 100L, total0[0]);
        // a buffer cut in the middle of the intr line (first 4 KB read)
        byte[] cut = b("cpu  1 2 3 4 5 6 7 8 0 0\ncpu0 1 2 3 4 5 6 7 8 0 0\nintr 1 2 3 4 5 6");
        eq("cut buffer", 2, Parse.procStat(cut, cut.length, busy0, total0));
        eq("cut buffer busy (user+nice+sys+irq+soft+steal)", 1L + 2 + 3 + 6 + 7 + 8, busy0[0]);
        eq("cut buffer total", 1L + 2 + 3 + 6 + 7 + 8 + 4 + 5, total0[0]);
        eq("empty", 0, Parse.procStat(new byte[0], 0, busy0, total0));
    }

    // ------------------------------------------------------------------ /proc/meminfo
    private static void meminfo() {
        String m = "MemTotal:        2854904 kB\nMemFree:          316348 kB\nMemAvailable:     483072 kB\n"
                + "Buffers:            1234 kB\nCached:           400000 kB\nSwapCached:        1000 kB\n"
                + "SwapTotal:         819196 kB\nSwapFree:          317212 kB\n";
        long[] out = new long[5];
        byte[] x = b(m);
        eq("meminfo found", 5, Parse.meminfo(x, x.length, out));
        eq("MemTotal", 2854904L, out[Parse.MEM_TOTAL]);
        eq("MemAvailable", 483072L, out[Parse.MEM_AVAILABLE]);
        eq("MemFree", 316348L, out[Parse.MEM_FREE]);
        eq("SwapTotal (not SwapCached)", 819196L, out[Parse.SWAP_TOTAL]);
        eq("SwapFree", 317212L, out[Parse.SWAP_FREE]);
        byte[] y = b("MemTotal:        1000 kB\nMemFree:          10 kB\n");
        eq("meminfo partial", 2, Parse.meminfo(y, y.length, out));
        eq("MemAvailable missing", -1L, out[Parse.MEM_AVAILABLE]);
    }

    // ------------------------------------------------------------------ zram mm_stat
    private static void zram() {
        byte[] x = b("555745280 79691776 83620864        0 83886080   1234     0    456     0\n");
        long[] out = new long[8];
        eq("mm_stat fields (capped)", 8, Parse.longs(x, x.length, out));
        eq("orig_data_size", 555745280L, out[0]);
        eq("compr_data_size", 79691776L, out[1]);
        eq("mem_used_total", 83620864L, out[2]);
        eq("pages", 456L, out[7]);
        byte[] e = b("\n");
        eq("empty mm_stat", 0, Parse.longs(e, e.length, out));
        byte[] one = b("42\n");
        eq("firstLong", 42L, Parse.firstLong(one, one.length));
        byte[] neg = b("  -7\n");
        eq("firstLong negative", -7L, Parse.firstLong(neg, neg.length));
        byte[] none = b("abc\n");
        eq("firstLong none", Parse.NONE, Parse.firstLong(none, none.length));
    }

    // ------------------------------------------------------------------ thermal scaling
    private static void thermal() {
        near("millidegrees", 58.0, Parse.thermalCelsius(58000), 1e-9);
        near("millidegrees fraction", 45.5, Parse.thermalCelsius(45500), 1e-9);
        near("degrees", 58.0, Parse.thermalCelsius(58), 1e-9);
        near("tenths", 58.0, Parse.thermalCelsius(580), 1e-9);
        yes("zero hidden", Double.isNaN(Parse.thermalCelsius(0)));
        yes("negative hidden", Double.isNaN(Parse.thermalCelsius(-5000)));
        yes("200 C hidden", Double.isNaN(Parse.thermalCelsius(200000)));
        yes("unreadable hidden", Double.isNaN(Parse.thermalCelsius(Parse.NONE)));
        near("150 C kept", 150.0, Parse.thermalCelsius(150000), 1e-9);
    }

    private static void clocks() {
        near("gpu MHz", 504, Parse.clockMhz(504), 1e-9);
        near("gpu kHz", 504, Parse.clockMhz(504_000), 1e-9);
        near("gpu Hz", 504, Parse.clockMhz(504_000_000L), 1e-9);
        yes("gpu clock unreadable", Double.isNaN(Parse.clockMhz(Parse.NONE)));
        near("gpu load 0..100", 50, Parse.gpuPercent(50, false), 1e-9);
        yes("gpu load above 100 without 255 scale", Double.isNaN(Parse.gpuPercent(200, false)));
        near("gpu load 255 scale", 100, Parse.gpuPercent(255, true), 1e-9);
        near("gpu load 255 scale half", 128 * 100.0 / 255, Parse.gpuPercent(128, true), 1e-9);
        yes("gpu load out of range", Double.isNaN(Parse.gpuPercent(300, true)));
    }

    // ------------------------------------------------------------------ HAL structs
    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void structs() {
        // 584: acc -613 -89 -4020, gyro -2 8 0, type 1, one pad byte (Z9X vendor log values)
        ByteBuffer m = le(14);
        m.putShort((short) -613).putShort((short) -89).putShort((short) -4020)
                .putShort((short) -2).putShort((short) 8).putShort((short) 0).put((byte) 1).put((byte) 0);
        int[] o = new int[8];
        yes("motion decodes", GmpfStructs.motion(m.array(), 14, o));
        eq("acc x", -613, o[0]);
        eq("acc y", -89, o[1]);
        eq("acc z", -4020, o[2]);
        eq("gyro x", -2, o[3]);
        eq("gyro y", 8, o[4]);
        eq("gyro z", 0, o[5]);
        eq("sensor type", 1, o[6]);
        eq("motion 13 bytes refused", false, GmpfStructs.motion(new byte[13], 13, o));
        eq("motion 16 bytes refused", false, GmpfStructs.motion(new byte[16], 16, o));
        near("tilt of the logged reading", 8.75, GmpfStructs.tiltDegrees(-613, -89, -4020), 0.05);
        near("flat", 0, GmpfStructs.tiltDegrees(0, 0, 4096), 1e-9);
        near("on its side", 90, GmpfStructs.tiltDegrees(4096, 0, 0), 1e-9);
        yes("no gravity = misread", Double.isNaN(GmpfStructs.tiltDegrees(1, 2, 3)));

        // IGmpf2 252: 8 x int16
        ByteBuffer c = le(16);
        short[] cur = {1200, 1100, 900, 0, -5, 0, 0, 32767};
        for (short v : cur) c.putShort(v);
        yes("LED currents decode", GmpfStructs.ledCurrents(c.array(), 16, o));
        for (int i = 0; i < 8; i++) eq("LED current " + i, (int) cur[i], o[i]);
        eq("LED currents 14 bytes refused", false, GmpfStructs.ledCurrents(new byte[14], 14, o));

        // 662: u32 3840, u32 2160, u8 interlace 0, u8 hdmi 1, 2 pad, u32 6000 (60.00 Hz)
        ByteBuffer h = le(16);
        h.putInt(3840).putInt(2160).put((byte) 0).put((byte) 1).put((byte) 0).put((byte) 0).putInt(6000);
        yes("HDMI info decodes", GmpfStructs.hdmiInfo(h.array(), 16, o));
        eq("hdmi width", 3840, o[0]);
        eq("hdmi height", 2160, o[1]);
        eq("hdmi interlace", 0, o[2]);
        eq("hdmi mode", 1, o[3]);
        eq("hdmi fps x100", 6000, o[4]);
        yes("hdmi plausible", GmpfStructs.hdmiPlausible(o));
        GmpfStructs.hdmiInfo(new byte[16], 16, o);
        eq("no signal (zeros) not plausible", false, GmpfStructs.hdmiPlausible(o));
        eq("HDMI info 12 bytes refused", false, GmpfStructs.hdmiInfo(new byte[12], 12, o));
        ByteBuffer hi = le(16);
        hi.putInt(1920).putInt(1080).put((byte) 1).put((byte) 0).put((byte) 0).put((byte) 0).putInt(5000);
        GmpfStructs.hdmiInfo(hi.array(), 16, o);
        eq("interlaced dvi", "1920 1080 1 0 5000", o[0] + " " + o[1] + " " + o[2] + " " + o[3] + " " + o[4]);

        // IGmpf2 319: u16 3840, u16 2160, u16 2398, u16 1, i32 3
        ByteBuffer v = le(12);
        v.putShort((short) 3840).putShort((short) 2160).putShort((short) 2398).putShort((short) 1).putInt(3);
        yes("video info decodes", GmpfStructs.videoInfo(v.array(), 12, o));
        eq("video", "3840 2160 2398 1 3", o[0] + " " + o[1] + " " + o[2] + " " + o[3] + " " + o[4]);
        yes("video plausible", GmpfStructs.videoPlausible(o));
        eq("video info 16 bytes refused", false, GmpfStructs.videoInfo(new byte[16], 16, o));
    }

    // ------------------------------------------------------------------ HAL allowlist
    private static void allowlist() {
        int[] igmpf = {438, 439, 339, 435, 437, 177, 175, 196, 279, 584, 266, 696, 620, 662, 592};
        int[] igmpf2 = {51, 241, 252, 25, 27, 68, 319, 22};
        for (int c : igmpf) yes("IGmpf " + c + " allowed", GmpfAllowlist.allowed(GmpfAllowlist.IGMPF, c));
        for (int c : igmpf2) yes("IGmpf2 " + c + " allowed", GmpfAllowlist.allowed(GmpfAllowlist.IGMPF2, c));
        eq("IGmpf reads exactly", igmpf.length, GmpfAllowlist.reads(GmpfAllowlist.IGMPF).length);
        eq("IGmpf2 reads exactly", igmpf2.length, GmpfAllowlist.reads(GmpfAllowlist.IGMPF2).length);

        // the owner's banned list: refused on both interfaces
        int[] banned = {298, 306, 431, 440, 589, 432, 121, 263, 275};
        for (int c : banned) {
            for (int iface : new int[] {GmpfAllowlist.IGMPF, GmpfAllowlist.IGMPF2}) {
                eq("banned " + c + " on " + iface, false, GmpfAllowlist.allowed(iface, c));
                refused("check(" + iface + ", " + c + ")", iface, c);
            }
        }
        for (int c : GmpfAllowlist.BANNED_IGMPF) {
            eq("BANNED_IGMPF " + c, false, GmpfAllowlist.allowed(GmpfAllowlist.IGMPF, c));
            refused("IGmpf banned " + c, GmpfAllowlist.IGMPF, c);
        }
        for (int c : GmpfAllowlist.BANNED_IGMPF2) {
            eq("BANNED_IGMPF2 " + c, false, GmpfAllowlist.allowed(GmpfAllowlist.IGMPF2, c));
            refused("IGmpf2 banned " + c, GmpfAllowlist.IGMPF2, c);
        }
        for (int c : banned) yes("owner's ban " + c + " listed for IGmpf", contains(GmpfAllowlist.BANNED_IGMPF, c));
        // numbers that mean something else on the other interface
        eq("IGmpf 51 (PEQ setter) refused", false, GmpfAllowlist.allowed(GmpfAllowlist.IGMPF, 51));
        eq("IGmpf 27 (sound setter) refused", false, GmpfAllowlist.allowed(GmpfAllowlist.IGMPF, 27));
        eq("IGmpf 252 (factory check) refused", false, GmpfAllowlist.allowed(GmpfAllowlist.IGMPF, 252));
        eq("IGmpf 319 (special autofocus) refused", false, GmpfAllowlist.allowed(GmpfAllowlist.IGMPF, 319));
        eq("IGmpf2 438 refused", false, GmpfAllowlist.allowed(GmpfAllowlist.IGMPF2, 438));
        eq("IGmpf2 52 (Boost setter) refused", false, GmpfAllowlist.allowed(GmpfAllowlist.IGMPF2, 52));
        // a few setters of Projector's GmpfClient
        for (int c : new int[] {176, 174, 195, 434, 436, 148, 152, 300, 307, 161, 119}) {
            eq("setter " + c + " refused", false, GmpfAllowlist.allowed(GmpfAllowlist.IGMPF, c));
        }
        eq("unknown interface", false, GmpfAllowlist.allowed(3, 438));
        try {
            GmpfAllowlist.check(GmpfAllowlist.IGMPF, 438);
            passed++;
        } catch (SecurityException e) {
            failed++;
            System.out.println("FAIL 438 refused: " + e);
        }
    }

    private static void refused(String what, int iface, int code) {
        try {
            GmpfAllowlist.check(iface, code);
            failed++;
            System.out.println("FAIL " + what + " was not refused");
        } catch (SecurityException e) {
            passed++;
        }
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    /** Static checks of the HAL path: one transact path, and the HidlCaller copy is the common file. */
    private static void halSources(String readerPath, String copyPath, String commonPath) throws Exception {
        String r = new String(Files.readAllBytes(Paths.get(readerPath)), StandardCharsets.UTF_8);
        eq("reader: exactly one call() (inside tx)", 1, count(r, ".call("));
        yes("reader: tx checks the allowlist first",
                r.indexOf("GmpfAllowlist.check(i.which, code)") >= 0
                        && r.indexOf("GmpfAllowlist.check(i.which, code)") < r.indexOf(".call("));
        for (String bad : new String[] {"callRet", "callU8", "callI32", "callBool", "transact(", "setTemperatureListener",
                "connectProjectorFocusManager", "extendCmd"}) {
            eq("reader: no " + bad, 0, count(r, bad));
        }
        eq("reader: every read goes through tx", count(r, "throws RemoteException {") - 1, count(r, "return tx("));
        String copy = new String(Files.readAllBytes(Paths.get(copyPath)), StandardCharsets.UTF_8);
        String common = new String(Files.readAllBytes(Paths.get(commonPath)), StandardCharsets.UTF_8);
        yes("HidlCaller copy has its header", copy.startsWith("/*\n * COPY of gsi/apps/common/hal/HidlCaller.java"));
        String body = copy.substring(copy.indexOf("*/\n") + 3)
                .replace("package org.lumen.sensors.hal;", "package org.z9x.projector.hal;");
        yes("HidlCaller copy identical to apps/common apart from the package", body.equals(common));
    }

    /**
     * Every source file (src/): HidlCaller is package-private in org.lumen.sensors.hal, so only hal/ (and
     * z9x/, through the hal imports) could reach the vendor HAL. Checks: no HidlCaller helper or raw
     * transact anywhere; in hal/ and z9x/ the one .call( is Z9xGmpfReader.tx; nothing outside hal/ and
     * z9x/ names the hal package, HidlCaller, the reader or the HAL source; only Z9xProfile creates the
     * HAL source and only the HAL source creates the reader (the device separation: core/, sources/,
     * ui/, Hub, HudService and SettingsActivity are device-neutral).
     */
    private static void separation(String srcRoot) throws Exception {
        Path root = Paths.get(srcRoot);
        List<Path> files;
        try (Stream<Path> st = Files.walk(root)) {
            files = st.filter(f -> f.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        yes("source tree found (" + files.size() + " files)", files.size() >= 30);
        int creators = 0, readerMakers = 0;
        for (Path f : files) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            if (rel.equals("org/lumen/sensors/hal/HidlCaller.java")) continue;
            String t = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            boolean hal = rel.startsWith("org/lumen/sensors/hal/"), z9x = rel.startsWith("org/lumen/sensors/z9x/");
            for (String bad : new String[] {"callRet", "callU8", "callI32", "callBool", "transact(", "HwBinder"}) {
                eq(rel + ": no " + bad, 0, count(t, bad));
            }
            if (hal || z9x) {
                eq(rel + ": .call( only in Z9xGmpfReader.tx", rel.endsWith("hal/Z9xGmpfReader.java") ? 1 : 0,
                        count(t, ".call("));
            } else {
                for (String name : new String[] {"org.lumen.sensors.hal", "HidlCaller", "Z9xGmpfReader", "Z9xHalSource"}) {
                    eq(rel + ": no " + name + " outside hal/ and z9x/", 0, count(t, name));
                }
            }
            if (!hal) eq(rel + ": HidlCaller only in hal/", 0, count(t, "HidlCaller"));
            if (z9x && !rel.endsWith("z9x/Z9xProfile.java") && !rel.endsWith("z9x/Z9xHalSource.java")) {
                eq(rel + ": no Z9xHalSource", 0, count(t, "Z9xHalSource"));
            }
            if (count(t, "new Z9xHalSource(") > 0) {
                creators++;
                yes(rel + ": only Z9xProfile creates the HAL source", rel.endsWith("z9x/Z9xProfile.java"));
            }
            if (count(t, "new Z9xGmpfReader(") > 0) {
                readerMakers++;
                yes(rel + ": only Z9xHalSource creates the reader", rel.endsWith("z9x/Z9xHalSource.java"));
            }
        }
        eq("HAL source created in one place", 1, creators);
        eq("reader created in one place", 1, readerMakers);
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }

    // ------------------------------------------------------------------ formatting
    private static String f(int format, double v, double v2, double v3, int flags, String text, Words w) {
        StringBuilder sb = new StringBuilder();
        Fmt.value(sb, format, v, v2, v3, flags, text, w);
        return sb.toString().replace(' ', ' ');
    }

    private static String f(int format, double v) { return f(format, v, 0, 0, 0, null, Words.english()); }

    private static void format() {
        Words en = Words.english();
        eq("temp", "58 °C", f(Fmt.TEMP, 58.4));
        eq("temp rounds", "59 °C", f(Fmt.TEMP, 58.5));
        eq("percent", "24%", f(Fmt.PERCENT, 23.6));
        eq("percent clamps", "100%", f(Fmt.PERCENT, 150));
        eq("MHz", "1895 MHz", f(Fmt.MHZ, 1895));
        eq("MB", "472 MB", f(Fmt.MEM, 483072));
        eq("GB", "2.7 GB", f(Fmt.MEM, 2854904));
        eq("RAM pair", "1.9 / 2.7 GB", f(Fmt.MEM_PAIR, 1960000, 2854904, 0, 0, null, en));
        eq("RAM pair small", "300 / 900 MB", f(Fmt.MEM_PAIR, 300 * 1024, 900 * 1024, 0, 0, null, en));
        eq("rate", "12.3 Mbit/s", f(Fmt.RATE, 12_345_678));
        eq("rate zero", "0.0 Mbit/s", f(Fmt.RATE, 0));
        eq("link speed", "1201 Mbit/s", f(Fmt.RATE, 1201e6));
        eq("dBm", "-65 dBm", f(Fmt.DBM, -65));
        eq("mm", "2394 mm", f(Fmt.MM, 2394));
        eq("volts", "19.5 V", f(Fmt.VOLT, 19.5));
        eq("hours", "1234 h", f(Fmt.HOURS, 1234.4));
        eq("few hours", "2.3 h", f(Fmt.HOURS, 2.25));
        eq("Hz whole", "60 Hz", f(Fmt.HZ, 60.0));
        eq("Hz film", "23.98 Hz", f(Fmt.HZ, 23.976));
        eq("Hz NTSC", "59.94 Hz", f(Fmt.HZ, 59.94));
        eq("Hz one decimal", "50.5 Hz", f(Fmt.HZ, 50.5));
        eq("degrees", "8.8°", f(Fmt.DEG, 8.75));
        eq("GHz", "2.4 GHz", f(Fmt.GHZ, 2.4));
        eq("GHz whole", "5 GHz", f(Fmt.GHZ, 5));
        eq("int", "87", f(Fmt.INT, 87));
        eq("xyz", "-613 -89 -4020", f(Fmt.XYZ, -613, -89, -4020, 0, null, en));
        eq("fraction off", "Off", f(Fmt.FRACTION, 0, 3, 0, 0, null, en));
        eq("fraction", "1/3", f(Fmt.FRACTION, 1, 3, 0, 0, null, en));
        eq("resolution", "3840×2160", f(Fmt.RES, 3840, 2160, 0, 0, null, en));
        eq("signal", "1920×1080p 60 Hz", f(Fmt.SIGNAL, 1920, 1080, 60, 0, null, en));
        eq("signal interlaced", "1920×1080i 50 Hz", f(Fmt.SIGNAL, 1920, 1080, 50, 1, null, en));
        eq("signal without rate", "3840×2160p", f(Fmt.SIGNAL, 3840, 2160, 0, 0, null, en));
        eq("text", "HDR10", f(Fmt.TEXT, 0, 0, 0, 0, "HDR10", en));
        eq("minutes", "35 min", f(Fmt.DURATION, 35 * 60 + 10));
        eq("hours and minutes", "2 h 05 min", f(Fmt.DURATION, 2 * 3600 + 5 * 60));
        eq("on", "On", f(Fmt.ON_OFF, 1));
        eq("no", "No", f(Fmt.YES_NO, 0));
        Words ru = new Words(new String[] {" °C", " %", " МГц", " МБ", " ГБ", " Мбит/с",
                " дБм", " мм", " В", " ч", " Гц", " мин", "°", " ГГц", "Вкл", "Выкл",
                "Да", "Нет", "мин", "макс"}, ',');
        eq("ru volts", "19,5 В", f(Fmt.VOLT, 19.5, 0, 0, 0, null, ru));
        eq("ru percent", "24 %", f(Fmt.PERCENT, 23.6, 0, 0, 0, null, ru));
        eq("ru GB", "2,7 ГБ", f(Fmt.MEM, 2854904, 0, 0, 0, null, ru));
        eq("ru duration", "1 ч 00 мин", f(Fmt.DURATION, 3600, 0, 0, 0, null, ru));
        StringBuilder sb = new StringBuilder();
        Fmt.fixed(sb, -0.04, 1, '.');
        eq("no negative zero", "0.0", sb.toString());
        sb.setLength(0);
        Fmt.fixed(sb, -1.25, 1, '.');
        eq("negative fixed", "-1.3", sb.toString());
        sb.setLength(0);
        Fmt.fixed(sb, 3.005, 2, '.');
        eq("two decimals pad", "3.01", sb.toString());
        sb.setLength(0);
        Fmt.fixed(sb, 7.05, 3, '.');
        eq("three decimals pad", "7.050", sb.toString());
        Sample s = new Sample("t", Group.TEMPS, Fmt.TEMP);
        s.set(61);
        sb.setLength(0);
        Fmt.format(sb, s, en);
        eq("sample format", "61 °C", sb.toString());
        try {
            new Words(new String[3], '.');
            failed++;
            System.out.println("FAIL short word list accepted");
        } catch (IllegalArgumentException e) {
            passed++;
        }
    }

    // ------------------------------------------------------------------ levels
    private static void levels() {
        eq("soc 74", Levels.NORMAL, Levels.level(Levels.K_SOC, 74.9));
        eq("soc 75", Levels.WARM, Levels.level(Levels.K_SOC, 75));
        eq("soc 85", Levels.HOT, Levels.level(Levels.K_SOC, 85));
        eq("dmd 64", Levels.NORMAL, Levels.level(Levels.K_DMD, 64));
        eq("dmd 65", Levels.WARM, Levels.level(Levels.K_DMD, 65));
        eq("dmd 75", Levels.HOT, Levels.level(Levels.K_DMD, 75));
        eq("led 59", Levels.NORMAL, Levels.level(Levels.K_LED, 59));
        eq("led 60", Levels.WARM, Levels.level(Levels.K_LED, 60));
        eq("led 70", Levels.HOT, Levels.level(Levels.K_LED, 70));
        eq("air 39", Levels.NORMAL, Levels.level(Levels.K_AIR, 39));
        eq("air 40", Levels.WARM, Levels.level(Levels.K_AIR, 40));
        eq("air 45", Levels.HOT, Levels.level(Levels.K_AIR, 45));
        eq("board 59", Levels.NORMAL, Levels.level(Levels.K_BOARD, 59));
        eq("board 60", Levels.WARM, Levels.level(Levels.K_BOARD, 60));
        eq("board 70", Levels.HOT, Levels.level(Levels.K_BOARD, 70));
        eq("throttle 0", Levels.NORMAL, Levels.level(Levels.K_THROTTLE, 0));
        eq("throttle 1", Levels.WARM, Levels.level(Levels.K_THROTTLE, 1));
        eq("flag 0", Levels.NORMAL, Levels.level(Levels.K_FLAG, 0));
        eq("flag 1", Levels.HOT, Levels.level(Levels.K_FLAG, 1));
        eq("no kind", Levels.NORMAL, Levels.level(Levels.K_NONE, 1000));
        eq("NaN", Levels.NORMAL, Levels.level(Levels.K_SOC, Double.NaN));
        eq("unknown kind", Levels.NORMAL, Levels.level(99, 1000));
        near("warm guide", 65, Levels.warmAt(Levels.K_DMD), 0);
        yes("no guide for flags", Float.isNaN(Levels.warmAt(Levels.K_FLAG)));
    }

    // ------------------------------------------------------------------ device profile
    private static void profile() {
        eq("Z9X by board", DeviceProfile.Z9X, DeviceProfile.decide("", "mt9952", "", "", ""));
        eq("Z9X by ro.hardware", DeviceProfile.Z9X, DeviceProfile.decide(null, null, "mt9952", null, null));
        eq("Z9X by model", DeviceProfile.Z9X, DeviceProfile.decide("", "", "", "G0082", ""));
        eq("Z9X by ro.xgimi.modelname", DeviceProfile.Z9X, DeviceProfile.decide("", "", "", "", " G0082 "));
        eq("Play 6 image", DeviceProfile.GENERIC, DeviceProfile.decide("play6", "mt5889", "mt5889", "G0058", ""));
        eq("ro.lumen.device set wins", DeviceProfile.GENERIC, DeviceProfile.decide("play6", "mt9952", "", "G0082", ""));
        eq("unknown device", DeviceProfile.GENERIC, DeviceProfile.decide("", "qcom", "", "", ""));
        eq("real Z9X properties", DeviceProfile.Z9X, DeviceProfile.decide("", "mt9952", "mt9952", "G0082", "G0082"));
        eq("other model on the MT9952 board", DeviceProfile.GENERIC,
                DeviceProfile.decide("", "mt9952", "mt9952", "G0099", ""));
        eq("other model in ro.xgimi.modelname", DeviceProfile.GENERIC,
                DeviceProfile.decide("", "mt9952", "", "", "G0099"));
        eq("model properties disagree", DeviceProfile.GENERIC, DeviceProfile.decide("", "mt9952", "", "G0082", "G0099"));
        eq("board alone when no model is set", DeviceProfile.Z9X, DeviceProfile.decide("", "mt9952", "", "", ""));
        eq("blank model properties count as unset", DeviceProfile.Z9X, DeviceProfile.decide("", "", "mt9952", "  ", null));
        eq("no properties", DeviceProfile.GENERIC, DeviceProfile.decide(null, null, null, null, null));
    }

    // ------------------------------------------------------------------ graph history
    private static void history() {
        Sample s = new Sample("g", Group.TEMPS, Fmt.TEMP).graph();
        for (int i = 1; i <= 130; i++) s.push(i);
        eq("history capped", Sample.HIST, s.histSize);
        eq("oldest of last 3", 128f, s.histAt(3, 0));
        eq("newest", 130f, s.histAt(3, 2));
        eq("oldest kept", 11f, s.histAt(Sample.HIST, 0));
        s.clearHistory();
        eq("cleared", 0, s.histSize);
        Sample plain = new Sample("p", Group.TEMPS, Fmt.TEMP);
        plain.push(1);
        eq("no history without graph()", 0, plain.histSize);
        Sample c = new Sample("c", Group.MEMORY, Fmt.PERCENT).compactOnly(8, 1);
        eq("compact only", Sample.MODE_COMPACT, c.modes);
    }

    // ------------------------------------------------------------------ registry
    private static void registry() {
        Registry r = new Registry();
        int v0 = r.layoutVersion();
        r.add(new Sample("b", Group.CPU, Fmt.PERCENT).order(10));
        r.add(new Sample("a", Group.TEMPS, Fmt.TEMP).order(20));
        r.add(new Sample("c", Group.TEMPS, Fmt.TEMP).order(10));
        Sample dup = r.add(new Sample("a", Group.TEMPS, Fmt.TEMP).order(1));
        eq("duplicate id returns the first", 20, dup.order);
        StringBuilder sb = new StringBuilder();
        synchronized (r.lock) {
            for (Sample s : r.samplesLocked()) sb.append(s.id);
        }
        eq("sorted by group then order", "cab", sb.toString());
        yes("layout version moved", r.layoutVersion() != v0);
        eq("nothing available yet", 0, r.availableGroups());
        dup.set(50);
        eq("temps available", 1 << Group.TEMPS, r.availableGroups());
    }
}
