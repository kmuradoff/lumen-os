package org.lumen.info.core;

import java.util.ArrayList;
import java.util.List;

/**
 * EDID decoder: the VESA base block (1.3 / 1.4) and CTA-861 extension blocks (video, audio, speaker, vendor
 * specific HDMI 1.4 / HDMI Forum, colorimetry, HDR static and dynamic metadata, Dolby Vision and HDR10+ vendor
 * video blocks, YCbCr 4:2:0 blocks, detailed timings). Pure Java (host tests), read only: it decodes bytes it
 * is given and never builds or writes an EDID. {@link #parse} never throws; a broken input gives
 * {@link #valid} false and an {@link #error}. Unknown blocks are kept as short notes in {@link #otherBlocks}.
 */
public final class Edid {
    // ---------------------------------------------------------------- model
    public static final class Timing {
        public int width, height, pixelClockKHz;
        public boolean interlaced;
        public double refreshHz;

        public String label() {
            return width + "x" + height + (interlaced ? "i" : "p") + Fmt.hz(refreshHz);
        }
    }

    public static final class Svd {
        public final int vic;
        public final boolean nativeFormat;

        Svd(int vic, boolean nativeFormat) {
            this.vic = vic;
            this.nativeFormat = nativeFormat;
        }
    }

    /** Short audio descriptor. */
    public static final class Sad {
        public int format, extFormat, channels, ratesMask, byte3;

        /** Kilohertz list, "32/44.1/48". */
        public String rates() {
            String[] r = {"32", "44.1", "48", "88.2", "96", "176.4", "192"};
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < r.length; i++) {
                if ((ratesMask & (1 << i)) == 0) continue;
                if (b.length() > 0) b.append('/');
                b.append(r[i]);
            }
            return b.toString();
        }

        /** LPCM: "16/20/24" bits. */
        public String lpcmBits() {
            StringBuilder b = new StringBuilder();
            if ((byte3 & 1) != 0) b.append("16");
            if ((byte3 & 2) != 0) b.append(b.length() > 0 ? "/" : "").append("20");
            if ((byte3 & 4) != 0) b.append(b.length() > 0 ? "/" : "").append("24");
            return b.toString();
        }

        /** AC-3, DTS and the other formats 2..8: maximum bit rate in kbit/s. */
        public int maxKbps() {
            return format >= 2 && format <= 8 ? byte3 * 8 : 0;
        }

        /** E-AC-3 with joint object coding (Dolby Atmos over DD+). */
        public boolean atmos() {
            return format == 10 && (byte3 & 1) != 0;
        }

        public String formatName() {
            return Edid.audioFormatName(format, extFormat);
        }
    }

    public static final class HdmiVsdb {
        public String physicalAddress = "";
        public int maxTmdsMHz;
        public boolean supportsAi, dc48, dc36, dc30, dcY444, dviDual, threeD;
        public int videoLatency = -1, audioLatency = -1;
        public final List<Integer> hdmiVics = new ArrayList<>();
    }

    /** HDMI Forum VSDB or SCDB (HDMI 2.0 / 2.1). */
    public static final class HdmiForum {
        public int version, maxTmdsMHz, maxFrl;
        public boolean scdc, rrCapable, scramble340, dc420_30, dc420_36, dc420_48, allm, fva, qms, cinemaVrr;
        public int vrrMin = -1, vrrMax = -1;
        public boolean dsc;
        public boolean scdb;   // came from the extended tag 0x79 block
    }

    public static final class HdrStatic {
        public boolean sdr, hdr, pq, hlg, type1;
        public double maxNits = Double.NaN, maxAvgNits = Double.NaN, minNits = Double.NaN;
    }

    public static final class DolbyVision {
        public int version = -1;
        public boolean p2160p60, yuv422_12, globalDimming;
        public int dmVersion = -1;
        /** Version 2 only: 0 low latency, 1 LL + LL over HDMI, 2 standard + LL, 3 standard + LL + LL over HDMI. */
        public int interfaceCode = -1;
        public int length;
    }

    public static final class VideoCap {
        public boolean qy, qs;
        public int pt, it, ce;
    }

    // ---------------------------------------------------------------- base block
    public final byte[] raw;
    public boolean valid;
    public String error;
    public int blockCount;
    public final List<Integer> badChecksumBlocks = new ArrayList<>();

    public String manufacturer = "";
    public int productCode;
    public long serial;
    public int week, year;
    public boolean modelYear;
    public int version, revision;
    public boolean digital;
    public int bitDepth;           // 0 unknown
    public int interfaceType;      // 1 DVI, 2 HDMI-a, 3 HDMI-b, 4 MDDI, 5 DisplayPort
    public int widthCm, heightCm;
    public String monitorName, serialText;
    public int rangeMinV = -1, rangeMaxV = -1, rangeMinH = -1, rangeMaxH = -1, rangeMaxClockMHz = -1;
    public final List<Timing> detailed = new ArrayList<>();
    public final List<String> established = new ArrayList<>();
    public final List<String> standard = new ArrayList<>();
    public int extensionCount;

    // ---------------------------------------------------------------- CTA-861
    public boolean hasCta;
    public int ctaRevision;
    public boolean underscan, basicAudio, ycc444, ycc422;
    public final List<Svd> svds = new ArrayList<>();
    public final List<Integer> ycc420Only = new ArrayList<>();
    public final List<Integer> ycc420Also = new ArrayList<>();
    public final List<Sad> audio = new ArrayList<>();
    public int speakers = -1;
    public HdmiVsdb hdmi;
    public HdmiForum forum;
    public HdrStatic hdrStatic;
    public final List<Integer> hdrDynamicTypes = new ArrayList<>();
    public DolbyVision dolby;
    public int hdr10PlusVersion = -1;
    public int colorimetry = -1;   // byte 1 | byte 2 << 8 of the colorimetry block
    public VideoCap videoCap;
    public final List<String> otherBlocks = new ArrayList<>();

    private Edid(byte[] raw) {
        this.raw = raw;
    }

    // ---------------------------------------------------------------- parse
    public static Edid parse(byte[] data) {
        Edid e = new Edid(data == null ? new byte[0] : data);
        try {
            e.decode();
        } catch (RuntimeException t) {
            e.valid = false;
            e.error = "decode: " + t;
        }
        return e;
    }

    private int u(int i) {
        return raw[i] & 0xff;
    }

    private void decode() {
        if (raw.length < 128) {
            error = "short: " + raw.length + " bytes";
            return;
        }
        int[] header = {0x00, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x00};
        for (int i = 0; i < 8; i++) {
            if (u(i) != header[i]) {
                error = allZero(0, 128) ? "empty (all zero)" : "no EDID header";
                return;
            }
        }
        valid = true;
        int v = (u(8) << 8) | u(9);
        manufacturer = "" + (char) ('@' + ((v >> 10) & 31)) + (char) ('@' + ((v >> 5) & 31)) + (char) ('@' + (v & 31));
        productCode = u(10) | (u(11) << 8);
        serial = (u(12) | (u(13) << 8) | (u(14) << 16) | ((long) u(15) << 24)) & 0xffffffffL;
        week = u(16);
        modelYear = week == 0xff;
        year = 1990 + u(17);
        version = u(18);
        revision = u(19);
        int in = u(20);
        digital = (in & 0x80) != 0;
        if (digital && (version > 1 || revision >= 4)) {
            int bd = (in >> 4) & 7;
            bitDepth = bd >= 1 && bd <= 6 ? 4 + 2 * bd : 0;
            interfaceType = in & 0x0f;
        }
        widthCm = u(21);
        heightCm = u(22);
        established();
        for (int i = 38; i < 54; i += 2) standardTiming(i);
        for (int d = 54; d < 126; d += 18) descriptor(d);
        extensionCount = u(126);

        blockCount = raw.length / 128;
        int wanted = 1 + extensionCount;
        // HDMI Forum EEODB (extended tag 0x78 in the first CTA block) overrides the base count
        if (blockCount > 1 && u(128) == 0x02) {
            int eeodb = findEeodb(128);
            if (eeodb > 0) wanted = 1 + eeodb;
        }
        blockCount = Math.min(blockCount, Math.max(1, wanted));
        for (int b = 0; b < blockCount; b++) {
            int sum = 0;
            for (int i = 0; i < 128; i++) sum += u(b * 128 + i);
            if ((sum & 0xff) != 0) badChecksumBlocks.add(b);
        }
        for (int b = 1; b < blockCount; b++) {
            int o = b * 128;
            if (u(o) == 0x02) cta(o);
            else if (u(o) == 0xf0) { /* block map: nothing to show */ }
            else otherBlocks.add("extension block " + b + " tag 0x" + hex2(u(o)));
        }
    }

    private boolean allZero(int from, int to) {
        for (int i = from; i < to && i < raw.length; i++) if (raw[i] != 0) return false;
        return true;
    }

    private void established() {
        String[] names = {
            "800x600@60", "800x600@56", "640x480@75", "640x480@72", "640x480@67", "640x480@60", "720x400@88",
            "720x400@70", "1280x1024@75", "1024x768@75", "1024x768@70", "1024x768@60", "1024x768@87i",
            "832x624@75", "800x600@75", "800x600@72", "", "", "", "", "", "", "", "1152x870@75"};
        int bits = (u(35) << 16) | (u(36) << 8) | u(37);
        for (int i = 0; i < 24; i++) {
            if ((bits & (1 << i)) != 0 && !names[i].isEmpty()) established.add(names[i]);
        }
    }

    private void standardTiming(int i) {
        int a = u(i), b = u(i + 1);
        if ((a == 1 && b == 1) || a == 0) return;
        int w = (a + 31) * 8;
        int ar = b >> 6;
        int h;
        switch (ar) {
            case 0: h = (version > 1 || revision >= 3) ? w * 10 / 16 : w; break;
            case 1: h = w * 3 / 4; break;
            case 2: h = w * 4 / 5; break;
            default: h = w * 9 / 16; break;
        }
        standard.add(w + "x" + h + "@" + ((b & 0x3f) + 60));
    }

    private void descriptor(int d) {
        if (u(d) != 0 || u(d + 1) != 0) {
            Timing t = dtd(d);
            if (t != null) detailed.add(t);
            return;
        }
        int tag = u(d + 3);
        switch (tag) {
            case 0xfc: monitorName = text(d + 5); break;
            case 0xff: serialText = text(d + 5); break;
            case 0xfd: {
                int f = u(d + 4);
                rangeMinV = u(d + 5) + ((f & 0x03) == 0x03 ? 255 : 0);
                rangeMaxV = u(d + 6) + ((f & 0x02) != 0 ? 255 : 0);
                rangeMinH = u(d + 7) + ((f & 0x0c) == 0x0c ? 255 : 0);
                rangeMaxH = u(d + 8) + ((f & 0x08) != 0 ? 255 : 0);
                rangeMaxClockMHz = u(d + 9) * 10;
                break;
            }
            default: break;
        }
    }

    private String text(int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < from + 13; i++) {
            int c = u(i);
            if (c == 0x0a || c == 0) break;
            b.append(c >= 0x20 && c < 0x7f ? (char) c : '?');
        }
        return b.toString().trim();
    }

    private Timing dtd(int d) {
        int clock = (u(d) | (u(d + 1) << 8)) * 10;   // kHz
        if (clock == 0) return null;
        Timing t = new Timing();
        t.pixelClockKHz = clock;
        t.width = u(d + 2) | ((u(d + 4) & 0xf0) << 4);
        int hBlank = u(d + 3) | ((u(d + 4) & 0x0f) << 8);
        t.height = u(d + 5) | ((u(d + 7) & 0xf0) << 4);
        int vBlank = u(d + 6) | ((u(d + 7) & 0x0f) << 8);
        t.interlaced = (u(d + 17) & 0x80) != 0;
        long total = (long) (t.width + hBlank) * (t.height + vBlank);
        t.refreshHz = total > 0 ? clock * 1000.0 / total : 0;
        if (t.interlaced) {
            t.height *= 2;
            t.refreshHz *= 2;
        }
        return t;
    }

    private int findEeodb(int o) {
        int end = Math.min(o + Math.max(4, u(o + 2)), o + 127);
        int p = o + 4;
        while (p < end) {
            int hdr = u(p), tag = hdr >> 5, len = hdr & 31;
            if (len == 0 && tag != 2) { p++; continue; }
            if (tag == 7 && len >= 2 && u(p + 1) == 0x78) return u(p + 2);
            p += 1 + len;
        }
        return -1;
    }

    // ---------------------------------------------------------------- CTA-861
    private void cta(int o) {
        hasCta = true;
        ctaRevision = u(o + 1);
        int dOff = u(o + 2);
        if (ctaRevision >= 2) {
            int f = u(o + 3);
            underscan |= (f & 0x80) != 0;
            basicAudio |= (f & 0x40) != 0;
            ycc444 |= (f & 0x20) != 0;
            ycc422 |= (f & 0x10) != 0;
        }
        int end = dOff == 0 ? o + 4 : Math.min(o + dOff, o + 127);
        int p = o + 4;
        if (ctaRevision >= 3) {
            while (p < end) {
                int hdr = u(p), tag = hdr >> 5, len = hdr & 31;
                if (p + 1 + len > end) {
                    otherBlocks.add("data block past the end at byte " + (p - o));
                    break;
                }
                dataBlock(tag, p + 1, len);
                p += 1 + len;
            }
        }
        if (dOff >= 4) {
            for (int d = o + dOff; d + 18 <= o + 127; d += 18) {
                if (u(d) == 0 && u(d + 1) == 0) break;
                Timing t = dtd(d);
                if (t != null) detailed.add(t);
            }
        }
    }

    private void dataBlock(int tag, int p, int len) {
        switch (tag) {
            case 1:
                for (int i = 0; i + 2 < len; i += 3) audio.add(sad(p + i));
                break;
            case 2:
                for (int i = 0; i < len; i++) {
                    int b = u(p + i);
                    if (b >= 129 && b <= 192) svds.add(new Svd(b - 128, true));
                    else if (b != 0 && b != 128 && b < 254) svds.add(new Svd(b, false));
                }
                break;
            case 3:
                vendor(p, len);
                break;
            case 4:
                if (len >= 1) speakers = u(p) | (len >= 2 ? u(p + 1) << 8 : 0) | (len >= 3 ? u(p + 2) << 16 : 0);
                break;
            case 7:
                if (len >= 1) extended(u(p), p + 1, len - 1);
                break;
            default:
                otherBlocks.add("data block tag " + tag + " (" + len + " bytes)");
                break;
        }
    }

    private Sad sad(int p) {
        Sad s = new Sad();
        int b0 = u(p);
        s.format = (b0 >> 3) & 0x0f;
        s.channels = (b0 & 7) + 1;
        s.ratesMask = u(p + 1) & 0x7f;
        s.byte3 = u(p + 2);
        if (s.format == 15) s.extFormat = s.byte3 >> 3;
        return s;
    }

    private static int oui(int a, int b, int c) {
        return a | (b << 8) | (c << 16);
    }

    private void vendor(int p, int len) {
        if (len < 3) return;
        int id = oui(u(p), u(p + 1), u(p + 2));
        if (id == 0x000c03) hdmiVsdb(p, len);
        else if (id == 0xc45dd8) hdmiForum(p + 3, len - 3, false);
        else otherBlocks.add("vendor block OUI " + hex6(id) + " (" + len + " bytes)");
    }

    private void hdmiVsdb(int p, int len) {
        HdmiVsdb h = new HdmiVsdb();
        hdmi = h;
        if (len >= 5) {
            h.physicalAddress = (u(p + 3) >> 4) + "." + (u(p + 3) & 15) + "." + (u(p + 4) >> 4) + "." + (u(p + 4) & 15);
        }
        if (len >= 6) {
            int f = u(p + 5);
            h.supportsAi = (f & 0x80) != 0;
            h.dc48 = (f & 0x40) != 0;
            h.dc36 = (f & 0x20) != 0;
            h.dc30 = (f & 0x10) != 0;
            h.dcY444 = (f & 0x08) != 0;
            h.dviDual = (f & 0x01) != 0;
        }
        if (len >= 7) h.maxTmdsMHz = u(p + 6) * 5;
        if (len < 8) return;
        int f8 = u(p + 7);
        int i = p + 8;
        int end = p + len;
        if ((f8 & 0x80) != 0 && i + 1 < end) {
            h.videoLatency = u(i);
            h.audioLatency = u(i + 1);
            i += 2;
        }
        if ((f8 & 0x40) != 0) i += 2;
        if ((f8 & 0x20) != 0 && i < end) {
            h.threeD = (u(i) & 0x80) != 0;
            i++;
            if (i < end) {
                int vicLen = u(i) >> 5;
                i++;
                for (int k = 0; k < vicLen && i < end; k++, i++) h.hdmiVics.add(u(i));
            }
        }
    }

    /** q: first byte after the OUI (Version), n: bytes from there. */
    private void hdmiForum(int q, int n, boolean scdb) {
        HdmiForum f = new HdmiForum();
        f.scdb = scdb;
        forum = f;
        if (n >= 1) f.version = u(q);
        if (n >= 2) f.maxTmdsMHz = u(q + 1) * 5;
        if (n >= 3) {
            int b = u(q + 2);
            f.scdc = (b & 0x80) != 0;
            f.rrCapable = (b & 0x40) != 0;
            f.scramble340 = (b & 0x08) != 0;
        }
        if (n >= 4) {
            int b = u(q + 3);
            f.maxFrl = b >> 4;
            f.dc420_48 = (b & 0x04) != 0;
            f.dc420_36 = (b & 0x02) != 0;
            f.dc420_30 = (b & 0x01) != 0;
        }
        if (n >= 5) {
            int b = u(q + 4);
            f.qms = (b & 0x40) != 0;
            f.cinemaVrr = (b & 0x10) != 0;
            f.fva = (b & 0x04) != 0;
            f.allm = (b & 0x02) != 0;
        }
        if (n >= 7) {
            f.vrrMin = u(q + 5) & 0x3f;
            f.vrrMax = ((u(q + 5) & 0xc0) << 2) | u(q + 6);
        }
        if (n >= 8) f.dsc = (u(q + 7) & 0x80) != 0;
    }

    /** ext: extended tag, p: first payload byte after it, n: payload length. */
    private void extended(int ext, int p, int n) {
        switch (ext) {
            case 0:
                if (n >= 1) {
                    VideoCap c = new VideoCap();
                    int b = u(p);
                    c.qy = (b & 0x80) != 0;
                    c.qs = (b & 0x40) != 0;
                    c.pt = (b >> 4) & 3;
                    c.it = (b >> 2) & 3;
                    c.ce = b & 3;
                    videoCap = c;
                }
                break;
            case 1:
                if (n >= 3) {
                    int id = oui(u(p), u(p + 1), u(p + 2));
                    if (id == 0x00d046) dolby(p + 3, n - 3);
                    else if (id == 0x90848b) hdr10PlusVersion = n >= 4 ? u(p + 3) & 3 : 0;
                    else otherBlocks.add("vendor video block OUI " + hex6(id));
                }
                break;
            case 5:
                if (n >= 1) colorimetry = u(p) | (n >= 2 ? u(p + 1) << 8 : 0);
                break;
            case 6: {
                HdrStatic h = new HdrStatic();
                if (n >= 1) {
                    int b = u(p);
                    h.sdr = (b & 1) != 0;
                    h.hdr = (b & 2) != 0;
                    h.pq = (b & 4) != 0;
                    h.hlg = (b & 8) != 0;
                }
                if (n >= 2) h.type1 = (u(p + 1) & 1) != 0;
                if (n >= 3 && u(p + 2) != 0) h.maxNits = 50.0 * Math.pow(2, u(p + 2) / 32.0);
                if (n >= 4 && u(p + 3) != 0) h.maxAvgNits = 50.0 * Math.pow(2, u(p + 3) / 32.0);
                if (n >= 5 && !Double.isNaN(h.maxNits)) {
                    double cv = u(p + 4) / 255.0;
                    h.minNits = h.maxNits * cv * cv / 100.0;
                }
                hdrStatic = h;
                break;
            }
            case 7: {
                int i = p, end = p + n;
                while (i + 2 < end) {
                    int l = u(i);
                    if (l < 2) break;
                    hdrDynamicTypes.add(u(i + 1) | (u(i + 2) << 8));
                    i += 1 + l;
                }
                break;
            }
            case 14:
                for (int i = 0; i < n; i++) {
                    int b = u(p + i);
                    ycc420Only.add(b >= 129 && b <= 192 ? b - 128 : b);
                }
                break;
            case 15:
                // bitmap over the SVDs in order; an empty map means every SVD also supports 4:2:0
                if (n == 0) {
                    for (Svd s : svds) ycc420Also.add(s.vic);
                } else {
                    for (int i = 0; i < n; i++) {
                        int b = u(p + i);
                        for (int k = 0; k < 8; k++) {
                            int idx = i * 8 + k;
                            if ((b & (1 << k)) != 0 && idx < svds.size()) ycc420Also.add(svds.get(idx).vic);
                        }
                    }
                }
                break;
            case 0x78:
                break;   // EEODB: counted in decode()
            case 0x79:
                if (n >= 2) hdmiForum(p + 2, n - 2, true);
                break;
            default:
                otherBlocks.add("extended block " + ext + " (" + n + " bytes)");
                break;
        }
    }

    private void dolby(int q, int n) {
        DolbyVision d = new DolbyVision();
        d.length = n + 5;
        dolby = d;
        if (n < 1) return;
        int b0 = u(q);
        d.version = (b0 >> 5) & 7;
        d.yuv422_12 = (b0 & 1) != 0;
        if (d.version == 0) {
            d.p2160p60 = (b0 & 2) != 0;
            d.globalDimming = (b0 & 4) != 0;
        } else if (d.version == 1) {
            d.p2160p60 = (b0 & 2) != 0;
            d.dmVersion = ((b0 >> 2) & 7) + 2;
            if (n >= 2) d.globalDimming = (u(q + 1) & 1) != 0;
        } else if (d.version == 2) {
            d.dmVersion = ((b0 >> 2) & 7) + 2;
            if (n >= 2) d.globalDimming = (u(q + 1) & 4) != 0;
            if (n >= 3) d.interfaceCode = u(q + 2) & 3;
        }
    }

    // ---------------------------------------------------------------- names (technical, not translated)
    public static String audioFormatName(int format, int ext) {
        switch (format) {
            case 1: return "LPCM";
            case 2: return "AC-3";
            case 3: return "MPEG-1";
            case 4: return "MP3";
            case 5: return "MPEG-2";
            case 6: return "AAC LC";
            case 7: return "DTS";
            case 8: return "ATRAC";
            case 9: return "DSD";
            case 10: return "E-AC-3";
            case 11: return "DTS-HD";
            case 12: return "MAT (TrueHD)";
            case 13: return "DST";
            case 14: return "WMA Pro";
            case 15:
                switch (ext) {
                    case 4: return "HE-AAC";
                    case 5: return "HE-AAC v2";
                    case 6: return "AAC LC (ext)";
                    case 7: return "DRA";
                    case 8: return "HE-AAC + MPEG Surround";
                    case 10: return "AAC LC + MPEG Surround";
                    case 11: return "MPEG-H 3D";
                    case 12: return "AC-4";
                    case 13: return "LPCM 3D";
                    default: return "ext " + ext;
                }
            default: return "format " + format;
        }
    }

    /** Speaker allocation block, "FL/FR LFE FC". */
    public String speakerNames() {
        if (speakers < 0) return "";
        String[] n = {"FL/FR", "LFE", "FC", "RL/RR", "RC", "FLC/FRC", "RLC/RRC", "FLW/FRW",
                      "TpFL/TpFR", "TpC", "TpFC", "LS/RS", "LFE2", "TpBC", "SiL/SiR", "TpSiL/TpSiR",
                      "TpBL/TpBR", "BtFC", "BtFL/BtFR", "TpLS/TpRS"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length; i++) {
            if ((speakers & (1 << i)) == 0) continue;
            if (b.length() > 0) b.append(' ');
            b.append(n[i]);
        }
        return b.toString();
    }

    /** Colorimetry block flags, "BT2020YCC BT2020RGB DCI-P3". */
    public String colorimetryNames() {
        if (colorimetry < 0) return "";
        String[] n = {"xvYCC601", "xvYCC709", "sYCC601", "opYCC601", "opRGB", "BT2020cYCC", "BT2020YCC", "BT2020RGB"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if ((colorimetry & (1 << i)) == 0) continue;
            if (b.length() > 0) b.append(' ');
            b.append(n[i]);
        }
        if ((colorimetry & 0x4000) != 0) b.append(b.length() > 0 ? " " : "").append("ICtCp");
        if ((colorimetry & 0x8000) != 0) b.append(b.length() > 0 ? " " : "").append("DCI-P3");
        return b.toString();
    }

    /** HDMI Forum Max_FRL_Rate code as text ("" for none). */
    public static String frlName(int code) {
        switch (code) {
            case 1: return "3G x3";
            case 2: return "6G x3";
            case 3: return "6G x4";
            case 4: return "8G x4";
            case 5: return "10G x4";
            case 6: return "12G x4";
            default: return "";
        }
    }

    /** The highest CTA-861 video mode in the SVD list (by pixels, then rate); null without SVDs. */
    public Vic bestVic() {
        Vic best = null;
        for (Svd s : svds) {
            Vic v = Vic.of(s.vic);
            if (v == null) continue;
            if (best == null || (long) v.width * v.height > (long) best.width * best.height
                    || ((long) v.width * v.height == (long) best.width * best.height && v.hz > best.hz)) best = v;
        }
        return best;
    }

    /** "1.4", "2.0" or "2.1" by the blocks present (HDMI Forum block with FRL = 2.1). */
    public String hdmiLevel() {
        if (forum != null) return forum.maxFrl > 0 ? "2.1" : "2.0";
        if (hdmi != null) return "1.4";
        return "";
    }

    static String hex2(int v) {
        String s = Integer.toHexString(v & 0xff);
        return s.length() < 2 ? "0" + s : s;
    }

    static String hex6(int v) {
        String s = Integer.toHexString(v);
        while (s.length() < 6) s = "0" + s;
        return s.toUpperCase();
    }

    /** Hex text of the raw bytes, 16 per line. */
    public String hexDump() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length; i++) {
            if (i > 0 && i % 16 == 0) b.append('\n');
            else if (i > 0) b.append(' ');
            b.append(hex2(raw[i]));
        }
        return b.toString();
    }
}
