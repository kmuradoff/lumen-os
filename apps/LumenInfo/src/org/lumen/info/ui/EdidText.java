package org.lumen.info.ui;

import android.content.Context;

import org.lumen.info.R;
import org.lumen.info.core.Edid;
import org.lumen.info.core.Fmt;
import org.lumen.info.core.Vic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A decoded EDID as labelled lines (detail dialog and the .txt next to an export). */
public final class EdidText {
    private EdidText() { }

    public static final class Line {
        public final String label, value;

        Line(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    public static String hdrKinds(Edid e) {
        List<String> k = new ArrayList<>();
        if (e.hdrStatic != null) {
            if (e.hdrStatic.pq) k.add("HDR10");
            if (e.hdrStatic.hlg) k.add("HLG");
        }
        if (e.hdr10PlusVersion >= 0) k.add("HDR10+");
        if (e.dolby != null) k.add("Dolby Vision");
        return String.join("/", k);
    }

    public static String audioShort(Edid e) {
        List<String> k = new ArrayList<>();
        for (Edid.Sad s : e.audio) {
            String n = s.formatName() + (s.atmos() ? " Atmos" : "");
            if (!k.contains(n)) k.add(n);
        }
        return String.join("/", k);
    }

    public static List<Line> lines(Context c, Edid e, String fileName) {
        List<Line> out = new ArrayList<>();
        if (fileName != null) out.add(new Line(c.getString(R.string.e_file), fileName));
        if (!e.valid) {
            out.add(new Line(c.getString(R.string.e_version), c.getString(R.string.e_invalid, e.error)));
            return out;
        }
        if (e.monitorName != null) out.add(new Line(c.getString(R.string.e_monitor), e.monitorName));
        out.add(new Line(c.getString(R.string.e_vendor), e.manufacturer + " " + String.format(Locale.US, "%04X", e.productCode)
                + (e.serial != 0 ? ", s/n " + e.serial : "")));
        out.add(new Line(c.getString(R.string.e_version), e.version + "." + e.revision
                + (e.hasCta ? ", CTA-861 rev " + e.ctaRevision : "") + ", " + e.blockCount + " x 128 B"));
        out.add(new Line(c.getString(R.string.e_date), e.modelYear ? c.getString(R.string.e_model_year, e.year)
                : c.getString(R.string.e_week, e.week, e.year)));
        out.add(new Line(c.getString(R.string.e_checksum), e.badChecksumBlocks.isEmpty() ? c.getString(R.string.e_ok)
                : c.getString(R.string.e_bad, e.badChecksumBlocks.toString())));
        if (!e.detailed.isEmpty()) {
            Edid.Timing t = e.detailed.get(0);
            out.add(new Line(c.getString(R.string.e_preferred), t.label() + " Hz, " + (t.pixelClockKHz / 1000.0) + " MHz"));
        }
        if (e.rangeMaxV > 0) {
            out.add(new Line(c.getString(R.string.e_range), e.rangeMinV + "-" + e.rangeMaxV + " Hz, " + e.rangeMinH + "-"
                    + e.rangeMaxH + " kHz, " + e.rangeMaxClockMHz + " MHz"));
        }
        String lvl = e.hdmiLevel();
        if (!lvl.isEmpty()) out.add(new Line(c.getString(R.string.e_hdmi), lvl));
        if (e.hdmi != null) {
            out.add(new Line(c.getString(R.string.e_phys), e.hdmi.physicalAddress));
            int tmds = Math.max(e.hdmi.maxTmdsMHz, e.forum != null ? e.forum.maxTmdsMHz : 0);
            if (tmds > 0) out.add(new Line(c.getString(R.string.e_tmds), tmds + " MHz"));
            List<String> dc = new ArrayList<>();
            if (e.hdmi.dc30) dc.add("30 bit");
            if (e.hdmi.dc36) dc.add("36 bit");
            if (e.hdmi.dc48) dc.add("48 bit");
            if (e.hdmi.dcY444) dc.add("YCbCr 4:4:4");
            out.add(new Line(c.getString(R.string.e_deep_color), dc.isEmpty() ? c.getString(R.string.e_none) : String.join(", ", dc)));
            if (e.hdmi.videoLatency >= 0) {
                out.add(new Line(c.getString(R.string.e_latency), e.hdmi.videoLatency + " / " + e.hdmi.audioLatency));
            }
            if (!e.hdmi.hdmiVics.isEmpty()) {
                List<String> v = new ArrayList<>();
                for (int x : e.hdmi.hdmiVics) v.add(Vic.hdmiVicLabel(x));
                out.add(new Line(c.getString(R.string.e_hdmi_vics), String.join(", ", v)));
            }
        }
        if (e.forum != null) {
            String frl = Edid.frlName(e.forum.maxFrl);
            if (!frl.isEmpty()) out.add(new Line(c.getString(R.string.e_frl), frl));
            List<String> f = new ArrayList<>();
            if (e.forum.scdc) f.add("SCDC");
            if (e.forum.allm) f.add("ALLM");
            if (e.forum.vrrMax > 0) f.add("VRR " + e.forum.vrrMin + "-" + e.forum.vrrMax + " Hz");
            if (e.forum.qms) f.add("QMS");
            if (e.forum.fva) f.add("FVA");
            if (e.forum.dsc) f.add("DSC");
            if (e.hdmi != null && e.hdmi.threeD) f.add("3D");
            if (!f.isEmpty()) out.add(new Line(c.getString(R.string.e_features), String.join(", ", f)));
            List<String> d = new ArrayList<>();
            if (e.forum.dc420_30) d.add("30 bit");
            if (e.forum.dc420_36) d.add("36 bit");
            if (e.forum.dc420_48) d.add("48 bit");
            if (!d.isEmpty()) out.add(new Line(c.getString(R.string.e_deep_color) + " 4:2:0", String.join(", ", d)));
        } else if (e.hdmi != null && e.hdmi.threeD) {
            out.add(new Line(c.getString(R.string.e_features), "3D"));
        }
        if (e.hasCta) {
            List<String> f = new ArrayList<>();
            if (e.ycc444) f.add("YCbCr 4:4:4");
            if (e.ycc422) f.add("YCbCr 4:2:2");
            if (e.underscan) f.add("underscan");
            if (e.basicAudio) f.add("basic audio");
            if (!f.isEmpty()) out.add(new Line("CTA", String.join(", ", f)));
        }
        if (!e.svds.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (Edid.Svd s : e.svds) {
                Vic v = Vic.of(s.vic);
                if (b.length() > 0) b.append(", ");
                b.append(v == null ? "VIC " + s.vic : v.label());
                if (v != null && v.aspect != Vic.AR_16_9) b.append(' ').append(v.aspectLabel());
                if (s.nativeFormat) b.append(" (").append(c.getString(R.string.e_native)).append(')');
            }
            out.add(new Line(c.getString(R.string.e_modes), b.toString()));
        }
        if (!e.ycc420Only.isEmpty() || !e.ycc420Also.isEmpty()) {
            List<String> v = new ArrayList<>();
            for (int x : e.ycc420Only) v.add(vicLabel(x) + " (4:2:0 only)");
            for (int x : e.ycc420Also) v.add(vicLabel(x));
            out.add(new Line(c.getString(R.string.e_420), String.join(", ", v)));
        }
        String hdr = hdrKinds(e);
        if (e.hdrStatic != null) {
            List<String> eotf = new ArrayList<>();
            if (e.hdrStatic.sdr) eotf.add("SDR");
            if (e.hdrStatic.hdr) eotf.add("HDR gamma");
            if (e.hdrStatic.pq) eotf.add("PQ (ST 2084)");
            if (e.hdrStatic.hlg) eotf.add("HLG");
            out.add(new Line(c.getString(R.string.e_hdr), String.join(", ", eotf)
                    + (e.hdrStatic.type1 ? ", static metadata type 1" : "")));
            if (!Double.isNaN(e.hdrStatic.maxNits)) {
                out.add(new Line(c.getString(R.string.e_hdr_lum), "max " + Fmt.nits(e.hdrStatic.maxNits) + ", avg "
                        + Fmt.nits(e.hdrStatic.maxAvgNits) + ", min " + Fmt.nits(e.hdrStatic.minNits) + " cd/m2"));
            }
        } else {
            out.add(new Line(c.getString(R.string.e_hdr), hdr.isEmpty() ? c.getString(R.string.e_none) : hdr));
        }
        if (!e.hdrDynamicTypes.isEmpty()) out.add(new Line(c.getString(R.string.e_hdr) + " dynamic", "types " + e.hdrDynamicTypes));
        out.add(new Line(c.getString(R.string.e_dv), e.dolby == null ? c.getString(R.string.e_none) : dolby(c, e.dolby)));
        out.add(new Line(c.getString(R.string.e_hdr10p), e.hdr10PlusVersion < 0 ? c.getString(R.string.e_none)
                : c.getString(R.string.e_dv_value, e.hdr10PlusVersion)));
        String col = e.colorimetryNames();
        if (!col.isEmpty()) out.add(new Line(c.getString(R.string.e_colorimetry), col));
        if (!e.audio.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (Edid.Sad s : e.audio) {
                if (b.length() > 0) b.append("; ");
                b.append(s.formatName());
                if (s.atmos()) b.append(" Atmos");
                b.append(' ').append(s.channels).append("ch ").append(s.rates()).append(" kHz");
                if (s.format == 1 && !s.lpcmBits().isEmpty()) b.append(' ').append(s.lpcmBits()).append(" bit");
                if (s.maxKbps() > 0) b.append(' ').append(s.maxKbps()).append(" kbit/s");
            }
            out.add(new Line(c.getString(R.string.e_audio), b.toString()));
        }
        String spk = e.speakerNames();
        if (!spk.isEmpty()) out.add(new Line(c.getString(R.string.e_speakers), spk));
        if (!e.otherBlocks.isEmpty()) out.add(new Line(c.getString(R.string.e_other), String.join("; ", e.otherBlocks)));
        return out;
    }

    private static String vicLabel(int vic) {
        Vic v = Vic.of(vic);
        return v == null ? "VIC " + vic : v.label();
    }

    private static String dolby(Context c, Edid.DolbyVision d) {
        StringBuilder b = new StringBuilder(c.getString(R.string.e_dv_value, d.version));
        if (d.dmVersion > 0) b.append(", DM ").append(d.dmVersion);
        if (d.p2160p60) b.append(", 2160p60");
        if (d.yuv422_12) b.append(", YUV 4:2:2 12 bit");
        if (d.globalDimming) b.append(", global dimming");
        if (d.interfaceCode >= 0) {
            String[] n = {"low latency", "low latency + LL over HDMI", "standard + low latency",
                          "standard + low latency + LL over HDMI"};
            b.append(", ").append(n[d.interfaceCode]);
        }
        return b.toString();
    }

    /** Plain text for the export (.txt beside the .bin). */
    public static String text(Context c, Edid e, String fileName) {
        StringBuilder b = new StringBuilder();
        for (Line l : lines(c, e, fileName)) b.append(l.label).append(": ").append(l.value).append('\n');
        b.append('\n').append(c.getString(R.string.e_raw)).append(":\n").append(e.hexDump()).append('\n');
        return b.toString();
    }
}
