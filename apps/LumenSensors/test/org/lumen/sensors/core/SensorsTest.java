// SPDX-License-Identifier: Apache-2.0
package org.lumen.sensors.core;

import org.lumen.sensors.hal.GmpfAllowlist;
import org.lumen.sensors.hal.GmpfStructs;
import org.lumen.sensors.z9x.Z9xSchedule;

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
 * and thermal parsing, the XGIMI HAL struct decoders (584 motion and its g scale, IGmpf2 252 LED currents,
 * 662 HDMI info, IGmpf2 319 video info, IGmpf2 27 light hours), the HAL allowlist (banned codes refused),
 * the Z9X read plan (periods per code, ToF, input-dependent 319 / 662, steady calls per second), value
 * formatting, level thresholds, the device profile decision, the graph history and the registry order.
 * Arguments: the reader source, the HidlCaller copy, apps/common/hal/HidlCaller.java and the app's src/
 * root (static checks of the HAL path, of the device-test fixes and of the device separation over every
 * source file).
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
        schedule();
        if (a.length >= 4) {
            halSources(a[0], a[1], a[2]);
            separation(a[3]);
            fixes(a[3]);
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
        near("tilt of the device test reading", 3.2, GmpfStructs.tiltDegrees(229, -29, 4092), 0.05);
        near("flat", 0, GmpfStructs.tiltDegrees(0, 0, 4096), 1e-9);
        near("on its side", 90, GmpfStructs.tiltDegrees(4096, 0, 0), 1e-9);
        yes("no gravity = misread", Double.isNaN(GmpfStructs.tiltDegrees(1, 2, 3)));
        near("accelerometer 4096 counts = 1 g", 1.0, GmpfStructs.accelG(4096), 1e-12);
        near("accelerometer z flat (device test)", 0.999, GmpfStructs.accelG(4092), 0.0005);
        near("accelerometer negative", -0.00708, GmpfStructs.accelG(-29), 0.00001);

        // IGmpf2 27 usage_time is whole hours (stock PJLink LAMP answers it as is); 0.1 divided by 3600
        near("light hours as is", 1234, GmpfStructs.lightHours(1234), 0);
        yes("light hours zero hides the row (Lumen answers 0 for every light)", Double.isNaN(GmpfStructs.lightHours(0)));
        near("light hours at the plausible end", 100_000, GmpfStructs.lightHours(100_000), 0);
        yes("light hours above 100000 hidden", Double.isNaN(GmpfStructs.lightHours(100_001)));
        yes("light hours of a seconds-sized counter hidden", Double.isNaN(GmpfStructs.lightHours(3_600_000)));
        yes("no usage_time (-1) hidden", Double.isNaN(GmpfStructs.lightHours(-1)));

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
        // IGmpf 157: bool true, then 7 x i32 as the vendor logged them (6, 3840, 2160, 6, 3840, 2160, 60)
        ByteBuffer t = le(40);
        for (int x : new int[] {6, 3840, 2160, 6, 3840, 2160, 60}) t.putInt(x);
        int[] ot = new int[7];
        yes("output timing decodes", GmpfStructs.outputTiming(t.array(), 40, ot));
        eq("output timing", "3840 2160 60", ot[4] + " " + ot[5] + " " + ot[6]);
        yes("output timing plausible", GmpfStructs.outputPlausible(ot));
        eq("output timing 28 bytes refused", false, GmpfStructs.outputTiming(new byte[28], 28, ot));
        ot[6] = 0;
        eq("output timing 0 Hz not plausible", false, GmpfStructs.outputPlausible(ot));
        eq("video info 16 bytes refused", false, GmpfStructs.videoInfo(new byte[16], 16, o));
    }

    // ------------------------------------------------------------------ HAL allowlist
    private static void allowlist() {
        int[] igmpf = {438, 439, 339, 435, 437, 177, 175, 196, 279, 584, 266, 696, 620, 662, 157};
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
        // 592 getSignalStatus (raw vendor enum, "Signal status code 3") is no longer read
        eq("IGmpf 592 (removed) refused", false, GmpfAllowlist.allowed(GmpfAllowlist.IGMPF, 592));
        refused("IGmpf 592 removed", GmpfAllowlist.IGMPF, 592);
        eq("IGmpf2 592 refused", false, GmpfAllowlist.allowed(GmpfAllowlist.IGMPF2, 592));
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

    // ------------------------------------------------------------------ Z9X read plan
    private static final int I1 = GmpfAllowlist.IGMPF, I2 = GmpfAllowlist.IGMPF2;

    /** Steady calls per second of these reads ({iface, code, how many values}). */
    private static double rate(int[][] reads, long refreshMs) {
        double sum = 0;
        for (int[] r : reads) sum += r[2] * Z9xSchedule.perSecond(r[0], r[1], refreshMs);
        return sum;
    }

    private static void schedule() {
        eq("gap between probe calls", 200L, Z9xSchedule.GAP_MS);
        eq("gap before a later call", 500L, Z9xSchedule.STEADY_GAP_MS);
        eq("probe pace", 200L, Z9xSchedule.gapMs(true));
        eq("steady pace", 500L, Z9xSchedule.gapMs(false));
        near("steady cap 2 per second", 2.0, Z9xSchedule.maxSteadyPerSecond(), 1e-12);
        // next due: on the beat when about on time, else a period after the call
        eq("on time keeps the beat", 14_000L, Z9xSchedule.nextDue(10_000, 10_005, 4_000));
        eq("1.5 s late keeps the beat", 14_000L, Z9xSchedule.nextDue(10_000, 11_505, 4_000));
        eq("3 s late: a period after the call", 17_005L, Z9xSchedule.nextDue(10_000, 13_005, 4_000));
        eq("3 s late on a 10 s read too", 23_005L, Z9xSchedule.nextDue(10_000, 13_005, 10_000));
        eq("a long wait (not wanted meanwhile)", 74_000L, Z9xSchedule.nextDue(0, 70_000, 4_000));
        eq("round at refresh 1 s", 4_000L, Z9xSchedule.periodMs(Z9xSchedule.P_ROUND, 1_000));
        eq("round at refresh 2 s", 4_000L, Z9xSchedule.periodMs(Z9xSchedule.P_ROUND, 2_000));
        eq("round at refresh 5 s", 5_000L, Z9xSchedule.periodMs(Z9xSchedule.P_ROUND, 5_000));
        eq("slow period", 10_000L, Z9xSchedule.periodMs(Z9xSchedule.P_SLOW, 2_000));
        eq("voltage period", 30_000L, Z9xSchedule.periodMs(Z9xSchedule.P_VOLTAGE, 2_000));
        eq("ToF period", 60_000L, Z9xSchedule.periodMs(Z9xSchedule.P_TOF, 1_000));
        eq("ToF period constant", 60_000L, Z9xSchedule.TOF_MS);
        yes("ToF never in the same second as the first reads", Z9xSchedule.TOF_AFTER_MS >= 2_000);
        eq("a failed once-per-start read waits 10 s", 10_000L, Z9xSchedule.periodMs(Z9xSchedule.P_ONCE, 1_000));

        int[][] want = {
                {I1, 196, Z9xSchedule.P_ROUND}, {I1, 438, Z9xSchedule.P_ROUND}, {I1, 439, Z9xSchedule.P_ROUND},
                {I1, 435, Z9xSchedule.P_ROUND}, {I1, 437, Z9xSchedule.P_ROUND},
                {I1, 177, Z9xSchedule.P_SLOW}, {I1, 175, Z9xSchedule.P_SLOW}, {I2, 51, Z9xSchedule.P_SLOW},
                {I2, 25, Z9xSchedule.P_SLOW}, {I2, 252, Z9xSchedule.P_SLOW},
                {I1, 696, Z9xSchedule.P_SLOW}, {I1, 620, Z9xSchedule.P_SLOW}, {I1, 662, Z9xSchedule.P_SLOW},
                {I1, 157, Z9xSchedule.P_SLOW},
                {I2, 319, Z9xSchedule.P_SLOW}, {I2, 22, Z9xSchedule.P_SLOW},
                {I1, 584, Z9xSchedule.P_SLOW}, {I1, 266, Z9xSchedule.P_SLOW}, {I2, 241, Z9xSchedule.P_SLOW},
                {I2, 68, Z9xSchedule.P_VOLTAGE}, {I1, 279, Z9xSchedule.P_TOF},
                {I2, 27, Z9xSchedule.P_ONCE}, {I1, 339, Z9xSchedule.P_ONCE}};
        for (int[] w : want) {
            eq("period kind of " + (w[0] == I2 ? "IGmpf2 " : "IGmpf ") + w[1], w[2], Z9xSchedule.kindOf(w[0], w[1]));
        }
        int planned = 0;
        for (int iface : new int[] {I1, I2}) {
            for (int c : GmpfAllowlist.reads(iface)) {
                planned++;
                yes("read " + iface + "/" + c + " has a period", Z9xSchedule.kindOf(iface, c) >= 0);
            }
        }
        eq("every read listed above", want.length, planned);
        eq("592 not planned", -1, Z9xSchedule.kindOf(I1, 592));
        eq("a banned code is not planned", -1, Z9xSchedule.kindOf(I1, 298));
        eq("IGmpf 319 is not IGmpf2 319", -1, Z9xSchedule.kindOf(I1, 319));
        near("ToF once a minute", 1 / 60.0, Z9xSchedule.perSecond(I1, 279, 2_000), 1e-12);
        near("light usage once per start", 0, Z9xSchedule.perSecond(I2, 27, 2_000), 0);

        // 696 decides the signal read: 662 on HDMI, IGmpf2 319 on the media input, neither when unknown
        eq("media input", Z9xSchedule.INPUT_MEDIA, Z9xSchedule.input(0));
        eq("HDMI 2", Z9xSchedule.INPUT_HDMI2, Z9xSchedule.input(2));
        eq("odd input value is unknown", Z9xSchedule.INPUT_UNKNOWN, Z9xSchedule.input(7));
        eq("negative input value is unknown", Z9xSchedule.INPUT_UNKNOWN, Z9xSchedule.input(-5));
        yes("HDMI 1 reads 662", Z9xSchedule.readsHdmiInfo(Z9xSchedule.INPUT_HDMI1));
        yes("HDMI 2 reads 662", Z9xSchedule.readsHdmiInfo(Z9xSchedule.INPUT_HDMI2));
        eq("HDMI 1 does not read 319", false, Z9xSchedule.readsVideoInfo(Z9xSchedule.INPUT_HDMI1));
        yes("media reads 319", Z9xSchedule.readsVideoInfo(Z9xSchedule.INPUT_MEDIA));
        eq("media does not read 662", false, Z9xSchedule.readsHdmiInfo(Z9xSchedule.INPUT_MEDIA));
        eq("unknown input reads no 662", false, Z9xSchedule.readsHdmiInfo(Z9xSchedule.INPUT_UNKNOWN));
        eq("unknown input reads no 319", false, Z9xSchedule.readsVideoInfo(Z9xSchedule.INPUT_UNKNOWN));
        for (int raw = -2; raw <= 4; raw++) {
            int in = Z9xSchedule.input(raw);
            yes("never both signal rows for input " + raw,
                    !(Z9xSchedule.readsHdmiInfo(in) && Z9xSchedule.readsVideoInfo(in)));
        }

        // steady calls per second, every group on, the owner's Z9X (4 sensors, 1 fan, HDMI, 68 absent)
        int[][] detailed = {
                {I1, 196, 1}, {I1, 438, 1}, {I1, 439, 4}, {I1, 435, 1}, {I1, 437, 1},
                {I1, 177, 1}, {I1, 175, 1}, {I2, 51, 1}, {I2, 25, 1}, {I2, 252, 1},
                {I1, 696, 1}, {I1, 620, 1}, {I1, 662, 1}, {I1, 157, 1}, {I2, 22, 1},
                {I1, 584, 1}, {I1, 266, 1}, {I2, 241, 1}, {I1, 279, 1}, {I2, 27, 1}};
        int[][] compact = {{I1, 196, 1}, {I1, 439, 3}, {I1, 437, 1}};   // air, DMD, LED, fan in the strip
        int[][] graphs = {{I1, 196, 1}, {I1, 438, 1}, {I1, 439, 4}};
        double d = rate(detailed, 2_000), c = rate(compact, 2_000), cap = Z9xSchedule.maxSteadyPerSecond();
        near("detailed asks for 8 / 4 s + 13 / 10 s + 1 / 60 s", 8 / 4.0 + 13 / 10.0 + 1 / 60.0, d, 1e-9);
        yes("detailed asks for more than the cap", d > cap);
        yes("detailed gets well under half of 0.1's 4.8", Math.min(d, cap) < 0.5 * 4.8);
        near("compact: 5 / 4 s", 1.25, c, 1e-9);
        near("compact at refresh 5 s: 5 / 5 s", 1.0, rate(compact, 5_000), 1e-9);
        near("graphs: 6 / 4 s", 1.5, rate(graphs, 2_000), 1e-9);
        yes("compact and graphs ask for less than the cap", c < cap && rate(graphs, 2_000) < cap);

        // the pump model: what each mode really gets
        double[] md = model(detailed, 2_000), mc = model(compact, 2_000), mg = model(graphs, 2_000);
        yes("model detailed: under half of 4.8 (" + md[0] + ")", md[0] < 0.5 * 4.8 && md[0] <= cap);
        yes("model detailed: at the cap (" + md[0] + ")", md[0] > 0.9 * cap);
        eq("model detailed: at most 2 calls in any second", 2.0, md[1]);
        yes("model detailed: calls at least the steady gap apart", md[2] >= Z9xSchedule.STEADY_GAP_MS);
        near("model detailed: 196 every round", 4.0, md[3], 0.1);
        yes("model detailed: a sensor about every 8 s, before the 10 s reads (" + md[4] + ")",
                md[4] > 6 && md[4] < 10 && md[4] < md[5]);
        yes("model detailed: 177 about every 14 s (" + md[5] + ")", md[5] >= 10 && md[5] < 18);
        yes("model detailed: ToF at most once a minute (" + md[6] + ")", md[6] >= 60 && md[6] < 90);
        near("model compact: 1.25 per second", 1.25, mc[0], 0.01);
        eq("model compact: at most 2 calls in any second", 2.0, mc[1]);
        near("model compact: a sensor every 4 s", 4.0, mc[4], 0.05);
        near("model graphs: 1.5 per second", 1.5, mg[0], 0.01);
        near("model graphs: a sensor every 4 s", 4.0, mg[4], 0.05);
        near("model compact at refresh 5 s: 1 per second", 1.0, model(compact, 5_000)[0], 0.01);
    }

    /** A modelled read: due time, period, length of the call, code; its calls in the measured window. */
    private static final class Read {
        long due, period, ms, first, last;
        int code, calls;
    }

    /**
     * A model of Z9xHalSource.pumpOnce and execute in steady state (every value probed; calls of 5 ms,
     * ToF 570 ms; 10 minutes after a 2-minute warm-up): the wanted read that was due first, a 196 before
     * it when the last one is a round old or due, the steady gap, Z9xSchedule.nextDue, ToF at least
     * TOF_MS after its last call. Returns {calls per second, most calls in one second, shortest gap in
     * ms, seconds between two 196 reads, between two reads of the first 439, of 177 (0 if absent), of
     * 279 (0 if absent)}.
     */
    private static double[] model(int[][] reads, long refreshMs) {
        java.util.ArrayList<Read> list = new java.util.ArrayList<>();
        for (int[] r : reads) {
            int k = Z9xSchedule.kindOf(r[0], r[1]);
            if (r[1] == 196 || k == Z9xSchedule.P_ONCE) continue;
            for (int i = 0; i < r[2]; i++) {
                Read x = new Read();
                x.period = Z9xSchedule.periodMs(k, refreshMs);
                x.ms = r[1] == 279 ? 570 : 5;
                x.code = r[1];
                list.add(x);
            }
        }
        Read screen = new Read();
        screen.period = Z9xSchedule.periodMs(Z9xSchedule.P_ROUND, refreshMs);
        screen.ms = 5;
        screen.code = 196;
        long now = 0, last = -60_000, checked = -60_000, from = 120_000, to = 720_000, minGap = Long.MAX_VALUE;
        java.util.ArrayList<Long> calls = new java.util.ArrayList<>();
        while (now < to) {
            Read best = null;
            for (Read x : list) if (best == null || x.due < best.due) best = x;
            long at = Math.max(best.due, last + Z9xSchedule.gapMs(false));
            if (at > now) {
                now = at;
                continue;
            }
            Read run = best;
            if (now - checked > screen.period || now >= screen.due) {
                if (screen.due > now) {
                    now = screen.due;
                    continue;
                }
                run = screen;
            }
            if (now >= from) {
                minGap = Math.min(minGap, now - last);
                calls.add(now);
                if (run.calls++ == 0) run.first = now;
                run.last = now;
            }
            long end = now + run.ms;
            last = end;
            if (run == screen) checked = end;
            run.due = Z9xSchedule.nextDue(run.due, end, run.period);
            if (run.code == 279) run.due = Math.max(run.due, now + Z9xSchedule.TOF_MS);
            now = end + Z9xSchedule.GAP_MS;
        }
        int most = 0;
        for (int i = 0, j = 0; i < calls.size(); i++) {
            while (calls.get(j) < calls.get(i) - 999) j++;
            most = Math.max(most, i - j + 1);
        }
        double[] out = {calls.size() / ((to - from) / 1000.0), most, minGap, every(screen), 0, 0, 0};
        for (Read x : list) {
            if (x.code == 439 && out[4] == 0) out[4] = every(x);
            if (x.code == 177) out[5] = every(x);
            if (x.code == 279) out[6] = every(x);
        }
        return out;
    }

    private static double every(Read x) {
        return x.calls > 1 ? (x.last - x.first) / 1000.0 / (x.calls - 1) : 0;
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

    /**
     * Static checks of the device-test fixes of 0.2 that need Android to run: 592 is gone from the code,
     * every HAL task takes its period from the plan (Z9xSchedule.kindOf of its own code), the HUD window
     * is a trusted overlay with the permission for it, and the HUD redraws only on a change.
     */
    private static void fixes(String srcRoot) throws Exception {
        Path root = Paths.get(srcRoot);
        String src = read(root.resolve("org/lumen/sensors/z9x/Z9xHalSource.java"));
        String reader = read(root.resolve("org/lumen/sensors/hal/Z9xGmpfReader.java"));
        String service = read(root.resolve("org/lumen/sensors/HudService.java"));
        String hud = read(root.resolve("org/lumen/sensors/ui/HudView.java"));
        String manifest = read(root.resolve("../AndroidManifest.xml"));
        for (String gone : new String[] {"SIGNAL_STATUS", "signalStatus", "s_signal"}) {
            eq("HAL source: no " + gone, 0, count(src, gone));
            eq("reader: no " + gone, 0, count(reader, gone));
        }
        yes("tasks take the period of their code", count(src, "Z9xSchedule.kindOf(iface, code)") == 1);
        int tasks = count(src, "new OneTask(\"") + count(src, "new Task(\"") + count(src, "super(\"");
        int planned = count(src, ", IGMPF, GmpfAllowlist.") + count(src, ", IGMPF2, GmpfAllowlist.");
        eq("every task names its interface and code", tasks, planned);
        yes("ToF task reads 279",
                src.contains("tofTask = new OneTask(\"279 ToF distance\", IGMPF, GmpfAllowlist.TOF_DISTANCE"));
        yes("ToF waits for every other first read", src.contains("if (t == tofTask && probesDoneAt == 0) return false;"));
        yes("ToF takes its turn by due time", src.contains("if (t != screenTask && wanted(t) && (best == null || t.due < best.due))")
                && count(src, "tofTask.due < best.due") == 0);
        yes("steady reads at the steady pace", src.contains("Z9xSchedule.gapMs(!best.probed && best != tofTask)"));
        yes("late reads due a period after the call", src.contains("t.due = Z9xSchedule.nextDue(t.due, end, t.period());"));
        yes("ToF a full minute after its last call", src.contains("tofCalledAt + Z9xSchedule.TOF_MS"));
        yes("662 only on HDMI", src.contains("needed() { return Z9xSchedule.readsHdmiInfo(inputSource); }"));
        yes("319 only on the media input", src.contains("needed() { return Z9xSchedule.readsVideoInfo(inputSource); }"));
        yes("accelerometer in g", src.contains("Fmt.XYZ_G") && src.contains("GmpfStructs.accelG(st[2])"));
        yes("raw light usage JSON logged", src.contains("Log.i(TAG, \"IGmpf2 27 light usage"));
        yes("HUD window is a trusted overlay", service.contains("p.setTrustedOverlay();"));
        yes("manifest asks for INTERNAL_SYSTEM_WINDOW",
                manifest.contains("<uses-permission android:name=\"android.permission.INTERNAL_SYSTEM_WINDOW\" />"));
        yes("manifest version 0.3",
                manifest.contains("android:versionCode=\"3\"") && manifest.contains("android:versionName=\"0.3\""));
        yes("HUD invalidates on a publish only on a change", hud.contains("if (changed) invalidate();"));
        eq("HUD opacity is the setting itself", 1, count(hud, "Math.round(255 * opacityPct / 100f)"));
    }

    private static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
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
        eq("few hours, whole", "7 h", f(Fmt.HOURS, 7));
        eq("no hours yet", "0 h", f(Fmt.HOURS, 0));
        eq("Hz whole", "60 Hz", f(Fmt.HZ, 60.0));
        eq("Hz film", "23.98 Hz", f(Fmt.HZ, 23.976));
        eq("Hz NTSC", "59.94 Hz", f(Fmt.HZ, 59.94));
        eq("Hz one decimal", "50.5 Hz", f(Fmt.HZ, 50.5));
        eq("degrees", "8.8°", f(Fmt.DEG, 8.75));
        eq("GHz", "2.4 GHz", f(Fmt.GHZ, 2.4));
        eq("GHz whole", "5 GHz", f(Fmt.GHZ, 5));
        eq("int", "87", f(Fmt.INT, 87));
        eq("gyroscope raw", "0 / 5 / -2", f(Fmt.XYZ, 0, 5, -2, 0, null, en));
        eq("xyz", "-613 / -89 / -4020", f(Fmt.XYZ, -613, -89, -4020, 0, null, en));
        double ax = GmpfStructs.accelG(229), ay = GmpfStructs.accelG(-29), az = GmpfStructs.accelG(4092);
        eq("accelerometer in g", "0.06 / -0.01 / 1.00 g", f(Fmt.XYZ_G, ax, ay, az, 0, null, en));
        eq("accelerometer near zero", "0.00 / 0.00 / -1.00 g", f(Fmt.XYZ_G, -0.004, 0.001, -1, 0, null, en));
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
                "Да", "Нет", "мин", "макс", " g"}, ',');
        eq("ru volts", "19,5 В", f(Fmt.VOLT, 19.5, 0, 0, 0, null, ru));
        eq("ru percent", "24 %", f(Fmt.PERCENT, 23.6, 0, 0, 0, null, ru));
        eq("ru GB", "2,7 ГБ", f(Fmt.MEM, 2854904, 0, 0, 0, null, ru));
        eq("ru duration", "1 ч 00 мин", f(Fmt.DURATION, 3600, 0, 0, 0, null, ru));
        eq("ru accelerometer in g", "0,06 / -0,01 / 1,00 g", f(Fmt.XYZ_G, ax, ay, az, 0, null, ru));
        eq("ru gyroscope raw", "0 / 5 / -2", f(Fmt.XYZ, 0, 5, -2, 0, null, ru));
        eq("ru hours", "1234 ч", f(Fmt.HOURS, 1234, 0, 0, 0, null, ru));
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
