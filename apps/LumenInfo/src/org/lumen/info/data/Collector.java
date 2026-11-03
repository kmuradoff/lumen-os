package org.lumen.info.data;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.HdrConversionMode;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.media.AudioDescriptor;
import android.media.AudioFormat;
import android.media.AudioProfile;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.CpuUsageInfo;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.HardwarePropertiesManager;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import org.lumen.info.core.Edid;
import org.lumen.info.core.Hb;
import org.lumen.info.core.Parse;
import org.lumen.info.hal.HbGmpf;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads everything once per second on its own thread while at least one consumer (the overlay or the screen) holds
 * it, and hands a {@link Snapshot} to the main thread. Nothing runs while no one holds it.
 *
 * Load (rule: fast on weak hardware): the vendor reads of a tick come from core.Hb.schedule: input source,
 * the HDMI timing while HDMI is on screen, one of the fast group (HDR type about every 2.5 s, video info, HDR Vivid,
 * output timing every 5 s) and one of the slow group (every 20 s; signal status every 10 s; the per-port HDMI reads
 * only while the HDMI page or the log asks): at most 4 calls per second on HDMI, 3 otherwise; small files every tick; service dumps every 2 to 10 s (the tiny
 * SurfaceFlinger --hwclayers, resource_manager, audio_flinger, media.metrics of the last 30 minutes only). The full
 * SurfaceFlinger dump is never taken. EDID files are read once and kept.
 */
public final class Collector {
    private static final String TAG = "LumenInfo";
    private static final long TICK_MS = 1000;

    public interface Listener { void onSnapshot(Snapshot s); }

    private static Collector sInstance;

    public static synchronized Collector get(Context c) {
        if (sInstance == null) sInstance = new Collector(c.getApplicationContext());
        return sInstance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();       // main thread
    private final Map<Object, Boolean> holders = new HashMap<>();     // main thread: owner -> wants EDID
    private HandlerThread thread;
    private Handler bg;
    private volatile boolean wantEdid;
    private Snapshot last;

    // ---- collector thread state
    private Snapshot w = new Snapshot();
    private long tick;
    private boolean vendorAvailable, vendorChecked;
    private int vendorFailures;
    private long[] prevStat;
    private boolean statReadable = true;
    private CpuUsageInfo[] prevUsage;
    private long prevRx = -1, prevRxAt;
    private String thermalPath;
    private boolean thermalSearched;
    private final Map<String, String> labels = new HashMap<>();
    private final Map<String, Edid> edidCache = new HashMap<>();
    private Map<String, Map<String, String>> ini;
    private String model;   // ro.boot.xgimi.modelname, read with the ini
    private boolean iniTried;

    private Collector(Context app) {
        this.app = app;
    }

    // ================================================================ consumers (main thread)
    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        if (last != null) l.onSnapshot(last);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public Snapshot last() {
        return last;
    }

    /** Start (or keep) collecting for owner; edid: also read the built-in EDID files. */
    public void acquire(Object owner, boolean edid) {
        holders.put(owner, edid);
        updateWantEdid();
        if (thread == null) {
            thread = new HandlerThread("LumenInfoCollector", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            bg = new Handler(thread.getLooper());
            bg.post(this::loop);
        } else if (edid) {
            bg.post(this::readPorts);
        }
    }

    public void release(Object owner) {
        holders.remove(owner);
        updateWantEdid();
        if (holders.isEmpty() && thread != null) {
            bg.removeCallbacksAndMessages(null);
            thread.quitSafely();
            thread = null;
            bg = null;
        }
    }

    private void updateWantEdid() {
        boolean e = false;
        for (Boolean b : holders.values()) e |= b;
        wantEdid = e;
    }

    // ================================================================ loop (collector thread)
    private void loop() {
        long start = SystemClock.uptimeMillis();
        try {
            collect();
        } catch (Throwable t) {
            Log.w(TAG, "collect: " + t);
        }
        tick++;
        final Snapshot s = w.copy();
        main.post(() -> publish(s));
        Handler h = bg;
        if (h != null) h.postAtTime(this::loop, start + TICK_MS);
    }

    private void publish(Snapshot s) {
        if (thread == null) return;
        last = s;
        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.onSnapshot(s);
            } catch (Throwable t) {
                Log.w(TAG, "listener: " + t);
            }
        }
    }

    private boolean every(int seconds) {
        return tick % seconds == 0;
    }

    private void collect() {
        w.time = System.currentTimeMillis();
        if (tick == 0) {
            w.lumenVersion = SystemProperties.get("ro.lumen.version", "");
            w.model = Build.MODEL;
            w.android = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
            w.cpuCores = Runtime.getRuntime().availableProcessors();
        }
        vendor();
        display();
        cpu();
        if (every(2)) memory();
        topApp();
        network();
        if (every(2)) hwc();
        if (every(3)) codecs();
        if (every(3)) audio();
        if (every(10)) metrics();
        if (every(10)) audioSettings();
        if (wantEdid && every(10)) readPorts();
    }

    // ---------------------------------------------------------------- vendor
    private void vendor() {
        if (!vendorChecked) {
            vendorChecked = true;
            vendorAvailable = HbGmpf.available();
            Log.i(TAG, "vendor gmpf services " + (vendorAvailable ? "found" : "not available"));
        }
        if (!vendorAvailable) return;
        if (vendorFailures > 20 && !every(30)) return;   // a broken vendor: retry slowly
        Integer src = opt(HbGmpf::currentInputSource);
        w.inputSource = src;
        if (src == null) {
            vendorFailures++;
            return;
        }
        vendorFailures = 0;
        w.vendor = true;
        boolean hdmi = Hb.isHdmi(src);
        if (!hdmi) {
            w.hdmi = null;
            w.allm = null;
            w.hdmiRange = null;
        }
        List<String> plan = Hb.schedule(tick, hdmi, wantEdid);
        for (int i = 1; i < plan.size(); i++) read(plan.get(i), src, hdmi);
    }

    /** One scheduled vendor read (Hb.schedule): one call, result into w; a failed call leaves null. */
    private void read(String what, int src, boolean hdmi) {
        switch (what) {
            case Hb.HDMI_INFO: w.hdmi = opt(HbGmpf::hdmiInfo); break;
            case Hb.HDR_TYPE: w.hdrType = opt(HbGmpf::hdrType); break;
            case Hb.VIDEO_INFO: w.videoInfo = opt(HbGmpf::videoInfo); break;
            case Hb.HDR_VIVID: w.hdrVivid = opt(HbGmpf::hdrVividPlaying); break;
            case Hb.OUTPUT_TIMING: w.outputTiming = opt(HbGmpf::outputTiming); break;
            case Hb.EDID_VERSION: w.edidVersion = opt(HbGmpf::edidVersion); break;
            case Hb.RANGE: w.hdmiRange = opt(HbGmpf::hdmiRange); break;
            case Hb.ALLM: w.allm = opt(() -> HbGmpf.allmStatus(src)); break;
            case Hb.PICTURE_MODE: w.pictureMode = opt(HbGmpf::pictureMode); break;
            case Hb.COLOR_SPACE: {
                Integer pm = w.pictureMode;
                int source = hdmi ? src : 0;
                w.colorSpace = pm != null && pm >= 0 && pm <= 40 ? opt(() -> HbGmpf.colorSpace(source, pm)) : null;
                break;
            }
            case Hb.COLOR_SPACE_INFO: {
                Integer pm = w.pictureMode;
                int source = hdmi ? src : 0;
                w.colorSpaceInfo = pm != null && pm >= 0 && pm <= 40 ? opt(() -> HbGmpf.colorSpaceInfo(source, pm)) : null;
                break;
            }
            case Hb.SYS_TEMP: w.systemTemp = opt(HbGmpf::systemTemperature); break;
            case Hb.SIGNAL_STATUS: w.signalStatus = opt(HbGmpf::signalStatus); break;
            case Hb.HDR_ENABLE: w.hdrEnable = opt(HbGmpf::hdrEnable); break;
            case Hb.SCREEN_RES: w.screenRes = opt(HbGmpf::screenResolution); break;
            case Hb.ARC_TYPE: w.arcType = opt(HbGmpf::arcType); break;
            case Hb.SPDIF_TYPE: w.spdifType = opt(HbGmpf::spdifType); break;
            case Hb.ARC_CONNECTED: w.arcConnected = opt(HbGmpf::arcConnected); break;
            case Hb.ARC_SUPPORT: w.arcSupport = opt(HbGmpf::arcSupportType); break;
            case Hb.AUDIO_DELAY: w.audioDelay = opt(HbGmpf::audioDelay); break;
            case Hb.CONNECT1: w.hdmiConnected[1] = opt(() -> HbGmpf.hdmiConnectStatus(1)); break;
            case Hb.CONNECT2: w.hdmiConnected[2] = opt(() -> HbGmpf.hdmiConnectStatus(2)); break;
            case Hb.HWCFG1: w.hdmiHwCfg[1] = opt(() -> HbGmpf.hdmiHwConfig(1)); break;
            case Hb.HWCFG2: w.hdmiHwCfg[2] = opt(() -> HbGmpf.hdmiHwConfig(2)); break;
            default: Log.w(TAG, "unknown vendor read " + what); break;
        }
    }

    private interface Getter<T> { T get() throws Exception; }

    private static <T> T opt(Getter<T> g) {
        try {
            return g.get();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- display
    private void display() {
        DisplayManager dm = app.getSystemService(DisplayManager.class);
        if (dm == null) return;
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return;
        Display.Mode m = d.getMode();
        w.displayW = m.getPhysicalWidth();
        w.displayH = m.getPhysicalHeight();
        w.displayHz = m.getRefreshRate();
        if (!every(10) && w.displayHdrTypes != null) return;
        try {
            Display.HdrCapabilities hc = d.getHdrCapabilities();
            w.displayHdrTypes = hc == null ? new int[0] : hc.getSupportedHdrTypes();
            w.maxNits = hc == null ? -1 : hc.getDesiredMaxLuminance();
        } catch (Throwable t) {
            w.displayHdrTypes = new int[0];
        }
        StringBuilder b = new StringBuilder();
        Map<String, List<String>> bySize = new java.util.LinkedHashMap<>();
        for (Display.Mode x : d.getSupportedModes()) {
            String k = x.getPhysicalWidth() + "x" + x.getPhysicalHeight();
            bySize.computeIfAbsent(k, z -> new ArrayList<>()).add(org.lumen.info.core.Fmt.hz(x.getRefreshRate()));
        }
        for (Map.Entry<String, List<String>> e : bySize.entrySet()) {
            if (b.length() > 0) b.append("; ");
            b.append(e.getKey()).append(' ').append(String.join("/", e.getValue())).append(" Hz");
        }
        w.supportedModes = b.toString();
        w.matchFrameRate = opt(dm::getMatchContentFrameRateUserPreference);
        try {
            HdrConversionMode hm = dm.getHdrConversionModeSetting();
            w.hdrConversion = hm.getConversionMode();
            w.hdrConversionType = hm.getPreferredHdrOutputType();
        } catch (Throwable t) {
            w.hdrConversion = null;
            w.hdrConversionType = null;
        }
    }

    // ---------------------------------------------------------------- cpu and thermal
    private void cpu() {
        double load = Double.NaN;
        if (statReadable) {
            long[] now = Parse.procStatCpu(Dumps.file("/proc/stat", 4096));
            if (now == null) {
                statReadable = false;
                Log.i(TAG, "/proc/stat not readable, CPU load from HardwarePropertiesManager");
            } else {
                load = Parse.loadPercent(prevStat, now);
                prevStat = now;
            }
        }
        if (!statReadable) {
            try {
                HardwarePropertiesManager hp = app.getSystemService(HardwarePropertiesManager.class);
                CpuUsageInfo[] u = hp == null ? null : hp.getCpuUsages();
                if (u != null && prevUsage != null && u.length == prevUsage.length) {
                    long da = 0, dt = 0;
                    for (int i = 0; i < u.length; i++) {
                        if (u[i] == null || prevUsage[i] == null) continue;
                        da += u[i].getActive() - prevUsage[i].getActive();
                        dt += u[i].getTotal() - prevUsage[i].getTotal();
                    }
                    if (dt > 0) load = Math.max(0, Math.min(100, da * 100.0 / dt));
                }
                prevUsage = u;
            } catch (Throwable t) {
                prevUsage = null;
            }
        }
        if (!Double.isNaN(load)) w.cpuLoad = load;
        String base = "/sys/devices/system/cpu/cpu0/cpufreq/";
        w.cpuCurKHz = Parse.toLong(Dumps.line(base + "scaling_cur_freq"));
        if (every(10)) {
            w.governor = Dumps.line(base + "scaling_governor");
            w.cpuMaxKHz = Parse.toLong(Dumps.line(base + "scaling_max_freq"));
        }
        if (every(2)) thermal();
    }

    private void thermal() {
        if (!thermalSearched) {
            thermalSearched = true;
            String fallback = null;
            for (int i = 0; i < 16; i++) {
                String z = "/sys/class/thermal/thermal_zone" + i;
                String type = Dumps.line(z + "/type");
                if (type == null) continue;
                String t = Dumps.line(z + "/temp");
                if (t == null || Parse.toLong(t) <= 0) continue;
                String lt = type.toLowerCase();
                if (lt.contains("cpu") || lt.contains("mtk-thermal") || lt.contains("soc")) {
                    thermalPath = z + "/temp";
                    break;
                }
                if (fallback == null) fallback = z + "/temp";
            }
            if (thermalPath == null) thermalPath = fallback;
        }
        double t = Double.NaN;
        if (thermalPath != null) {
            long v = Parse.toLong(Dumps.line(thermalPath));
            if (v > 0) t = v > 1000 ? v / 1000.0 : v;
        }
        if (Double.isNaN(t)) {
            try {
                HardwarePropertiesManager hp = app.getSystemService(HardwarePropertiesManager.class);
                float[] a = hp.getDeviceTemperatures(HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                        HardwarePropertiesManager.TEMPERATURE_CURRENT);
                if (a != null && a.length > 0 && a[0] > 0) t = a[0];
            } catch (Throwable ignored) {
            }
        }
        w.cpuTemp = t;
    }

    // ---------------------------------------------------------------- memory, app, network
    private void memory() {
        ActivityManager am = app.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        w.memTotal = mi.totalMem;
        w.memAvail = mi.availMem;
    }

    @SuppressWarnings("deprecation")
    private void topApp() {
        try {
            ActivityManager am = app.getSystemService(ActivityManager.class);
            List<ActivityManager.RunningTaskInfo> t = am.getRunningTasks(1);
            if (t == null || t.isEmpty() || t.get(0).topActivity == null) return;
            String pkg = t.get(0).topActivity.getPackageName();
            w.topPackage = pkg;
            w.topLabel = label(pkg);
        } catch (Throwable ignored) {
        }
    }

    private String label(String pkg) {
        String l = labels.get(pkg);
        if (l != null) return l;
        try {
            PackageManager pm = app.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            l = String.valueOf(pm.getApplicationLabel(ai));
        } catch (Throwable t) {
            l = pkg;
        }
        labels.put(pkg, l);
        return l;
    }

    @SuppressWarnings("deprecation")
    private void network() {
        long rx = TrafficStats.getTotalRxBytes();
        long now = SystemClock.elapsedRealtime();
        if (rx >= 0 && prevRx >= 0 && now > prevRxAt) w.rxBps = (rx - prevRx) * 8000.0 / (now - prevRxAt);
        prevRx = rx;
        prevRxAt = now;
        if (!every(5) && tick > 0) return;
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        Network n = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
        w.netType = null;
        w.wifiStandard = null;
        w.linkMbps = w.rxLinkMbps = w.freqMHz = -1;
        w.rssi = Integer.MIN_VALUE;
        if (nc == null) return;
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            w.netType = "Ethernet";
            w.linkMbps = Parse.toInt(Dumps.line("/sys/class/net/eth0/speed"));
        } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            w.netType = "Wi-Fi";
            WifiManager wm = app.getSystemService(WifiManager.class);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            if (wi != null) {
                w.linkMbps = wi.getTxLinkSpeedMbps() > 0 ? wi.getTxLinkSpeedMbps() : wi.getLinkSpeed();
                w.rxLinkMbps = wi.getRxLinkSpeedMbps();
                w.rssi = wi.getRssi();
                w.freqMHz = wi.getFrequency();
                w.wifiStandard = wifiStandard(wi.getWifiStandard());
            }
        } else {
            w.netType = "?";
        }
    }

    private static String wifiStandard(int s) {
        switch (s) {
            case ScanResult.WIFI_STANDARD_LEGACY: return "802.11a/b/g";
            case ScanResult.WIFI_STANDARD_11N: return "802.11n (Wi-Fi 4)";
            case ScanResult.WIFI_STANDARD_11AC: return "802.11ac (Wi-Fi 5)";
            case ScanResult.WIFI_STANDARD_11AX: return "802.11ax (Wi-Fi 6)";
            case ScanResult.WIFI_STANDARD_11BE: return "802.11be (Wi-Fi 7)";
            default: return null;
        }
    }

    // ---------------------------------------------------------------- dumps
    private void hwc() {
        String d = Dumps.service("SurfaceFlinger", new String[] {"--hwclayers"}, 800);
        w.videoLayer = d == null ? null : Parse.videoLayer(Parse.hwcLayers(d));
    }

    private void codecs() {
        String d = Dumps.service("media.resource_manager", null, 800);
        List<Parse.CodecClient> c = Parse.videoCodecs(d);
        w.codecs = c;
        w.codecOwner = null;
        if (c.isEmpty()) return;
        try {
            ActivityManager am = app.getSystemService(ActivityManager.class);
            for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                if (p.pid != c.get(0).pid) continue;
                String pkg = p.pkgList != null && p.pkgList.length > 0 ? p.pkgList[0] : p.processName;
                w.codecOwner = label(pkg);
                break;
            }
        } catch (Throwable ignored) {
        }
    }

    private void audio() {
        String d = Dumps.service("media.audio_flinger", null, 1200);
        List<Parse.AudioThread> t = Parse.audioThreads(d);
        w.audio = Parse.activeAudio(t);
        Parse.AudioThread primary = null;
        for (Parse.AudioThread a : t) if (a.flags.contains("PRIMARY")) primary = a;
        w.primary = primary;
    }

    private void metrics() {
        String d = Dumps.service("media.metrics", new String[] {"--since", "-1800"}, 1500);
        Parse.CodecRecord r = Parse.lastVideoDecoder(d);
        if (r != null) w.lastDecoder = r;
    }

    private void audioSettings() {
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            StringBuilder b = new StringBuilder();
            for (AudioDeviceInfo i : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                String n = deviceName(i.getType());
                if (n == null || b.indexOf(n) >= 0) continue;
                if (b.length() > 0) b.append(", ");
                b.append(n);
            }
            w.audioDevices = b.toString();
        } catch (Throwable t) {
            w.audioDevices = null;
        }
        try {
            w.surroundMode = Settings.Global.getInt(app.getContentResolver(), "encoded_surround_output", 0);
        } catch (Throwable t) {
            w.surroundMode = null;
        }
        arcDevice();
    }

    /**
     * The device on the ARC / eARC port, both directions: the CEC audio system there (name, addresses) and the
     * Short Audio Descriptors it reported (the framework asks them over CEC, eARC brings them in its capability
     * data; AudioDeviceInfo keeps them as STANDARD_EDID descriptors), plus the encodings Android may send it. What
     * we tell it is the audio block of our own EDID on that port (Rows, from the built-in file). Read only.
     */
    private void arcDevice() {
        w.arcPort = -1;
        w.arcDeviceName = null;
        w.arcDeviceLogical = w.arcDevicePort = w.arcDevicePhysical = w.arcDeviceVendor = -1;
        try {
            HdmiControlManager m = app.getSystemService(HdmiControlManager.class);
            if (m != null) {
                List<HdmiPortInfo> ports = m.getPortInfo();
                if (ports != null) for (HdmiPortInfo p : ports) if (p != null && p.isArcSupported()) { w.arcPort = p.getId(); break; }
                List<HdmiDeviceInfo> list = m.getConnectedDevices();
                if (list != null) for (HdmiDeviceInfo d : list) {
                    if (d == null || !d.isCecDevice() || d.getDeviceType() != HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM) continue;
                    w.arcDeviceName = d.getDisplayName();
                    w.arcDeviceLogical = d.getLogicalAddress();
                    w.arcDevicePort = d.getPortId();
                    w.arcDevicePhysical = d.getPhysicalAddress();
                    w.arcDeviceVendor = d.getVendorId();
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "arc device: " + t);
        }
        w.arcOurs = null;
        w.arcOursFile = null;
        if (w.arcPort > 0 && vendorAvailable && w.edidVersion != null) {
            loadIni();
            int ver = w.edidVersion == Hb.EDID_20 ? Hb.EDID_20 : Hb.EDID_14;
            String f = Hb.edidFile(ini, model, w.arcPort, ver);
            Edid e = f == null ? null : edid(f);
            if (e != null && e.valid) {
                w.arcOurs = e.audio;
                w.arcOursFile = new File(f).getName();
            }
        }
        w.arcLink = null;
        w.arcSads = null;
        w.arcFormats = null;
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            AudioDeviceInfo arc = null;
            for (AudioDeviceInfo i : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (i.getType() == AudioDeviceInfo.TYPE_HDMI_EARC) { arc = i; break; }
                if (i.getType() == AudioDeviceInfo.TYPE_HDMI_ARC && arc == null) arc = i;
            }
            if (arc == null) return;
            w.arcLink = arc.getType() == AudioDeviceInfo.TYPE_HDMI_EARC ? "HDMI eARC" : "HDMI ARC";
            List<Edid.Sad> sads = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (AudioDescriptor d : arc.getAudioDescriptors()) {
                if (d.getStandard() != AudioDescriptor.STANDARD_EDID) continue;
                byte[] b = d.getDescriptor();
                String key = Arrays.toString(b);
                if (seen.contains(key)) continue;   // the framework may list one descriptor more than once
                seen.add(key);
                Edid.Sad s = Edid.sad(b, 0);
                if (s != null) sads.add(s);
            }
            w.arcSads = sads;
            List<String> enc = new ArrayList<>();
            int maxCh = 0;
            for (AudioProfile p : arc.getAudioProfiles()) {
                String n = encodingName(p.getFormat());
                if (n != null && !enc.contains(n)) enc.add(n);
                for (int mask : p.getChannelMasks()) maxCh = Math.max(maxCh, Integer.bitCount(mask));
            }
            if (!enc.isEmpty()) w.arcFormats = String.join(", ", enc) + (maxCh > 0 ? "|" + maxCh : "");
        } catch (Throwable t) {
            Log.w(TAG, "arc descriptors: " + t);
        }
    }

    private static String encodingName(int f) {
        switch (f) {
            case AudioFormat.ENCODING_PCM_16BIT: case AudioFormat.ENCODING_PCM_FLOAT:
            case AudioFormat.ENCODING_PCM_24BIT_PACKED: case AudioFormat.ENCODING_PCM_32BIT: return "PCM";
            case AudioFormat.ENCODING_AC3: return "Dolby Digital";
            case AudioFormat.ENCODING_E_AC3: return "Dolby Digital Plus";
            case AudioFormat.ENCODING_E_AC3_JOC: return "Dolby Atmos (DD+)";
            case AudioFormat.ENCODING_DOLBY_TRUEHD: return "Dolby TrueHD";
            case AudioFormat.ENCODING_DOLBY_MAT: return "Dolby MAT";
            case AudioFormat.ENCODING_DTS: return "DTS";
            case AudioFormat.ENCODING_DTS_HD: return "DTS-HD";
            case AudioFormat.ENCODING_DTS_UHD_P1: return "DTS:X";
            case AudioFormat.ENCODING_AAC_LC: return "AAC";
            default: return null;
        }
    }

    private static String deviceName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "Speaker";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            case AudioDeviceInfo.TYPE_HDMI_ARC: return "HDMI ARC";
            case AudioDeviceInfo.TYPE_HDMI_EARC: return "HDMI eARC";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth A2DP";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return "Bluetooth LE";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL: return "S/PDIF";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Headphones";
            default: return null;
        }
    }

    // ---------------------------------------------------------------- built-in EDID files
    private void readPorts() {
        loadIni();
        if (!vendorAvailable) return;   // not an HB: these folders mean nothing elsewhere
        Integer v = w.edidVersion;
        if (v == null) v = opt(HbGmpf::edidVersion);
        if (v == null) return;
        w.edidVersion = v;
        int ver = v == Hb.EDID_20 ? Hb.EDID_20 : Hb.EDID_14;   // the stock UI shows every other value as 1.4
        List<Snapshot.Port> ports = new ArrayList<>();
        for (int p : Hb.HDMI_PORTS) {
            Snapshot.Port port = new Snapshot.Port();
            port.port = p;
            port.file = Hb.edidFile(ini, model, p, ver);
            if (port.file == null) continue;
            port.fromIni = Hb.edidFromIni(ini, p, ver);
            port.link = linkOf(port.file);
            port.edid = edid(port.file);
            ports.add(port);
        }
        w.ports = ports;
    }

    /** The vendor ini and the list of built-in EDID files, read once. */
    private void loadIni() {
        if (!iniTried) {
            iniTried = true;
            model = SystemProperties.get("ro.boot.xgimi.modelname", "");
            String vi = Hb.vendorIni(model);
            ini = vi == null ? null : Parse.ini(Dumps.file(vi, 256 * 1024));
            if (ini != null && ini.isEmpty()) ini = null;
            String ed = Hb.edidDir(model);
            List<String> files = new ArrayList<>();
            Map<String, String> links = new HashMap<>();
            String[] names = ed == null ? null : new File(ed).list();
            if (names != null) {
                Arrays.sort(names);
                for (String n : names) {
                    if (!Hb.isEdidFileName(n)) continue;
                    files.add(n);
                    String l = linkOf(ed + "/" + n);
                    if (l != null) links.put(n, l);
                }
            }
            w.builtinFiles = files;
            w.builtinLinks = links;
            w.edidDir = ed;
            if (ed != null && new File(ed, "edid_cfg.ini").exists()) {
                String l = linkOf(ed + "/edid_cfg.ini");
                w.edidCfgLink = l == null ? "" : l;
            }
            w.iniRead = ini != null;
        }
    }

    /** The target of a symlink as stored ("../../G0000/tv/x.bin"), or null when the path is not a link. */
    private static String linkOf(String path) {
        try {
            Path p = Paths.get(path);
            return Files.isSymbolicLink(p) ? Files.readSymbolicLink(p).toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Decoded built-in file, cached; null when it cannot be read. Collector thread. */
    private Edid edid(String path) {
        if (edidCache.containsKey(path)) return edidCache.get(path);
        byte[] b = Dumps.bytes(path, 4096);
        Edid e = b == null ? null : Edid.parse(b);
        edidCache.put(path, e);
        return e;
    }

    // ================================================================ actions (main thread in, result on main)
    /** Decode one built-in file by name (EDID folder only). */
    public void decodeBuiltin(String name, Consumer<Edid> done) {
        Handler h = bg;
        if (h == null || !Hb.isEdidFileName(name)) {
            done.accept(null);
            return;
        }
        h.post(() -> {
            Edid e = w.edidDir == null ? null : edid(w.edidDir + "/" + name);
            main.post(() -> done.accept(e));
        });
    }

    /** TV 40 with 1 or 2, then read the version back. done(true) when the vendor accepted it. */
    public void switchEdid(int version, Consumer<Boolean> done) {
        Handler h = bg;
        if (h == null || (version != Hb.EDID_14 && version != Hb.EDID_20)) {
            done.accept(false);
            return;
        }
        h.post(() -> {
            Boolean ok = opt(() -> HbGmpf.setEdidVersion(version));
            Log.i(TAG, "setEdidVersion(" + version + ") -> " + ok);
            w.edidVersion = opt(HbGmpf::edidVersion);
            readPorts();
            main.post(() -> done.accept(ok != null && ok));
        });
    }

    /**
     * Copy a built-in EDID file unchanged to Download (raw .bin) plus a decoded .txt beside it. Never writes
     * anywhere else; the vendor files are only read. done(display name or null).
     */
    public void export(String name, String text, Consumer<String> done) {
        Handler h = bg;
        if (h == null || !Hb.isEdidFileName(name)) {
            done.accept(null);
            return;
        }
        h.post(() -> {
            String res = null;
            byte[] raw = w.edidDir == null ? null : Dumps.bytes(w.edidDir + "/" + name, 4096);
            if (raw != null) {
                String base = "lumen_edid_" + name.substring(0, name.length() - 4);
                boolean a = save(base + ".bin", "application/octet-stream", raw);
                boolean b = text == null || save(base + ".txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
                if (a && b) res = Environment.DIRECTORY_DOWNLOADS + "/" + base + ".bin";
            }
            final String r = res;
            main.post(() -> done.accept(r));
        });
    }

    private boolean save(String name, String mime, byte[] data) {
        try {
            ContentResolver cr = app.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u != null) {
                try (OutputStream o = cr.openOutputStream(u)) {
                    o.write(data);
                }
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "MediaStore save " + name + ": " + t);
        }
        try {
            File f = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(data);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "file save " + name + ": " + t);
            return false;
        }
    }
}
