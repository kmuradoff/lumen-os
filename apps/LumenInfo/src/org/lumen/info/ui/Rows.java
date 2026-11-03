package org.lumen.info.ui;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.Display;

import org.lumen.info.R;
import org.lumen.info.core.Edid;
import org.lumen.info.core.Fmt;
import org.lumen.info.core.HalStructs;
import org.lumen.info.core.Hb;
import org.lumen.info.core.Parse;
import org.lumen.info.core.Vic;
import org.lumen.info.data.Snapshot;
import org.lumen.info.hal.HbGmpf;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Turns a Snapshot into labelled rows for the screen and the overlay. A row whose value is null is "not available"
 * (the screen says so, the overlay leaves it out); rows that do not apply now (HDMI rows while an app plays) are
 * not built at all.
 */
public final class Rows {
    public static final int VIDEO = 0, AUDIO = 1, HDMI = 2, SYSTEM = 3;
    public static final int LAYOUT_VIDEO = 0, LAYOUT_FULL = 1;

    public static final class Row {
        public final String key, label, value;

        Row(String key, String label, String value) {
            this.key = key;
            this.label = label;
            this.value = value;
        }
    }

    private final Context c;
    private final List<Row> out = new ArrayList<>();

    /** Not an HB (no vendor service): rows without a value are left out, nothing unsupported is shown. */
    private final boolean hideEmpty;

    private Rows(Context c) {
        this.c = c;
        this.hideEmpty = !HbGmpf.available();
    }

    private void add(String key, int label, String value) {
        if (value == null && hideEmpty) return;
        out.add(new Row(key, c.getString(label), value));
    }

    private void addRaw(String key, String label, String value) {
        out.add(new Row(key, label, value));
    }

    private String s(int id, Object... a) {
        return c.getString(id, a);
    }

    // ================================================================ sections
    public static List<Row> section(Context c, Snapshot s, int section) {
        Rows r = new Rows(c);
        if (s == null) return r.out;
        switch (section) {
            case VIDEO: r.video(s, true); break;
            case AUDIO: r.audio(s, true); break;
            case HDMI: r.hdmi(s); break;
            default: r.system(s, true); break;
        }
        return r.out;
    }

    /** Overlay rows: only rows with a value. */
    public static List<Row> overlay(Context c, Snapshot s, int layout) {
        Rows r = new Rows(c);
        if (s == null) return r.out;
        boolean full = layout == LAYOUT_FULL;
        r.video(s, full);
        r.audio(s, full);
        r.system(s, full);
        List<Row> keep = new ArrayList<>();
        for (Row x : r.out) if (x.value != null) keep.add(x);
        return keep;
    }

    // ================================================================ video
    private boolean onHdmi(Snapshot s) {
        return s.inputSource != null && Hb.isHdmi(s.inputSource);
    }

    private void video(Snapshot s, boolean full) {
        boolean hdmi = onHdmi(s);
        add("source", R.string.r_source, s.inputSource == null ? null
                : hdmi ? s(R.string.v_hdmi_n, s.inputSource) : s(R.string.v_android));
        if (!hdmi) add("app", R.string.r_app, s.topLabel);
        if (hdmi) {
            add("signal", R.string.r_signal, signal(s));
        } else {
            add("decoder", R.string.r_decoder, decoder(s));
            add("format", R.string.r_format, format(s));
        }
        add("resolution", R.string.r_resolution, hdmi && s.hdmi != null && s.hdmi.plausible()
                ? s.hdmi.width + "x" + s.hdmi.height + (s.hdmi.interlace ? "i" : "p") : null);
        add("framerate", R.string.r_framerate, frameRate(s, hdmi));
        add("display", R.string.r_display, s.displayW > 0
                ? s.displayW + "x" + s.displayH + " " + Fmt.hz(s.displayHz) + " Hz" : null);
        add("hdr_in", R.string.r_hdr_in, hdrIn(s));
        add("hdr_out", R.string.r_hdr_out, hdrOut(s));
        add("output", R.string.r_output, output(s));
        add("color_space", R.string.r_color_space, colorSpace(s));
        if (full) {
            add("video_info", R.string.r_video_info, videoInfo(s));
            if (hdmi) add("signal_status", R.string.r_signal_status, s.signalStatus == null ? null : String.valueOf(s.signalStatus));
            add("hdr_switch", R.string.r_hdr_switch, s.hdrEnable == null ? null : s(s.hdrEnable ? R.string.on : R.string.off));
            add("screen_zoom", R.string.r_screen_zoom, s.screenRes == null ? null
                    : s(R.string.v_screen_zoom, s.screenRes.center, s.screenRes.horizontal, s.screenRes.vertical));
            if (hdmi) {
                add("range", R.string.r_range, range(s.hdmiRange));
                add("allm", R.string.r_allm, s.allm == null ? null : s(s.allm ? R.string.on : R.string.off));
                add("edid", R.string.r_edid_version, s.edidVersion == null ? null : "EDID " + Hb.edidVersionName(s.edidVersion));
            }
            add("hdr_policy", R.string.r_hdr_policy, hdrPolicy(s));
            add("match_rate", R.string.r_match_rate, matchRate(s.matchFrameRate));
            add("modes", R.string.r_modes, s.supportedModes);
            add("layer", R.string.r_layer, layer(s));
            if (!hdmi) add("last_decoder", R.string.r_last_decoder, lastDecoder(s));
        }
    }

    /** TV 78, plus "HDR Vivid" from TV 135 when the type itself does not say it. */
    private String hdrIn(Snapshot s) {
        if (s.hdrType == null) return s.hdrVivid != null && s.hdrVivid ? "HDR Vivid" : null;
        String n = Hb.hdrTypeName(s.hdrType);
        if (s.hdrVivid != null && s.hdrVivid && s.hdrType != 6) n += ", " + s(R.string.v_hdr_vivid_playing);
        return n;
    }

    /** DISPLAY 57: what the chip sends to the panel, "3840x2160 60 Hz (panel 3840x2160), high frame rate". */
    private String output(Snapshot s) {
        HalStructs.OutputTiming t = s.outputTiming;
        if (t == null || !t.plausible()) return null;
        StringBuilder b = new StringBuilder(HalStructs.size(t.currH, t.currV));
        String r = HalStructs.rate(t.vfreq);
        if (r != null) b.append(' ').append(r);
        String panel = HalStructs.size(t.dispWidth, t.dispHeight);
        if (panel != null && (t.dispWidth != t.currH || t.dispHeight != t.currV)) b.append(", ").append(s(R.string.v_panel, panel));
        if (t.highFrameRate()) b.append(", ").append(s(R.string.v_high_frame_rate));
        return b.toString();
    }

    /** TV 122: "3840x2160, 60 Hz, HDMI, type 3" (the vendor's videoType code is shown as a number). */
    private String videoInfo(Snapshot s) {
        HalStructs.VideoInfo v = s.videoInfo;
        if (v == null || !v.plausible()) return null;
        StringBuilder b = new StringBuilder(HalStructs.size(v.width, v.height));
        String r = HalStructs.rate(v.frameRate);
        if (r != null) b.append(", ").append(r);
        if (onHdmi(s)) b.append(", ").append(v.hdmiOrDvi != 0 ? "HDMI" : s(R.string.v_dvi));
        b.append(", ").append(s(R.string.v_type_n, v.videoType));
        return b.toString();
    }

    private String signal(Snapshot s) {
        if (s.hdmi == null) return null;
        if (!s.hdmi.plausible()) return s(R.string.v_no_signal);
        return s.hdmi.width + "x" + s.hdmi.height + (s.hdmi.interlace ? "i " : "p ")
                + Fmt.centiHz(s.hdmi.frameRateCenti) + " Hz" + (s.hdmi.hdmiMode ? ", HDMI" : ", " + s(R.string.v_dvi));
    }

    private String decoder(Snapshot s) {
        if (s.codecs == null) return null;
        if (s.codecs.isEmpty()) return s(R.string.v_decoder_idle);
        Parse.CodecClient k = s.codecs.get(0);
        StringBuilder b = new StringBuilder();
        b.append(k.name.isEmpty() ? s(k.hardware ? R.string.v_decoder_hw : R.string.v_decoder_sw) : k.name);
        if (k.secure) b.append(", ").append(s(R.string.v_decoder_secure));
        if (s.codecOwner != null) b.append(", ").append(s(R.string.v_decoder_used_by, s.codecOwner));
        return b.toString();
    }

    /** Codec family from a live decoder name (c2.mtk.hevc.decoder -> HEVC). */
    private String format(Snapshot s) {
        if (s.codecs == null || s.codecs.isEmpty()) return null;
        String n = s.codecs.get(0).name.toLowerCase(Locale.ROOT);
        String f = null;
        if (n.contains("dolby") || n.contains(".dv")) f = "Dolby Vision";
        else if (n.contains("hevc") || n.contains("h265")) f = "HEVC (H.265)";
        else if (n.contains("avc") || n.contains("h264")) f = "AVC (H.264)";
        else if (n.contains("vp9")) f = "VP9";
        else if (n.contains("av1")) f = "AV1";
        else if (n.contains("vp8")) f = "VP8";
        else if (n.contains("mpeg2")) f = "MPEG-2";
        else if (n.contains("mpeg4")) f = "MPEG-4";
        return f == null ? null : f + " " + s(R.string.v_by_name);
    }

    private String frameRate(Snapshot s, boolean hdmi) {
        if (hdmi) return s.hdmi != null && s.hdmi.plausible() ? Fmt.centiHz(s.hdmi.frameRateCenti) + " Hz" : null;
        if (s.videoLayer != null && s.videoLayer.frameRate > 0) return s(R.string.v_app_vote, Fmt.hz(s.videoLayer.frameRate));
        return null;
    }

    private String hdrOut(Snapshot s) {
        if (s.displayHdrTypes == null) return null;
        if (s.displayHdrTypes.length == 0) return s(R.string.v_sdr_only);
        StringBuilder b = new StringBuilder();
        for (int t : s.displayHdrTypes) {
            if (b.length() > 0) b.append(", ");
            b.append(displayHdrName(t));
        }
        if (s.maxNits > 0) b.append(", ").append(s(R.string.v_up_to_nits, Fmt.nits(s.maxNits)));
        return b.toString();
    }

    static String displayHdrName(int t) {
        switch (t) {
            case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION: return "Dolby Vision";
            case Display.HdrCapabilities.HDR_TYPE_HDR10: return "HDR10";
            case Display.HdrCapabilities.HDR_TYPE_HLG: return "HLG";
            case Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS: return "HDR10+";
            default: return "type " + t;
        }
    }

    /**
     * DISPLAY 145 when it answered (stock V3DisplayManager: type 0 = native gamut, type 1 = level is the DISPLAY 103
     * value), else DISPLAY 103.
     */
    private String colorSpace(Snapshot s) {
        HalStructs.ColorSpaceInfo i = s.colorSpaceInfo;
        if (i != null) {
            if (i.type == 0) return s(R.string.v_native);
            String n = Hb.colorSpaceName(i.level);
            n = n == null ? s(R.string.v_native) : n;
            return i.type == 1 ? n : n + " (" + s(R.string.v_type_n, i.type) + ")";
        }
        if (s.colorSpace == null) return null;
        String n = Hb.colorSpaceName(s.colorSpace);
        return n == null ? s(R.string.v_native) : n;
    }

    private String range(Integer v) {
        if (v == null) return null;
        switch (v) {
            case 0: return s(R.string.v_range_auto);
            case 1: return s(R.string.v_range_full);
            case 2: return s(R.string.v_range_limited);
            default: return "? (" + v + ")";
        }
    }

    private String hdrPolicy(Snapshot s) {
        if (s.hdrConversion == null) return null;
        switch (s.hdrConversion) {
            case 0: return s(R.string.v_hdr_unsupported);
            case 1: return s(R.string.v_hdr_passthrough);
            case 2: return s(R.string.v_hdr_system);
            case 3: return s(R.string.v_hdr_force, s.hdrConversionType == null || s.hdrConversionType <= 0
                    ? "SDR" : displayHdrName(s.hdrConversionType));
            default: return "? (" + s.hdrConversion + ")";
        }
    }

    private String matchRate(Integer v) {
        if (v == null) return null;
        switch (v) {
            case 0: return s(R.string.v_match_never);
            case 1: return s(R.string.v_match_seamless);
            case 2: return s(R.string.v_match_always);
            default: return "? (" + v + ")";
        }
    }

    private String layer(Snapshot s) {
        Parse.HwcLayer l = s.videoLayer;
        if (l == null) return null;
        String comp = "SIDEBAND".equalsIgnoreCase(l.composition) ? s(R.string.v_layer_sideband) : l.composition;
        return comp + ", " + (l.right - l.left) + "x" + (l.bottom - l.top);
    }

    private String lastDecoder(Snapshot s) {
        Parse.CodecRecord r = s.lastDecoder;
        if (r == null) return null;
        StringBuilder b = new StringBuilder(r.codec);
        String m = Parse.mimeName(r.mime);
        if (!m.isEmpty()) b.append(", ").append(m);
        if (r.width > 0 && r.height > 0) b.append(", ").append(r.width).append('x').append(r.height);
        if (r.frameRate > 0) b.append(", ").append(Fmt.hz(r.frameRate)).append(" fps");
        if (r.bitrate > 0) b.append(", ").append(Fmt.bitrate(r.bitrate));
        if (!r.time.isEmpty()) b.append(" (").append(r.time).append(')');
        return b.toString();
    }

    // ================================================================ audio
    private void audio(Snapshot s, boolean full) {
        Parse.AudioThread a = s.audio;
        add("audio_out", R.string.r_audio_out, a != null && !a.devices.isEmpty() ? a.devices : s.audioDevices);
        add("audio_format", R.string.r_audio_format, a == null ? null : audioFormat(a));
        String mode;
        if (a == null) mode = s.primary != null ? s(R.string.v_standby) : null;
        else if (a.passthrough()) mode = s(R.string.v_passthrough);
        else if ("OFFLOAD".equals(a.type)) mode = s(R.string.v_offload);
        else mode = s(R.string.v_pcm);
        add("audio_mode", R.string.r_audio_mode, mode);
        if (full) {
            add("audio_type", R.string.r_audio_type, a == null ? null : a.type);
            add("surround", R.string.r_surround, surround(s.surroundMode));
            add("audio_devices", R.string.r_audio_devices, s.audioDevices);
            add("arc", R.string.r_arc, arc(s));
            add("arc_device", R.string.r_arc_device, arcDevice(s));
            add("arc_theirs", R.string.r_arc_theirs, s.arcLink == null ? null : sads(s.arcSads));
            add("arc_ours", R.string.r_arc_ours, s.arcOurs == null ? null
                    : s(R.string.v_hdmi_n, s.arcPort) + ": " + sads(s.arcOurs));
            add("arc_formats", R.string.r_arc_formats, arcFormats(s.arcFormats));
            add("spdif", R.string.r_spdif, s.spdifType == null ? null
                    : s.spdifType == 0 ? s(R.string.v_none) : s(R.string.v_present_type, s.spdifType));
            add("audio_delay", R.string.r_audio_delay, s.audioDelay == null ? null : s(R.string.v_ms, s.audioDelay));
        }
    }

    /** GmAudio 28 (type, 0 none), 33 (ARC device connected), 34 (its support type, raw). */
    private String arc(Snapshot s) {
        if (s.arcType == null && s.arcConnected == null) return null;
        StringBuilder b = new StringBuilder();
        if (s.arcType != null) b.append(s.arcType == 0 ? s(R.string.v_none) : s(R.string.v_present_type, s.arcType));
        if (s.arcConnected != null) {
            if (b.length() > 0) b.append(", ");
            b.append(s(s.arcConnected ? R.string.v_connected : R.string.v_not_connected));
        }
        if (s.arcSupport != null && s.arcSupport != 0) b.append(", ").append(s(R.string.v_support_n, s.arcSupport));
        return b.toString();
    }

    /** The CEC audio system on the ARC port: "LG SOUND BAR, HDMI ARC, HDMI 1, CEC 5, 1.0.0.0". */
    private String arcDevice(Snapshot s) {
        if (s.arcDeviceName == null && s.arcLink == null) return null;
        List<String> k = new ArrayList<>();
        if (s.arcDeviceName != null) k.add(s.arcDeviceName.isEmpty() ? "?" : s.arcDeviceName);
        if (s.arcLink != null) k.add(s.arcLink);
        if (s.arcDevicePort > 0) k.add(s(R.string.v_hdmi_n, s.arcDevicePort));
        if (s.arcDeviceLogical >= 0) k.add("CEC " + s.arcDeviceLogical);
        if (s.arcDevicePhysical >= 0) {
            int a = s.arcDevicePhysical;
            k.add(((a >> 12) & 15) + "." + ((a >> 8) & 15) + "." + ((a >> 4) & 15) + "." + (a & 15));
        }
        return String.join(", ", k);
    }

    /** Short Audio Descriptors: "PCM, 2 ch, 32-192 kHz, 16/20/24 bit; Dolby Digital, 6 ch, 32/44.1/48 kHz, up to 640 kbit/s". */
    private String sads(List<Edid.Sad> list) {
        if (list == null) return null;
        if (list.isEmpty()) return s(R.string.v_none);
        List<String> out = new ArrayList<>();
        for (Edid.Sad x : list) {
            StringBuilder b = new StringBuilder(x.formatName());
            if (x.atmos()) b.append(" Atmos");
            b.append(", ").append(s(R.string.v_channels, x.channels));
            String r = x.rates();
            if (!r.isEmpty()) b.append(", ").append(s(R.string.v_sad_khz, r));
            if (x.format == 1) {
                String bits = x.lpcmBits();
                if (!bits.isEmpty()) b.append(", ").append(s(R.string.v_sad_bits, bits));
            } else if (x.maxKbps() > 0) {
                b.append(", ").append(s(R.string.v_sad_kbps, x.maxKbps()));
            }
            out.add(b.toString());
        }
        return String.join("; ", out);
    }

    /** "Dolby Digital, DTS|8" (Collector) -> "Dolby Digital, DTS, up to 8 ch". */
    private String arcFormats(String v) {
        if (v == null) return null;
        int bar = v.indexOf('|');
        if (bar < 0) return v;
        try {
            return v.substring(0, bar) + ", " + s(R.string.v_max_channels, Integer.parseInt(v.substring(bar + 1)));
        } catch (NumberFormatException e) {
            return v.substring(0, bar);
        }
    }

    private String audioFormat(Parse.AudioThread a) {
        StringBuilder b = new StringBuilder(a.halFormat);
        if (a.sampleRate > 0) b.append(", ").append(Fmt.hz(a.sampleRate / 1000.0)).append(" kHz");
        if (a.channels > 0) b.append(", ").append(s(R.string.v_channels, a.channels));
        return b.toString();
    }

    private String surround(Integer v) {
        if (v == null) return null;
        switch (v) {
            case 0: return s(R.string.v_surround_auto);
            case 1: return s(R.string.v_surround_never);
            case 2: return s(R.string.v_surround_always);
            case 3: return s(R.string.v_surround_manual);
            default: return "? (" + v + ")";
        }
    }

    // ================================================================ system
    private void system(Snapshot s, boolean full) {
        add("cpu", R.string.r_cpu, Double.isNaN(s.cpuLoad) ? null : Fmt.pct(s.cpuLoad));
        add("cpu_temp", R.string.r_cpu_temp, Double.isNaN(s.cpuTemp) ? null : String.format(Locale.US, "%.0f °C", s.cpuTemp));
        if (full) {
            add("sys_temp", R.string.r_sys_temp, s.systemTemp == null ? null : s.systemTemp + " °C");
            String gov = s.governor;
            if (s.cpuCurKHz > 0) gov = (gov == null ? "" : gov + ", ") + Fmt.khz(s.cpuCurKHz)
                    + (s.cpuMaxKHz > 0 ? " / " + Fmt.khz(s.cpuMaxKHz) : "");
            if (gov != null && s.cpuCores > 0) gov += ", " + s(R.string.v_cores, s.cpuCores);
            add("governor", R.string.r_governor, gov);
        }
        add("memory", R.string.r_memory, s.memTotal > 0 ? s(R.string.v_mem, Fmt.bytes(s.memAvail), Fmt.bytes(s.memTotal)) : null);
        String net = s.netType == null ? s(R.string.v_offline) : s.netType;
        if (s.wifiStandard != null) net += ", " + s.wifiStandard;
        if (!full && s.linkMbps > 0) net += ", " + s(R.string.v_link, s.linkMbps);
        add("net", R.string.r_net, net);
        if (full) {
            add("link", R.string.r_link, s.linkMbps <= 0 ? null : s.rxLinkMbps > 0
                    ? s(R.string.v_link_rx, s.linkMbps, s.rxLinkMbps) : s(R.string.v_link, s.linkMbps));
            if (s.rssi != Integer.MIN_VALUE) {
                add("signal_level", R.string.r_signal_level, s.rssi + " dBm" + (s.freqMHz > 0 ? ", " + s.freqMHz + " MHz" : ""));
            }
            add("rx", R.string.r_rx, Double.isNaN(s.rxBps) ? null : Fmt.bitrate(s.rxBps));
            add("time", R.string.r_time, DateFormat.getTimeFormat(c).format(new Date(s.time)));
            if (onHdmi(s)) add("app", R.string.r_app, s.topLabel);
        }
    }

    /** System page extras on the screen only. */
    public static List<Row> about(Context c, Snapshot s) {
        Rows r = new Rows(c);
        if (s == null) return r.out;
        r.add("device", R.string.r_device, s.model);
        r.add("lumen", R.string.r_lumen, s.lumenVersion == null || s.lumenVersion.isEmpty() ? null : s.lumenVersion);
        r.add("android", R.string.r_android, s.android);
        return r.out;
    }

    // ================================================================ HDMI page
    private void hdmi(Snapshot s) {
        add("source", R.string.r_source, s.inputSource == null ? null
                : onHdmi(s) ? s(R.string.v_hdmi_n, s.inputSource) : s(R.string.v_android));
        if (onHdmi(s)) {
            add("signal", R.string.r_signal, signal(s));
            add("hdr_in", R.string.r_hdr_in, s.hdrType == null ? null : Hb.hdrTypeName(s.hdrType));
            add("range", R.string.r_range, range(s.hdmiRange));
            add("allm", R.string.r_allm, s.allm == null ? null : s(s.allm ? R.string.on : R.string.off));
        }
        add("edid", R.string.r_edid_version, s.edidVersion == null ? null : "EDID " + Hb.edidVersionName(s.edidVersion));
        if (s.ports == null) return;
        for (Snapshot.Port p : s.ports) {
            String file = p.file.substring(p.file.lastIndexOf('/') + 1);
            if (!p.fromIni) file += " " + s(R.string.v_file_guess);
            Boolean cable = p.port < s.hdmiConnected.length ? s.hdmiConnected[p.port] : null;
            if (cable != null) addRaw("cable" + p.port, s(R.string.r_port_cable, p.port),
                    s(cable ? R.string.v_connected : R.string.v_not_connected));
            HalStructs.HdmiHwCfg hw = p.port < s.hdmiHwCfg.length ? s.hdmiHwCfg[p.port] : null;
            if (hw != null) addRaw("hw" + p.port, s(R.string.r_port_hw, p.port), portHw(hw));
            addRaw("file" + p.port, s(R.string.r_port_file, p.port), file);
            addRaw("edid" + p.port, s(R.string.r_port_edid, p.port),
                    p.edid == null ? s(R.string.v_edid_unreadable) : summary(c, p.edid));
        }
    }

    /** TV 153: "port 1, CEC on, ARC off, address 1.0.0.0". */
    private String portHw(HalStructs.HdmiHwCfg hw) {
        StringBuilder b = new StringBuilder(s(R.string.v_hw_port, hw.hwPortId));
        b.append(", CEC ").append(s(hw.cec ? R.string.on : R.string.off));
        b.append(", ARC ").append(s(hw.arc ? R.string.on : R.string.off));
        String a = HalStructs.physicalAddress(hw.physicalAddr);
        if (a != null) b.append(", ").append(s(R.string.v_phys_addr, a));
        return b.toString();
    }

    /** One line: best mode, HDMI level, HDR kinds, audio formats. */
    public static String summary(Context c, Edid e) {
        if (!e.valid) return c.getString(R.string.e_invalid, e.error);
        List<String> parts = new ArrayList<>();
        Vic best = e.bestVic();
        if (best != null) parts.add(best.label());
        String lvl = e.hdmiLevel();
        if (!lvl.isEmpty()) parts.add("HDMI " + lvl);
        String hdr = EdidText.hdrKinds(e);
        if (!hdr.isEmpty()) parts.add(hdr);
        String audio = EdidText.audioShort(e);
        if (!audio.isEmpty()) parts.add(audio);
        return String.join(", ", parts);
    }
}
