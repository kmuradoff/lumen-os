package org.lumen.info.data;

import org.lumen.info.core.Edid;
import org.lumen.info.core.HalStructs;
import org.lumen.info.core.Parse;
import org.lumen.info.hal.HbGmpf;

import java.util.ArrayList;
import java.util.List;

/**
 * One reading of everything LumenInfo shows. Written by the Collector on its thread, then handed to the main
 * thread and never changed again. null (or -1 / NaN) means "not available": no source on this device, no
 * permission, or nothing playing.
 */
public final class Snapshot implements Cloneable {
    public long time;

    // ---- vendor (HB gmpf), null without the vendor service
    public boolean vendor;              // the vendor services answered at least once
    public Integer inputSource;         // TV 4
    public HbGmpf.HdmiInfo hdmi;        // TV 38 (only read while an HDMI input is on screen)
    public Integer hdrType;             // TV 78
    public Integer hdmiRange;           // TV 64
    public Boolean allm;                // TV 131 (HDMI only)
    public Integer edidVersion;         // TV 39
    public Integer pictureMode;         // TV 34
    public Integer colorSpace;          // DISPLAY 103
    public Integer systemTemp;          // TEMPERATURE 6, deg C
    public HalStructs.VideoInfo videoInfo;          // TV 122 (null: false or no struct)
    public Integer signalStatus;                    // TV 106 (raw vendor code)
    public Boolean hdrEnable;                       // TV 51 (HDR switch of the picture settings)
    public Boolean hdrVivid;                        // TV 135
    public HalStructs.OutputTiming outputTiming;    // DISPLAY 57
    public HalStructs.ColorSpaceInfo colorSpaceInfo; // DISPLAY 145
    public HalStructs.ScreenResolution screenRes;   // DISPLAY 31
    public Integer arcType, spdifType;              // GmAudio 28 / 29 (0 = none)
    public Boolean arcConnected;                    // GmAudio 33 (HDMI ARC)
    public Integer arcSupport;                      // GmAudio 34 (HDMI ARC, raw)
    public Integer audioDelay;                      // GmAudio 13, ms
    public Boolean[] hdmiConnected = new Boolean[3];               // TV 70 by port 1 / 2 (only with the HDMI page)
    public HalStructs.HdmiHwCfg[] hdmiHwCfg = new HalStructs.HdmiHwCfg[3]; // TV 153 by port 1 / 2

    // ---- display output (Android display API)
    public int displayW = -1, displayH = -1;
    public float displayHz = -1;
    public int[] displayHdrTypes;       // Display.HdrCapabilities: 1 DV, 2 HDR10, 3 HLG, 4 HDR10+
    public float maxNits = -1;
    public String supportedModes;       // "3840x2160 50/60"
    public Integer matchFrameRate;      // DisplayManager match content frame rate preference (0 never, 1 seamless, 2 always)
    public Integer hdrConversion;       // HdrConversionMode: 1 passthrough, 2 system, 3 force
    public Integer hdrConversionType;   // forced / preferred HDR type of the conversion

    // ---- video layer and decoder (dumps)
    public Parse.HwcLayer videoLayer;   // SurfaceFlinger --hwclayers
    public List<Parse.CodecClient> codecs = new ArrayList<>();   // media.resource_manager
    public String codecOwner;           // app label of the process holding the decoder
    public Parse.CodecRecord lastDecoder;   // media.metrics

    // ---- audio
    public Parse.AudioThread audio;     // the playing output (audio_flinger), null when all are in standby
    public Parse.AudioThread primary;   // the primary output (even in standby)
    public String audioDevices;         // AudioManager outputs: "Speaker, HDMI ARC"
    // ---- the device on the ARC / eARC port: what it tells us and what we tell it
    public String arcDeviceName;        // CEC audio system on the ARC port (HdmiControlManager), "LG SOUND BAR"
    public int arcDeviceLogical = -1, arcDevicePort = -1, arcDevicePhysical = -1, arcDeviceVendor = -1;
    public String arcLink;              // "HDMI ARC" / "HDMI eARC": the AudioDeviceInfo Android sees
    public List<Edid.Sad> arcSads;      // its Short Audio Descriptors (AudioDeviceInfo descriptors, from CEC / eARC)
    public String arcFormats;           // the encodings Android may send it, with the most channels
    public int arcPort = -1;            // the HDMI port with ARC (HdmiPortInfo)
    public List<Edid.Sad> arcOurs;      // what we tell it: the audio block of our EDID on that port (built-in file)
    public String arcOursFile;
    public Integer surroundMode;        // Settings.Global encoded_surround_output

    // ---- system
    public double cpuLoad = Double.NaN;
    public double cpuTemp = Double.NaN;
    public String governor;
    public long cpuCurKHz = -1, cpuMaxKHz = -1;
    public int cpuCores = -1;
    public long memTotal = -1, memAvail = -1;
    public String netType;              // "Wi-Fi", "Ethernet", null offline
    public String wifiStandard;         // "802.11ax"
    public int linkMbps = -1, rxLinkMbps = -1, rssi = Integer.MIN_VALUE, freqMHz = -1;
    public double rxBps = Double.NaN;
    public String topPackage, topLabel;
    public String lumenVersion, model, android;

    // ---- HDMI inputs (only while the screen's HDMI page or an export asks)
    public static final class Port {
        public int port;
        public String file;              // the built-in EDID file of the selected version
        public boolean fromIni;
        public Edid edid;                // decoded file, null if unreadable
    }

    public List<Port> ports;
    public boolean iniRead;
    public List<String> builtinFiles;   // names in the EDID folder

    /** Shallow copy: the Collector replaces lists and objects, it never changes them in place (arrays are copied). */
    public Snapshot copy() {
        try {
            Snapshot c = (Snapshot) super.clone();
            c.hdmiConnected = hdmiConnected.clone();
            c.hdmiHwCfg = hdmiHwCfg.clone();
            return c;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }
}
