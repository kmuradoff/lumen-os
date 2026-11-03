package org.lumen.info.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text parsers for what the app reads (pure Java, host tests): service dumps (audio_flinger, resource_manager,
 * media.metrics, SurfaceFlinger --hwclayers), /proc files and the vendor ini. Every parser is lenient: a line it
 * does not know is skipped, a missing value stays null / -1, nothing throws on odd input.
 */
public final class Parse {
    private Parse() { }

    // ================================================================ audio_flinger
    public static final class AudioThread {
        public String name = "", type = "";      // type: MIXER, DIRECT, OFFLOAD, DUPLICATING, ...
        public boolean standby = true;
        public int sampleRate = -1, channels = -1;
        public String halFormat = "", devices = "", flags = "";

        /** Not PCM on a DIRECT output: the encoded stream goes out as it is (passthrough). */
        public boolean passthrough() {
            return !halFormat.isEmpty() && !halFormat.startsWith("PCM") && !"OFFLOAD".equals(type);
        }
    }

    private static final Pattern THREAD = Pattern.compile("^Output thread 0x[0-9a-f]+, name ([^,]+), tid \\d+, type \\d+ \\(([A-Z_]+)\\)");
    private static final Pattern PAREN = Pattern.compile("\\(([^)]*)\\)");

    /** Output threads of `dumpsys media.audio_flinger`. */
    public static List<AudioThread> audioThreads(String dump) {
        List<AudioThread> out = new ArrayList<>();
        if (dump == null) return out;
        AudioThread cur = null;
        for (String line : dump.split("\n")) {
            Matcher m = THREAD.matcher(line);
            if (m.find()) {
                cur = new AudioThread();
                cur.name = m.group(1).trim();
                cur.type = m.group(2);
                out.add(cur);
                continue;
            }
            if (!line.startsWith(" ")) {
                cur = null;
                continue;
            }
            if (cur == null) continue;
            String t = line.trim();
            if (t.startsWith("Standby:")) cur.standby = t.endsWith("yes");
            else if (t.startsWith("Sample rate:")) cur.sampleRate = firstInt(t);
            else if (t.startsWith("Channel count:")) cur.channels = firstInt(t);
            else if (t.startsWith("HAL format:")) cur.halFormat = stripPrefix(inParens(t), "AUDIO_FORMAT_");
            else if (t.startsWith("Output devices:")) cur.devices = devices(inParens(t));
            else if (t.startsWith("AudioStreamOut:")) cur.flags = stripPrefix(inParens(t), "AUDIO_OUTPUT_FLAG_");
        }
        return out;
    }

    /** The output that plays now (not in standby), DIRECT / OFFLOAD first; else null. */
    public static AudioThread activeAudio(List<AudioThread> threads) {
        AudioThread best = null;
        for (AudioThread a : threads) {
            if (a.standby) continue;
            if (best == null || ("MIXER".equals(best.type) && !"MIXER".equals(a.type))) best = a;
        }
        return best;
    }

    private static String devices(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (String p : s.split("[|, ]+")) {
            if (p.isEmpty()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(stripPrefix(p, "AUDIO_DEVICE_OUT_"));
        }
        return b.toString();
    }

    // ================================================================ resource_manager
    public static final class CodecClient {
        public int pid = -1;
        public String name = "";
        public boolean video, hardware, secure;
    }

    /**
     * Codec clients of `dumpsys media.resource_manager`: the Processes section (Pid, then per client Name and
     * Resources) when it lists clients; else hardware video codecs added and not yet removed in the event log
     * (name unknown). Video clients only.
     */
    public static List<CodecClient> videoCodecs(String dump) {
        List<CodecClient> out = new ArrayList<>();
        if (dump == null) return out;
        String[] lines = dump.split("\n");
        int pid = -1;
        boolean inProcesses = false;
        CodecClient cur = null;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("Processes:")) { inProcesses = true; continue; }
            if (t.startsWith("Process Pid override") || t.startsWith("Events logs")) { inProcesses = false; continue; }
            if (!inProcesses) continue;
            if (t.startsWith("Pid:")) { pid = firstInt(t); cur = null; continue; }
            if (t.startsWith("Client:")) { cur = new CodecClient(); cur.pid = pid; continue; }
            if (cur == null) continue;
            if (t.startsWith("Name:")) cur.name = t.substring(5).trim();
            if (t.contains("video-codec")) {
                cur.video = true;
                if (t.contains("hw-video-codec")) cur.hardware = true;
                if (t.contains("secure-codec") && !t.contains("non-secure-codec")) cur.secure = true;
                if (!out.contains(cur)) out.add(cur);
            }
        }
        if (!out.isEmpty()) return out;
        // event log, newest first: walk from the oldest
        Map<String, CodecClient> live = new LinkedHashMap<>();
        Pattern add = Pattern.compile("addResource\\(pid (\\d+), uid \\d+ clientId (\\d+), resources (.*)\\)");
        Pattern rem = Pattern.compile("removeResource\\(pid \\d+, uid \\d+ clientId (\\d+)\\)");
        boolean inEvents = false;
        List<String> events = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().startsWith("Events logs")) { inEvents = true; continue; }
            if (inEvents) events.add(line);
        }
        for (int i = events.size() - 1; i >= 0; i--) {
            String l = events.get(i);
            Matcher a = add.matcher(l);
            if (a.find()) {
                String res = a.group(3);
                if (!res.contains("video-codec")) continue;
                CodecClient c = live.get(a.group(2));
                if (c == null) {
                    c = new CodecClient();
                    c.pid = Integer.parseInt(a.group(1));
                    live.put(a.group(2), c);
                }
                c.video = true;
                if (res.contains("hw-video-codec")) c.hardware = true;
                if (res.contains("secure-codec/") && !res.contains("non-secure-codec/")) c.secure = true;
                continue;
            }
            Matcher r = rem.matcher(l);
            if (r.find()) live.remove(r.group(1));
        }
        out.addAll(live.values());
        return out;
    }

    // ================================================================ media.metrics
    public static final class CodecRecord {
        public String codec = "", mime = "", time = "";
        public int width = -1, height = -1;
        public double frameRate = -1;
        public long bitrate = -1;
        public boolean encoder;
    }

    private static final Pattern KV_COLON = Pattern.compile("([a-z_]+):(\\S*)");

    /**
     * The newest video decoder record of `dumpsys media.metrics --since -N`: statsd atom lines
     * "mediametrics_codec_reported" (key:value) or records "{android.media.mediacodec, ...}" (key=value).
     * MediaCodec reports when a codec is released, so this is the last finished or reset session.
     */
    public static CodecRecord lastVideoDecoder(String dump) {
        if (dump == null) return null;
        CodecRecord best = null;
        for (String line : dump.split("\n")) {
            CodecRecord r = null;
            if (line.contains("mediametrics_codec_reported")) {
                Map<String, String> kv = new HashMap<>();
                Matcher m = KV_COLON.matcher(line);
                while (m.find()) kv.put(m.group(1), m.group(2));
                r = new CodecRecord();
                r.codec = str(kv.get("codec"));
                r.mime = str(kv.get("mime"));
                r.width = toInt(kv.get("width"));
                r.height = toInt(kv.get("height"));
                r.frameRate = toDouble(kv.get("frame_rate"));
                r.bitrate = toLong(kv.get("bitrate"));
                r.encoder = "1".equals(kv.get("encoder")) || "true".equals(kv.get("encoder"));
                r.time = line.trim().length() >= 18 ? line.trim().substring(0, 18) : "";
            } else if (line.contains("{android.media.mediacodec")) {
                Map<String, String> kv = new HashMap<>();
                Matcher p = PAREN.matcher(line);
                String last = null;
                List<String> groups = new ArrayList<>();
                while (p.find()) groups.add(p.group(1));
                if (!groups.isEmpty()) last = groups.get(groups.size() - 1);
                if (last != null) {
                    for (String part : last.split(", ")) {
                        int eq = part.indexOf('=');
                        if (eq <= 0) continue;
                        String k = part.substring(0, eq);
                        int dot = k.lastIndexOf('.');
                        kv.put(dot >= 0 ? k.substring(dot + 1) : k, part.substring(eq + 1));
                    }
                }
                r = new CodecRecord();
                r.codec = str(kv.get("codec"));
                r.mime = str(kv.get("mime"));
                r.width = toInt(kv.get("width"));
                r.height = toInt(kv.get("height"));
                r.frameRate = toDouble(kv.get("frame-rate"));
                r.bitrate = toLong(kv.get("bitrate"));
                r.encoder = "1".equals(kv.get("encoder"));
                if (groups.size() >= 1) r.time = groups.get(0);
            }
            if (r == null || r.encoder || !r.mime.startsWith("video/") || r.codec.isEmpty()) continue;
            best = r;   // records are oldest first
        }
        return best;
    }

    // ================================================================ SurfaceFlinger --hwclayers
    public static final class HwcLayer {
        public String name = "", composition = "";
        public int left, top, right, bottom;
        public double frameRate = -1;   // the app's own frame rate vote on the layer, -1 none
        public boolean focused;
    }

    /** Layers of `dumpsys SurfaceFlinger --hwclayers` (name line, then the row of values). */
    public static List<HwcLayer> hwcLayers(String dump) {
        List<HwcLayer> out = new ArrayList<>();
        if (dump == null) return out;
        String pendingName = null;
        Pattern hz = Pattern.compile("([0-9]+(?:\\.[0-9]+)?) Hz");
        for (String line : dump.split("\n")) {
            if (line.startsWith("---") || line.contains("Layer name") || line.contains("Comp Type")
                    || line.startsWith("Display ")) continue;
            if (line.contains("|")) {
                if (pendingName == null) continue;
                String[] c = line.split("\\|");
                if (c.length < 5) continue;
                HwcLayer l = new HwcLayer();
                l.name = pendingName;
                l.composition = c[2].trim();
                int[] f = ints(c[4]);
                if (f.length >= 4) {
                    l.left = f[0]; l.top = f[1]; l.right = f[2]; l.bottom = f[3];
                }
                if (c.length >= 7) {
                    Matcher m = hz.matcher(c[6]);
                    if (m.find()) l.frameRate = Double.parseDouble(m.group(1));
                    l.focused = c[6].contains("[*]");
                }
                out.add(l);
                pendingName = null;
            } else if (!line.trim().isEmpty()) {
                pendingName = line.trim();
            }
        }
        return out;
    }

    /** The video layer: SurfaceView or a sideband / video composition, the largest one. */
    public static HwcLayer videoLayer(List<HwcLayer> layers) {
        HwcLayer best = null;
        long bestArea = -1;
        for (HwcLayer l : layers) {
            boolean video = l.name.startsWith("SurfaceView") || "SIDEBAND".equalsIgnoreCase(l.composition);
            if (!video) continue;
            long a = (long) (l.right - l.left) * (l.bottom - l.top);
            if (a > bestArea) { best = l; bestArea = a; }
        }
        return best;
    }

    /** Package of a layer name "SurfaceView[org.pkg/org.pkg.Act](BLAST)#12"; "" if none. */
    public static String layerPackage(String name) {
        int a = name.indexOf('['), s = name.indexOf('/');
        if (a < 0 || s < a) return "";
        return name.substring(a + 1, s);
    }

    // ================================================================ /proc
    /** /proc/stat first "cpu" line: {busy, total} ticks, null if absent. */
    public static long[] procStatCpu(String text) {
        if (text == null) return null;
        for (String line : text.split("\n")) {
            if (!line.startsWith("cpu ")) continue;
            String[] p = line.trim().split("\\s+");
            long total = 0, idle = 0;
            for (int i = 1; i < p.length && i <= 8; i++) {
                long v = toLong(p[i]);
                if (v < 0) continue;
                total += v;
                if (i == 4 || i == 5) idle += v;   // idle, iowait
            }
            return new long[] {total - idle, total};
        }
        return null;
    }

    public static double loadPercent(long[] prev, long[] now) {
        if (prev == null || now == null) return Double.NaN;
        long dt = now[1] - prev[1], db = now[0] - prev[0];
        if (dt <= 0) return Double.NaN;
        return Math.max(0, Math.min(100, db * 100.0 / dt));
    }

    /** /proc/meminfo value in kB, -1 if absent. */
    public static long meminfo(String text, String key) {
        if (text == null) return -1;
        for (String line : text.split("\n")) {
            if (line.startsWith(key + ":")) return toLong(line.substring(key.length() + 1).trim().split("\\s+")[0]);
        }
        return -1;
    }

    // ================================================================ vendor ini
    /** Sections of an ini file: [name] -> key -> value (quotes, trailing ';' and '#' comments removed). */
    public static Map<String, Map<String, String>> ini(String text) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (text == null) return out;
        Map<String, String> cur = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[") && line.contains("]")) {
                cur = new LinkedHashMap<>();
                out.put(line.substring(1, line.indexOf(']')).trim(), cur);
                continue;
            }
            if (cur == null) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1);
            int hash = v.indexOf('#');
            if (hash >= 0 && !inQuotes(v, hash)) v = v.substring(0, hash);
            v = v.trim();
            if (v.endsWith(";")) v = v.substring(0, v.length() - 1).trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
            cur.put(k, v);
        }
        return out;
    }

    private static boolean inQuotes(String s, int pos) {
        int q = 0;
        for (int i = 0; i < pos; i++) if (s.charAt(i) == '"') q++;
        return q % 2 == 1;
    }

    // ================================================================ helpers
    static String inParens(String s) {
        int a = s.indexOf('('), b = s.lastIndexOf(')');
        return a >= 0 && b > a ? s.substring(a + 1, b) : "";
    }

    static String stripPrefix(String s, String prefix) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (String p : s.split("\\|")) {
            p = p.trim();
            if (p.startsWith(prefix)) p = p.substring(prefix.length());
            if (b.length() > 0) b.append('|');
            b.append(p);
        }
        return b.toString();
    }

    public static int firstInt(String s) {
        Matcher m = Pattern.compile("-?\\d+").matcher(s);
        return m.find() ? Integer.parseInt(m.group()) : -1;
    }

    static int[] ints(String s) {
        Matcher m = Pattern.compile("-?\\d+").matcher(s);
        List<Integer> l = new ArrayList<>();
        while (m.find()) l.add(Integer.parseInt(m.group()));
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    static String str(String s) {
        return s == null ? "" : s;
    }

    public static int toInt(String s) {
        try { return s == null ? -1 : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    public static long toLong(String s) {
        try { return s == null ? -1 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    static double toDouble(String s) {
        try { return s == null ? -1 : Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    /** "video/hevc" -> "HEVC", "video/x-vnd.on2.vp9" -> "VP9", "video/avc" -> "AVC (H.264)". */
    public static String mimeName(String mime) {
        if (mime == null || mime.isEmpty()) return "";
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.equals("video/hevc")) return "HEVC (H.265)";
        if (m.equals("video/avc")) return "AVC (H.264)";
        if (m.endsWith("vp9")) return "VP9";
        if (m.endsWith("vp8")) return "VP8";
        if (m.endsWith("av01")) return "AV1";
        if (m.equals("video/dolby-vision")) return "Dolby Vision";
        if (m.equals("video/mpeg2")) return "MPEG-2";
        if (m.equals("video/mp4v-es")) return "MPEG-4";
        return mime;
    }
}
