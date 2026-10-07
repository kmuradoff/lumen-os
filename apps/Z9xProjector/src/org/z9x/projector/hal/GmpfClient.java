/*
 * GmpfClient: whitelisted, typed Java hwbinder client for the stock XGIMI Z9X vendor HAL
 *   xgimi.hardware.gmpf@1.0::IGmpf/default   (vendor service "gmpfHw", domain hal_gmpf_xgimi)
 * IGmpf2 is a separate interface with its own codes: see Gmpf2Client.
 *
 * Every code and wire type below was checked against research/focus/gmpf_IGmpf_codes.tsv (vendor
 * proxy table: "code method signature flags W= R="); the column values are quoted per method as
 *   "TSV: W=... R=...". u8 = writeInt8 on the wire (same byte as writeUint8). Sources of the value
 * sets: research/v61/inventory/FEATURE_SPEC.md (FS), research/v61/RESULT_quicksettings.json (QS),
 * RESULT_display.json (DS), RESULT_power.json (PW), research/v6/RESULT_hal.json (HAL).
 *
 * SAFETY (hard rules of the Z9X project):
 *  - There is NO generic transact: every public method is one typed, whitelisted call with fixed or
 *    range-checked arguments (IllegalArgumentException before anything is sent).
 *  - Never present here: autoFocus 301, 299, factory/calibration codes (IGmpf 453 factoryControlMotor,
 *    583 gyroscopeCorrection, 400/421 reset, 94/95/533/556 factory WB, 99/101/102 auto-colour
 *    calibration), 155/80 (no sensor), power 229/233/241/422/543, newAutoKst 24, manualFocus other
 *    than {17,0,1,2}. IGmpf2 76 cleanAllCalibrationData is not in Gmpf2Client either.
 *  - Calls are refused on the main thread (HidlCaller). Callers use Hal.run / HalController threads.
 *  - Only methods with a reviewed caller outside hal/ are compiled; the rest are parked in a comment
 *    block at the end of the class (gsi/apps/check_hal_callers.py fails the build otherwise).
 *  - Errors: RemoteException = not delivered / HAL dead; ReplyException = delivered, bad reply:
 *    do not repeat one-shot calls.
 *  - The focusEvent callback replies Status + true immediately (the vendor waits for it while it
 *    holds its listener mutex), never calls the HAL and never blocks; it only hands the event to
 *    the EventSink, which must just post it.
 */
package org.z9x.projector.hal;

import android.os.HwBinder;
import android.os.HwBlob;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.IHwInterface;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import java.util.ArrayList;

public final class GmpfClient extends HidlCaller {

    // ---------------------------------------------------------------- descriptors (VERIFIED)
    public static final String IGMPF = "xgimi.hardware.gmpf@1.0::IGmpf";
    public static final String IPROJECTOR_FOCUS_CALLBACK = "xgimi.hardware.gmpf@1.0::IProjectorFocusCallback";
    static final String IBASE = "android.hidl.base@1.0::IBase";

    // ------------------------------------------------- IGmpf transaction codes (private, VERIFIED)
    // sound
    private static final int GET_SOUND_PROCESS_TYPE = 26;          // ()      -> i32
    private static final int SET_SOUND_PROCESS_TYPE = 27;          // (i32)   -> bool
    private static final int GET_VOLUME_BALANCE = 41;              // ()      -> bool
    private static final int SET_VOLUME_BALANCE = 42;              // (bool)  -> bool
    private static final int GET_DTS_EFFECTS = 43;                 // ()      -> bool
    private static final int SET_DTS_EFFECTS = 44;                 // (bool)  -> bool
    // v6.4 SoC PEQ (speaker path, research/v64/peq): TSV 50 W=writeInt32 x4 R=readInt32; 51 W=writeBool R=readInt32
    private static final int SET_PEQ = 50;                         // (i32 band, i32 gain0.1dB, i32 fcHz, i32 q0.1) -> i32
    private static final int SET_PEQ_ENABLE = 51;                  // (bool)  -> i32
    private static final int SET_AUDIO_OUTPUT = 59;                // (u8)    -> void
    private static final int GET_AUDIO_OUTPUT = 60;                // ()      -> u8
    private static final int SET_SOUND_EFFECT = 61;                // (u8)    -> void
    private static final int GET_SOUND_EFFECT = 62;                // ()      -> u8
    // v6.4 AI sound: TSV 63 setAutoSoundeffect W=writeUint8 R= (void)
    private static final int SET_AUTO_SOUND_EFFECT = 63;           // (u8)    -> void
    // eye protection
    private static final int GET_HUMAN_DETECT_MODE = 87;           // ()      -> i32
    private static final int SET_HUMAN_DETECT_MODE = 88;           // (i32)   -> bool
    // wall colour
    private static final int SET_AUTO_COLOR_DEFAULT = 100;         // ()      -> bool
    private static final int START_AUTO_COLOR = 106;               // ()      -> bool
    // colour space
    private static final int GET_DISP_COLOR_SPACE = 111;           // (i32,i32) -> i32
    private static final int SWITCH_DISP_COLOR_SPACE = 112;        // (i32)   -> i32
    // vendor UI watchdog (v6.2b eye protection): TSV 114 W=hidl_string R=readInt8,
    // 115 W=hidl_string,writeInt32,writeInt32 R=readInt8
    private static final int UI_HEART_BEAT_DO = 114;               // (hidl_string) -> i8
    private static final int UI_HEART_BEAT_SET = 115;              // (hidl_string, i32, i32) -> i8
    // zoom & shift
    private static final int ZOOM_FINISH = 120;                    // (i32)   -> i32
    private static final int ZOOM_START = 121;                     // (i32)   -> i32
    private static final int TRANSLATION_KST = 141;                // (i32,bool) -> i32
    // AK overlay ack
    private static final int UI_AK_DISPLAY = 146;                  // (i32)   -> void
    // eye protection screen
    private static final int GET_HUMAN_DETECT_SCREEN = 147;        // ()      -> bool
    private static final int SET_HUMAN_DETECT_SCREEN = 148;        // (bool)  -> bool
    // manual keystone
    private static final int CHECK_TRAPEZOID_COORDINATE = 150;     // (KstPoint buf 326) -> bool
    private static final int GET_HUMAN_DETECT = 151;               // ()      -> bool
    private static final int SET_HUMAN_DETECT = 152;               // (bool)  -> bool
    private static final int GENERATE_LOGO_BY_TRAPEZOID = 158;     // (i32)   -> i32
    // output timing (Ultra 120 Hz)
    private static final int SET_OUTPUT_TIMING = 161;              // (i32)   -> bool
    // projection / lamp
    private static final int SET_PROJECTOR_PUT_MODE = 172;         // (u8)    -> void
    private static final int GET_PROJECTOR_PUT_MODE = 173;         // ()      -> u8
    private static final int SET_DLP_LUMENS_MODE = 174;            // (u8)    -> void
    private static final int GET_DLP_LUMENS_MODE = 175;            // ()      -> u8
    private static final int SET_DLP_LUMENS_LEVEL = 176;           // (u8)    -> void
    private static final int GET_DLP_LUMENS_LEVEL = 177;           // ()      -> u8
    private static final int IS_VIDEO_PLAYING = 180;               // ()      -> bool
    private static final int CORRECT_KEYSTONE_FULL_POINT = 185;    // (KstPoint buf 326) -> i32
    private static final int GET_CORRECT_KEYSTONE_FULL_POINT = 186;// (u8)    -> KstPoint (cb)
    // 3D
    private static final int ENABLE_3D = 190;                      // (u8)    -> bool
    private static final int ENABLE_3D_TO_2D = 191;                // (u8)    -> bool
    private static final int GET_CURRENT_3D_FORMAT = 192;          // ()      -> u8
    private static final int IS_3D_TO_2D_ENABLED = 193;            // ()      -> bool
    // screen (lamp) on/off
    private static final int SET_SCREEN_ON_OFF = 195;              // (bool)  -> void
    private static final int GET_SCREEN_ON_OFF = 196;              // ()      -> bool
    private static final int CORRECT_KEYSTONE_RESET = 207;         // (u8)    -> void
    // power diagnostics (read-only)
    private static final int GET_BOOT_REASON = 230;                // ()      -> i8
    // misc
    private static final int GO_TO_BOOT_COMPLETED = 237;           // (i32)   -> bool
    private static final int GET_PICTURE_ADJUST_AUTO_COLOR = 249;  // ()      -> bool
    private static final int SET_PICTURE_ADJUST_AUTO_COLOR = 250;  // (bool)  -> void
    private static final int NEW_AUTO_KST = 272;                   // (i32)   -> i8
    private static final int GET_PROJ_CURTAIN_ADP = 289;           // ()      -> bool
    private static final int SET_PROJ_CURTAIN_ADP = 290;           // (bool)  -> void
    private static final int GET_PROJ_SCREEN_ADP = 291;            // ()      -> bool
    private static final int SET_PROJ_SCREEN_ADP = 292;            // (bool)  -> void
    private static final int CONNECT_PROJECTOR_FOCUS_MANAGER = 298;// (i32, binder non-null) -> void
    private static final int MANUAL_FOCUS = 300;                   // (u8)    -> void
    private static final int POWER_ON_AF_ENABLE = 302;             // (bool)  -> void
    private static final int IS_POWER_ON_AF_ENABLED = 303;         // ()      -> bool
    private static final int SET_PROJECTOR_FOCUS_LISTENER = 306;   // (i32, callback non-null) -> void
    private static final int SEND_FOCUS_STATUS = 307;              // (u8)    -> void
    private static final int AUTO_KST = 326;                       // ()      -> i8
    private static final int GET_SYSTEM_UI_STATE = 390;            // ()      -> i32
    private static final int SET_SYSTEM_UI_STATE = 391;            // (i32)   -> void
    private static final int GET_BOOT_ANIM_STATE = 394;            // ()      -> i32
    private static final int SET_BOOT_ANIM_STATE = 395;            // (i32)   -> void
    private static final int ENABLE_POWER_ON_MUSIC = 419;          // (bool)  -> void
    private static final int IS_POWER_ON_MUSIC_ENABLED = 420;      // ()      -> bool
    private static final int GET_SYSTEM_TEMPERATURE = 438;         // ()      -> u8
    private static final int SAVE_POWER_ON_AK_FLAG = 552;          // (bool)  -> void
    private static final int GET_POWER_ON_AK_FLAG = 553;           // ()      -> bool
    private static final int TEMPORARY_SWITCH_TRIGGER = 573;       // (i32,bool) -> bool
    private static final int GET_AUTO_REVERSE = 579;               // ()      -> bool
    private static final int SET_AUTO_REVERSE = 580;               // (bool)  -> bool
    private static final int SET_ACC_TRIGGER_AK = 585;             // (bool)  -> bool
    private static final int GET_ACC_TRIGGER_AK = 586;             // ()      -> bool
    private static final int SET_ANG_TRIGGER_AF = 587;             // (bool)  -> bool
    private static final int GET_ANG_TRIGGER_AF = 588;             // ()      -> bool
    private static final int GET_HDR_TYPE = 620;                   // ()      -> i32
    private static final int GET_DOLBY_VISION_PIC_MODE = 623;      // ()      -> i32
    private static final int SET_DOLBY_VISION_PIC_MODE = 624;      // (i32)   -> bool
    private static final int GET_WAKE_UP_SOURCE = 639;             // ()      -> i32
    private static final int GET_GAME_MODE_OPTION = 647;           // ()      -> i32
    private static final int SET_GAME_MODE_OPTION = 648;           // (i32)   -> bool
    private static final int GET_UCD_LEVEL = 651;                  // (i32)   -> i32
    private static final int SET_UCD_LEVEL = 655;                  // (i32,i32) -> bool
    // v6.2 game module (BEGIN): read-only HDMI signal info for org.z9x.projector.game.
    private static final int GET_HDMI_INFO = 662;                  // ()      -> stHdmiInfo (cb, 16 bytes)
    // v6.2 game module (END)
    private static final int GET_ASPECT_RATIO = 664;               // ()      -> i32
    private static final int SET_ASPECT_RATIO = 665;               // (i32)   -> bool
    private static final int GET_PICTURE_MODE = 666;               // (i32)   -> i32
    private static final int SET_PICTURE_MODE = 667;               // (i32,i32) -> bool
    private static final int GET_MEMC_LEVEL = 668;                 // (i32)   -> i32
    private static final int SET_MEMC_LEVEL = 669;                 // (i32,i32) -> bool
    private static final int GET_3DNR = 670;                       // (i32)   -> i32
    private static final int SET_3DNR = 671;                       // (i32,i32) -> bool
    private static final int GET_CURRENT_INPUT_SOURCE = 696;       // ()      -> i32

    /** Picture/PQ calls always address window/source 0 (stock XgimiVideo: setPictureMode(0, i)). */
    private static final int WINDOW_MAIN = 0;

    // Fixed argument values (no free-form value is accepted from callers).
    private static final byte FOCUS_STATUS_REMOTE_AF = 44;    // stock FocusUIV2.autoFocus (remote key)
    private static final byte FOCUS_STATUS_UI_READY = 17;     // stock FocusUIV2.start: power-on AF flow
    private static final byte FOCUS_STATUS_AF_CARD_CLOSED = 5;  // FS 2.7: AF card closed -> 307(5) then 307(3)
    private static final byte FOCUS_STATUS_AF_CARD_DONE = 3;
    private static final int SYSTEM_UI_STATE_READY = 1;       // stock SystemUI systemUIServicesCompleted
    private static final int BOOT_ANIM_STATE_FINISHED = 2;    // stock bootanimation exit
    private static final int TEMPORARY_SWITCH_AK = 2;         // KeyStoneManager TEMPORARY_SWITCH_AK=2 (FS 2.2)

    // ================================================================== value enums
    /** manualFocus arguments allowed by the project rules: {17, 0, 1, 2} only. */
    public enum ManualFocus {
        /** Overlay opened (stock FocusAdjust sends 17 on open). */
        ENTER(17),
        /** Continuous move, stock "RIGHT" (stock DPAD_RIGHT down). */
        STOCK_RIGHT(0),
        /** Continuous move, stock "LEFT" (stock DPAD_LEFT down). */
        STOCK_LEFT(1),
        /** Stop the motor. */
        STOP(2);
        final byte wire;
        ManualFocus(int v) { wire = (byte) v; }
    }

    /** goToBootCompleted arguments: 0 early (stock XRMService.onCreate), 1 after the boot animation. */
    public enum BootStage {
        EARLY(0), AFTER_BOOTANIM(1);
        final int wire;
        BootStage(int v) { wire = v; }
    }

    /**
     * newAutoKst modes used by stock (FS 2.1): 9 = settings button / panel tile ("misckey"),
     * 10 = retry from the "angle too large" notice of manual keystone, 11 = curtain re-fit
     * (event 120 dialog OK). Never 24 (tilt-shift only) or any factory mode.
     */
    public enum AutoKstMode {
        MISCKEY(9), RETRY_ANGLE(10), CURTAIN_REFIT(11);
        final int wire;
        AutoKstMode(int v) { wire = v; }
    }

    /**
     * Picture modes of the Z9X (QS decisions "Picture mode"; picture.json 'other' values VERIFIED).
     * AI = "AI picture" (mode 10, FS 0.2); HDR_VIVID only while getHdrType()==6; COLOR_ACCURACY only
     * for SDR/HDR10 content; PERFORMANCE needs the stock heat warning first. Never 19/20 (Youku).
     */
    public enum PictureMode {
        AI(10), STANDARD(1), SPORT(9), HDR_VIVID(26), COLOR_ACCURACY(29), OFFICE(25), PERFORMANCE(5);
        public final int wire;
        PictureMode(int v) { wire = v; }
        /** Maps a read-back value; 10..17 all show as AI (FS 3.2). null = unknown/other. */
        public static PictureMode fromWire(int v) {
            if (v >= 10 && v <= 17) return AI;
            for (PictureMode m : values()) if (m.wire == v) return m;
            return null;
        }
    }

    /** AI picture scene sub-modes (stock XRMService defaultvideoinfo, FS 3.3). Only while in AI mode. */
    public enum AiScene {
        TV(10), VARIETY(11), CHILDREN(12), CONCERT(13), DOCUMENTARY(14), ANIME(15), FILM(16), FOOTBALL(17);
        public final int wire;
        AiScene(int v) { wire = v; }
    }

    /** Basic picture items, 0..100 each, window 0 (QS "Basic picture settings"; TSV W=i32,i32 R=bool / W=i32 R=i32). */
    public enum PqItem {
        BRIGHTNESS(692, 693), CONTRAST(694, 695), SATURATION(690, 691), SHARPNESS(688, 689);
        final int get, set;
        PqItem(int g, int s) { get = g; set = s; }
    }

    /** MEMC (motion compensation) 0 Off, 1 Low, 2 Medium, 3 High (FS 3.4). */
    public enum MemcLevel {
        OFF(0), LOW(1), MEDIUM(2), HIGH(3);
        public final int wire;
        MemcLevel(int v) { wire = v; }
    }

    /** Noise reduction 0 Off, 1 Low, 2 Medium, 3 High, 4 Auto (FS 3.5). */
    public enum NoiseReduction {
        OFF(0), LOW(1), MEDIUM(2), HIGH(3), AUTO(4);
        public final int wire;
        NoiseReduction(int v) { wire = v; }
    }

    /** Colour temperature = DLP lumens mode; Z9X offers ONLY 16 and 17 (7/8/15 are max/min/test, FS 3.4). */
    public enum ColorTemp {
        D65(16), COLOR_TEMP_1(17);
        public final int wire;
        ColorTemp(int v) { wire = v; }
    }

    /** Colour space (FS 3.5): 4 Native, 1 BT.2020, 7 Adobe RGB, 2 DCI-P3, 3 Rec.709. */
    public enum ColorSpace {
        NATIVE(4), BT2020(1), ADOBE_RGB(7), DCI_P3(2), REC709(3);
        public final int wire;
        ColorSpace(int v) { wire = v; }
    }

    /** Projector put mode (FS 2.9 VERIFIED ProjectionRepository): 0 desk front .. 3 ceiling rear. */
    public enum PutMode {
        DESK_FRONT(0), CEILING_FRONT(1), DESK_REAR(2), CEILING_REAR(3);
        public final int wire;
        PutMode(int v) { wire = v; }
        public static PutMode fromWire(int v) {
            for (PutMode m : values()) if (m.wire == v) return m;
            return null;
        }
    }

    /** HDMI aspect ratio, platform values because mt5877 is GMPF2 (QS VERIFIED): 0 4:3, 1 16:9, 2 Auto, 4 Original. */
    public enum AspectRatio {
        R4_3(0), R16_9(1), AUTO(2), ORIGINAL(4);
        public final int wire;
        AspectRatio(int v) { wire = v; }
    }

    /**
     * HDMI game mode option (DS, gamemode_version=3): 0 basic, 1 basic+HFR, 2 top speed+HFR,
     * 3 top speed. Stock picks 1/2 only when support_hfr and the current option is already 1 or 2.
     */
    public enum GameModeOption {
        BASIC(0), BASIC_HFR(1), TOP_SPEED_HFR(2), TOP_SPEED(3);
        public final int wire;
        GameModeOption(int v) { wire = v; }
        public boolean isTopSpeed() { return this == TOP_SPEED || this == TOP_SPEED_HFR; }
        public static GameModeOption fromWire(int v) {
            for (GameModeOption m : values()) if (m.wire == v) return m;
            return null;
        }
    }

    /** "Ultra 120 Hz" output timing (DS VERIFIED stock newsettings): 4 = on (1080p120), 6 = off (4K60). */
    public enum OutputTiming {
        ULTRA_120_ON(4), ULTRA_120_OFF(6);
        final int wire;
        OutputTiming(int v) { wire = v; }
    }

    /**
     * Sound mode (QS SoundEnum VERIFIED): 3 AI, 20 Standard, 1 Movie, 2 Music, 12 Sports, 4 Karaoke.
     * v6.3.1 (research/v63/ampeq, decoded TAS5805M tables): only 1/2/12/4 select a real G0082 amp
     * table (movie/music/sport/ktv_5805); 20 is "TI58XX mode err" (nothing written, INIT stays after
     * a reboot) and is never sent.
     * v6.4 AI sound (research/v64/aisound, libxgimi Msrv::setSoundEffectsMode @0x159cb74): 61(3) saves
     * AUTO in the vendor DB and stores property xgimi.pkg.effect.mod (or 1) as the pending sub-mode;
     * the vendor "aud2_effect" thread then writes that real table (1/2/12/4). Our AiSoundEngine picks
     * the sub-mode per foreground app with 63 {@link #setAutoSoundEffect}. STANDARD stays here only so
     * a read-back can be recognised.
     */
    public enum SoundEffect {
        AI(3), STANDARD(20), MOVIE(1), MUSIC(2), SPORTS(12), KARAOKE(4);
        public final int wire;
        SoundEffect(int v) { wire = v; }
        public static SoundEffect fromWire(int v) {
            for (SoundEffect m : values()) if (m.wire == v) return m;
            return null;
        }
    }

    /**
     * Output path for 59 setAudioOutput (EN_XGIMI_AUDIO_PATH, stock libxgimiaudiopolicy
     * threadInit / setXgimiPathForDeviceDelay, research/audio/dis/xap.ann): 0 built-in speaker,
     * 2 HDMI ARC, 3 BT A2DP, 4 wired headset/headphones, 6 USB, 100 "switching" (Msrv mutes every
     * TV path and keeps the current path). Never 1 (SPDIF, no jack on Z9X) and never the wireless
     * speaker paths 7..13.
     */
    public enum AudioPath {
        SPEAKER(0), ARC(2), BT_A2DP(3), WIRED(4), USB(6), SWITCHING(100);
        public final int wire;
        AudioPath(int v) { wire = v; }
    }

    /** Sound enhancement (QS: Harman = 2, DTS Virtual:X = 5; never 3/7/100). */
    public enum SoundProcess {
        HARMAN(2), DTS_VIRTUAL_X(5);
        public final int wire;
        SoundProcess(int v) { wire = v; }
    }

    /** Eye protection mode (FS 5.1): 0 follow, 1 safe. The stock wrapper rejects >= 2. */
    public enum HumanDetectMode {
        FOLLOW(0), SAFE(1);
        public final int wire;
        HumanDetectMode(int v) { wire = v; }
    }

    /** 3D (FS 3.6): enable3D 1 off, 2 side-by-side, 3 top-bottom; enable3DTo2D 2 / 3. */
    public enum Mode3D {
        OFF(1), SIDE_BY_SIDE(2), TOP_BOTTOM(3);
        public final int wire;
        Mode3D(int v) { wire = v; }
    }

    /** Input source of getCurrentInputSource (QS): 0 media/storage, 1 HDMI 1, 2 HDMI 2. */
    public static final int SOURCE_MEDIA = 0, SOURCE_HDMI1 = 1, SOURCE_HDMI2 = 2;

    /** getHdrType values (QS PictureUtils VERIFIED). */
    public static final int HDR_SDR = 0, HDR_HDR10 = 1, HDR_DOLBY_VISION = 2, HDR_HLG = 4,
            HDR_HDR10_PLUS = 5, HDR_VIVID = 6;

    /** AK overlay event types that are acknowledged with 146 (FS 1.1 table). 110 is never acked. */
    private static final int[] AK_ACK_TYPES = {106, 107, 109, 113, 114, 115, 116, 118};

    /** Request reached the HAL (transact returned) but the reply was bad. Do not repeat one-shot calls. */
    public static final class ReplyException extends RemoteException {
        ReplyException(String msg) { super(msg); }
    }

    // ------------------------------------------------------------- NUI fifo reader gate (v6.2b)
    /**
     * Property set to "ready" by /system/etc/xgimi/z9x_nui.sh (init service z9x_nui, root) once it
     * holds both vendor native-UI fifos /data/vendor/tmp/nuififo + retnuififo open; init sets it to
     * "down" while the service restarts or is stopped. Empty on every boot until then.
     */
    public static final String NUI_READY_PROP = "sys.z9x.nui";

    /**
     * True when a reader holds the vendor native-UI fifo (research/v62x RESULT_eye VERIFIED):
     * libxgimi Msrv_System_Control::nuiCommand does a BLOCKING open(nuififo, O_WRONLY) under a
     * mutex, and IGmpf 148 (on-path nativeShowUIHD(3)), the HD heartbeat timeout (-> 148 path) and
     * possibly 152 reach it. Without a reader the calling HAL binder thread, and our z9x-hal /
     * z9x-power thread with it, would block until reboot. Fails closed: an unreadable property
     * (enforcing SELinux) reads as not ready. Any thread.
     */
    public static boolean nuiReaderReady() {
        try {
            return "ready".equals(SystemProperties.get(NUI_READY_PROP, ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Thrown instead of sending 148 / 152 / 114 / 115 while {@link #nuiReaderReady()} is false. Nothing was sent. */
    public static final class NuiNotReadyException extends RemoteException {
        NuiNotReadyException(int code) {
            super("IGmpf " + code + " not sent: no NUI fifo reader (" + NUI_READY_PROP + " != ready)");
        }
    }

    private static void requireNuiReader(int code) throws NuiNotReadyException {
        if (!nuiReaderReady()) throw new NuiNotReadyException(code);
    }

    /** Receives focus events. Called on the hwbinder thread AFTER the reply was sent: only post. */
    public interface EventSink {
        void onFocusEvent(int type, String value);
    }

    // ------------------------------------------------------------------------- state
    /** Strong static references: the HAL keeps only a binder reference to our callback. */
    private static FocusCallback sCallback;
    private static volatile EventSink sSink;

    public GmpfClient(EventSink sink) {
        super(IGMPF);
        synchronized (GmpfClient.class) {
            sSink = sink;
            if (sCallback == null) sCallback = new FocusCallback();
        }
    }

    // ---------------------------------------------------------------- connection
    /**
     * HwBinder.getService(IGmpf, "default", retry=false) + linkToDeath. Does not wait for the
     * service. Returns true when connected. Never throws.
     */
    public boolean connect(IHwBinder.DeathRecipient death, long cookie) {
        return connectInternal(death, cookie);
    }

    // ------------------------------------------------------------- listener (298 then 306)
    /** 298 connectProjectorFocusManager(myPid, callback). Same non-null callback as 306. */
    public void connectFocusManager() throws RemoteException {
        callPidBinder(CONNECT_PROJECTOR_FOCUS_MANAGER, Process.myPid(), callback());
    }

    /** 306 setProjectorFocusListener(myPid, callback). Never null (a null crashes the HAL later). */
    public void setFocusListener() throws RemoteException {
        callPidBinder(SET_PROJECTOR_FOCUS_LISTENER, Process.myPid(), callback());
    }

    private static synchronized FocusCallback callback() {
        if (sCallback == null) sCallback = new FocusCallback();
        return sCallback;
    }

    // ------------------------------------------------------------------ boot handshake
    /** 391 setSystemUIState(1). Plain store in gmpf_main; idempotent. */
    public void setSystemUiReady() throws RemoteException { callI32(SET_SYSTEM_UI_STATE, SYSTEM_UI_STATE_READY); }

    /** 390 getSystemUIState(). */
    public int getSystemUiState() throws RemoteException { return callRetI32(GET_SYSTEM_UI_STATE); }

    /** 395 setBootAnimState(2). Plain store in gmpf_main; idempotent. */
    public void setBootAnimFinished() throws RemoteException { callI32(SET_BOOT_ANIM_STATE, BOOT_ANIM_STATE_FINISHED); }

    /** 394 getBootAnimState(). */
    public int getBootAnimState() throws RemoteException { return callRetI32(GET_BOOT_ANIM_STATE); }

    /** 237 goToBootCompleted(0 or 1). The caller guarantees once per boot (per stage). */
    public boolean goToBootCompleted(BootStage s) throws RemoteException {
        return callI32RetBool(GO_TO_BOOT_COMPLETED, s.wire);
    }

    /** 307 sendFocusStatus(17): starts the vendor power-on AF flow. Caller: handshake only, last. */
    public void sendPowerOnUiReady() throws RemoteException { callU8(SEND_FOCUS_STATUS, FOCUS_STATUS_UI_READY); }

    // ---------------------------------------------------------------------- focus
    /** 307 sendFocusStatus(44): remote focus key short press (stock FocusUIV2.autoFocus). */
    public void sendRemoteAutofocus() throws RemoteException { callU8(SEND_FOCUS_STATUS, FOCUS_STATUS_REMOTE_AF); }


    /** 300 manualFocus(17 / 0 / 1 / 2). Returns immediately; the vendor runs the motor itself. */
    public void manualFocus(ManualFocus m) throws RemoteException { callU8(MANUAL_FOCUS, m.wire); }

    /** 303 / 302: autofocus at power-on. TSV 302 W=writeBool R=; 303 R=readBool. */
    public boolean getPowerOnAf() throws RemoteException { return callRetBool(IS_POWER_ON_AF_ENABLED); }
    public void setPowerOnAf(boolean on) throws RemoteException { callBool(POWER_ON_AF_ENABLE, on); }
    /** 588 / 587: autofocus after the projector was moved. TSV 587 W=writeBool R=readBool. */
    public boolean getMoveAf() throws RemoteException { return callRetBool(GET_ANG_TRIGGER_AF); }
    public boolean setMoveAf(boolean on) throws RemoteException { return callBoolRetBool(SET_ANG_TRIGGER_AF, on); }

    // -------------------------------------------------------------------- keystone
    /** 326 autoKst(). May block inside the HAL for seconds: keystone thread only. TSV R=readInt8. */
    public byte autoKst() throws RemoteException { return call(AUTO_KST, request(), RET_I8); }

    /** 272 newAutoKst(9 / 10 / 11). May block: keystone thread only. TSV W=writeInt32 R=readInt8. */
    public byte newAutoKst(AutoKstMode m) throws RemoteException {
        HwParcel q = request();
        q.writeInt32(m.wire);
        return call(NEW_AUTO_KST, q, RET_I8);
    }

    /**
     * 146 uiAkDisplay(type): acknowledge that the AK overlay rendered step {@code type}
     * (FS 1.1; vendor AK_Process::uiAkDisplayMode @0x6ae8a0 waits up to 5 s for it).
     * TSV: W=writeInt32 R=(void). Only the types of the FS 1.1 table: 106 107 109 113 114 115 116 118.
     */
    public void uiAkDisplay(int type) throws RemoteException {
        boolean ok = false;
        for (int t : AK_ACK_TYPES) if (t == type) { ok = true; break; }
        if (!ok) throw new IllegalArgumentException("uiAkDisplay: type " + type + " is not an AK ack type");
        callI32(UI_AK_DISPLAY, type);
    }

    /**
     * 186 getCorrectKeystoneFullPiont(mode 0): current 4 corners. TSV: W=writeUint8, reply via
     * callback = Status + KstPoint (readBuffer 326, same struct as 150/185). Throws ReplyException
     * if the reply is not a 326-byte buffer or a corner is outside the 3840x2160 panel (v6.4: the
     * coordinates are DLP panel pixels, VERIFIED live; KstPoint).
     */
    public KstPoint getKeystonePoints() throws RemoteException {
        HwParcel q = request();
        q.writeInt8((byte) KstPoint.MODE_FOUR_POINT);
        try {
            return call(GET_CORRECT_KEYSTONE_FULL_POINT, q,
                    r -> KstPoint.fromBlob(r.readBuffer(KstPoint.WIRE_SIZE)));
        } catch (IllegalArgumentException e) {
            throw new ReplyException("186 corner out of range: " + e.getMessage());
        }
    }

    /** 150 checkTrapezoidCoordinate(KstPoint) -> true when the vendor accepts the corners. TSV W=writeBuffer R=readBool. */
    public boolean checkKeystonePoints(KstPoint p) throws RemoteException {
        HwParcel q = request();
        q.writeBuffer(p.toBlob());
        return call(CHECK_TRAPEZOID_COORDINATE, q, RET_BOOL);
    }

    /**
     * 185 correctKeystoneFullPiont(KstPoint) -> i32. Applied immediately (stock: no animation).
     * Callers must have called {@link #checkKeystonePoints} with the same points first (FS 2.4).
     * TSV W=writeBuffer R=readInt32.
     */
    public int applyKeystonePoints(KstPoint p) throws RemoteException {
        HwParcel q = request();
        q.writeBuffer(p.toBlob());
        return call(CORRECT_KEYSTONE_FULL_POINT, q, RET_I32);
    }

    /** 158 generateLogoByTrapezoid(0): regenerate the boot logo after manual keystone. TSV W=writeInt32 R=readInt32. */
    public int regenerateBootLogo() throws RemoteException { return callI32RetI32(GENERATE_LOGO_BY_TRAPEZOID, 0); }

    /**
     * 573 temporarySwitchTrigger(2, enable): pause (false) / resume (true) real-time AK around the
     * manual keystone / zoom / rotation screens (FS 2.2). Only type 2. TSV W=writeInt32,writeBool R=readBool.
     */
    public boolean setRealtimeAkTemporarily(boolean enable) throws RemoteException {
        return callI32BoolRetBool(TEMPORARY_SWITCH_TRIGGER, TEMPORARY_SWITCH_AK, enable);
    }

    /**
     * 207 correctKeystoneReset(0): ONLY for the HDMI game "top speed" mode, as stock does after
     * setGameModeOption(2|3) (DS). Refused unless 647 currently reads 2 or 3. TSV W=writeUint8 R=.
     */
    public void resetKeystoneForTopSpeedGame() throws RemoteException {
        GameModeOption cur = GameModeOption.fromWire(callRetI32(GET_GAME_MODE_OPTION));
        if (cur == null || !cur.isTopSpeed()) {
            throw new IllegalStateException("correctKeystoneReset only in top-speed game mode (now " + cur + ")");
        }
        callU8(CORRECT_KEYSTONE_RESET, 0);
    }


    /** Picture shift direction for 141 translationKst (FS 2.5): 1 up, 2 down, 3 left, 4 right. */
    public enum ShiftDir {
        UP(1), DOWN(2), LEFT(3), RIGHT(4);
        final int wire;
        ShiftDir(int v) { wire = v; }
    }


    // ------------------------------------------------- projection toggles (getter first, setter on click)
    /** 289 / 290: fit the picture into the projection screen (curtain). TSV 290 W=writeBool R=. */
    public boolean getCurtainFit() throws RemoteException { return callRetBool(GET_PROJ_CURTAIN_ADP); }
    public void setCurtainFit(boolean on) throws RemoteException { callBool(SET_PROJ_CURTAIN_ADP, on); }
    /** 291 / 292: obstacle avoidance. TSV 292 W=writeBool R=. */
    public boolean getObstacleAvoid() throws RemoteException { return callRetBool(GET_PROJ_SCREEN_ADP); }
    public void setObstacleAvoid(boolean on) throws RemoteException { callBool(SET_PROJ_SCREEN_ADP, on); }
    /** 553 / 552: auto keystone at power-on. TSV 552 W=writeBool R=. */
    public boolean getPowerOnAk() throws RemoteException { return callRetBool(GET_POWER_ON_AK_FLAG); }
    public void setPowerOnAk(boolean on) throws RemoteException { callBool(SAVE_POWER_ON_AK_FLAG, on); }
    /** 586 / 585: real-time keystone (auto keystone after the projector was moved). TSV 585 W=writeBool R=readBool. */
    public boolean getMoveAk() throws RemoteException { return callRetBool(GET_ACC_TRIGGER_AK); }
    public boolean setMoveAk(boolean on) throws RemoteException { return callBoolRetBool(SET_ACC_TRIGGER_AK, on); }
    /** 579 / 580: automatic flip ("Auto" projection mode, FS 2.9). TSV 580 W=writeBool R=readBool. */
    public boolean getAutoReverse() throws RemoteException { return callRetBool(GET_AUTO_REVERSE); }
    public boolean setAutoReverse(boolean on) throws RemoteException { return callBoolRetBool(SET_AUTO_REVERSE, on); }

    /** 173 getProjectorPutMode() -> u8 (null if not 0..3). */
    public PutMode getPutMode() throws RemoteException { return PutMode.fromWire(callRetU8(GET_PROJECTOR_PUT_MODE)); }
    /** 172 setProjectorPutMode(u8). Flips the image at once: confirm first (QS). TSV W=writeUint8 R=. */
    public void setPutMode(PutMode m) throws RemoteException { callU8(SET_PROJECTOR_PUT_MODE, m.wire); }

    // ------------------------------------------------------------------ lamp / screen
    /** 177 getDlpLumensLevel() -> 1..10 (u8). */
    public int getLampLevel() throws RemoteException { return callRetU8(GET_DLP_LUMENS_LEVEL); }
    /** 176 setDlpLumensLevel(u8 1..10). Stock default 10 (FS 3.1). TSV W=writeUint8 R=. */
    public void setLampLevel(int level) throws RemoteException {
        callU8(SET_DLP_LUMENS_LEVEL, checkRange("lamp level", level, 1, 10));
    }

    /** 175 getDlpLumensMode() -> u8 (colour temperature; null if not 16/17). */
    public ColorTemp getColorTemp() throws RemoteException {
        int v = callRetU8(GET_DLP_LUMENS_MODE);
        for (ColorTemp c : ColorTemp.values()) if (c.wire == v) return c;
        return null;
    }
    /** 174 setDlpLumensMode(u8 16|17). Re-read the lamp level (177) afterwards (QS). TSV W=writeUint8 R=. */
    public void setColorTemp(ColorTemp c) throws RemoteException { callU8(SET_DLP_LUMENS_MODE, c.wire); }

    /** 196 getScreenOnOff(): light/screen on. TSV R=readBool. */
    public boolean getScreenOn() throws RemoteException { return callRetBool(GET_SCREEN_ON_OFF); }
    /** 195 setScreenOnOff(bool): lamp + lens door, as stock xgimiPrepareSleep (PW VERIFIED). TSV W=writeBool R=. */
    public void setScreenOn(boolean on) throws RemoteException { callBool(SET_SCREEN_ON_OFF, on); }

    /**
     * 151 / 152: smart eye protection (ToF human detect). Confirm before switching OFF (QS).
     * TSV 152 W=writeBool R=readBool. v6.2b: 152 only while the NUI fifo reader is up
     * ({@link NuiNotReadyException} otherwise; its full vendor path is not traced).
     */
    public boolean getEyeProtection() throws RemoteException { return callRetBool(GET_HUMAN_DETECT); }
    public boolean setEyeProtection(boolean on) throws RemoteException {
        requireNuiReader(SET_HUMAN_DETECT);
        return callBoolRetBool(SET_HUMAN_DETECT, on);
    }
    /** 147 getHumanDetectScreenOnOff(). TSV R=readBool. */
    public boolean getEyeProtectionScreenOn() throws RemoteException { return callRetBool(GET_HUMAN_DETECT_SCREEN); }
    /**
     * 148 setHumanDetectScreenOnOff(TRUE) only: give the light back after an eye-protection blank
     * (FS 5.1 safety rule "only ever send 148(true)"). TSV W=writeBool R=readBool.
     */
    public boolean restoreEyeProtectionScreen() throws RemoteException {
        requireNuiReader(SET_HUMAN_DETECT_SCREEN);              // v6.2b: 148 -> nativeShowUIHD(3) -> nuiCommand
        return callBoolRetBool(SET_HUMAN_DETECT_SCREEN, true);
    }

    /**
     * 115 uiHeartBeatSet(hidl_string name, i32 cmd, i32 periodMs) -> i8. cmd 1 arms the vendor UI
     * watchdog of HumDet_Process (thread "hd_heart-beat"), 0 disarms it. Without a 114 beat of the
     * same name within the period the vendor itself calls setHumanDetectScreenOnOff(1) (light
     * back, then focusEvent 601; VERIFIED vtable+0x34). Name and period are fixed by the caller
     * (stock "NativeUIWatch"); period 500..10000 ms. Only while the NUI reader is up (the timeout
     * path goes through nuiCommand). TSV W=writeBuffer+hidl_string,writeInt32,writeInt32 R=readInt8.
     */
    public byte uiHeartBeatSet(String name, boolean arm, int periodMs) throws RemoteException {
        checkWatchName(name);
        checkRange("heartbeat period", periodMs, 500, 10_000);
        requireNuiReader(UI_HEART_BEAT_SET);
        HwParcel q = request();
        q.writeString(name);                                       // hidl_string
        q.writeInt32(arm ? 1 : 0);
        q.writeInt32(periodMs);
        return call(UI_HEART_BEAT_SET, q, RET_I8);
    }

    /** 114 uiHeartBeatDo(hidl_string name) -> i8: one beat of the armed watchdog. TSV W=hidl_string R=readInt8. */
    public byte uiHeartBeatDo(String name) throws RemoteException {
        checkWatchName(name);
        requireNuiReader(UI_HEART_BEAT_DO);
        HwParcel q = request();
        q.writeString(name);                                       // hidl_string
        return call(UI_HEART_BEAT_DO, q, RET_I8);
    }

    /** Watchdog names: short ASCII identifiers only (the vendor strncmp's a fixed buffer). */
    private static void checkWatchName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,31}")) {
            throw new IllegalArgumentException("bad watchdog name: " + name);
        }
    }

    /** 438 getSystemTemperature() -> u8 deg C (Boost / overheat checks; threshold UNVERIFIED). */
    public int getSystemTemperature() throws RemoteException { return callRetU8(GET_SYSTEM_TEMPERATURE); }

    // ------------------------------------------------------------------ picture
    /** 666 getPictureMode(0) -> i32 (10..17 = AI). TSV W=writeInt32 R=readInt32. */
    public int getPictureModeRaw() throws RemoteException { return callI32RetI32(GET_PICTURE_MODE, WINDOW_MAIN); }
    /** 667 setPictureMode(0, m). Re-read every PQ getter afterwards (vendor side effects, FS 3.2). TSV W=writeInt32,writeInt32 R=readBool. */
    public boolean setPictureMode(PictureMode m) throws RemoteException {
        return callI32I32RetBool(SET_PICTURE_MODE, WINDOW_MAIN, m.wire);
    }
    /** 667 setPictureMode(0, 10..17): AI scene refinement. Only while the current mode is AI (FS 3.3). */
    public boolean setAiScene(AiScene s) throws RemoteException {
        return callI32I32RetBool(SET_PICTURE_MODE, WINDOW_MAIN, s.wire);
    }

    /** 692/694/690/688 get(0) -> 0..100. */
    public int getPq(PqItem item) throws RemoteException { return callI32RetI32(item.get, WINDOW_MAIN); }
    /** 693/695/691/689 set(0, v 0..100). Debounce sliders (200 ms, drop older values). */
    public boolean setPq(PqItem item, int value) throws RemoteException {
        return callI32I32RetBool(item.set, WINDOW_MAIN, checkRange(item.name(), value, 0, 100));
    }

    /** 668 getMemcLevel(0) -> 0..3. */
    public int getMemcLevel() throws RemoteException { return callI32RetI32(GET_MEMC_LEVEL, WINDOW_MAIN); }
    /** 669 setMemcLevel(0, l). TSV W=writeInt32,writeInt32 R=readBool. */
    public boolean setMemcLevel(MemcLevel l) throws RemoteException {
        return callI32I32RetBool(SET_MEMC_LEVEL, WINDOW_MAIN, l.wire);
    }




    /** 620 getHdrType() -> HDR_* constants. */
    public int getHdrType() throws RemoteException { return callRetI32(GET_HDR_TYPE); }



    /** 696 getCurrentInputSource() -> SOURCE_*. UNVERIFIED whether it reports 1/2 while our TIF plays HDMI (QS open question). */
    public int getCurrentInputSource() throws RemoteException { return callRetI32(GET_CURRENT_INPUT_SOURCE); }

    // ------------------------------------------------------------------ 3D
    /** 192 getCurrent3DFormat() -> u8. */
    public int getCurrent3DFormat() throws RemoteException { return callRetU8(GET_CURRENT_3D_FORMAT); }
    /** 193 is3DTo2DEnabled(). */
    public boolean is3DTo2DEnabled() throws RemoteException { return callRetBool(IS_3D_TO_2D_ENABLED); }

    // ------------------------------------------------------------------ HDMI / game / 120 Hz
    /** 664 getAspectRatio() -> i32. */
    public int getAspectRatioRaw() throws RemoteException { return callRetI32(GET_ASPECT_RATIO); }
    /** 665 setAspectRatio(i32). HDMI only. TSV W=writeInt32 R=readBool. */
    public boolean setAspectRatio(AspectRatio a) throws RemoteException { return callI32RetBool(SET_ASPECT_RATIO, a.wire); }

    /** 647 getGameModeOption() -> 0..3 (null otherwise). */
    public GameModeOption getGameModeOption() throws RemoteException {
        return GameModeOption.fromWire(callRetI32(GET_GAME_MODE_OPTION));
    }
    /**
     * 648 setGameModeOption(0..3). HDMI only. After TOP_SPEED(_HFR) stock also calls 207: use
     * {@link #resetKeystoneForTopSpeedGame()} and treat keystone as disabled. TSV W=writeInt32 R=readBool.
     */
    public boolean setGameModeOption(GameModeOption o) throws RemoteException {
        return callI32RetBool(SET_GAME_MODE_OPTION, o.wire);
    }

    // ------------------------------------------------------------------ v6.2 game module (BEGIN)
    /**
     * Decoded IGmTvType::stHdmiInfo of 662 getHdmiInfo (research/v62 hdmi-console).
     * Size VERIFIED 16 bytes (proxy 0x1bdbbc transacts 0x296; reply lambda 0x25e580 reads Status
     * then readBuffer(16), no bool before the struct; stub lambda 0x26a910 writeBuffer(..,16)).
     * Field ORDER is UNVERIFIED: taken from XGIMI's AIDL twin (u32Width, u32Height, bInterlace,
     * bIsHdmiMode, u32FrameRate) with natural alignment -> offsets 0/4/8/9/12. Frame rate unit
     * 1/100 Hz (stock compares getHDMIFrameRate() >= 6200). Callers must gate every action on
     * {@link #plausible()}; {@link #rawHex} is logged for the first device test.
     */
    public static final class HdmiSignal {
        public final int width, height, frameRateCenti;
        public final boolean interlace, hdmiMode;
        public final String rawHex;

        HdmiSignal(HwBlob b) {
            width = b.getInt32(0);
            height = b.getInt32(4);
            interlace = b.getInt8(8) != 0;
            hdmiMode = b.getInt8(9) != 0;
            frameRateCenti = b.getInt32(12);
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", b.getInt8(i) & 0xff));
            rawHex = sb.toString();
        }

        /** Refuse to act on a misdecoded struct or on "no signal" (all zero). */
        public boolean plausible() {
            return width >= 640 && width <= 7680 && height >= 480 && height <= 4320
                    && frameRateCenti >= 2300 && frameRateCenti <= 24500;
        }

        /** >= 100 Hz (stock hdmi_game_bt_toast: top speed is meant for inputs above 100 Hz). */
        public boolean isHighFrameRate() { return plausible() && frameRateCenti >= 10000; }

        @Override
        public String toString() {
            return width + "x" + height + (interlace ? "i" : "p") + "@" + frameRateCenti / 100f
                    + (hdmiMode ? " hdmi" : " dvi") + " raw=" + rawHex;
        }
    }

    /**
     * 662 getHdmiInfo(): current HDMI input timing. Read-only, no argument. A reply that is not a
     * 16-byte buffer fails only this call (ReplyException). Caller: game.GameProfile, polled every
     * few seconds only while an HDMI session of org.z9x.tvinput is on screen. TSV: W= (cb struct).
     */
    public HdmiSignal getHdmiInfo() throws RemoteException {
        return call(GET_HDMI_INFO, request(), r -> new HdmiSignal(r.readBuffer(16)));
    }
    // ------------------------------------------------------------------ v6.2 game module (END)

    /**
     * 161 setOutputTiming(4 on | 6 off): "Ultra 120 Hz". The screen blanks briefly; output drops to
     * 1080p. Caller must refuse while 3D is on or the HDMI input runs at >= 62 Hz (stock rule, DS);
     * the HDMI frame rate (662 getHdmiInfo struct) is NOT readable here (layout UNVERIFIED).
     * Read-back 157 getOutputTimingInfo is NOT implemented (struct layout UNVERIFIED): persist the
     * last value set. TSV W=writeInt32 R=readBool.
     */
    public boolean setOutputTiming(OutputTiming t) throws RemoteException { return callI32RetBool(SET_OUTPUT_TIMING, t.wire); }

    // ------------------------------------------------------------------ sound
    /** 62 getSoundeffect() -> u8 (null if not a known mode). TSV W= R=readUint8. */
    public SoundEffect getSoundEffect() throws RemoteException { return SoundEffect.fromWire(getSoundEffectRaw()); }
    /** 62 getSoundeffect() -> u8, raw 0..255 (v6.3.1 boot safety check of SoundProfiles). */
    public int getSoundEffectRaw() throws RemoteException { return callRetU8(GET_SOUND_EFFECT) & 0xff; }
    /**
     * 61 setSoundeffect(u8). TSV W=writeUint8 R=. Only AI / MOVIE / MUSIC / SPORTS / KARAOKE are sent
     * (AI = the vendor AUTO path over the same 4 real tables); refused otherwise (20 Standard has no
     * amp table). Callers: user action, and the boot safety check (61(1) Movie when 62 reads 20 or
     * an unmapped value, SoundProfiles.bootCheck). AI is sent on a user action only.
     */
    public void setSoundEffect(SoundEffect e) throws RemoteException {
        if (e != SoundEffect.AI && e != SoundEffect.MOVIE && e != SoundEffect.MUSIC
                && e != SoundEffect.SPORTS && e != SoundEffect.KARAOKE) {
            throw new IllegalArgumentException("sound mode " + e + " has no amp table: not sent");
        }
        noteSoundWrite();
        try { callU8(SET_SOUND_EFFECT, e.wire); } finally { noteSoundWrite(); }
    }

    /**
     * v6.4: elapsedRealtime of our last sound-mode call (61 / 63 / 44+27 / 42), before and after the
     * transaction. audio.VendorSoundWatch attributes a vendor _setSoundEffectsMode run seen shortly
     * after it to us (not a vendor restore). Any thread.
     */
    public static volatile long lastSoundWriteAt = Long.MIN_VALUE / 2;

    private static void noteSoundWrite() { lastSoundWriteAt = android.os.SystemClock.elapsedRealtime(); }

    /**
     * v6.4: 63 setAutoSoundeffect(u8 sub-mode), the AI-sound sub-mode (stock XRMService
     * GmAudioManager.setAutoSoundeffect). Msrv_AudioManage_Control::setAutoSoundEffectsMode
     * @0x159d104 only logs and stores the value at [this+0x178] (no DB, no amp I/O on the binder
     * thread, so it cannot block gmpf_main); the aud2_effect thread applies a change within its
     * 100 ms poll with one amp table write (~60 ms amp mute). It does NOT check that the mode is AI:
     * callers must have read 62 == 3 right before. Only MOVIE(1) / MUSIC(2) / SPORTS(12) /
     * KARAOKE(4) (real G0082 *_5805 tables); never 0 / 201 (vendor sentinels), 3, 5 (no
     * news_5805.cfg) or 20. Caller: audio.AiSoundEngine only. TSV W=writeUint8 R=.
     */
    public void setAutoSoundEffect(SoundEffect sub) throws RemoteException {
        if (sub != SoundEffect.MOVIE && sub != SoundEffect.MUSIC && sub != SoundEffect.SPORTS
                && sub != SoundEffect.KARAOKE) {
            throw new IllegalArgumentException("AI sub-mode " + sub + " has no amp table: not sent");
        }
        noteSoundWrite();
        try { callU8(SET_AUTO_SOUND_EFFECT, sub.wire); } finally { noteSoundWrite(); }
    }

    // ------------------------------------------------------------------ v6.4 SoC PEQ (equalizer)
    /** Whitelist of 50 SetPEQ: bands 1..13 (MTK basic-sound PEQ has 13 peaking bands). */
    public static final int PEQ_BANDS = 13;
    /**
     * Our gain limits for 50, in 0.1 dB (stricter than the HAL's -180..180): boosts up to +6 dB, cuts
     * down to -12 dB (a user cut of -6 dB plus the automatic preamp shift of up to -6 dB; cuts
     * cannot clip).
     */
    public static final int PEQ_GAIN_MIN = -120, PEQ_GAIN_MAX = 60;

    /**
     * v6.4: 50 SetPEQ(band, gainTenthDb, fcHz, qTenth) -> i32 (1 ok, 0 range/MI error). libporting
     * PlatformAudio::SetPEQ @0x29a2c stores the band and pushes it to the DSP (MI_AOUT_SetAttr 0x1001
     * on the speaker path) only while the PEQ is enabled; every band is a peaking filter.
     * Range-checked here: band 1..13, gain -120..60 (0.1 dB), fc 50..20000 Hz (MTK doc minimum 50),
     * Q 0.5..16.0 (5..160). Caller: audio.SoundEq only. TSV W=writeInt32 x4 R=readInt32.
     */
    public int setPeqBand(int band, int gainTenthDb, int fcHz, int qTenth) throws RemoteException {
        return callI32x4RetI32(SET_PEQ, checkRange("peq band", band, 1, PEQ_BANDS),
                checkRange("peq gain", gainTenthDb, PEQ_GAIN_MIN, PEQ_GAIN_MAX),
                checkRange("peq fc", fcHz, 50, 20_000), checkRange("peq q", qTenth, 5, 160));
    }

    /**
     * v6.4: 51 SetPEQEnable(bool) -> i32 (1 ok). true pushes all 13 stored bands and enables the
     * speaker PEQ; false bypasses it (bands stay stored). No getter exists. Caller: audio.SoundEq
     * only. TSV W=writeBool R=readInt32.
     */
    public int setPeqEnabled(boolean on) throws RemoteException { return callBoolRetI32(SET_PEQ_ENABLE, on); }

    /**
     * v6.3.1: 59 setAudioOutput(u8 path), the stock audioserver (libxgimiaudiopolicy setXgimiPath)
     * report of the current media output. Without it the vendor path stays 100, 27/44 only write
     * the DB and DTS never applies. Caller: audio.AudioPathReporter only (debounced, HAL thread).
     * TSV W=writeUint8 R=.
     */
    public void setAudioOutput(AudioPath p) throws RemoteException { callU8(SET_AUDIO_OUTPUT, p.wire); }

    /**
     * 60 getAudioOutput() -> u8. Msrv GetOutputType: the path the vendor saved in its DB at the last
     * successful SetOutputType (survives a reboot), 0 while its audio thread has not started.
     * Read together with vender.xgimi.audio.ready (AudioPathReporter). TSV W= R=readUint8.
     */
    public int getAudioOutputRaw() throws RemoteException { return callRetU8(GET_AUDIO_OUTPUT) & 0xff; }

    /** 43 getDtsEffectsEnable(). If true, the enhancement shows neither option (QS). */
    public boolean getDtsEffects() throws RemoteException { return callRetBool(GET_DTS_EFFECTS); }
    /** 26 getSoundProcessType() -> i32 (2 Harman, 5 DTS Virtual:X, others = unknown). */
    public int getSoundProcessRaw() throws RemoteException { return callRetI32(GET_SOUND_PROCESS_TYPE); }
    /**
     * Sound enhancement, stock setSoundProcessTypeFusion order: 44 setDtsEffectsEnable(false) then
     * 27 setSoundProcessType(2|5). User action only, never at boot. TSV 44 W=writeBool R=readBool;
     * 27 W=writeInt32 R=readBool.
     */
    public boolean setSoundEnhancement(SoundProcess p) throws RemoteException {
        noteSoundWrite();
        try {
            callBoolRetBool(SET_DTS_EFFECTS, false);
            return setSoundProcess(p);
        } finally {
            noteSoundWrite();
        }
    }

    /** 27 setSoundProcessType(2|5). Only through {@link #setSoundEnhancement}. TSV W=writeInt32 R=readBool. */
    private boolean setSoundProcess(SoundProcess p) throws RemoteException {
        return callI32RetBool(SET_SOUND_PROCESS_TYPE, p.wire);
    }

    /**
     * v6.3: 41 getVolumeBalanceEnable() (volume levelling). Read-back of the Z9X sound profile.
     * TSV W= R=readBool.
     */
    public boolean getVolumeBalance() throws RemoteException { return callRetBool(GET_VOLUME_BALANCE); }

    /**
     * v6.3: 42 setVolumeBalanceEnable(false), the only value sent (no volume-levelling UI). v6.3.1:
     * sent by a sound-enhancement choice only while 41 reads true, i.e. it restores the stock
     * default "off" (the vendor DB read enable:0 on this unit before we ever sent 42, 2026-10-03
     * logcat; volume_balance_enable is not in CustomerEnvTbl). In AMP mode "on" routes the SoC
     * through DTS TruVolume (RESULT_sound). User action only. TSV W=writeBool R=readBool.
     */
    public boolean disableVolumeBalance() throws RemoteException {
        noteSoundWrite();
        try { return callBoolRetBool(SET_VOLUME_BALANCE, false); } finally { noteSoundWrite(); }
    }



    // ------------------------------------------------------------------ wall colour

    // ------------------------------------------------------------------ power diagnostics (read-only)
    /** 230 getBootReason() -> i8. Logging only (PW phase 1). */
    public int getBootReason() throws RemoteException { return callRetI8(GET_BOOT_REASON); }
    /** 639 getWakeUpSource() -> i32. Logging only. */
    public int getWakeUpSource() throws RemoteException { return callRetI32(GET_WAKE_UP_SOURCE); }

    // ------------------------------------------------------------- private helpers
    private void callPidBinder(int code, int pid, IHwBinder cb) throws RemoteException {
        if (cb == null) throw new RemoteException("callback must be non-null");
        HwParcel q = request();
        q.writeInt32(pid);
        q.writeStrongBinder(cb);
        call(code, q, RET_VOID);
    }

    // ===================================================================== callback object
    /**
     * Java xgimi.hardware.gmpf@1.0::IProjectorFocusCallback.
     * focusEvent = transaction 1: token, i32 type, hidl_string value; reply Status(0) + bool(true).
     * IBase-reserved codes are answered like the hidl-gen Java IBase.Stub.
     */
    private static final class FocusCallback extends HwBinder implements IHwInterface {
        static final int FOCUS_EVENT = 1;
        static final int HIDL_DESCRIPTOR_CHAIN = 0x0f43484e;        // 256067662 interfaceChain
        static final int HIDL_DEBUG = 0x0f444247;                   // 256131655 debug
        static final int HIDL_GET_DESCRIPTOR = 0x0f445343;          // 256136003 interfaceDescriptor
        static final int HIDL_HASH_CHAIN = 0x0f485348;              // 256398152 getHashChain
        static final int HIDL_SET_HAL_INSTRUMENTATION = 0x0f494e54; // 256462420 oneway
        static final int HIDL_PING = 0x0f504e47;                    // 256921159 ping
        static final int HIDL_GET_REF_INFO = 0x0f524546;            // 257049926 getDebugInfo
        static final int HIDL_SYSPROPS_CHANGED = 0x0f535953;        // 257120595 oneway

        private static final byte[] HASH_SELF = hex("657623bcdeadd6be8c60deb99c61eecef80732eebedd3dad9ae54949dbd2611e");
        private static final byte[] HASH_BASE = hex("ec7fd79ed02dfa85bc499426adae3ebe23ef0524f3cd6957139324b83b18ca4c");

        @Override
        public void onTransact(int code, HwParcel req, HwParcel reply, int flags) {
            if (code == FOCUS_EVENT) {
                int type = -1;
                String value = null;
                boolean parsed = false;
                try {
                    req.enforceInterface(IPROJECTOR_FOCUS_CALLBACK);
                    type = req.readInt32();
                    value = req.readString();
                    parsed = true;
                } catch (Throwable t) {
                    Log.w(TAG, "focusEvent parse: " + t);
                }
                // Reply at once, also on a parse error: the vendor waits for Status + bool.
                try {
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    reply.writeBool(true);
                    reply.send();
                } catch (Throwable t) {
                    Log.w(TAG, "focusEvent reply: " + t);
                }
                if (parsed) {
                    EventSink s = sSink;
                    if (s != null) {
                        try { s.onFocusEvent(type, value); } catch (Throwable t) { Log.w(TAG, "sink: " + t); }
                    }
                }
                return;
            }
            try {
                switch (code) {
                    case HIDL_DESCRIPTOR_CHAIN: {
                        req.enforceInterface(IBASE);
                        ArrayList<String> chain = new ArrayList<>();
                        chain.add(IPROJECTOR_FOCUS_CALLBACK);
                        chain.add(IBASE);
                        reply.writeStatus(HwParcel.STATUS_SUCCESS);
                        reply.writeStringVector(chain);
                        reply.send();
                        break;
                    }
                    case HIDL_GET_DESCRIPTOR: {
                        req.enforceInterface(IBASE);
                        reply.writeStatus(HwParcel.STATUS_SUCCESS);
                        reply.writeString(IPROJECTOR_FOCUS_CALLBACK);
                        reply.send();
                        break;
                    }
                    case HIDL_PING: {
                        req.enforceInterface(IBASE);
                        reply.writeStatus(HwParcel.STATUS_SUCCESS);
                        reply.send();
                        break;
                    }
                    case HIDL_HASH_CHAIN: {
                        req.enforceInterface(IBASE);
                        reply.writeStatus(HwParcel.STATUS_SUCCESS);
                        HwBlob vec = new HwBlob(16);              // hidl_vec<uint8_t[32]>
                        vec.putInt32(8, 2);                       // mSize
                        vec.putBool(12, false);                   // mOwnsBuffer
                        HwBlob data = new HwBlob(2 * 32);
                        data.putInt8Array(0, HASH_SELF);
                        data.putInt8Array(32, HASH_BASE);
                        vec.putBlob(0, data);                     // mBuffer
                        reply.writeBuffer(vec);
                        reply.send();
                        break;
                    }
                    case HIDL_GET_REF_INFO: {                      // DebugInfo{i32 pid; u64 ptr; i32 arch}
                        req.enforceInterface(IBASE);
                        reply.writeStatus(HwParcel.STATUS_SUCCESS);
                        HwBlob info = new HwBlob(24);
                        info.putInt32(0, Process.myPid());
                        info.putInt64(8, 0L);
                        info.putInt32(16, 0);                     // Architecture.UNKNOWN
                        reply.writeBuffer(info);
                        reply.send();
                        break;
                    }
                    case HIDL_DEBUG: {
                        req.enforceInterface(IBASE);
                        req.readNativeHandle();
                        req.readStringVector();
                        reply.writeStatus(HwParcel.STATUS_SUCCESS);
                        reply.send();
                        break;
                    }
                    case HIDL_SET_HAL_INSTRUMENTATION:
                    case HIDL_SYSPROPS_CHANGED:
                    default:
                        // oneway IBase calls, linkToDeath/unlinkToDeath and unknown codes: no reply
                        break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "IBase code " + code + ": " + t);
            }
        }

        @Override public IHwInterface queryLocalInterface(String descriptor) {
            return IPROJECTOR_FOCUS_CALLBACK.equals(descriptor) || IBASE.equals(descriptor) ? this : null;
        }
        @Override public IHwBinder asBinder() { return this; }
        @Override public boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie) { return true; }
        @Override public boolean unlinkToDeath(IHwBinder.DeathRecipient recipient) { return true; }
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    // ================================================================== PARKED (not compiled)
    // No reviewed caller outside hal/ (check_hal_callers.py fails the build on a transacting
    // method without one). Kept as reference: re-enable a method only together with its caller,
    // and only after a device test where its argument values are marked UNVERIFIED.
    //
    // /**
    //  * 307 sendFocusStatus(5) then (3): our AF test card (focusEvent 333/334/335) was closed
    //  * (FS 2.7, stock FocusUIV2 dapeng branch). TSV: W=writeUint8 R=. Only after a card was shown.
    //  */
    // public void sendAfCardClosed() throws RemoteException {
    //     callU8(SEND_FOCUS_STATUS, FOCUS_STATUS_AF_CARD_CLOSED);
    //     callU8(SEND_FOCUS_STATUS, FOCUS_STATUS_AF_CARD_DONE);
    // }
    //
    // /** 121 zoomStart(0) / 120 zoomFinish(0): wrap a zoom & shift session (FS 2.5). TSV W=writeInt32 R=readInt32. */
    // public int zoomSessionStart() throws RemoteException { return callI32RetI32(ZOOM_START, 0); }
    //
    // public int zoomSessionFinish() throws RemoteException { return callI32RetI32(ZOOM_FINISH, 0); }
    //
    // /**
    //  * 141 translationKst(dir, onlyCheck) -> i32 (-1 = not possible). Stock calls it with onlyCheck=true
    //  * first and repeats with false only when the result is not -1 (FS 2.5).
    //  * TSV W=writeInt32,writeBool R=readInt32.
    //  */
    // public int shiftPicture(ShiftDir d, boolean onlyCheck) throws RemoteException {
    //     HwParcel q = request();
    //     q.writeInt32(d.wire);
    //     q.writeBool(onlyCheck);
    //     return call(TRANSLATION_KST, q, RET_I32);
    // }
    //
    // /** 87 / 88: eye protection mode, 0 follow / 1 safe. TSV 88 W=writeInt32 R=readBool. */
    // public int getEyeProtectionMode() throws RemoteException { return callRetI32(GET_HUMAN_DETECT_MODE); }
    //
    // public boolean setEyeProtectionMode(HumanDetectMode m) throws RemoteException {
    //     return callI32RetBool(SET_HUMAN_DETECT_MODE, m.wire);
    // }
    //
    // /** 670 get3DNR(0) -> 0..4. */
    // public int getNoiseReduction() throws RemoteException { return callI32RetI32(GET_3DNR, WINDOW_MAIN); }
    //
    // /** 671 set3DNR(0, n). TSV W=writeInt32,writeInt32 R=readBool. */
    // public boolean setNoiseReduction(NoiseReduction n) throws RemoteException {
    //     return callI32I32RetBool(SET_3DNR, WINDOW_MAIN, n.wire);
    // }
    //
    // /** 651 getUcdLevel(0) -> 0..3 ("local contrast"). */
    // public int getLocalContrast() throws RemoteException { return callI32RetI32(GET_UCD_LEVEL, WINDOW_MAIN); }
    //
    // /** 655 setUcdLevel(0, 0..3). TSV W=writeInt32,writeInt32 R=readBool. */
    // public boolean setLocalContrast(int level) throws RemoteException {
    //     return callI32I32RetBool(SET_UCD_LEVEL, WINDOW_MAIN, checkRange("ucd level", level, 0, 3));
    // }
    //
    // /**
    //  * 111 getDispColorSpace(source, picMode) -> i32. UNVERIFIED argument meaning (FS 3.5 "read
    //  * 111(source, picMode)"): pass getCurrentInputSource() and getPictureModeRaw().
    //  */
    // public int getColorSpaceRaw(int source, int pictureMode) throws RemoteException {
    //     return callI32I32RetI32(GET_DISP_COLOR_SPACE, checkRange("source", source, 0, 2),
    //             checkRange("picture mode", pictureMode, 0, 40));
    // }
    //
    // /** 112 switchDispColorSpace(cs) -> i32. Not available in Boost (FS 3.5). TSV W=writeInt32 R=readInt32. */
    // public int setColorSpace(ColorSpace cs) throws RemoteException { return callI32RetI32(SWITCH_DISP_COLOR_SPACE, cs.wire); }
    //
    // /** 623 getDolbyVisionPicMode() -> 0 bright / 1 soft. Practically unreachable on this SKU (QS verdict). */
    // public int getDolbyVisionPicMode() throws RemoteException { return callRetI32(GET_DOLBY_VISION_PIC_MODE); }
    //
    // /**
    //  * 624 setDolbyVisionPicMode(0 bright | 1 soft). GATED: refused unless 620 getHdrType() == 2 right
    //  * now (Dolby content playing). The Z9X SKU has no DV (SupportHashkeyMode=8), so expect it to be
    //  * refused; never advertise DV. TSV W=writeInt32 R=readBool.
    //  */
    // public boolean setDolbyVisionPicMode(boolean soft) throws RemoteException {
    //     int hdr = getHdrType();
    //     if (hdr != HDR_DOLBY_VISION) throw new IllegalStateException("Dolby Vision picture mode needs hdrType 2 (now " + hdr + ")");
    //     return callI32RetBool(SET_DOLBY_VISION_PIC_MODE, soft ? 1 : 0);
    // }
    //
    // /** 180 isVideoPlaying(). */
    // public boolean isVideoPlaying() throws RemoteException { return callRetBool(IS_VIDEO_PLAYING); }
    //
    // /** 190 enable3D(u8 1|2|3). Only while isVideoPlaying (FS 3.6); resets software keystone. TSV W=writeUint8 R=readBool. */
    // public boolean set3DMode(Mode3D m) throws RemoteException { return callU8RetBool(ENABLE_3D, m.wire); }
    //
    // /** 191 enable3DTo2D(u8 2 SBS | 3 TB). TSV W=writeUint8 R=readBool. */
    // public boolean set3DTo2D(boolean topBottom) throws RemoteException { return callU8RetBool(ENABLE_3D_TO_2D, topBottom ? 3 : 2); }
    //
    // (41 / 42 volume levelling: un-parked in v6.3 for the Z9X sound profile, see the sound section.)
    //
    // /** 420 / 419: power-on sound (vendor plays it). TSV 419 W=writeBool R=. */
    // public boolean getPowerOnMusic() throws RemoteException { return callRetBool(IS_POWER_ON_MUSIC_ENABLED); }
    //
    // public void setPowerOnMusic(boolean on) throws RemoteException { callBool(ENABLE_POWER_ON_MUSIC, on); }
    //
    // /**
    //  * 106 startAutoColor() -> bool. Stock sequence (FS 2.8): IGmpf2 53 setAEOnOff(false) first
    //  * (Gmpf2Client.disableAutoExposureForWallColor), then this. Never 99/101/102 (calibration).
    //  */
    // public boolean startWallColorAdaptation() throws RemoteException { return callRetBool(START_AUTO_COLOR); }
    //
    // /** 100 setAutoColorDefault() -> bool ("Default" button of the wall colour dialog). */
    // public boolean resetWallColorAdaptation() throws RemoteException { return callRetBool(SET_AUTO_COLOR_DEFAULT); }
    //
    // /** 249 / 250: auto colour after AK (advanced switch only). TSV 250 W=writeBool R=. */
    // public boolean getAutoColorAfterAk() throws RemoteException { return callRetBool(GET_PICTURE_ADJUST_AUTO_COLOR); }
    //
    // public void setAutoColorAfterAk(boolean on) throws RemoteException { callBool(SET_PICTURE_ADJUST_AUTO_COLOR, on); }
}
