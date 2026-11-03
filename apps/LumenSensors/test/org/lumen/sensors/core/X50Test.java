// SPDX-License-Identifier: Apache-2.0
package org.lumen.sensors.core;

import org.lumen.sensors.hal.X50Structs;
import org.lumen.sensors.x50.X50Schedule;

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

/**
 * Host test of the X50 (G0111) profile of Lumen Sensors (sh test/run.sh): the reply decoders on the raw replies the X50
 * gave on 2026-11-01 (`service call`, words after the exception code), the light usage JSON, the units, the enum notes,
 * the profile decision, the read plan and the steady calls per second, and the allow list: the Call enum of
 * hal/X50Gmpf.java = devices/g0111/research/allowed_lumensensors.tsv = the plan, every row in aidl_codes.tsv with the
 * same method and signature, none BANNED / REVIEW in the HB banned.tsv, none of the excluded calls (laser currents,
 * ambient light, ToF, voltage). Plus static checks of X50HalSource.
 * Arguments: the app's src/ root and devices/ of the tree.
 */
public final class X50Test {
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
        if (Math.abs(want - got) <= eps) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static void yes(String what, boolean v) { eq(what, true, v); }

    public static void main(String[] a) throws Exception {
        structs();
        lightUsage();
        units();
        profile();
        schedule();
        if (a.length >= 2) {
            allowList(Paths.get(a[0]), Paths.get(a[1]));
            source(Paths.get(a[0]));
        } else {
            failed++;
            System.out.println("FAIL static X50 checks: run through test/run.sh");
        }
        System.out.println((failed == 0 ? "OK" : "FAILED") + " (X50): " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }

    /** Hex words of a `service call` reply, without the first word (the exception code 0). */
    private static int[] words(String hex) {
        String[] p = hex.trim().split("\\s+");
        int[] w = new int[p.length];
        for (int i = 0; i < p.length; i++) w[i] = (int) Long.parseLong(p[i], 16);
        return w;
    }

    // ------------------------------------------------------------------ decoders on the X50's raw replies
    private static void structs() {
        int[] out = new int[8];
        // MOTION 2 (light off, lying on the table): flag 1, size 0x28, int[3] acc, int[3] ang, byte type
        int[] m = words("00000001 00000028 00000003 0000000a fffffff9 ffffefdf 00000003 fffffff5 fffffff6 ffffffff 00000000");
        yes("motion decodes", X50Structs.motion(m, m.length, out));
        eq("acc x", 10, out[0]);
        eq("acc y", -7, out[1]);
        eq("acc z (as sent)", -4129, out[2]);
        eq("ang x", -11, out[3]);
        eq("ang z", -1, out[5]);
        eq("msensorType", 0, out[6]);
        yes("motion null struct", !X50Structs.motion(new int[] {0}, 1, out));
        yes("motion cut short", !X50Structs.motion(m, 9, out));
        int[] m2 = m.clone();
        m2[2] = 2;
        yes("motion with 2 acc values refused", !X50Structs.motion(m2, m2.length, out));
        int[] m3 = m.clone();
        m3[1] = 0x26;
        yes("motion size not whole words refused", !X50Structs.motion(m3, m3.length, out));
        int[] m4 = m.clone();
        m4[1] = 0x24;                                   // 9 field words: no room for the byte, still both arrays
        yes("motion without the type byte", X50Structs.motion(m4, m4.length, out) && out[6] == 0);

        // DISPLAY 57: bool 1, flag 1, size 0x34, 7 ints, then stDlpSupportTiming (flag 1, size 0x10, 3 ints)
        int[] o = words("00000001 00000001 00000034 00000006 00000f00 00000870 00000006 00000f00 00000870 0000003c "
                + "00000001 00000010 00000006 00000004 00000007");
        yes("output timing decodes", X50Structs.outputTiming(o, o.length, out));
        eq("output hres", 3840, out[4]);
        eq("output vres", 2160, out[5]);
        eq("output Hz", 60, out[6]);
        eq("user timing idx", 6, out[0]);
        yes("output plausible", X50Structs.outputPlausible(out));
        int[] o2 = o.clone();
        o2[0] = 0;
        yes("output: the vendor said false", !X50Structs.outputTiming(o2, o2.length, out));
        int[] o3 = o.clone();
        o3[1] = 0;
        yes("output: null struct", !X50Structs.outputTiming(o3, o3.length, out));
        yes("output: reply shorter than the size", !X50Structs.outputTiming(o, 10, out));

        // TV 38 (HDMI 2, 4K60): flag 1, size 0x18, width, height, interlace, hdmi, rate x100
        int[] h = words("00000001 00000018 00000f00 00000870 00000000 00000001 00001770");
        yes("hdmi decodes", X50Structs.hdmiInfo(h, h.length, out));
        eq("hdmi width", 3840, out[0]);
        eq("hdmi height", 2160, out[1]);
        eq("hdmi progressive", 0, out[2]);
        eq("hdmi mode", 1, out[3]);
        eq("hdmi rate", 6000, out[4]);
        yes("hdmi plausible", X50Structs.hdmiPlausible(out));
        int[] h0 = words("00000001 00000018 00000000 00000000 00000000 00000000 00000000");
        yes("hdmi no signal decodes", X50Structs.hdmiInfo(h0, h0.length, out));
        yes("hdmi no signal is not plausible", !X50Structs.hdmiPlausible(out));
        yes("hdmi null", !X50Structs.hdmiInfo(new int[] {0}, 1, out));

        // TV 122: bool 1, flag 1, size 0x18, width, height, rate x100, hdmiOrDvi, type
        int[] v = words("00000001 00000001 00000018 00000f00 00000870 00001770 00000000 00000000");
        yes("video decodes", X50Structs.videoInfo(v, v.length, out));
        eq("video width", 3840, out[0]);
        eq("video rate", 6000, out[2]);
        yes("video plausible", X50Structs.videoPlausible(out));
        yes("video: false", !X50Structs.videoInfo(words("00000000 00000000"), 2, out));
    }

    private static void lightUsage() {
        // DISPLAY 176 on the X50 2026-11-01 (String16 of 139 characters)
        String json = "{\"current_light\":0,\"error_code\":0,\"light_list\":[{\"light_id\":0,\"usage_time\":0},"
                + "{\"light_id\":1,\"usage_time\":0},{\"light_id\":2,\"usage_time\":0}]}";
        eq("the survey reply is 139 characters", 139, json.length());
        int[] ids = new int[X50Structs.LIGHTS_MAX];
        long[] t = new long[X50Structs.LIGHTS_MAX];
        eq("three lasers", 3, X50Structs.lightUsage(json, ids, t));
        eq("third id", 2, ids[2]);
        eq("hours 0", 0L, t[1]);
        yes("0 hours hides the row", Double.isNaN(X50Structs.lightHours(t[0])));
        String used = "{\"light_list\":[{\"light_id\":0,\"usage_time\":1234},{\"usage_time\":56,\"light_id\":1},"
                + "{\"light_id\":2}]}";
        eq("three entries", 3, X50Structs.lightUsage(used, ids, t));
        eq("hours 1234", 1234L, t[0]);
        eq("key order does not matter", 56L, t[1]);
        eq("no usage_time", -1L, t[2]);
        near("1234 h", 1234, X50Structs.lightHours(t[0]), 0);
        yes("missing usage hides the row", Double.isNaN(X50Structs.lightHours(t[2])));
        yes("too many hours", Double.isNaN(X50Structs.lightHours(100_001)));
        eq("no light_list", -1, X50Structs.lightUsage("{\"error_code\":1}", ids, t));
        eq("null text", -1, X50Structs.lightUsage(null, ids, t));
        eq("empty list", 0, X50Structs.lightUsage("{\"light_list\":[]}", ids, t));
        int[] two = new int[2];
        long[] twoT = new long[2];
        eq("capped at the array", 2, X50Structs.lightUsage(json, two, twoT));
    }

    private static void units() {
        near("z up, survey value", 1.0081, X50Structs.accelZUpG(-4129), 1e-4);
        near("z up, the other survey value", 1.011, X50Structs.accelZUpG(-4141), 1e-3);
        near("x in g", 10 / 4096.0, X50Structs.accelG(10), 1e-12);
        near("lying flat is about 0 degrees", 0, X50Structs.tiltDegrees(10, -7, -4129), 0.2);
        near("tilted 30 degrees", 30, X50Structs.tiltDegrees(0, 2048, -3547), 0.1);
        yes("no gravity, no tilt", Double.isNaN(X50Structs.tiltDegrees(0, 0, 10)));
        yes("fan mode 14 is unknown (raw code shown)", !X50Structs.fanModeKnown(14));
        yes("fan mode 10 auto is known", X50Structs.fanModeKnown(10));
        yes("fan mode 0 is known", X50Structs.fanModeKnown(0));
        yes("light mode 24 is unknown (raw code shown)", !X50Structs.lightModeKnown(24));
        yes("light mode 16 D65", X50Structs.lightModeKnown(16));
        eq("SDR", "SDR", X50Structs.hdrName(0));
        eq("Dolby Vision", "Dolby Vision", X50Structs.hdrName(2));
        eq("HDR 3 unknown", null, X50Structs.hdrName(3));
    }

    private static void profile() {
        yes("X50 by both model properties", DeviceProfile.isX50("", "G0111", "G0111"));
        yes("X50 by the boot model alone", DeviceProfile.isX50(null, " G0111 ", ""));
        yes("ro.lumen.device set: not the stock X50", !DeviceProfile.isX50("x50", "G0111", "G0111"));
        yes("ro.xgimi.modelname disagrees", !DeviceProfile.isX50("", "G0111", "G0082"));
        yes("only ro.xgimi.modelname: no", !DeviceProfile.isX50("", "", "G0111"));
        yes("the Z9X is not an X50", !DeviceProfile.isX50("", "G0082", "G0082"));
        yes("the HB is not an X50", !DeviceProfile.isX50("", "G0130", "G0130"));
        yes("nothing set", !DeviceProfile.isX50(null, null, null));
        eq("the X50 is still GENERIC for the Z9X decision", DeviceProfile.GENERIC,
                DeviceProfile.decide("", "mt9681", "mt9681", "G0111", "G0111"));
        eq("the Z9X decision unchanged", DeviceProfile.Z9X, DeviceProfile.decide("", "mt9952", "mt9952", "G0082", "G0082"));
    }

    // ------------------------------------------------------------------ read plan
    private static final String T = "TemperatureManager", D = "DisplayManager", V = "GmTvManager";

    private static void schedule() {
        eq("probe gap", 200L, X50Schedule.gapMs(true));
        eq("steady gap", 500L, X50Schedule.gapMs(false));
        near("2 calls per second at most", 2.0, X50Schedule.maxSteadyPerSecond(), 1e-12);
        eq("round at refresh 2 s", 4_000L, X50Schedule.periodMs(X50Schedule.P_ROUND, 2_000));
        eq("round at refresh 5 s", 5_000L, X50Schedule.periodMs(X50Schedule.P_ROUND, 5_000));
        eq("slow", 10_000L, X50Schedule.periodMs(X50Schedule.P_SLOW, 1_000));
        eq("failed once-read retry", 10_000L, X50Schedule.periodMs(X50Schedule.P_ONCE, 1_000));
        eq("on time keeps the beat", 14_000L, X50Schedule.nextDue(10_000, 10_005, 4_000));
        eq("late: a period after the call", 17_005L, X50Schedule.nextDue(10_000, 13_005, 4_000));
        eq("gate is a round read", X50Schedule.P_ROUND, X50Schedule.kindOf(D, 28));
        eq("sensors round", X50Schedule.P_ROUND, X50Schedule.kindOf(T, 7));
        eq("fans round", X50Schedule.P_ROUND, X50Schedule.kindOf(T, 5));
        eq("overheat slow", X50Schedule.P_SLOW, X50Schedule.kindOf(T, 12));
        eq("light usage once", X50Schedule.P_ONCE, X50Schedule.kindOf(D, 176));
        eq("motion slow", X50Schedule.P_SLOW, X50Schedule.kindOf("MotionDetectionManager", 2));
        eq("ToF not planned", -1, X50Schedule.kindOf("ProjectorFocusManager", 46));
        eq("ambient light not planned", -1, X50Schedule.kindOf("ProjectorFocusManager", 50));
        eq("laser currents not planned", -1, X50Schedule.kindOf("GmFactoryManager", 128));
        eq("extendCmd not planned", -1, X50Schedule.kindOf(T, 9));
        eq("TV 4 is not DISPLAY 4", -1, X50Schedule.kindOf(D, 4));
        eq("HDMI 2", X50Schedule.INPUT_HDMI2, X50Schedule.input(2));
        eq("odd input", X50Schedule.INPUT_UNKNOWN, X50Schedule.input(9));
        yes("HDMI reads TV 38", X50Schedule.readsHdmiInfo(1) && !X50Schedule.readsVideoInfo(1));
        yes("media reads TV 122", X50Schedule.readsVideoInfo(0) && !X50Schedule.readsHdmiInfo(0));
        yes("unknown reads neither", !X50Schedule.readsHdmiInfo(-1) && !X50Schedule.readsVideoInfo(-1));

        // asked calls per second, refresh 2 s, 7 sensors, 2 fans, HDMI input (the class comment's sums)
        double compact = per(D, 28) + 3 * per(T, 7) + per(T, 5);
        near("compact 1.25 per second", 1.25, compact, 1e-9);
        double graphs = per(D, 28) + per(T, 6) + 7 * per(T, 7);
        near("graphs 2.25 per second asked (the gap caps it at 2)", 2.25, graphs, 1e-9);
        double detailed = per(D, 28) + per(T, 6) + 7 * per(T, 7) + per(T, 3) + 2 * per(T, 5)
                + per(T, 12) + per(D, 7) + per(D, 5) + per(D, 152) + per(D, 178) + per(V, 4) + per(V, 78) + per(V, 38)
                + per(D, 57) + per(D, 179) + per("MotionDetectionManager", 2) + per("ProjectorFocusManager", 59);
        near("detailed 4.2 per second asked", 4.2, detailed, 1e-9);
        yes("the steady gap keeps every mode at 2 per second or less", X50Schedule.maxSteadyPerSecond() <= 2.0);
    }

    private static double per(String svc, int code) {
        return X50Schedule.perSecond(svc, code, 2_000);
    }

    // ------------------------------------------------------------------ allow list
    private static List<String[]> tsv(Path p) throws Exception {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;
            rows.add(line.split("\t", -1));
        }
        return rows;
    }

    private static void allowList(Path src, Path devices) throws Exception {
        String client = new String(Files.readAllBytes(src.resolve("org/lumen/sensors/hal/X50Gmpf.java")),
                StandardCharsets.UTF_8);
        // Iface NAME("xgimi.hardware.gmpf.IXxx") -> service Xxx
        Map<String, String> ifaces = new HashMap<>();
        Matcher im = Pattern.compile("\\b([A-Z]+)\\(\"xgimi\\.hardware\\.gmpf\\.I(\\w+)\"\\)").matcher(client);
        while (im.find()) ifaces.put(im.group(1), im.group(2));
        eq("five interfaces", 5, ifaces.size());
        Set<String> calls = new HashSet<>();
        Matcher cm = Pattern.compile("\\b[A-Z_]+\\((null|Iface\\.([A-Z]+)), (0x[0-9A-Fa-f]+|\\d+), \"(\\w+)\"\\)").matcher(client);
        while (cm.find()) {
            String svc = cm.group(2) == null ? "-" : ifaces.get(cm.group(2));
            calls.add(svc + "\t" + Integer.decode(cm.group(3)) + "\t" + cm.group(4));
        }
        eq("21 calls", 21, calls.size());

        Path research = devices.resolve("g0111/research");
        List<String[]> allowed = tsv(research.resolve("allowed_lumensensors.tsv"));
        Set<String> rows = new HashSet<>();
        Map<String, String> sig = new HashMap<>();
        for (String[] r : allowed) {
            yes("allowed row has 5 columns: " + String.join(" ", r), r.length == 5);
            String key = r[0] + "\t" + Integer.decode(r[1]) + "\t" + r[2];
            rows.add(key);
            sig.put(key, r[3]);
            yes("allowed row cites a source: " + r[2], r[4].length() > 20);
        }
        eq("Call enum = allowed_lumensensors.tsv", new java.util.TreeSet<>(rows), new java.util.TreeSet<>(calls));

        Map<String, String> table = new HashMap<>();
        for (String[] r : tsv(research.resolve("aidl_codes.tsv"))) {
            if (r.length >= 4) table.put(r[0] + "\t" + r[1] + "\t" + r[2], r[3]);
        }
        Set<String> planned = new HashSet<>();
        for (String p : X50Schedule.planned()) planned.add(p);
        for (String key : rows) {
            String[] k = key.split("\t");
            if (k[0].equals("-")) continue;
            eq("in aidl_codes.tsv with the same signature: " + key, sig.get(key), table.get(key));
            yes("planned: " + key, planned.contains(k[0] + "\t" + k[1]));
            String s = sig.get(key);
            yes("getter shape: " + key, s != null && !s.startsWith("void")
                    && (s.endsWith("()") || s.endsWith("(byte)") || s.matches("boolean\\(\\w+\\)")));
            yes("getter name: " + key, k[2].startsWith("get") || k[2].startsWith("is"));
        }
        eq("every planned read is a call", planned.size(), rows.size() - 2);
        for (String[] r : tsv(devices.resolve("goat_elegant/research/banned.tsv"))) {
            if (r.length < 5 || !(r[4].equals("BANNED") || r[4].equals("REVIEW"))) continue;
            for (String key : rows) {
                String[] k = key.split("\t");
                if (k[0].equals(r[0]) && (k[1].equals(r[1]) || k[2].equals(r[2]))) {
                    failed++;
                    System.out.println("FAIL banned on the HB: " + key);
                }
            }
        }
        passed++;
        for (String[] gone : new String[][] {{"GmFactoryManager", "128"}, {"ProjectorFocusManager", "50"},
                {"ProjectorFocusManager", "46"}, {"PowerManager", "20"}, {"TemperatureManager", "9"},
                {"TemperatureManager", "2"}, {"TemperatureManager", "4"}}) {
            for (String key : rows) {
                if (key.startsWith(gone[0] + "\t" + gone[1] + "\t")) {
                    failed++;
                    System.out.println("FAIL excluded call in the allow list: " + key);
                }
            }
        }
        passed++;
        yes("client guards the X50 hash", client.contains("\"715affd032170b4d256c28c9bbc3c6cbaf5de492\""));
        yes("client: checkService only", client.contains("ServiceManager.checkService(") && !client.contains("getService("));
        yes("client: main thread refused", client.contains("vendor call on the main thread refused"));
        yes("client: sensor index 1..12", client.contains("if (index < 1 || index > 12)"));
        yes("client: fan 0 / 1", client.contains("if (fan != 0 && fan != 1)"));
    }

    private static void source(Path src) throws Exception {
        String s = new String(Files.readAllBytes(src.resolve("org/lumen/sensors/x50/X50HalSource.java")),
                StandardCharsets.UTF_8);
        String hub = new String(Files.readAllBytes(src.resolve("org/lumen/sensors/Hub.java")), StandardCharsets.UTF_8);
        int tasks = count(s, "new OneTask(\"") + count(s, "new Task(\"") + count(s, "super(\"");
        Matcher named = Pattern.compile("(new OneTask|new Task|super)\\(\"[^\"]*\"( \\+ \\w+)?, Call\\.[A-Z_]+,").matcher(s);
        int withCall = 0;
        while (named.find()) withCall++;
        yes("tasks found (" + tasks + ")", tasks >= 20);
        eq("every task names its call", tasks, withCall);
        yes("tasks take the period of their call", s.contains("X50Schedule.kindOf(call.service(), call.code)"));
        yes("light gate first", s.contains("if (lampOff || now - screenCheckedAt > periodOf(X50Schedule.P_ROUND)"));
        yes("steady pace", s.contains("lastCall + X50Schedule.gapMs(!best.probed)"));
        yes("late reads a period after the call", s.contains("t.due = X50Schedule.nextDue(t.due, end, t.period());"));
        yes("no call in the first minute", s.contains("BOOT_SETTLE_MS = 60_000"));
        yes("TV 38 only on HDMI", s.contains("needed() { return X50Schedule.readsHdmiInfo(inputSource); }"));
        yes("TV 122 only on media", s.contains("needed() { return X50Schedule.readsVideoInfo(inputSource); }"));
        yes("z axis turned", s.contains("X50Structs.accelZUpG(st[2])"));
        yes("unknown codes raw", count(s, ": rawCode(v)") == 2);
        yes("three lasers labelled", s.contains("R.string.s_laser_r)") && s.contains("R.string.s_laser_g)")
                && s.contains("R.string.s_laser_r2)"));
        eq("no LED labels on the X50", 0, count(s, "R.string.s_led"));
        yes("raw light usage logged", s.contains("Log.i(TAG, \"DISPLAY 176 light usage"));
        yes("Hub asks the X50 profile only when not a Z9X", hub.contains("boolean x50 = !z9x && X50Profile.isX50();"));
        yes("Hub: SoC zone of the X50", hub.contains("(x50 ? X50Profile.SOC_ZONE : -1)"));
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }
}
