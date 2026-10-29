package org.lumen.projector.hal;

import android.os.HwBlob;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.Process;
import android.os.RemoteException;

/**
 * Play6Gmpf: the typed IGmpf client of the XGIMI Play 6 (xgimi.hardware.gmpf@1.0::IGmpf/default, a
 * 32-bit vendor). The stock boot handshake members come first and stay exactly as tested (t1..t11,
 * check K: tools/play6/check_handshake.py); every other call gets its own typed method below them.
 *
 * Codes: research/play6/gmpf_codes_play6.tsv, read from the Play 6 vendor proxy library (BpHwGmpf:
 * the same 702 codes as on the Z9X, research/play6/gmpf_diff.txt). Wire types as in Z9xProjector
 * GmpfClient: 391 setSystemUIState(i32), 237 goToBootCompleted(i32) -> bool, 394 getBootAnimState()
 * -> i32, 395 setBootAnimState(i32). The transport (main-thread refusal, two-way, ReplyException)
 * is the shared HidlCaller.
 *
 * Lumen P1 (docs/play6/SETTINGS_PLAN.md section 5 step 10, Notes A, B, C; check H:
 * tools/play6/check_play6_hal.py):
 *  - wire types of the new methods: research/play6/z9x_gmpf_codes.txt (same argument lists on both
 *    devices, C 2.1), quoted per method as "W=... R=...";
 *  - every code constant carries `// tsv:<method>` (checked against the TSV by name), every value
 *    constant `// value`; nothing of the Note A ban list is declared (no 301, 181, 158, 114 / 115,
 *    207 / 129 until owner decision O3, no power state or calibration call). IGmpf 235 / 242 left the
 *    ban list when the owner reversed decision O2 (research battery_sound_features.md 6.1 B2): Lumen
 *    registers with the vendor power manager as stock does, and only receives events;
 *  - the special values of Note C leave only from the methods named there: 307 with 44 only from
 *    requestAutofocus, with 17 only from sendPowerOnUiReady (caller power/UiReady17 only); 667 never
 *    carries 4 (t15: the vendor refuses it in its independent game mode, IGmpf2 35 sets the Game picture);
 *  - only methods with a reviewed caller outside hal/ are compiled (check H rule 6 =
 *    apps/check_hal_callers.py fails the build on a transacting method without a caller); the setters
 *    of the P1 pages came with their callers in plan step 16, the eye protection setter waits in the
 *    PARKED block at the end for the NUI fifo reader (step 18);
 *  - the hwbinder callback of 298 / 306 is Play6FocusCallback, the one of 235 / 242 is Play6PowerCallback
 *    (both answer IBase with the hash recorded offline).
 */
public final class Play6Gmpf extends HidlCaller {
    public static final String IGMPF = "xgimi.hardware.gmpf@1.0::IGmpf";

    private static final int GO_TO_BOOT_COMPLETED = 237;   // (i32) -> bool
    private static final int SET_SYSTEM_UI_STATE = 391;    // (i32) -> void
    private static final int GET_BOOT_ANIM_STATE = 394;    // ()    -> i32
    private static final int SET_BOOT_ANIM_STATE = 395;    // (i32) -> void

    private static final int SYSTEM_UI_STATE_READY = 1;      // stock SystemUI systemUIServicesCompleted
    private static final int BOOT_ANIM_STATE_FINISHED = 2;   // stock bootanimation exit

    /** goToBootCompleted arguments: 0 early (stock XRMService.onCreate), 1 after the boot animation. */
    public static final int STAGE_EARLY = 0, STAGE_AFTER_BOOTANIM = 1;

    public Play6Gmpf() {
        super(IGMPF);
    }

    /** HwBinder.getService(IGmpf, "default"), no wait, no death recipient. Never throws. */
    public boolean connect() {
        return connectInternal(null, 0);
    }

    /** 391 setSystemUIState(1). */
    public void setSystemUiReady() throws RemoteException { callI32(SET_SYSTEM_UI_STATE, SYSTEM_UI_STATE_READY); }

    /** 395 setBootAnimState(2). */
    public void setBootAnimFinished() throws RemoteException { callI32(SET_BOOT_ANIM_STATE, BOOT_ANIM_STATE_FINISHED); }

    /** 394 getBootAnimState(). */
    public int getBootAnimState() throws RemoteException { return callRetI32(GET_BOOT_ANIM_STATE); }

    /** 237 goToBootCompleted(stage). The caller guarantees once per boot per stage. */
    public boolean goToBootCompleted(int stage) throws RemoteException {
        return callI32RetBool(GO_TO_BOOT_COMPLETED, checkRange("stage", stage, STAGE_EARLY, STAGE_AFTER_BOOTANIM));
    }

    // ============================================================ Lumen P1 (plan section 5 step 10)
    // Codes (private; "W=... R=..." from research/play6/z9x_gmpf_codes.txt).
    // focus
    private static final int CONNECT_PROJECTOR_FOCUS_MANAGER = 298;  // tsv:connectProjectorFocusManager W=i32,binder R=
    private static final int MANUAL_FOCUS = 300;                     // tsv:manualFocus W=u8 R=
    private static final int SET_PROJECTOR_FOCUS_LISTENER = 306;     // tsv:setProjectorFocusListener W=i32,binder R=
    private static final int SEND_FOCUS_STATUS = 307;                // tsv:sendFocusStatus W=u8 R=
    private static final int SET_ANG_TRIGGER_AF = 587;               // tsv:setAngTriggerAF W=bool R=bool
    private static final int GET_ANG_TRIGGER_AF = 588;               // tsv:getAngTriggerAF W= R=bool
    // keystone
    private static final int GET_CURRENT_KEYSTONE_MODE = 130;        // tsv:getCurrentKeystoneMode W= R=i32
    private static final int CHECK_TRAPEZOID_COORDINATE = 150;       // tsv:checkTrapezoidCoordinate W=KstPoint R=bool
    private static final int CORRECT_KEYSTONE_FULL_POINT = 185;      // tsv:correctKeystoneFullPiont W=KstPoint R=i32
    private static final int GET_CORRECT_KEYSTONE_FULL_POINT = 186;  // tsv:getCorrectKeystoneFullPiont W=u8 R=cb KstPoint
    private static final int CORRECT_KEYSTONE_RESET = 207;           // tsv:correctKeystoneReset W=u8 R= (owner O3, 2026-10-21)
    private static final int SWITCH_KEYSTONE_MODE = 129;             // tsv:switchKeystoneMode W=i32,i32 R=bool (owner O3)
    private static final int SET_DLP_RGB_LEVEL = 178;                // tsv:setDlpLumensComponent_RGB_Level W=stDisplayRgB R=
    private static final int GET_DLP_RGB_LEVEL = 179;                // tsv:getDlpLumensComponent_RGB_Level W= R=cb stDisplayRgB
    private static final int AUTO_KST = 326;                         // tsv:autoKst W= R=i8
    // digital zoom and shift (stock ZoomDisplacementWind, non-GMPF2 path; P3 item 21)
    private static final int GET_CURRENT_ZOOM_STEP2 = 116;           // tsv:getCurrentZoomStep2 W= R=cb(i32, st_ZoomStep)
    private static final int GET_ZOOM_STEP_RANGE = 117;              // tsv:getZoomStepRange W= R=cb(i32, st_ZoomStepRange)
    private static final int SET_DIGITAL_ZOOM_STEP = 118;            // tsv:setDigitalZoomStep W=i32 R=i32
    private static final int ZOOM_FINISH = 120;                      // tsv:zoomFinish W=i32 R=i32
    private static final int ZOOM_START = 121;                       // tsv:zoomStart W=i32 R=i32
    private static final int TRANSLATION_KST = 141;                  // tsv:translationKst W=i32,bool R=i32
    private static final int GET_SYSTEM_UI_STATE = 390;              // tsv:getSystemUIState W= R=i32
    private static final int SAVE_POWER_ON_AK_FLAG = 552;            // tsv:savePowerOnAKFlag W=bool R=
    private static final int GET_POWER_ON_AK_FLAG = 553;             // tsv:getPowerOnAKFlag W= R=bool
    private static final int TEMPORARY_SWITCH_TRIGGER = 573;         // tsv:temporarySwitchTrigger W=i32,bool R=bool
    private static final int SET_ACC_TRIGGER_AK = 585;               // tsv:setAccTriggerAK W=bool R=bool
    private static final int GET_ACC_TRIGGER_AK = 586;               // tsv:getAccTriggerAK W= R=bool
    // light and picture
    private static final int SET_SCREEN_ON_OFF = 195;                // tsv:setScreenOnOff W=bool R=
    private static final int GET_SCREEN_ON_OFF = 196;                // tsv:getScreenOnOff W= R=bool
    private static final int SET_DLP_LUMENS_LEVEL = 176;             // tsv:setDlpLumensLevel W=u8 R=
    private static final int GET_DLP_LUMENS_LEVEL = 177;             // tsv:getDlpLumensLevel W= R=u8
    private static final int GET_HDR_TYPE = 620;                     // tsv:getHdrType W= R=i32
    private static final int GET_PICTURE_MODE = 666;                 // tsv:getPictureMode W=i32 R=i32
    private static final int SET_PICTURE_MODE = 667;                 // tsv:setPictureMode W=i32,i32 R=bool
    private static final int GET_MEMC_LEVEL = 668;                   // tsv:getMemcLevel W=i32 R=i32
    private static final int SET_MEMC_LEVEL = 669;                   // tsv:setMemcLevel W=i32,i32 R=bool
    private static final int GET_SHARPNESS = 688;                    // tsv:getSharpness W=i32 R=i32
    private static final int SET_SHARPNESS = 689;                    // tsv:setSharpness W=i32,i32 R=bool
    private static final int GET_SATURATION = 690;                   // tsv:getSaturation W=i32 R=i32
    private static final int SET_SATURATION = 691;                   // tsv:setSaturation W=i32,i32 R=bool
    private static final int GET_BRIGHTNESS = 692;                   // tsv:getBrightness W=i32 R=i32
    private static final int SET_BRIGHTNESS = 693;                   // tsv:setBrightness W=i32,i32 R=bool
    private static final int GET_CONTRAST = 694;                     // tsv:getContrast W=i32 R=i32
    private static final int SET_CONTRAST = 695;                     // tsv:setContrast W=i32,i32 R=bool
    // picture, stock parity (dayao tree, Custom picture mode and all modes; inventory B 4.2)
    private static final int SET_DLP_LUMENS_MODE = 174;              // tsv:setDlpLumensMode W=u8 R=
    private static final int GET_DLP_LUMENS_MODE = 175;              // tsv:getDlpLumensMode W= R=u8
    private static final int GET_HDR_ENABLE = 649;                   // tsv:getHdrEnable W= R=bool
    private static final int SET_HDR_ENABLE = 650;                   // tsv:setHdrEnable W=bool R=bool
    private static final int GET_UCD_LEVEL = 651;                    // tsv:getUcdLevel W=i32 R=i32
    private static final int SET_UCD_LEVEL = 655;                    // tsv:setUcdLevel W=i32,i32 R=bool
    private static final int RESET_PQ_SETTINGS = 658;                // tsv:resetPqSettings W=i32 R=i32
    private static final int GET_3DNR = 670;                         // tsv:get3DNR W=i32 R=i32
    private static final int SET_3DNR = 671;                         // tsv:set3DNR W=i32,i32 R=bool
    private static final int GET_BLUE_OFFSET = 672;                  // tsv:getColorTempBlueOffset W=i32 R=i32
    private static final int SET_BLUE_OFFSET = 673;                  // tsv:setColorTempBlueOffset W=i32,i32 R=bool
    private static final int GET_GREEN_OFFSET = 674;                 // tsv:getColorTempGreenOffset W=i32 R=i32
    private static final int SET_GREEN_OFFSET = 675;                 // tsv:setColorTempGreenOffset W=i32,i32 R=bool
    private static final int GET_RED_OFFSET = 676;                   // tsv:getColorTempRedOffset W=i32 R=i32
    private static final int SET_RED_OFFSET = 677;                   // tsv:setColorTempRedOffset W=i32,i32 R=bool
    private static final int GET_BLUE_GAIN = 678;                    // tsv:getColorTempBlueGain W=i32 R=i32
    private static final int SET_BLUE_GAIN = 679;                    // tsv:setColorTempBlueGain W=i32,i32 R=bool
    private static final int GET_GREEN_GAIN = 680;                   // tsv:getColorTempGreenGain W=i32 R=i32
    private static final int SET_GREEN_GAIN = 681;                   // tsv:setColorTempGreenGain W=i32,i32 R=bool
    private static final int GET_RED_GAIN = 682;                     // tsv:getColorTempRedGain W=i32 R=i32
    private static final int SET_RED_GAIN = 683;                     // tsv:setColorTempRedGain W=i32,i32 R=bool
    private static final int GET_COLOR_TEMPERATURE = 684;            // tsv:getColorTemperature W=i32 R=i32
    private static final int SET_COLOR_TEMPERATURE = 685;            // tsv:setColorTemperature W=i32,i32 R=bool
    // input, HDMI, game
    private static final int GET_HDMI_AUTO_SWITCH = 402;             // tsv:getHdmiAutoSwitch W= R=bool
    private static final int GET_GAME_MODE_OPTION = 647;             // tsv:getGameModeOption W= R=i32
    private static final int SET_GAME_MODE_OPTION = 648;             // tsv:setGameModeOption W=i32 R=bool
    private static final int GET_ASPECT_RATIO = 664;                 // tsv:getAspectRatio W= R=i32
    private static final int SET_ASPECT_RATIO = 665;                 // tsv:setAspectRatio W=i32 R=bool
    private static final int GET_HDMI_INFO = 662;                    // tsv:getHdmiInfo W= R=cb stHdmiInfo
    private static final int GET_EARC_ENABLE_STATE = 632;            // tsv:getEARCEnableState W= R=bool
    private static final int SET_EARC_ENABLE = 633;                  // tsv:setEARCEnable W=bool R=bool
    private static final int GET_CURRENT_INPUT_SOURCE = 696;         // tsv:getCurrentInputSource W= R=i32
    private static final int SWITCH_INPUT_SOURCE_WITH_CALLING_PID = 698; // tsv:switchInputSourceWithCallingPid W=i32,u32 R=bool
    private static final int GET_SIGNAL_STATUS = 592;                // tsv:getSignalStatus W= R=i32
    private static final int SET_DISPLAY_AREA = 659;                 // tsv:setDisplayArea W=i32,i32,i32,i32 R=bool
    private static final int GET_HDMI_RANGE = 634;                   // tsv:getHdmiRange W= R=i8
    private static final int SET_HDMI_RANGE = 635;                   // tsv:setHdmiRange W=i8 R=bool
    private static final int SET_EDID_VERSION = 660;                 // tsv:setEdidVersion W=i32 R=bool
    private static final int GET_EDID_VERSION = 661;                 // tsv:getEdidVersion W= R=i32
    // music player (the ambient speaker's device-closed event, stock XRM MachineStateService) and boot music
    private static final int CONNECT_MUSIC_PLAYER_MANAGER = 215;     // tsv:connectMusicPlayerManager W=i32,binder R=
    private static final int SET_MUSIC_PLAYER_LISTENER = 227;        // tsv:setMusicPlayerListener W=i32,binder R=
    private static final int ENABLE_POWER_ON_MUSIC = 419;            // tsv:enablePowerOnMusic W=bool R=
    private static final int IS_POWER_ON_MUSIC_ENABLED = 420;        // tsv:isPowerOnMusicEnabled W= R=bool
    // sound
    private static final int SET_SOUND_EFFECT = 61;                  // tsv:setSoundeffect W=u8 R=
    private static final int GET_SOUND_EFFECT = 62;                  // tsv:getSoundeffect W= R=u8
    // equalizer (owner 2026-10-21, the Z9X SoundEq on the Play 6 SoC PEQ of the built-in speaker path)
    private static final int GET_SOUND_PROCESS_TYPE = 26;            // tsv:getSoundProcessType W= R=i32
    private static final int GET_DTS_EFFECTS_ENABLE = 43;            // tsv:getDtsEffectsEnable W= R=bool
    private static final int SET_PEQ = 50;                           // tsv:SetPEQ W=i32,i32,i32,i32 R=i32
    private static final int SET_PEQ_ENABLE = 51;                    // tsv:SetPEQEnable W=bool R=i32
    private static final int SET_AUDIO_OUTPUT = 59;                  // tsv:setAudioOutput W=u8 R=
    private static final int GET_AUDIO_OUTPUT = 60;                  // tsv:getAudioOutput W= R=u8
    // projection, eye protection
    private static final int GET_HUMAN_DETECT = 151;                 // tsv:getHumanDetectOnOff W= R=bool
    private static final int SET_HUMAN_DETECT = 152;                 // tsv:setHumanDetectOnOff W=bool R=bool
    private static final int SET_HUMAN_DETECT_SCREEN = 148;          // tsv:setHumanDetectScreenOnOff W=bool R=bool (true only)
    private static final int SET_PROJECTOR_PUT_MODE = 172;           // tsv:setProjectorPutMode W=u8 R=
    private static final int GET_PROJECTOR_PUT_MODE = 173;           // tsv:getProjectorPutMode W= R=u8
    private static final int GET_AUTO_REVERSE = 579;                 // tsv:getAutoReverse W= R=bool
    private static final int SET_AUTO_REVERSE = 580;                 // tsv:setAutoReverse W=bool R=bool
    // battery, power board, system (read only; plan section 3)
    private static final int CONNECT_POWER_MANAGER = 235;            // tsv:connectPowerManager W=i32,binder R=
    private static final int IS_ADAPTER_POWERED = 238;               // tsv:isAdapterPowered W= R=bool
    private static final int IS_BATTERY_CHARGED_FULL = 239;          // tsv:isBatteryChargedFull W= R=bool
    private static final int GET_BATTERY_LEVEL = 240;                // tsv:getBatteryLevel W= R=u8
    private static final int SET_POWER_LISTENER = 242;               // tsv:setPowerListener W=i32,binder R=
    private static final int GET_ECO_STATE = 327;                    // tsv:getEcoState W= R=bool
    private static final int SET_ECO_STATE = 328;                    // tsv:setEcoState W=bool R=
    private static final int GET_ALL_FEATURE_LIST = 404;             // tsv:getAllFeatureList W= R=cb hidl_string
    private static final int GET_SYSTEM_TEMPERATURE = 438;           // tsv:getSystemTemperature W= R=u8

    // Fixed argument values (no free-form value is accepted from callers).
    private static final int FOCUS_STATUS_REMOTE_AF = 44;     // value stock FocusUIV2.autoFocus (remote focus key)
    private static final int FOCUS_STATUS_UI_READY = 17;      // value stock FocusUIV2.start: power-on AF / AK flow
    private static final int WINDOW_MAIN = 0;                 // value picture calls address window 0 (stock XgimiVideo)
    private static final int KST_MODE_FOUR_POINT = 0;         // value 186 mode 0 = 4 corners (Play6KstPoint)
    private static final int TEMPORARY_SWITCH_AK = 2;         // value KeyStoneManager TEMPORARY_SWITCH_AK
    private static final int KST_RESET_TYPE = 0;              // value 207 argument of stock V3DisplayManager.setIChipReset()
    private static final int KST_MODE_SIX = 6;                // value 130 reply after which stock sends 129(0, 0)
    private static final int KST_SWITCH_DEFAULT = 0;          // value 129 arguments (0, 0) of stock setIChipReset
    private static final int RGB_LEVEL_SIZE = 4;              // value stDisplayRgB: R, G, B, C (u8 each)
    private static final int RGB_LEVEL_MIN = 1;               // value stock brightness.json light_r_v4 / light_g / light_b
    private static final int RGB_LEVEL_MAX = 10;              // value
    private static final int RGB_LEVEL_C = 0;                 // value C byte, stock BrightnessManager.setRGB sends 0
    public static final int MANUAL_FOCUS_ENTER = 17;          // value stock FocusAdjust: overlay opened
    public static final int MANUAL_FOCUS_STOCK_RIGHT = 0;     // value stock DPAD_RIGHT down
    public static final int MANUAL_FOCUS_STOCK_LEFT = 1;      // value stock DPAD_LEFT down
    public static final int MANUAL_FOCUS_STOP = 2;            // value stop the motor
    // picture modes of the Play 6 (stock dayao tree, plan 1.4); 4 = MStar Game mode, only from game mode
    public static final int PICTURE_AI = 10;                  // value
    public static final int PICTURE_MOVIE = 1;                // value
    public static final int PICTURE_SPORT = 9;                // value
    public static final int PICTURE_TV = 7;                   // value
    public static final int PICTURE_CUSTOM = 3;               // value
    public static final int PICTURE_OFFICE = 25;              // value
    public static final int PICTURE_PERFORMANCE = 5;          // value
    public static final int PICTURE_GAME = 4;                 // value 666 reply only: IGmpf2 35(0) sets it (t15)
    // aspect ratio, the vendor values of 665 / 664 (libxgimi Device_Scaler::setAspectRatio, SDK GmTvManager
    // RATIO_*; research hdmi_volume_game.md 4): 0 4:3, 1 16:9, 2 full screen (our "Auto"), 3 point to point,
    // 4 original; 5 and up return false (but the vendor DB keeps them). The stock menu's "common" values
    // (auto 3, 16:9 1, 4:3 2, original 5) are converted by stock before 665; t14 sent them raw.
    public static final int ASPECT_4_3 = 0;                   // value
    public static final int ASPECT_16_9 = 1;                  // value
    public static final int ASPECT_AUTO = 2;                  // value full screen
    public static final int ASPECT_POINT = 3;                 // value 664 reply only (shown as Original)
    public static final int ASPECT_ORIGINAL = 4;              // value
    public static final int ASPECT_MAX = 4;                   // value a 664 reply above it is repaired with 665(2)
    private static final int HDMI_INFO_SIZE = 16;             // value stHdmiInfo {u32 w, u32 h, bool, bool, u32 fps}
    // sound modes (plan 1.6): 3 AI, 2 Music, 1 Movie, 4 Karaoke, 12 Sports, 20 Standard
    public static final int SOUND_AI = 3;                     // value
    public static final int SOUND_MUSIC = 2;                  // value
    public static final int SOUND_MOVIE = 1;                  // value
    public static final int SOUND_KARAOKE = 4;                // value
    public static final int SOUND_SPORTS = 12;                // value
    public static final int SOUND_STANDARD = 20;              // value
    // SoC PEQ (libporting PlatformTvCtrl::SetPEQ @0x37530, research 2026-10-21): band 1..13, gain 0.1 dB, fc Hz,
    // Q 0.1; the vendor accepts gain -180..180; Lumen sends -120..+60 only
    public static final int PEQ_BANDS = 13;                   // value
    public static final int PEQ_GAIN_MIN = -120;              // value
    public static final int PEQ_GAIN_MAX = 60;                // value
    public static final int PEQ_FC_MIN = 50;                  // value
    public static final int PEQ_FC_MAX = 20000;               // value
    public static final int PEQ_Q_MIN = 5;                    // value
    public static final int PEQ_Q_MAX = 160;                  // value
    /** 26: process 2 = AMP (the Play 6 default, CustomerEnvTbl sound_process_type 2); 3..5 Dolby / DTS. */
    public static final int SOUND_PROCESS_AMP = 2;            // value
    /** 60: output path 0 = the built-in speaker (the path the PEQ sits on). */
    public static final int AUDIO_OUTPUT_SPEAKER = 0;         // value

    /** getCurrentInputSource replies (stock): 0 media / storage, 1 HDMI (the Play 6 has one port, ARC). */
    // 59 setAudioOutput paths (EN_XGIMI_AUDIO_PATH, the Z9X GmpfClient.AudioPath set): never 1 (SPDIF) or 7..13
    public static final int AUDIO_PATH_SPEAKER = 0;           // value
    public static final int AUDIO_PATH_ARC = 2;               // value
    public static final int AUDIO_PATH_A2DP = 3;              // value
    public static final int AUDIO_PATH_WIRED = 4;             // value
    public static final int AUDIO_PATH_USB = 6;               // value
    public static final int AUDIO_PATH_SWITCHING = 100;       // value
    public static final int SOURCE_MEDIA = 0, SOURCE_HDMI1 = 1;   // value 696 replies, 698 sources
    // the full 1920 x 1080 panel for 659 (stock player signalIn, full window; HDMI fallback only)
    private static final int AREA_ORIGIN = 0;                 // value 659 left / top
    private static final int AREA_WIDTH = 1920;               // value 659 width (the Play 6 panel)
    private static final int AREA_HEIGHT = 1080;              // value 659 height
    // picture style (174 setDlpLumensMode, stock color_style_*: standard 0, Movie D65 16, Office CVIA 12, custom 6)
    public static final int STYLE_STANDARD = 0;               // value
    public static final int STYLE_MOVIE_D65 = 16;             // value
    public static final int STYLE_OFFICE = 12;                // value
    public static final int STYLE_CUSTOM = 6;                 // value
    private static final int RESET_CUSTOM_PICTURE = 3;        // value 658: stock PictureViewModel resetPictureMode(3)
    public static final int HDMI_RANGE_AUTO = 0;              // value 635: 0 auto, 1 full, 2 limited
    public static final int HDMI_RANGE_LIMITED = 2;           // value
    public static final int EDID_14 = 1;                      // value 660: 1 HDMI 1.4 (compatible)
    public static final int EDID_20 = 2;                      // value 660: 2 HDMI 2.0 (enhanced)
    // digital zoom and shift (stock KeyStoneManager / ZoomDisplacementWind, v1.7.116)
    private static final int ZOOM_RESERVED = 0;               // value 121 zoomStart(0) / 120 zoomFinish(0), as stock
    private static final int ZOOM_STEP_RANGE_SIZE = 16;       // value st_ZoomStepRange: 4 x i32 (stock rp.ZoomStepRange)
    private static final int ZOOM_STEP_SIZE = 8;              // value st_ZoomStep: 2 x i32 (stock rp.ZoomStep)
    /** Sanity bounds of a digital zoom step (the stock dial has tens of steps). */
    public static final int ZOOM_STEP_MIN = -200;             // value
    public static final int ZOOM_STEP_MAX = 200;              // value
    /** 141 translationKst directions (stock ZoomDisplacementWind.onKey: DPAD up 1, down 2, left 3, right 4). */
    public static final int SHIFT_UP = 1;                     // value
    public static final int SHIFT_DOWN = 2;                   // value
    public static final int SHIFT_LEFT = 3;                   // value
    public static final int SHIFT_RIGHT = 4;                  // value

    /** HwBinder.getService(IGmpf, "default", retry=false) + linkToDeath (the Hal facade's own instance). Never throws. */
    public boolean connect(IHwBinder.DeathRecipient death, long cookie) {
        return connectInternal(death, cookie);
    }

    // ------------------------------------------------------------- listener (298 then 306)
    /** 298 connectProjectorFocusManager(myPid, callback). The same non-null callback as 306. */
    public void connectFocusManager(Play6FocusCallback cb) throws RemoteException {
        callPidBinder(CONNECT_PROJECTOR_FOCUS_MANAGER, Process.myPid(), cb);
    }

    /** 306 setProjectorFocusListener(myPid, callback). Never null (a null crashes the HAL later). */
    public void setFocusListener(Play6FocusCallback cb) throws RemoteException {
        callPidBinder(SET_PROJECTOR_FOCUS_LISTENER, Process.myPid(), cb);
    }

    // ------------------------------------------------------- power listener (235 then 242)
    // As stock com.xgimi.gmpf.api.PowerManager (research battery_sound_features.md 1.1): 235 with the pid
    // and an IBase token, then 242 with the pid and the callback; the token here is the same callback
    // object, as 298 / 306 use one object. Caller: the Hal facade, once per connection after the start
    // gate (kill switch persist.lumen.p6.power_listener).

    /** 235 connectPowerManager(myPid, callback). The same non-null callback as 242. */
    public void connectPowerManager(Play6PowerCallback cb) throws RemoteException {
        callPidBinder(CONNECT_POWER_MANAGER, Process.myPid(), cb);
    }

    /** 242 setPowerListener(myPid, callback). Never null. */
    public void setPowerListener(Play6PowerCallback cb) throws RemoteException {
        callPidBinder(SET_POWER_LISTENER, Process.myPid(), cb);
    }

    // ---------------------------------------------------------------------- focus
    /** 307 sendFocusStatus(44): remote focus key short press (stock FocusUIV2.autoFocus). Caller: Hal. */
    public void requestAutofocus() throws RemoteException { callU8(SEND_FOCUS_STATUS, FOCUS_STATUS_REMOTE_AF); }

    /**
     * 307 sendFocusStatus(17): the power-on UI is ready, the vendor starts its power-on AF / AK flow
     * (stock SystemUI FocusUIV2.start, not ATV). Caller: power/UiReady17 only, once per boot_id after
     * the start gate. Never part of the boot handshake.
     */
    public void sendPowerOnUiReady() throws RemoteException { callU8(SEND_FOCUS_STATUS, FOCUS_STATUS_UI_READY); }

    /** 588 getAngTriggerAF(): autofocus after the projector moved (half of stock smart autofocus). */
    public boolean getMoveAf() throws RemoteException { return callRetBool(GET_ANG_TRIGGER_AF); }

    // -------------------------------------------------------------------- keystone
    /** 390 getSystemUIState(). */
    public int getSystemUiState() throws RemoteException { return callRetI32(GET_SYSTEM_UI_STATE); }

    /** 553 getPowerOnAKFlag(): auto keystone at power-on. */
    public boolean getPowerOnAk() throws RemoteException { return callRetBool(GET_POWER_ON_AK_FLAG); }

    /** 586 getAccTriggerAK(): real-time keystone. */
    public boolean getMoveAk() throws RemoteException { return callRetBool(GET_ACC_TRIGGER_AK); }

    /** 130 getCurrentKeystoneMode(): logged; 129 switchKeystoneMode only from resetKeystone (O3). */
    public int getKeystoneMode() throws RemoteException { return callRetI32(GET_CURRENT_KEYSTONE_MODE); }

    /**
     * 186 getCorrectKeystoneFullPiont(0): the current 4 corners. W=u8, reply via callback = Status +
     * KstPoint (readBuffer 326, the struct of 150 / 185). Throws ReplyException when the reply is not a
     * 326-byte buffer or a corner lies outside 0..1920 x 0..1080; the message then carries the raw values.
     */
    public Play6KstPoint getKeystonePoints() throws RemoteException {
        HwParcel q = request();
        q.writeInt8((byte) KST_MODE_FOUR_POINT);
        try {
            return call(GET_CORRECT_KEYSTONE_FULL_POINT, q, r -> Play6KstPoint.fromBlob(r.readBuffer(Play6KstPoint.WIRE_SIZE)));
        } catch (IllegalArgumentException e) {
            throw new ReplyException("186 corner out of range: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- light, picture
    /**
     * 196 getScreenOnOff(): the lamp (light) is on. Read after every 195 and on every wake (stock
     * XgimiWindowManager.xgimiWakeUp polls it; research sleep_stock.md 3 and 9.2). Caller: power/Lamp.
     */
    public boolean getScreenOnOff() throws RemoteException { return callRetBool(GET_SCREEN_ON_OFF); }

    /** 177 getDlpLumensLevel() -> 1..10 (the vendor may cap it on battery, B 9.6). */
    public int getLampLevel() throws RemoteException { return callRetU8(GET_DLP_LUMENS_LEVEL); }

    /** 666 getPictureMode(0). 4 = the MStar Game picture mode set by game mode (plan 1.4). */
    public int getPictureMode() throws RemoteException { return callI32RetI32(GET_PICTURE_MODE, WINDOW_MAIN); }

    /** 668 getMemcLevel(0) -> 0 off .. 3 high. */
    public int getMemcLevel() throws RemoteException { return callI32RetI32(GET_MEMC_LEVEL, WINDOW_MAIN); }

    /** 620 getHdrType() -> 0 SDR, 1 HDR10, 4 HLG (the Play 6 has no Dolby Vision, C 5.5). */
    public int getHdrType() throws RemoteException { return callRetI32(GET_HDR_TYPE); }

    // ---------------------------------------------------------- input, HDMI, game, sound
    /** 696 getCurrentInputSource() -> SOURCE_*. A hint only, never drives an action. */
    public int getCurrentInputSource() throws RemoteException { return callRetI32(GET_CURRENT_INPUT_SOURCE); }

    /**
     * 698 switchInputSourceWithCallingPid(source, myPid): connects the input to the main video window
     * (gmpf_main PlatformTvCtrl::setInputSource: MI_DISP disconnect, connect, unmute, enable). Stock calls it
     * from its HDMI player with 1 when HDMI is shown and 0 when it leaves (research hdmi_stock.md 3); the
     * vendor TV input HAL and TIS never do, so without it the HDMI window stays black. Sources: 0 Android
     * (MM), 1 HDMI (the one port); nothing else. The pid is always our own (Process.myPid). W=i32,u32 R=bool
     * (u32 and i32 are the same 4 bytes). Caller: source/HdmiSource only (check H), on p6-hal.
     */
    public boolean switchInputSource(int source) throws RemoteException {
        return callI32I32RetBool(SWITCH_INPUT_SOURCE_WITH_CALLING_PID,
                checkSet("input source", source, SOURCE_MEDIA, SOURCE_HDMI1), Process.myPid());
    }

    /**
     * 592 getSignalStatus(): the HDMI signal state (read only). Assumed to use the values of the vendor's
     * type 0 TV event (1 lost, 2 unstable or unsupported, 3 stable; UNVERIFIED for this getter). Used only by
     * the HDMI fallback behind persist.lumen.p6.hdmi_fallback.
     */
    public int getSignalStatus() throws RemoteException { return callRetI32(GET_SIGNAL_STATUS); }

    /**
     * 659 setDisplayArea(0, 0, 1920, 1080): the HDMI picture over the whole panel, as the stock player does
     * at signal in (full window). The reply is a 1-byte bool, so the parcel is built here (HidlCaller has no
     * i32 x 4 -> bool helper and stays unchanged). Caller: source/HdmiSource's debug fallback only.
     */
    public boolean setFullDisplayArea() throws RemoteException {
        HwParcel q = request();
        q.writeInt32(AREA_ORIGIN);
        q.writeInt32(AREA_ORIGIN);
        q.writeInt32(AREA_WIDTH);
        q.writeInt32(AREA_HEIGHT);
        return call(SET_DISPLAY_AREA, q, RET_BOOL);
    }

    /**
     * 662 getHdmiInfo() -> {width, height, interlace 0/1, isHdmiMode 0/1, frameRate (Hz x 100)} of the HDMI
     * signal (reply via callback = Status + stHdmiInfo, 16 bytes: u32 width @0, u32 height @4, bool interlace
     * @8, bool isHdmiMode @9, u32 frameRate @12; research hdmi_volume_game.md 5.1). Read only, while HDMI is
     * the current source: the panel offers "Original" only for a signal smaller than the panel.
     */
    public int[] getHdmiInfo() throws RemoteException {
        return call(GET_HDMI_INFO, request(), r -> {
            HwBlob b = r.readBuffer(HDMI_INFO_SIZE);
            return new int[] {b.getInt32(0), b.getInt32(4), b.getBool(8) ? 1 : 0, b.getBool(9) ? 1 : 0, b.getInt32(12)};
        });
    }

    /** 402 getHdmiAutoSwitch(): the vendor HDMI plug-and-play switch (Diagnostics only, plan 1.5). */
    public boolean getHdmiAutoSwitch() throws RemoteException { return callRetBool(GET_HDMI_AUTO_SWITCH); }

    /** 647 getGameModeOption(). */
    public int getGameModeOption() throws RemoteException { return callRetI32(GET_GAME_MODE_OPTION); }

    /** 664 getAspectRatio(): the vendor value of the current source (0 4:3, 1 16:9, 2 full, 3 / 4 original). */
    public int getAspectRatio() throws RemoteException { return callRetI32(GET_ASPECT_RATIO); }

    /** 62 getSoundeffect() -> 3 AI, 2 Music, 1 Movie, 4 Karaoke, 12 Sports, 20 Standard. */
    public int getSoundEffect() throws RemoteException { return callRetU8(GET_SOUND_EFFECT); }

    // -------------------------------------------------------- projection, eye protection
    /** 173 getProjectorPutMode() -> 0 front table, 1 front ceiling, 2 rear table, 3 rear ceiling. */
    public int getPutMode() throws RemoteException { return callRetU8(GET_PROJECTOR_PUT_MODE); }

    /** 579 getAutoReverse(): automatic projection mode (accelerometer). */
    public boolean getAutoProjection() throws RemoteException { return callRetBool(GET_AUTO_REVERSE); }

    /** 151 getHumanDetectOnOff(): eye protection switch. */
    public boolean getEyeProtection() throws RemoteException { return callRetBool(GET_HUMAN_DETECT); }

    // --------------------------------------------- battery, power board, system (read only)
    /** 240 getBatteryLevel() -> percent of the internal battery (the vendor's smoothed value). */
    public int getBatteryLevel() throws RemoteException { return callRetU8(GET_BATTERY_LEVEL); }

    /** 238 isAdapterPowered(): mains or USB-C adapter present. */
    public boolean isAdapterPowered() throws RemoteException { return callRetBool(IS_ADAPTER_POWERED); }

    /** 239 isBatteryChargedFull(). */
    public boolean isBatteryFull() throws RemoteException { return callRetBool(IS_BATTERY_CHARGED_FULL); }

    /** 327 getEcoState(): power saving (eco) is on. The vendor also turns it on and off by itself. */
    public boolean getEcoState() throws RemoteException { return callRetBool(GET_ECO_STATE); }

    /** 438 getSystemTemperature() -> degrees C (which sensor on the G0058 is UNVERIFIED, C 2.1). */
    public int getSystemTemperature() throws RemoteException { return callRetU8(GET_SYSTEM_TEMPERATURE); }

    /**
     * 404 getAllFeatureList() -> the vendor feature list (reply via callback = Status + hidl_string).
     * May take long inside the HAL: callers use the p6-long thread (Hal.runLong).
     */
    public String getAllFeatureList() throws RemoteException { return call(GET_ALL_FEATURE_LIST, request(), r -> r.readString()); }

    // ------------------------------------------------------------- setters (plan step 16)
    // Each one is sent only on a user action of the quick panel, Projector settings, the manual focus
    // overlay or the manual keystone card (their callers are named per method).

    /** v when it is one of {@code allowed}, else IllegalArgumentException before anything is sent. */
    private static int checkSet(String what, int v, int... allowed) {
        for (int a : allowed) if (a == v) return v;
        throw new IllegalArgumentException(what + " " + v + " not allowed");
    }

    /** 300 manualFocus(17 enter / 0 stock right / 1 stock left / 2 stop). Caller: MotorController. */
    public void manualFocus(int m) throws RemoteException {
        callU8(MANUAL_FOCUS, checkSet("manual focus", m, MANUAL_FOCUS_ENTER, MANUAL_FOCUS_STOCK_RIGHT,
                MANUAL_FOCUS_STOCK_LEFT, MANUAL_FOCUS_STOP));
    }

    /** 587 setAngTriggerAF(on): second half of the smart autofocus switch, after IGmpf2 64 (stock order). */
    public boolean setMoveAf(boolean on) throws RemoteException { return callBoolRetBool(SET_ANG_TRIGGER_AF, on); }

    /** 326 autoKst(). Blocks inside the HAL for seconds: the p6-long thread only (Hal.requestKeystone). W= R=i8. */
    public byte autoKst() throws RemoteException { return call(AUTO_KST, request(), RET_I8); }

    /** 585 setAccTriggerAK(on): real-time keystone. */
    public boolean setMoveAk(boolean on) throws RemoteException { return callBoolRetBool(SET_ACC_TRIGGER_AK, on); }

    /** 552 savePowerOnAKFlag(on): auto keystone at power-on (the vendor flow starts on 307(17)). */
    public void setPowerOnAk(boolean on) throws RemoteException { callBool(SAVE_POWER_ON_AK_FLAG, on); }

    /** 573 temporarySwitchTrigger(2, enable): pause (false) / resume (true) real-time AK around manual keystone. */
    public boolean setRealtimeAkRunning(boolean enable) throws RemoteException {
        return callI32BoolRetBool(TEMPORARY_SWITCH_TRIGGER, TEMPORARY_SWITCH_AK, enable);
    }

    /**
     * Keystone reset, stock V3DisplayManager.setIChipReset() (owner decision O3, 2026-10-21: "as in stock"):
     * 207 correctKeystoneReset(0), then 130 getCurrentKeystoneMode and 129 switchKeystoneMode(0, 0) only when it
     * answered 6. Stock also sends 181 correctKeystone(50) here; 181 stays banned (Note A), so it is not sent.
     * The caller pauses real-time keystone around it (573). Returns the 130 reply.
     */
    public int resetKeystone() throws RemoteException {
        callU8(CORRECT_KEYSTONE_RESET, KST_RESET_TYPE);
        int mode = callRetI32(GET_CURRENT_KEYSTONE_MODE);
        if (mode == KST_MODE_SIX) callI32I32RetBool(SWITCH_KEYSTONE_MODE, KST_SWITCH_DEFAULT, KST_SWITCH_DEFAULT);
        return mode;
    }

    /**
     * 179 getDlpLumensComponent_RGB_Level() -> {R, G, B, C}: the light levels of the Custom picture style
     * (stock BrightnessManager.getLightInfo). Reply via callback: Status + stDisplayRgB (readBuffer 4, u8 each).
     * Layout from the vendor Parcelable (R, G, B, C bytes); the caller shows the rows only for values 1..10.
     */
    public int[] getRgbLevels() throws RemoteException {
        return call(GET_DLP_RGB_LEVEL, request(), r -> {
            HwBlob b = r.readBuffer(RGB_LEVEL_SIZE);
            return new int[] {b.getInt8(0) & 0xff, b.getInt8(1) & 0xff, b.getInt8(2) & 0xff, b.getInt8(3) & 0xff};
        });
    }

    /**
     * 178 setDlpLumensComponent_RGB_Level({R, G, B, 0}), each 1..10: stock BrightnessManager.setLedRed / Green /
     * Blue send all three with C = 0. Custom picture style only (the page enforces it).
     */
    public void setRgbLevels(int red, int green, int blue) throws RemoteException {
        HwBlob b = new HwBlob(RGB_LEVEL_SIZE);
        b.putInt8(0, (byte) checkRange("red level", red, RGB_LEVEL_MIN, RGB_LEVEL_MAX));
        b.putInt8(1, (byte) checkRange("green level", green, RGB_LEVEL_MIN, RGB_LEVEL_MAX));
        b.putInt8(2, (byte) checkRange("blue level", blue, RGB_LEVEL_MIN, RGB_LEVEL_MAX));
        b.putInt8(3, (byte) RGB_LEVEL_C);
        HwParcel q = request();
        q.writeBuffer(b);
        call(SET_DLP_RGB_LEVEL, q, RET_VOID);
    }

    /** 150 checkTrapezoidCoordinate(KstPoint) -> true when the vendor accepts the corners. W=KstPoint R=bool. */
    public boolean checkKeystonePoints(Play6KstPoint p) throws RemoteException {
        HwParcel q = request();
        q.writeBuffer(p.toBlob());
        return call(CHECK_TRAPEZOID_COORDINATE, q, RET_BOOL);
    }

    /** 185 correctKeystoneFullPiont(KstPoint) -> i32, after 150 with the same points. W=KstPoint R=i32. */
    public int applyKeystonePoints(Play6KstPoint p) throws RemoteException {
        HwParcel q = request();
        q.writeBuffer(p.toBlob());
        return call(CORRECT_KEYSTONE_FULL_POINT, q, RET_I32);
    }

    /** 176 setDlpLumensLevel(1..10). */
    public void setLampLevel(int level) throws RemoteException {
        callU8(SET_DLP_LUMENS_LEVEL, checkRange("lamp level", level, 1, 10));
    }

    /** 667 setPictureMode(0, m), m one of the Play 6 list (10, 1, 9, 7, 3, 25, 5); never 4 (Note C, t15). */
    public boolean setPictureMode(int m) throws RemoteException {
        return callI32I32RetBool(SET_PICTURE_MODE, WINDOW_MAIN, checkSet("picture mode", m, PICTURE_AI,
                PICTURE_MOVIE, PICTURE_SPORT, PICTURE_TV, PICTURE_CUSTOM, PICTURE_OFFICE, PICTURE_PERFORMANCE));
    }

    /** 669 setMemcLevel(0, 0..3). Only while 666 is 1, 7 or 3 (the page enforces it). */
    public boolean setMemcLevel(int level) throws RemoteException {
        return callI32I32RetBool(SET_MEMC_LEVEL, WINDOW_MAIN, checkRange("memc level", level, 0, 3));
    }

    /** 688 / 690 / 692 / 694 (0): the Custom items, read only while 666 == 3. */
    public int getSharpness() throws RemoteException { return callI32RetI32(GET_SHARPNESS, WINDOW_MAIN); }
    public int getSaturation() throws RemoteException { return callI32RetI32(GET_SATURATION, WINDOW_MAIN); }
    public int getBrightness() throws RemoteException { return callI32RetI32(GET_BRIGHTNESS, WINDOW_MAIN); }
    public int getContrast() throws RemoteException { return callI32RetI32(GET_CONTRAST, WINDOW_MAIN); }

    /** 689 / 691 / 693 / 695 (0, 0..100): the Custom items, only in Custom (3). */
    public boolean setSharpness(int v) throws RemoteException {
        return callI32I32RetBool(SET_SHARPNESS, WINDOW_MAIN, checkRange("sharpness", v, 0, 100));
    }
    public boolean setSaturation(int v) throws RemoteException {
        return callI32I32RetBool(SET_SATURATION, WINDOW_MAIN, checkRange("saturation", v, 0, 100));
    }
    public boolean setBrightness(int v) throws RemoteException {
        return callI32I32RetBool(SET_BRIGHTNESS, WINDOW_MAIN, checkRange("brightness", v, 0, 100));
    }
    public boolean setContrast(int v) throws RemoteException {
        return callI32I32RetBool(SET_CONTRAST, WINDOW_MAIN, checkRange("contrast", v, 0, 100));
    }

    /** 665 setAspectRatio(0 4:3 / 1 16:9 / 2 full screen / 4 original), the vendor values (t15). */
    public boolean setAspectRatio(int r) throws RemoteException {
        return callI32RetBool(SET_ASPECT_RATIO, checkSet("aspect ratio", r, ASPECT_4_3, ASPECT_16_9, ASPECT_AUTO,
                ASPECT_ORIGINAL));
    }

    /** 648 setGameModeOption(the value 647 reports right now). Caller: the game-mode On path only (Note C). */
    public boolean applyGameModeOption() throws RemoteException {
        int opt = callRetI32(GET_GAME_MODE_OPTION);
        return callI32RetBool(SET_GAME_MODE_OPTION, opt);
    }

    /** 61 setSoundeffect(3 / 2 / 1 / 4 / 12 / 20). */
    public void setSoundEffect(int m) throws RemoteException {
        callU8(SET_SOUND_EFFECT, checkSet("sound mode", m, SOUND_AI, SOUND_MUSIC, SOUND_MOVIE, SOUND_KARAOKE,
                SOUND_SPORTS, SOUND_STANDARD));
    }

    /** 172 setProjectorPutMode(0..3); stock sends 580(false) first (the page does). */
    public void setPutMode(int m) throws RemoteException { callU8(SET_PROJECTOR_PUT_MODE, checkRange("put mode", m, 0, 3)); }

    /** 580 setAutoReverse(on). */
    public boolean setAutoProjection(boolean on) throws RemoteException { return callBoolRetBool(SET_AUTO_REVERSE, on); }

    /**
     * 195 setScreenOnOff(on): the lamp off / on while Android stays awake (stock power menu "Screen off",
     * PowerUtils.shutScreen = GmDisplayManager.setScreenOnOff with xgimi.api.version 324; the stock
     * framework also sends 195(false) before its sleep; research sleep_stock.md 4 and 7). Caller:
     * power/Lamp only (the lamp-only standby, its exit and the wake check). No other power code: 228 to
     * 234, 241, 422 and 543 stay banned.
     */
    public void setScreenOnOff(boolean on) throws RemoteException { callBool(SET_SCREEN_ON_OFF, on); }

    /**
     * 328 setEcoState(on): the Power saving switch of the Brightness page (stock eco_mode_switch). Sent only
     * on the user's own switch action; the vendor's automatic eco (IGmpf2 48 setAutoEcoMode) stays banned.
     */
    public void setEcoState(boolean on) throws RemoteException { callBool(SET_ECO_STATE, on); }

    // ------------------------------------------------- digital zoom and shift (P3 item 21)
    // Stock Play 6 (non-GMPF2, research B 4.1 zoom_and_displacement): ZoomDisplacementWind sends IGmpf2 125(1)
    // and 573(2,false) on open, 121 zoomStart(0), reads 117 / 116 (JNI getCurrentZoomStep(ZoomStep) is the HAL
    // getCurrentZoomStep2), a size step is 118 with the neighbour of the 116 digital read (KeyStoneManager
    // setScreenResolutions: 4 = +1 smaller, 0 = -1 bigger), a shift step is 141(dir, true) then 141(dir, false)
    // when the check answered 0; on close 120 zoomFinish(0), 158(0) (never here: owner decision O4), 125(0),
    // 573(2,true). Struct layouts as the Z9X client and the stock Parcelables; UNVERIFIED on the Play 6:
    // readBuffer fails on any other size (our call only), the caller validates the values.
    // Caller: panel/Play6ZoomShiftPanel (the size and shift card) and panel/Play6Snapshot (parse probe only).

    /** 121 zoomStart(0) -> i32: opens the zoom session (stock initZoom). */
    public int zoomSessionStart() throws RemoteException { return callI32RetI32(ZOOM_START, ZOOM_RESERVED); }

    /** 120 zoomFinish(0) -> i32: closes the session; always after 121, also on errors (stock hideKstWind). */
    public int zoomSessionFinish() throws RemoteException { return callI32RetI32(ZOOM_FINISH, ZOOM_RESERVED); }

    /**
     * 117 getZoomStepRange() -> {ret, opticalMin, opticalMax, digitalMin, digitalMax}: reply Status, i32,
     * st_ZoomStepRange (16 bytes: zoomInOpticalMinNum, zoomOutOpticalMaxNum, zoomInDigtalMinNum,
     * zoomOutDigtalMaxNum, the order of stock rp.ZoomStepRange). Layout UNVERIFIED on the Play 6.
     */
    public int[] getZoomStepRange() throws RemoteException {
        return call(GET_ZOOM_STEP_RANGE, request(), r -> {
            int ret = r.readInt32();
            HwBlob b = r.readBuffer(ZOOM_STEP_RANGE_SIZE);
            return new int[] {ret, b.getInt32(0), b.getInt32(4), b.getInt32(8), b.getInt32(12)};
        });
    }

    /**
     * 116 getCurrentZoomStep2() -> {ret, optical, digital}: reply Status, i32, st_ZoomStep (8 bytes:
     * zoomOpticalstep, zoomDigtalstep, stock rp.ZoomStep). Layout UNVERIFIED on the Play 6.
     */
    public int[] getZoomStep() throws RemoteException {
        return call(GET_CURRENT_ZOOM_STEP2, request(), r -> {
            int ret = r.readInt32();
            HwBlob b = r.readBuffer(ZOOM_STEP_SIZE);
            return new int[] {ret, b.getInt32(0), b.getInt32(4)};
        });
    }

    /**
     * 118 setDigitalZoomStep(step) -> i32. Callers send only the neighbour of a fresh 116 digital read inside
     * the 117 digital range (+1 smaller, -1 bigger, stock setScreenResolutions 4 / 0), inside a 121 session.
     */
    public int setDigitalZoomStep(int step) throws RemoteException {
        return callI32RetI32(SET_DIGITAL_ZOOM_STEP, checkRange("digital zoom step", step, ZOOM_STEP_MIN, ZOOM_STEP_MAX));
    }

    /**
     * 141 translationKst(dir, onlyCheck) -> 0 possible / -1 at the edge. As stock: (dir, true) first and
     * (dir, false) only when that answered 0; the step size is the vendor's. A software move of the picture
     * inside the panel (no motor). W=i32,bool R=i32 (no HidlCaller helper for it, so the parcel is built here).
     */
    public int shiftPicture(int dir, boolean onlyCheck) throws RemoteException {
        HwParcel q = request();
        q.writeInt32(checkSet("shift direction", dir, SHIFT_UP, SHIFT_DOWN, SHIFT_LEFT, SHIFT_RIGHT));
        q.writeBool(onlyCheck);
        return call(TRANSLATION_KST, q, RET_I32);
    }

    // ------------------------------------------- stock parity: picture, HDMI, boot music (2026-10-21)
    // Stock paths (inventory B 4.2 / 4.4 / 4.7, dayao tree): the picture items of the Custom mode (3DNR, colour
    // temperature and its gains / offsets, picture style), HDR and local contrast; HDMI colour range and EDID
    // version; boot music. Each getter is read before its row is shown; each setter is sent on the user's row
    // action only, with the stock value set. Callers: panel/Play6PanelController, panel/Play6Snapshot,
    // SettingsActivity (boot music).

    /** 670 get3DNR(0) -> 0 off, 1 weak, 2 medium, 3 strong, 4 auto. */
    public int getNoiseReduction() throws RemoteException { return callI32RetI32(GET_3DNR, WINDOW_MAIN); }

    /** 671 set3DNR(0, 0..4). Custom picture mode only (the page enforces it). */
    public boolean setNoiseReduction(int v) throws RemoteException {
        return callI32I32RetBool(SET_3DNR, WINDOW_MAIN, checkRange("noise reduction", v, 0, 4));
    }

    /** 684 getColorTemperature(0) -> 0 cool, 1 standard, 2 warm, 3 custom. */
    public int getColorTemperature() throws RemoteException { return callI32RetI32(GET_COLOR_TEMPERATURE, WINDOW_MAIN); }

    /** 685 setColorTemperature(0, 0..3). Custom picture mode only. */
    public boolean setColorTemperature(int v) throws RemoteException {
        return callI32I32RetBool(SET_COLOR_TEMPERATURE, WINDOW_MAIN, checkRange("color temperature", v, 0, 3));
    }

    /** 682 / 680 / 678 (0): red / green / blue gain 0..100. */
    public int getRedGain() throws RemoteException { return callI32RetI32(GET_RED_GAIN, WINDOW_MAIN); }
    public int getGreenGain() throws RemoteException { return callI32RetI32(GET_GREEN_GAIN, WINDOW_MAIN); }
    public int getBlueGain() throws RemoteException { return callI32RetI32(GET_BLUE_GAIN, WINDOW_MAIN); }
    /** 676 / 674 / 672 (0): red / green / blue offset 0..100. */
    public int getRedOffset() throws RemoteException { return callI32RetI32(GET_RED_OFFSET, WINDOW_MAIN); }
    public int getGreenOffset() throws RemoteException { return callI32RetI32(GET_GREEN_OFFSET, WINDOW_MAIN); }
    public int getBlueOffset() throws RemoteException { return callI32RetI32(GET_BLUE_OFFSET, WINDOW_MAIN); }

    /** 683 / 681 / 679 (0, 0..100): gains; 677 / 675 / 673 (0, 0..100): offsets. Custom picture mode only. */
    public boolean setRedGain(int v) throws RemoteException {
        return callI32I32RetBool(SET_RED_GAIN, WINDOW_MAIN, checkRange("red gain", v, 0, 100));
    }
    public boolean setGreenGain(int v) throws RemoteException {
        return callI32I32RetBool(SET_GREEN_GAIN, WINDOW_MAIN, checkRange("green gain", v, 0, 100));
    }
    public boolean setBlueGain(int v) throws RemoteException {
        return callI32I32RetBool(SET_BLUE_GAIN, WINDOW_MAIN, checkRange("blue gain", v, 0, 100));
    }
    public boolean setRedOffset(int v) throws RemoteException {
        return callI32I32RetBool(SET_RED_OFFSET, WINDOW_MAIN, checkRange("red offset", v, 0, 100));
    }
    public boolean setGreenOffset(int v) throws RemoteException {
        return callI32I32RetBool(SET_GREEN_OFFSET, WINDOW_MAIN, checkRange("green offset", v, 0, 100));
    }
    public boolean setBlueOffset(int v) throws RemoteException {
        return callI32I32RetBool(SET_BLUE_OFFSET, WINDOW_MAIN, checkRange("blue offset", v, 0, 100));
    }

    /** 649 getHdrEnable(): HDR auto (true) / off. */
    public boolean getHdrAuto() throws RemoteException { return callRetBool(GET_HDR_ENABLE); }

    /** 650 setHdrEnable(auto). */
    public boolean setHdrAuto(boolean on) throws RemoteException { return callBoolRetBool(SET_HDR_ENABLE, on); }

    /** 651 getUcdLevel(0) -> local contrast 0 off .. 3 strong. */
    public int getLocalContrast() throws RemoteException { return callI32RetI32(GET_UCD_LEVEL, WINDOW_MAIN); }

    /** 655 setUcdLevel(0, 0..3). Only in Movie, TV and Custom (the page enforces it, stock dayao tree). */
    public boolean setLocalContrast(int v) throws RemoteException {
        return callI32I32RetBool(SET_UCD_LEVEL, WINDOW_MAIN, checkRange("local contrast", v, 0, 3));
    }

    /** 175 getDlpLumensMode() -> picture style (STYLE_*; other values are shown as nothing selected). */
    public int getPictureStyle() throws RemoteException { return callRetU8(GET_DLP_LUMENS_MODE); }

    /** 174 setDlpLumensMode(0 / 16 / 12 / 6): picture style, Custom picture mode only (stock color_style_custom_state). */
    public void setPictureStyle(int s) throws RemoteException {
        callU8(SET_DLP_LUMENS_MODE, checkSet("picture style", s, STYLE_STANDARD, STYLE_MOVIE_D65, STYLE_OFFICE, STYLE_CUSTOM));
    }

    /** 658 resetPqSettings(3): stock "Reset" of the Custom picture mode (PictureViewModel.resetPictureMode). */
    public int resetCustomPicture() throws RemoteException { return callI32RetI32(RESET_PQ_SETTINGS, RESET_CUSTOM_PICTURE); }

    /** 632 getEARCEnableState(): stock eARC mode, true = Auto, false = Off (as the Z9X 1.0.4 row). */
    public boolean getEarc() throws RemoteException { return callRetBool(GET_EARC_ENABLE_STATE); }

    /** 633 setEARCEnable(on), as stock VoiceRepository.setEarcSound. */
    public boolean setEarc(boolean on) throws RemoteException { return callBoolRetBool(SET_EARC_ENABLE, on); }

    /** 634 getHdmiRange() -> 0 auto, 1 full, 2 limited. */
    public int getHdmiRange() throws RemoteException { return callRetI8(GET_HDMI_RANGE); }

    /** 635 setHdmiRange(0..2). HDMI source only (the page enforces it). */
    public boolean setHdmiRange(int r) throws RemoteException {
        HwParcel q = request();
        q.writeInt8((byte) checkRange("hdmi range", r, HDMI_RANGE_AUTO, HDMI_RANGE_LIMITED));
        return call(SET_HDMI_RANGE, q, RET_BOOL);
    }

    /** 661 getEdidVersion() -> 1 HDMI 1.4, 2 HDMI 2.0. */
    public int getEdidVersion() throws RemoteException { return callRetI32(GET_EDID_VERSION); }

    /** 660 setEdidVersion(1 / 2): the source re-reads the EDID (the page confirms first, as stock). */
    public boolean setEdidVersion(int v) throws RemoteException {
        return callI32RetBool(SET_EDID_VERSION, checkSet("edid version", v, EDID_14, EDID_20));
    }

    /** 420 isPowerOnMusicEnabled(). */
    public boolean getBootMusic() throws RemoteException { return callRetBool(IS_POWER_ON_MUSIC_ENABLED); }

    /** 419 enablePowerOnMusic(on): stock General > Advanced > boot music. */
    public void setBootMusic(boolean on) throws RemoteException { callBool(ENABLE_POWER_ON_MUSIC, on); }

    // ------------------------------------------- music player listener (215 then 227), device closed / opened
    // As stock com.xgimi.gmpf.api.MusicPlayerManager native_setup: 215 with the pid and an IBase token, then 227
    // with the pid and the callback; the token here is the same callback object (as 298 / 306 and 235 / 242).
    // Caller: the Hal facade, once per connection after the start gate (kill switch persist.lumen.p6.music_listener).

    /** 215 connectMusicPlayerManager(myPid, callback). */
    public void connectMusicPlayer(Play6MusicPlayerCallback cb) throws RemoteException {
        callPidBinder(CONNECT_MUSIC_PLAYER_MANAGER, Process.myPid(), cb);
    }

    /** 227 setMusicPlayerListener(myPid, callback). Never null. */
    public void setMusicPlayerListener(Play6MusicPlayerCallback cb) throws RemoteException {
        callPidBinder(SET_MUSIC_PLAYER_LISTENER, Process.myPid(), cb);
    }

    // ------------------------------------------------------------- equalizer (owner 2026-10-21)
    // Caller: audio/P6SoundEq only. Read first every time: 26 == 2 (AMP), 43 false (no DTS), 60 == 0 (speaker);
    // never 27 / 44 (process switch), 45..49 (HPF, DRC, prescale, volume dB).

    /** 26 getSoundProcessType(). */
    public int getSoundProcess() throws RemoteException { return callRetI32(GET_SOUND_PROCESS_TYPE); }

    /** 43 getDtsEffectsEnable(). */
    public boolean getDtsEffects() throws RemoteException { return callRetBool(GET_DTS_EFFECTS_ENABLE); }

    /**
     * Lumen 1.0.6: 59 setAudioOutput(u8 path), the stock audioserver's report of the media output (libxgimiaudiopolicy
     * setXgimiPath; the Z9X sends it since v6.3.1). Msrv SetOutputType mutes / unmutes the amp per path: without it
     * the Play 6 speaker kept playing at full level next to a Bluetooth speaker (device test 2026-10-24).
     * 100 = switching (every TV path muted until the next path). Caller: audio.BtAudio only (HAL thread).
     */
    public void setAudioOutput(int path) throws RemoteException {
        callU8(SET_AUDIO_OUTPUT, checkSet("audio path", path, AUDIO_PATH_SPEAKER, AUDIO_PATH_ARC, AUDIO_PATH_A2DP,
                AUDIO_PATH_WIRED, AUDIO_PATH_USB, AUDIO_PATH_SWITCHING));
    }

    /** 60 getAudioOutput() -> the vendor's output path (0 speaker). */
    public int getAudioOutput() throws RemoteException { return callRetU8(GET_AUDIO_OUTPUT); }

    /** 50 SetPEQ(band 1..13, gain 0.1 dB, fc Hz, Q 0.1) -> 1 ok. */
    public int setPeqBand(int band, int gainTenthDb, int fcHz, int qTenth) throws RemoteException {
        return callI32x4RetI32(SET_PEQ, checkRange("peq band", band, 1, PEQ_BANDS),
                checkRange("peq gain", gainTenthDb, PEQ_GAIN_MIN, PEQ_GAIN_MAX),
                checkRange("peq fc", fcHz, PEQ_FC_MIN, PEQ_FC_MAX), checkRange("peq q", qTenth, PEQ_Q_MIN, PEQ_Q_MAX));
    }

    /** 51 SetPEQEnable(on) -> 1 ok. true only after all 13 bands were written. */
    public int setPeqEnabled(boolean on) throws RemoteException { return callBoolRetI32(SET_PEQ_ENABLE, on); }

    // ------------------------------------------------------------- private helpers
    private void callPidBinder(int code, int pid, IHwBinder cb) throws RemoteException {
        if (cb == null) throw new RemoteException("callback must be non-null");
        HwParcel q = request();
        q.writeInt32(pid);
        q.writeStrongBinder(cb);
        call(code, q, RET_VOID);
    }

    // ------------------------------------------------------------- eye protection (stock parity 2026-10-21)
    // Caller: eye/EyeGuard and the Eye page. 148 / 152 reach the vendor's nuiCommand, which blocks a HAL thread
    // (and ours) without a reader of /data/vendor/tmp/nuififo: both are sent only while the image's z9x_nui
    // service reports sys.z9x.nui=ready (the Z9X files, tools/play6/compose.sh 3g). 114 / 115 stay banned.

    /** The NUI fifo reader (image service z9x_nui) is up. Fails closed. Any thread. */
    public static boolean nuiReaderReady() {
        try {
            return "ready".equals(android.os.SystemProperties.get("sys.z9x.nui", ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Thrown instead of sending 148 / 152 while {@link #nuiReaderReady()} is false. Nothing was sent. */
    public static final class NuiNotReadyException extends RemoteException {
        NuiNotReadyException(String what) {
            super(what + " not sent: no NUI fifo reader (sys.z9x.nui != ready)");
        }
    }

    private static void requireNuiReader(String what) throws NuiNotReadyException {
        if (!nuiReaderReady()) throw new NuiNotReadyException(what);
    }

    /** 152 setHumanDetectOnOff(on): the eye protection switch (stock more_eye), only with the reader up. */
    public boolean setEyeProtection(boolean on) throws RemoteException {
        requireNuiReader("152");
        return callBoolRetBool(SET_HUMAN_DETECT, on);
    }

    /**
     * 148 setHumanDetectScreenOnOff(true): give the light back after the vendor dimmed it for a person in front
     * of the lens (stock GmpfClient.restoreEyeProtectionScreen). Never false. Only with the reader up.
     */
    public boolean restoreEyeProtectionScreen() throws RemoteException {
        requireNuiReader("148");
        return callBoolRetBool(SET_HUMAN_DETECT_SCREEN, true);
    }
}
