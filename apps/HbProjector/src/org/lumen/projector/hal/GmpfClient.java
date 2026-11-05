/*
 * GmpfClient (Z9X HB): whitelisted, typed client of the stock XGIMI vendor service on the Z9X HB (goat_elegant),
 * stable AIDL xgimi.hardware.gmpf V1. HB body of the Z9X class of the same name: every public member of the Z9X
 * GmpfClient with the same signature (except connect), so the rest of the app compiles
 * against it unchanged. The Z9X sent IGmpf HIDL codes; here every method names one Call of AidlCaller (service,
 * code, stub method; table in the design notes). Comments quote the HB service and code, and the Z9X
 * code in brackets.
 *
 * SAFETY (the Z9X rules, unchanged):
 *  - There is NO generic transact: every public method is one typed, whitelisted call with fixed or
 *    range-checked arguments (IllegalArgumentException before anything is sent).
 *  - Features the HB does not have throw NotOnHbException and send nothing: 3D
 *    (except the two neutral getters), Ultra 120 Hz, Harman / DTS:X sound enhancement, volume levelling. REVIEW
 *    rows of banned.tsv (section 9) stay refused the same way until reviewed: regenerateBootLogo,
 *    uiHeartBeatSet / uiHeartBeatDo, getBootReason. newAutoKst is approved (9.1, 2026-10-29) with mode 9, 10 or 11
 *    only, plus 12 for the stock curtain align hint window (2026-10-30, hb-t7); setKstPrepare (Gmpf2Client) with type 2 only (9.3, zoom case, hb-t5).
 *  - hb-t5 optical zoom and keystone reset (stock newsettings ZoomStateView / KeyStoneManager, V3DisplayManager):
 *    95 setOpticalZoomStep only inside an open 185(true, 2) + 93 session, +-1 from the last 98 read and inside the
 *    last 97 range, 190 = 0; 63 zoomControl only (5, 10) and (12, 10); 189 only next to 190 at index 0 (hb-t6); 38 / 84 / 85 only inside resetKeystoneStock (9.7).
 *  - Calls are refused on the main thread (AidlCaller). Callers use Hal.run / HalController threads.
 *  - Errors: RemoteException = not delivered / vendor dead; ReplyException = delivered, bad reply: do not repeat
 *    one-shot calls.
 *  - The focus callback (IProjectorFocusCallback, oneway) never calls the HAL and never blocks; it only hands
 *    the event to the EventSink, which must just post it.
 */
package org.lumen.projector.hal;

import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

public final class GmpfClient extends AidlCaller {

    // ---------------------------------------------------------------- descriptors (stock stubs)
    public static final String IGMPF = "xgimi.hardware.gmpf.IGmpf";
    public static final String IPROJECTOR_FOCUS_CALLBACK = "xgimi.hardware.gmpf.IProjectorFocusCallback";

    /** Picture/PQ calls always address window/source 0 (stock XgimiVideo: setPictureMode(0, i)). */
    private static final int WINDOW_MAIN = 0;

    // Fixed argument values (no free-form value is accepted from callers).
    private static final int FOCUS_STATUS_REMOTE_AF = 44;    // stock HB SystemUI FocusUIV2.autoFocus (remote key)
    private static final int FOCUS_STATUS_UI_READY = 17;     // stock HB SystemUI FocusUIV2.start: power-on AF flow
    private static final int FOCUS_STATUS_AF_UI_OPEN = 4;    // stock HB SystemUI AutoMaticFA: the AF card is on screen
    private static final int FOCUS_STATUS_AF_CARD_CLOSED = 5; // stock HB AutoMaticFA.hideAutoMaticFaView: card gone, then 3
    private static final int FOCUS_STATUS_AF_CARD_DONE = 3;   // also alone: stock HB AutoMaticFA stop / cancel
    private static final int SYSTEM_UI_STATE_READY = 1;      // stock SystemUI systemUIServicesCompleted
    private static final int BOOT_ANIM_STATE_RUNNING = 1;    // libbootanimation "set boot anim running to gmpf!"
    private static final int BOOT_ANIM_STATE_FINISHED = 2;   // libbootanimation "set boot anim stopped to gmpf!"
    private static final int TEMPORARY_SWITCH_AK = 2;        // KeyStoneManager TEMPORARY_SWITCH_AK=2 (FS 2.2)
    /** AuxiliaryCurtainAlignDialog.RESET_TEMPORARY_SWITCH_AK = 0 (stock HB SystemUI, sent as (0, false) on dismiss). */
    private static final int RESET_TEMPORARY_SWITCH_AK = 0;

    // ================================================================== value enums (Z9X values)
    /** manualFocus arguments allowed by the project rules: {17, 0, 1, 2} only (HB stock: stock analysis (d)). */
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
     * newAutoKst modes of the Z9X (FS 2.1): 9 misckey, 10 retry from the angle notice, 11 curtain re-fit; HB hb-t7:
     * 12 = the "Continue" of the stock curtain align hint window (AuxiliaryCurtainAlignDialog, 2026-10-30).
     */
    public enum AutoKstMode {
        MISCKEY(9), RETRY_ANGLE(10), CURTAIN_REFIT(11), CURTAIN_ALIGN(12);
        final int wire;
        AutoKstMode(int v) { wire = v; }
    }

    /** Picture modes (Z9X values). OFFICE is not offered on the HB (HbFeatures, setPictureMode refuses it). */
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

    /** Basic picture items, 0..100 each, window 0 (TV 5..12). */
    public enum PqItem {
        BRIGHTNESS, CONTRAST, SATURATION, SHARPNESS
    }

    /** 2-point white balance (TV 17..28): gain and offset per colour, 0..100 sent as shown, window 0. */
    public enum WbItem {
        RED_GAIN, GREEN_GAIN, BLUE_GAIN, RED_OFFSET, GREEN_OFFSET, BLUE_OFFSET
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

    /**
     * Colour temperature = DLP lumens mode. Z9X HB: the stock list of the dapengse tree, node
     * image_mode_4_color_temp_dapengse (newsettings res/raw/picture.json map {"0": led_standard_mode, "16": led_movie_mode},
     * sent by BrightnessViewModel.postArrowAbout -> BrightnessRepository.choiceLedMode -> setDlpLumensMode). The Z9X
     * values 16 / 17 are not this list.
     */
    public enum ColorTemp {
        STANDARD(0), MOVIE(16);
        public final int wire;
        ColorTemp(int v) { wire = v; }
    }

    /** Colour space (FS 3.5): 4 Native, 1 BT.2020, 7 Adobe RGB, 2 DCI-P3, 3 Rec.709. */
    public enum ColorSpace {
        NATIVE(4), BT2020(1), ADOBE_RGB(7), DCI_P3(2), REC709(3);
        public final int wire;
        ColorSpace(int v) { wire = v; }
    }

    /** HDMI colour range of the current HDMI input (stock SignalEnum): 0 auto, 1 full, 2 limited. */
    public enum HdmiRange {
        AUTO(0), FULL(1), LIMITED(2);
        public final int wire;
        HdmiRange(int v) { wire = v; }
    }

    /**
     * EDID of the current HDMI input, HB wire values. Stock G0130 newsettings SignalEnum: HDMI1_4 = 1, HDMI2_0 = 2
     * (4 only when edid_2_version_val is "3"; G0130 has "1"), sent through GmDeviceManager.setEDIDVersion ->
     * com.xgimi.gmuiapi.manager.GmTvManager.setEDIDVersion, which does version-- when SDK_INT > 26 (getEDIDVersion
     * adds 1 back), -> TV 40. So the wire is 0 = 1.4 and 1 = 2.0, as on the Z9X; SignalManager.getHdmiVersion shows 1
     * as "2.0" and every other read as "1.4". libxgimi Msrv_Tv_Control::_setEdidVersion loads edid14_n for 0 and
     * edid20_non_hrr_n for 1 (support_hfr + game mode version 3); 2 (stock EN_EDID_AUTO 3 minus 1) lets the game mode
     * pick the file. Checked 2026-11-04 against an external grab of HDMI 2. EDID_AUTO and EDID_21 have no stock row
     * on G0130 and are never sent (setEdidVersion refuses them).
     */
    public static final int EDID_14 = 0, EDID_20 = 1, EDID_AUTO = 2, EDID_21 = 3;

    /** Projector put mode (FS 2.9): 0 desk front .. 3 ceiling rear. */
    public enum PutMode {
        DESK_FRONT(0), CEILING_FRONT(1), DESK_REAR(2), CEILING_REAR(3);
        public final int wire;
        PutMode(int v) { wire = v; }
        public static PutMode fromWire(int v) {
            for (PutMode m : values()) if (m.wire == v) return m;
            return null;
        }
    }

    /**
     * HDMI aspect ratio: 0 4:3, 1 16:9, 2 Auto, 4 Original; 5 21:9 and 6 32:9 only from the game bar (stock HB xhplayer
     * GmDisplayManager.getVideoRatioFromCommonVal on a GMPF2 platform: common 6 / 7 -> 5 / 6, stock analysis).
     */
    public enum AspectRatio {
        R4_3(0), R16_9(1), AUTO(2), ORIGINAL(4), R21_9(5), R32_9(6);
        public final int wire;
        AspectRatio(int v) { wire = v; }
    }

    /** HDMI game mode option (gamemode_version=3): 0 basic, 1 basic+HFR, 2 top speed+HFR, 3 top speed. */
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

    /** "Ultra 120 Hz" output timing of the Z9X: not on the HB (setOutputTiming refuses). */
    public enum OutputTiming {
        ULTRA_120_ON(4), ULTRA_120_OFF(6);
        final int wire;
        OutputTiming(int v) { wire = v; }
    }

    /**
     * Sound mode, the same values in the HB stock (newsettings com.xgimi.sound.SoundEnum, stock analysis):
     * 3 AI (auto), 20 Standard, 1 Movie, 2 Music, 12 Sports, 4 Karaoke. 20 is never sent (as on the Z9X).
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
     * Output path for AUDIO 2 setAudioOutput (EN_XGIMI_AUDIO_PATH, same libxgimiaudiopolicy as the Z9X): 0 speaker,
     * 2 ARC, 3 BT A2DP, 4 wired, 6 USB, 100 switching (all inside the AIDL byte range 0..127).
     */
    public enum AudioPath {
        SPEAKER(0), ARC(2), BT_A2DP(3), WIRED(4), USB(6), SWITCHING(100);
        public final int wire;
        AudioPath(int v) { wire = v; }
    }

    /** Sound enhancement of the Z9X (Harman / DTS Virtual:X): not on the HB. */
    public enum SoundProcess {
        HARMAN(2), DTS_VIRTUAL_X(5);
        public final int wire;
        SoundProcess(int v) { wire = v; }
    }

    /** Eye protection mode (FS 5.1): 0 follow, 1 safe. */
    public enum HumanDetectMode {
        FOLLOW(0), SAFE(1);
        public final int wire;
        HumanDetectMode(int v) { wire = v; }
    }

    /** 3D of the Z9X: not on the HB (support_3D=0). */
    public enum Mode3D {
        OFF(1), SIDE_BY_SIDE(2), TOP_BOTTOM(3);
        public final int wire;
        Mode3D(int v) { wire = v; }
    }

    /** Input source of getCurrentInputSource: 0 media/storage, 1 HDMI 1, 2 HDMI 2. */
    public static final int SOURCE_MEDIA = 0, SOURCE_HDMI1 = 1, SOURCE_HDMI2 = 2;

    /** getHdrType values (QS PictureUtils). */
    public static final int HDR_SDR = 0, HDR_HDR10 = 1, HDR_DOLBY_VISION = 2, HDR_HLG = 4,
            HDR_HDR10_PLUS = 5, HDR_VIVID = 6;

    /** AK overlay event types that are acknowledged with DISPLAY 68 (FS 1.1 table). 110 is never acked. */
    private static final int[] AK_ACK_TYPES = {106, 107, 109, 113, 114, 115, 116, 118};

    // ------------------------------------------------------------- NUI fifo reader gate (Z9X v6.2b)
    /**
     * Property set to "ready" by a reader service of the vendor native-UI fifos. The Z9X image has z9x_nui.sh; no
     * HB image sets it yet, so it is false and eye protection sends nothing.
     */
    public static final String NUI_READY_PROP = "sys.z9x.nui";

    /** True when a reader holds the vendor native-UI fifo. Fails closed. Any thread. */
    public static boolean nuiReaderReady() {
        try {
            return "ready".equals(SystemProperties.get(NUI_READY_PROP, ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Thrown instead of sending the eye protection calls while {@link #nuiReaderReady()} is false. Nothing was sent. */
    public static final class NuiNotReadyException extends RemoteException {
        NuiNotReadyException(String what) {
            super(what + " not sent: no NUI fifo reader (" + NUI_READY_PROP + " != ready)");
        }
    }

    private static void requireNuiReader(String what) throws NuiNotReadyException {
        if (!nuiReaderReady()) throw new NuiNotReadyException(what);
    }

    /** Receives focus events. Called on a binder thread (oneway, nothing to answer): only post. */
    public interface EventSink {
        void onFocusEvent(int type, String value);
    }

    /** The vendor process died (all 14 services at once). Called once per death, on a binder thread: only post. */
    public interface DeathListener {
        void onVendorDied();
    }

    // ------------------------------------------------------------------------- state
    public GmpfClient(EventSink sink) {
        setFocusSink(sink == null ? null : sink::onFocusEvent);
    }

    // ---------------------------------------------------------------- connection
    /**
     * checkService + linkToDeath + version guard of SYSTEM, POWER and FOCUS (the handshake interfaces). Does not
     * wait for the services. Returns true when all three are connected and verified. Never throws. The listener
     * hears every later vendor death (replaces the Z9X connect(IHwBinder.DeathRecipient, long)).
     */
    public boolean connect(DeathListener death) {
        return connectHandshake(death == null ? null : death::onVendorDied);
    }

    // ------------------------------------------------------------- listener (Z9X 298 then 306)
    /** FOCUS 1 setFocusCallback(cb) [Z9X 298 + 306]: the stock registration, the one static callback object. */
    public void connectFocusManager() throws RemoteException {
        callRegisterFocusCallback(Call.SET_FOCUS_CALLBACK);
    }

    private static volatile boolean sFoldLogged;

    /** [Z9X 306] Folded into setFocusCallback on the HB: sends nothing. */
    public void setFocusListener() throws RemoteException {
        if (!sFoldLogged) {
            sFoldLogged = true;
            Log.i(TAG, "setFocusListener: folded into setFocusCallback on the HB, nothing sent");
        }
    }

    // ------------------------------------------------------------------ boot handshake
    /** SYSTEM 36 setSystemUIState(1) [Z9X 391]. */
    public void setSystemUiReady() throws RemoteException { callI32(Call.SET_SYSTEM_UI_STATE, SYSTEM_UI_STATE_READY); }

    /** SYSTEM 37 getSystemUIState() [Z9X 390]. */
    public int getSystemUiState() throws RemoteException { return callRetI32(Call.GET_SYSTEM_UI_STATE); }

    /** SYSTEM 32 setBootAnimState(2) [Z9X 395]. */
    public void setBootAnimFinished() throws RemoteException { callI32(Call.SET_BOOT_ANIM_STATE, BOOT_ANIM_STATE_FINISHED); }

    /**
     * SYSTEM 32 setBootAnimState(1): HbBoot row A (stock BootAnimation constructor), only while the boot animation
     * runs and SYSTEM 33 is not 2 yet; the HB handshake checks both (HalController, its only caller). HB-only
     * public member (the Z9X handshake has no row A).
     */
    public void setBootAnimRunning() throws RemoteException {
        callI32(Call.SET_BOOT_ANIM_STATE, BOOT_ANIM_STATE_RUNNING);
    }

    /** SYSTEM 33 getBootAnimState() [Z9X 394]. */
    public int getBootAnimState() throws RemoteException { return callRetI32(Call.GET_BOOT_ANIM_STATE); }

    /** POWER 2 goToBootCompleted(0 or 1) [Z9X 237]. The caller guarantees once per boot (per stage). */
    public boolean goToBootCompleted(BootStage s) throws RemoteException {
        return callI32RetBool(Call.GO_TO_BOOT_COMPLETED, s.wire);
    }

    /** FOCUS 8 sendFocusStatus(17) [Z9X 307(17)]: starts the vendor power-on AF flow. Handshake only, last. */
    public void sendPowerOnUiReady() throws RemoteException { callU8(Call.SEND_FOCUS_STATUS, FOCUS_STATUS_UI_READY); }

    // ---------------------------------------------------------------------- focus
    /** FOCUS 8 sendFocusStatus(44) [Z9X 307(44)]: remote focus key short press (stock FocusUIV2.autoFocus). */
    public void sendRemoteAutofocus() throws RemoteException { callU8(Call.SEND_FOCUS_STATUS, FOCUS_STATUS_REMOTE_AF); }

    /**
     * FOCUS 8 sendFocusStatus(4) [Z9X 307(4)]: our AF card is on screen. Stock HB SystemUI AutoMaticFA
     * (SystemUI_release_838 focus/AutoMaticFA.java:234-240) posts setAutoFocusCommand("4") once the card view is laid
     * out; the vendor AF (XLAB_AUTO_FOCUS2, "need requeset UI_img" after focusEvent 335) waits about 3 s for it, then
     * ends with AF_flow_excute_status -4 / EVENT_FLOW_ERR (seen on the HB with hb-t6, 2026-10-30). Caller: AfCardOverlay.
     */
    public void sendAfUiOpen() throws RemoteException { callU8(Call.SEND_FOCUS_STATUS, FOCUS_STATUS_AF_UI_OPEN); }

    /**
     * FOCUS 8 sendFocusStatus(5) then (3) [Z9X 307]: our AF card was closed. Stock HB AutoMaticFA.hideAutoMaticFaView
     * sends "5" then "3" whenever a shown card goes (AutoMaticFA.java:456-470). Caller: AfCardOverlay, after a 4.
     */
    public void sendAfCardClosed() throws RemoteException {
        callU8(Call.SEND_FOCUS_STATUS, FOCUS_STATUS_AF_CARD_CLOSED);
        callU8(Call.SEND_FOCUS_STATUS, FOCUS_STATUS_AF_CARD_DONE);
    }

    /**
     * FOCUS 8 sendFocusStatus(3) [Z9X 307(3)]: the user stopped the AF on the card (BACK, LEFT / RIGHT). Stock HB
     * AutoMaticFA newVontrolAf / oldVontrolAf send "3" first, then hide the card. Caller: AfCardOverlay.
     */
    public void sendAfStop() throws RemoteException { callU8(Call.SEND_FOCUS_STATUS, FOCUS_STATUS_AF_CARD_DONE); }

    /** FOCUS 2 manualFocus(17 / 0 / 1 / 2) [Z9X 300]. Returns immediately; the vendor runs the motor itself. */
    public void manualFocus(ManualFocus m) throws RemoteException { callU8(Call.MANUAL_FOCUS, m.wire); }

    /** FOCUS 5 / 4 [Z9X 303 / 302]: autofocus at power-on. */
    public boolean getPowerOnAf() throws RemoteException { return callRetBool(Call.IS_POWER_ON_AF_ENABLED); }
    public void setPowerOnAf(boolean on) throws RemoteException { callBool(Call.POWER_ON_AF_ENABLE, on); }
    /** MOTION 5 / 4 [Z9X 588 / 587]: autofocus after the projector was moved. */
    public boolean getMoveAf() throws RemoteException { return callRetBool(Call.GET_ANG_TRIGGER_AF); }
    public boolean setMoveAf(boolean on) throws RemoteException { return callBoolRetBool(Call.SET_ANG_TRIGGER_AF, on); }

    // -------------------------------------------------------------------- keystone
    /** FOCUS 27 autoKst() -> byte [Z9X 326]. May block inside the HAL for seconds: keystone thread only. */
    public byte autoKst() throws RemoteException {
        HbFeatures.require(HbFeatures.F.AUTO_KEYSTONE, "autoKst");      // every path (panel, remote, voice, Setup)
        return callRetI8(Call.AUTO_KST);
    }

    /**
     * FOCUS 53 newAutoKst(int mode) -> byte [Z9X 272]. Decided 2026-10-29: the
     * EXCEPTION row of banned.tsv, only with the modes the stock G0130 callers send and the Z9X enum has:
     * 9 (newsettings KeyStoneManager.autoKeystone, "com.xgimi.misckey"), 10 (newsettings KeyStoneWind onClick),
     * 11 (SystemUI CurtainAdaptManager.autoKeystone, the curtain re-fit). Any other value (null, or an enum constant
     * added later) is refused with IllegalArgumentException and nothing is sent. Mode 11 (fit to screen) also needs
     * CURTAIN_FIT ON; 9 and 10 need AUTO_KEYSTONE ON. May block inside the HAL for seconds: keystone thread only.
     */
    public byte newAutoKst(AutoKstMode m) throws RemoteException {
        final int mode = checkedAutoKstMode(m);
        HbFeatures.require(mode == NEW_AUTO_KST_CURTAIN || mode == NEW_AUTO_KST_CURTAIN_ALIGN
                        ? HbFeatures.F.CURTAIN_FIT : HbFeatures.F.AUTO_KEYSTONE,
                "newAutoKst(" + mode + ")");
        return call(Call.NEW_AUTO_KST, o -> o.i32(mode), RET_I8);
    }

    /**
     * The only newAutoKst modes allowed on the HB (allowed.tsv row ProjectorFocusManager 53, 9.1; 12 added hb-t7 with
     * the decision of 2026-10-30, stock HB SystemUI AuxiliaryCurtainAlignDialog.onKeyUp KEYCODE_DPAD_CENTER).
     */
    static final int[] NEW_AUTO_KST_MODES = {9, 10, 11, 12};
    private static final int NEW_AUTO_KST_CURTAIN = 11;
    private static final int NEW_AUTO_KST_CURTAIN_ALIGN = 12;

    /** The wire value of {@code m} when it is one of {@link #NEW_AUTO_KST_MODES}; else IllegalArgumentException. */
    static int checkedAutoKstMode(AutoKstMode m) {
        if (m == null) throw new IllegalArgumentException("newAutoKst: no mode");
        for (int v : NEW_AUTO_KST_MODES) if (v == m.wire) return v;
        throw new IllegalArgumentException("newAutoKst: mode " + m.wire + " is not 9, 10, 11 or 12 (9.1)");
    }

    /**
     * DISPLAY 68 uiAkDisplay(type) [Z9X 146]: acknowledge that the AK overlay rendered step {@code type}.
     * Only the types of the FS 1.1 table: 106 107 109 113 114 115 116 118.
     */
    public void uiAkDisplay(int type) throws RemoteException {
        boolean ok = false;
        for (int t : AK_ACK_TYPES) if (t == type) { ok = true; break; }
        if (!ok) throw new IllegalArgumentException("uiAkDisplay: type " + type + " is not an AK ack type");
        callI32(Call.UI_AK_DISPLAY, type);
    }

    /**
     * DISPLAY 16 getCorrectKeystoneFullPiont(mode 0) -> KstPoint [Z9X 186]: current 4 corners. Throws ReplyException
     * if the reply is not an 81-point KstPoint or a corner is outside the 3840x2160 panel.
     */
    public KstPoint getKeystonePoints() throws RemoteException {
        HbFeatures.require(HbFeatures.F.MANUAL_KEYSTONE, "getCorrectKeystoneFullPiont");
        final byte mode = (byte) KstPoint.MODE_FOUR_POINT;
        try {
            return call(Call.GET_CORRECT_KEYSTONE_FULL_PIONT, o -> o.i8(mode), r -> r.object(KstPoint::readFrom));
        } catch (IllegalArgumentException e) {
            throw new ReplyException("16 corner out of range: " + e.getMessage());
        }
    }

    /** DISPLAY 64 checkTrapezoidCoordinate(KstPoint) -> true when the vendor accepts the corners [Z9X 150]. */
    public boolean checkKeystonePoints(KstPoint p) throws RemoteException {
        HbFeatures.require(HbFeatures.F.MANUAL_KEYSTONE, "checkTrapezoidCoordinate");
        return call(Call.CHECK_TRAPEZOID_COORDINATE, o -> o.object(p::writeTo), RET_BOOL);
    }

    /**
     * DISPLAY 15 setCorrectKeystoneFullPiont(mode 0, KstPoint) -> boolean [Z9X 185 -> i32]. Returns 1 for true, 0
     * for false (the Z9X callers only log it). Callers must have called {@link #checkKeystonePoints} first.
     */
    public int applyKeystonePoints(KstPoint p) throws RemoteException {
        HbFeatures.require(HbFeatures.F.MANUAL_KEYSTONE, "setCorrectKeystoneFullPiont");
        final byte mode = (byte) KstPoint.MODE_FOUR_POINT;
        return call(Call.SET_CORRECT_KEYSTONE_FULL_PIONT, o -> { o.i8(mode); o.object(p::writeTo); }, RET_BOOL) ? 1 : 0;
    }

    /** [Z9X 158] DISPLAY 56 generateLogoByTrapezoid is a REVIEW row: refused, nothing sent. */
    public int regenerateBootLogo() throws RemoteException {
        throw new NotOnHbException("generateLogoByTrapezoid(0) (not used on the HB)");
    }

    /** MOTION 15 temporarySwitchTrigger(2, enable) [Z9X 573]: pause / resume real-time AK. Type 2. */
    public boolean setRealtimeAkTemporarily(boolean enable) throws RemoteException {
        return callI32BoolRetBool(Call.TEMPORARY_SWITCH_TRIGGER, TEMPORARY_SWITCH_AK, enable);
    }

    /**
     * MOTION 15 temporarySwitchTrigger(0, false): end the temporary switch, exactly as stock HB SystemUI
     * AuxiliaryCurtainAlignDialog.dismiss (RESET_TEMPORARY_SWITCH_AK = 0, always with false). hb-t7, only from
     * CurtainAlignOverlay after its show sent (2, false). No other value of type 0 is sent.
     */
    public boolean resetRealtimeAkTemporary() throws RemoteException {
        return callI32BoolRetBool(Call.TEMPORARY_SWITCH_TRIGGER, RESET_TEMPORARY_SWITCH_AK, false);
    }

    /**
     * hb-t5 keystone reset, the stock G0130 V3DisplayManager.setIChipReset body
     * (newsettings classes3 198d44, SystemUI classes2): DISPLAY 38 correctKeystoneReset(0), 84 getCurrentKeystoneMode(),
     * and 85 switchKeystoneMode(0, 0) only when 84 returned 6. Not the stock correctKeystone(50) (11, banned) and no 158.
     * Refused in top-speed game mode (that has its own 38 path above). Returns the 84 mode. HAL thread.
     */
    public int resetKeystoneStock() throws RemoteException {
        HbFeatures.require(HbFeatures.F.KEYSTONE_RESET, "resetKeystoneStock");
        GameModeOption cur = getGameModeOption();
        if (cur != null && cur.isTopSpeed()) {
            throw new IllegalStateException("keystone reset refused in top-speed game mode (" + cur + ")");
        }
        callU8(Call.CORRECT_KEYSTONE_RESET, 0);
        int mode = callRetI32(Call.GET_CURRENT_KEYSTONE_MODE);
        if (mode == KEYSTONE_MODE_SWITCH_BACK) {
            boolean r = callI32I32RetBool(Call.SWITCH_KEYSTONE_MODE, 0, 0);
            Log.i(TAG, "keystone reset: 84 -> 6, 85(0,0) -> " + r);
        }
        return mode;
    }

    /** The 84 value after which stock setIChipReset sends 85(0, 0). */
    private static final int KEYSTONE_MODE_SWITCH_BACK = 6;

    /**
     * DISPLAY 38 correctKeystoneReset(0) [Z9X 207]: ONLY in HDMI game "top speed" mode (refused otherwise). Stock HB
     * switchGameModeSpeed calls setIChipReset after 76(2), so 84 and 85(0, 0) after a 6 follow as in resetKeystoneStock.
     */
    public void resetKeystoneForTopSpeedGame() throws RemoteException {
        GameModeOption cur = getGameModeOption();
        if (cur == null || !cur.isTopSpeed()) {
            throw new IllegalStateException("correctKeystoneReset only in top-speed game mode (now " + cur + ")");
        }
        callU8(Call.CORRECT_KEYSTONE_RESET, 0);
        int mode = callRetI32(Call.GET_CURRENT_KEYSTONE_MODE);
        if (mode == KEYSTONE_MODE_SWITCH_BACK) {
            boolean r = callI32I32RetBool(Call.SWITCH_KEYSTONE_MODE, 0, 0);
            Log.i(TAG, "top speed keystone reset: 84 -> 6, 85(0,0) -> " + r);
        }
    }

    /** Picture shift direction for translationKst (FS 2.5): 1 up, 2 down, 3 left, 4 right. */
    public enum ShiftDir {
        UP(1), DOWN(2), LEFT(3), RIGHT(4);
        final int wire;
        ShiftDir(int v) { wire = v; }
    }

    /** DISPLAY 72 translationKst(dir, onlyCheck) -> 0 possible / -1 at the edge [Z9X 141]. */
    public int shiftPicture(ShiftDir d, boolean onlyCheck) throws RemoteException {
        HbFeatures.require(HbFeatures.F.ZOOM_SHIFT, "translationKst");
        return callI32BoolRetI32(Call.TRANSLATION_KST, d.wire, onlyCheck);
    }

    // ------------------------------------------------- fit to my screen size, reads only
    private static final Object SENSOR_LOCK = new Object();
    private static final long TOF_GAP_MS = 1_000, SENSOR_WINDOW_MS = 60_000, MOTION_GAP_MS = 80;
    private static final int TOF_PER_WINDOW = 6, MOTION_PER_WINDOW = 20;
    private static final long[] sTofAt = new long[TOF_PER_WINDOW], sMotionAt = new long[MOTION_PER_WINDOW];
    private static int sTofNext, sMotionNext;

    /** Rate guard of the two sensor reads: at least gap apart and at most stamps.length per minute. */
    private static void sensorBudget(String what, long[] stamps, int next, long gapMs) {
        long now = SystemClock.elapsedRealtime();
        long last = stamps[(next + stamps.length - 1) % stamps.length];
        if (last != 0 && now - last < gapMs) throw new IllegalStateException(what + " within " + gapMs + " ms of the last one");
        long oldest = stamps[next];
        if (oldest != 0 && now - oldest < SENSOR_WINDOW_MS) {
            throw new IllegalStateException(what + ": " + stamps.length + " reads in the last minute already");
        }
        stamps[next] = now;
    }

    /**
     * FOCUS 46 getTofDistance() -> int mm (the Z9X IGmpf 279): one active ToF measurement (about 0.6 s on the vendor's
     * single thread). Stock caller: newsettings CalibrationTofDialog.java:424 / CalibrationCurtainDialog.java:1197 /
     * HallCalibrationDistanceDialog.java:239 (CalibrationManager.getTofDistance -> ProjectorFocusManagerWrapper:238).
     * Refused (nothing sent) unless FIT_SCREEN is ON, within 1 s of the last read or after 6 reads in a minute; the
     * fit itself reads 3 per run (FitRun). Never in a loop or a timer.
     */
    public int getTofDistance() throws RemoteException {
        HbFeatures.require(HbFeatures.F.FIT_SCREEN, "getTofDistance");
        synchronized (SENSOR_LOCK) {
            sensorBudget("46 getTofDistance", sTofAt, sTofNext, TOF_GAP_MS);
            sTofNext = (sTofNext + 1) % sTofAt.length;
        }
        return callRetI32(Call.GET_TOF_DISTANCE);
    }

    /**
     * MOTION 2 getMotionDetection() -> MsensorDataStruct {int[] accData, int[] angData, byte msensorType} (the Z9X
     * IGmpf 584), returned as {acc0, acc1, acc2, ang0, ang1, ang2, type}. Stock caller: newsettings
     * MotionDetectionManagerWrapper.getMotionDetection:41 (stock IMotionDetectionManager proxy: readTypedObject).
     * Axis order and signs UNVERIFIED. Refused unless FIT_SCREEN is ON, within
     * 80 ms of the last read or after 20 reads in a minute; the fit reads 5 per run.
     */
    public int[] getMotionDetection() throws RemoteException {
        HbFeatures.require(HbFeatures.F.FIT_SCREEN, "getMotionDetection");
        synchronized (SENSOR_LOCK) {
            sensorBudget("motion 2 getMotionDetection", sMotionAt, sMotionNext, MOTION_GAP_MS);
            sMotionNext = (sMotionNext + 1) % sMotionAt.length;
        }
        return call(Call.GET_MOTION_DETECTION, null, r -> r.object(q -> {
            int[] acc = q.ints(8), ang = q.ints(8);
            int type = q.i8();
            if (acc.length != 3 || ang.length != 3) {
                throw new IllegalStateException("MsensorDataStruct arrays " + acc.length + " / " + ang.length);
            }
            return new int[] {acc[0], acc[1], acc[2], ang[0], ang[1], ang[2], type};
        }));
    }

    // ------------------------------------------------- projection toggles (getter first, setter on click)
    /** FOCUS 36 / 35 [Z9X 289 / 290]: fit the picture into the projection screen (curtain). */
    public boolean getCurtainFit() throws RemoteException { return callRetBool(Call.GET_PROJ_CURTAIN_ADP); }
    public void setCurtainFit(boolean on) throws RemoteException {
        HbFeatures.require(HbFeatures.F.CURTAIN_FIT, "setProjCurtainAdp");
        callBool(Call.SET_PROJ_CURTAIN_ADP, on);
    }
    /** FOCUS 34 / 33 [Z9X 291 / 292]: obstacle avoidance. */
    public boolean getObstacleAvoid() throws RemoteException { return callRetBool(Call.GET_PROJ_SCREEN_ADP); }
    public void setObstacleAvoid(boolean on) throws RemoteException {
        HbFeatures.require(HbFeatures.F.OBSTACLE_AVOID, "setProjScreenAdp");
        callBool(Call.SET_PROJ_SCREEN_ADP, on);
    }
    /** FACTORY 46 / 45 [Z9X 553 / 552]: auto keystone at power-on (the two EXCEPTION rows of IGmFactoryManager). */
    public boolean getPowerOnAk() throws RemoteException { return callRetBool(Call.GET_POWER_ON_AK_FLAG); }
    public void setPowerOnAk(boolean on) throws RemoteException { callBool(Call.SAVE_POWER_ON_AK_FLAG, on); }
    /** MOTION 7 / 6 [Z9X 586 / 585]: auto keystone after the projector was moved. */
    public boolean getMoveAk() throws RemoteException { return callRetBool(Call.GET_ACC_TRIGGER_AK); }
    public boolean setMoveAk(boolean on) throws RemoteException { return callBoolRetBool(Call.SET_ACC_TRIGGER_AK, on); }
    /** MOTION 9 / 8 [Z9X 579 / 580]: automatic flip ("Auto" projection mode). */
    public boolean getAutoReverse() throws RemoteException { return callRetBool(Call.GET_AUTO_REVERSE); }
    public boolean setAutoReverse(boolean on) throws RemoteException { return callBoolRetBool(Call.SET_AUTO_REVERSE, on); }

    /** DISPLAY 3 getProjectorPutMode() -> byte (null if not 0..3) [Z9X 173]. */
    public PutMode getPutMode() throws RemoteException { return PutMode.fromWire(callRetU8(Call.GET_PROJECTOR_PUT_MODE)); }
    /** DISPLAY 2 setProjectorPutMode(byte) [Z9X 172]. Flips the image at once: confirm first. */
    public void setPutMode(PutMode m) throws RemoteException { callU8(Call.SET_PROJECTOR_PUT_MODE, m.wire); }

    // ------------------------------------------------------------------ lamp / screen
    /** DISPLAY 7 getDlpLumensLevel() -> 1..10 [Z9X 177]. */
    public int getLampLevel() throws RemoteException { return callRetU8(Call.GET_DLP_LUMENS_LEVEL); }
    /** DISPLAY 6 setDlpLumensLevel(byte 1..10) [Z9X 176]. */
    public void setLampLevel(int level) throws RemoteException {
        callU8(Call.SET_DLP_LUMENS_LEVEL, checkRange("lamp level", level, 1, 10));
    }

    /**
     * DISPLAY 5 getDlpLumensMode() -> raw byte [Z9X 175]. Snapshot.colorTemp stays null if it is not 0 / 16 (e.g. a
     * Boost or Performance mode), as before (Z9X 1.0.8 moved the ColorTemp match into Snapshot).
     */
    public int getLumensModeRaw() throws RemoteException { return callRetU8(Call.GET_DLP_LUMENS_MODE); }
    /** DISPLAY 4 setDlpLumensMode(byte 0|16) [Z9X 174]: only the ColorTemp values. Only while HbFeatures.F.COLOR_TEMP is ON. */
    public void setColorTemp(ColorTemp c) throws RemoteException {
        HbFeatures.require(HbFeatures.F.COLOR_TEMP, "setDlpLumensMode");
        callU8(Call.SET_DLP_LUMENS_MODE, c.wire);
    }

    /** DISPLAY 28 getScreenOnOff() [Z9X 196]: light/screen on. */
    public boolean getScreenOn() throws RemoteException { return callRetBool(Call.GET_SCREEN_ON_OFF); }
    /** DISPLAY 27 setScreenOnOff(bool) [Z9X 195]: lamp + lens door, as stock xgimiPrepareSleep. */
    public void setScreenOn(boolean on) throws RemoteException { callBool(Call.SET_SCREEN_ON_OFF, on); }

    /**
     * DISPLAY 62 / 61 [Z9X 151 / 152]: smart eye protection (ToF human detect). 61 only while the NUI fifo
     * reader is up ({@link NuiNotReadyException} otherwise).
     */
    public boolean getEyeProtection() throws RemoteException { return callRetBool(Call.GET_HUMAN_DETECT_ON_OFF); }
    public boolean setEyeProtection(boolean on) throws RemoteException {
        requireNuiReader("DISPLAY 61 setHumanDetectOnOff");
        return callBoolRetBool(Call.SET_HUMAN_DETECT_ON_OFF, on);
    }
    /** DISPLAY 67 getHumanDetectScreenOnOff() [Z9X 147]. */
    public boolean getEyeProtectionScreenOn() throws RemoteException { return callRetBool(Call.GET_HUMAN_DETECT_SCREEN_ON_OFF); }
    /** DISPLAY 66 setHumanDetectScreenOnOff(TRUE) only [Z9X 148]: give the light back after an eye-protection blank. */
    public boolean restoreEyeProtectionScreen() throws RemoteException {
        requireNuiReader("DISPLAY 66 setHumanDetectScreenOnOff");
        return callBoolRetBool(Call.SET_HUMAN_DETECT_SCREEN_ON_OFF, true);
    }

    /**
     * [Z9X 115] DISPLAY 99 uiHeartBeatSet is a REVIEW row: refused, nothing sent. The
     * argument checks of the Z9X stay, and the NUI gate comes first as on the Z9X.
     */
    public byte uiHeartBeatSet(String name, boolean arm, int periodMs) throws RemoteException {
        checkWatchName(name);
        checkRange("heartbeat period", periodMs, 500, 10_000);
        requireNuiReader("DISPLAY 99 uiHeartBeatSet");
        throw new NotOnHbException("uiHeartBeatSet (not used on the HB)");
    }

    /** [Z9X 114] DISPLAY 100 uiHeartBeatDo is a REVIEW row: refused, nothing sent. */
    public byte uiHeartBeatDo(String name) throws RemoteException {
        checkWatchName(name);
        requireNuiReader("DISPLAY 100 uiHeartBeatDo");
        throw new NotOnHbException("uiHeartBeatDo (not used on the HB)");
    }

    /** Watchdog names: short ASCII identifiers only. */
    private static void checkWatchName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,31}")) {
            throw new IllegalArgumentException("bad watchdog name: " + name);
        }
    }

    /** TEMPERATURE 6 getSystemTemperature() -> byte deg C [Z9X 438]. */
    public int getSystemTemperature() throws RemoteException { return callRetU8(Call.GET_SYSTEM_TEMPERATURE); }

    // ------------------------------------------------------------------ picture
    /** TV 34 getPictureMode(0) -> int (10..17 = AI) [Z9X 666]. */
    public int getPictureModeRaw() throws RemoteException { return callI32RetI32(Call.GET_PICTURE_MODE, WINDOW_MAIN); }
    /** TV 33 setPictureMode(0, m) [Z9X 667]. OFFICE is not on the HB (refused). */
    public boolean setPictureMode(PictureMode m) throws RemoteException {
        if (m == PictureMode.OFFICE) HbFeatures.require(HbFeatures.F.OFFICE_MODE, "setPictureMode(OFFICE)");
        return callI32I32RetBool(Call.SET_PICTURE_MODE, WINDOW_MAIN, m.wire);
    }
    /** TV 33 setPictureMode(0, 10..17): AI scene refinement. Only while the current mode is AI (FS 3.3). */
    public boolean setAiScene(AiScene s) throws RemoteException {
        return callI32I32RetBool(Call.SET_PICTURE_MODE, WINDOW_MAIN, s.wire);
    }

    /** TV 8 / 6 / 10 / 12 get(0) -> 0..100 [Z9X 692 / 694 / 690 / 688]. */
    public int getPq(PqItem item) throws RemoteException {
        switch (item) {
            case BRIGHTNESS: return getWindowValue(Call.GET_BRIGHTNESS);
            case CONTRAST: return getWindowValue(Call.GET_CONTRAST);
            case SATURATION: return getWindowValue(Call.GET_SATURATION);
            case SHARPNESS: return getWindowValue(Call.GET_SHARPNESS);
            default: throw new IllegalArgumentException("pq " + item);
        }
    }
    /** TV 7 / 5 / 9 / 11 set(0, v 0..100) [Z9X 693 / 695 / 691 / 689]. Debounce sliders. */
    public boolean setPq(PqItem item, int value) throws RemoteException {
        int v = checkRange(item.name(), value, 0, 100);
        switch (item) {
            case BRIGHTNESS: return setWindowValue(Call.SET_BRIGHTNESS, v);
            case CONTRAST: return setWindowValue(Call.SET_CONTRAST, v);
            case SATURATION: return setWindowValue(Call.SET_SATURATION, v);
            case SHARPNESS: return setWindowValue(Call.SET_SHARPNESS, v);
            default: throw new IllegalArgumentException("pq " + item);
        }
    }

    /** TV 32 getMemcLevel(0) -> 0..3 [Z9X 668]. */
    public int getMemcLevel() throws RemoteException { return callI32RetI32(Call.GET_MEMC_LEVEL, WINDOW_MAIN); }
    /** TV 31 setMemcLevel(0, l) [Z9X 669]. */
    public boolean setMemcLevel(MemcLevel l) throws RemoteException {
        return callI32I32RetBool(Call.SET_MEMC_LEVEL, WINDOW_MAIN, l.wire);
    }

    /** TV 18 / 20 / 22 / 24 / 26 / 28 getColorTemp*(0) -> 0..100 [Z9X 682 .. 672]. */
    public int getWb(WbItem item) throws RemoteException {
        switch (item) {
            case RED_GAIN: return getWindowValue(Call.GET_COLOR_TEMP_RED_GAIN);
            case GREEN_GAIN: return getWindowValue(Call.GET_COLOR_TEMP_GREEN_GAIN);
            case BLUE_GAIN: return getWindowValue(Call.GET_COLOR_TEMP_BLUE_GAIN);
            case RED_OFFSET: return getWindowValue(Call.GET_COLOR_TEMP_RED_OFFSET);
            case GREEN_OFFSET: return getWindowValue(Call.GET_COLOR_TEMP_GREEN_OFFSET);
            case BLUE_OFFSET: return getWindowValue(Call.GET_COLOR_TEMP_BLUE_OFFSET);
            default: throw new IllegalArgumentException("wb " + item);
        }
    }
    /** TV 17 / 19 / 21 / 23 / 25 / 27 setColorTemp*(0, 0..100) [Z9X 683 .. 673]. */
    public boolean setWb(WbItem item, int value) throws RemoteException {
        int v = checkRange(item.name(), value, 0, 100);
        switch (item) {
            case RED_GAIN: return setWindowValue(Call.SET_COLOR_TEMP_RED_GAIN, v);
            case GREEN_GAIN: return setWindowValue(Call.SET_COLOR_TEMP_GREEN_GAIN, v);
            case BLUE_GAIN: return setWindowValue(Call.SET_COLOR_TEMP_BLUE_GAIN, v);
            case RED_OFFSET: return setWindowValue(Call.SET_COLOR_TEMP_RED_OFFSET, v);
            case GREEN_OFFSET: return setWindowValue(Call.SET_COLOR_TEMP_GREEN_OFFSET, v);
            case BLUE_OFFSET: return setWindowValue(Call.SET_COLOR_TEMP_BLUE_OFFSET, v);
            default: throw new IllegalArgumentException("wb " + item);
        }
    }

    /** A window-0 getter of the PQ / WB tables: one Call per item, chosen by the switches above. */
    private int getWindowValue(Call c) throws RemoteException { return callI32RetI32(c, WINDOW_MAIN); }

    private boolean setWindowValue(Call c, int v) throws RemoteException { return callI32I32RetBool(c, WINDOW_MAIN, v); }

    /** TV 49 getUcdLevel(0) -> 0..3 ("local contrast") [Z9X 651]. */
    public int getLocalContrast() throws RemoteException { return callI32RetI32(Call.GET_UCD_LEVEL, WINDOW_MAIN); }
    /** TV 45 setUcdLevel(0, 0..3) [Z9X 655]. */
    public boolean setLocalContrast(int level) throws RemoteException {
        return callI32I32RetBool(Call.SET_UCD_LEVEL, WINDOW_MAIN, checkRange("ucd level", level, 0, 3));
    }

    /** TV 30 get3DNR(0) -> 0..4 [Z9X 670]. */
    public int getNoiseReduction() throws RemoteException { return callI32RetI32(Call.GET_3DNR, WINDOW_MAIN); }
    /** TV 29 set3DNR(0, n) [Z9X 671]. */
    public boolean setNoiseReduction(NoiseReduction n) throws RemoteException {
        return callI32I32RetBool(Call.SET_3DNR, WINDOW_MAIN, n.wire);
    }

    /** DISPLAY 103 getDispColorSpace(source, pictureMode) -> ColorSpace wire [Z9X 111]. */
    public int getColorSpaceRaw(int source, int pictureMode) throws RemoteException {
        return callI32I32RetI32(Call.GET_DISP_COLOR_SPACE, checkRange("source", source, 0, 3),
                checkRange("picture mode", pictureMode, 0, 40));
    }
    /** DISPLAY 102 switchDispColorSpace(cs) -> int [Z9X 112]. */
    public int setColorSpace(ColorSpace cs) throws RemoteException { return callI32RetI32(Call.SWITCH_DISP_COLOR_SPACE, cs.wire); }

    /** TV 78 getHdrType() -> HDR_* constants [Z9X 620]. */
    public int getHdrType() throws RemoteException { return callRetI32(Call.GET_HDR_TYPE); }

    /** TV 4 getCurrentInputSource() -> SOURCE_* [Z9X 696]. */
    public int getCurrentInputSource() throws RemoteException { return callRetI32(Call.GET_CURRENT_INPUT_SOURCE); }

    /**
     * TV 3 switchInputSource(source) -> bool [Z9X 698; HB stock xhplayer TV.smali:1568/1603]: SOURCE_HDMI1 (1) /
     * SOURCE_HDMI2 (2) connect that input to the main window, SOURCE_MEDIA (0) gives the window back to Android.
     * Any other source is refused. Stock: InputActivity tunes with GmTvManager.switchInputSource(inputToInt(id)),
     * "HDMI1TVINPUTSERVICE" -> 1, "HDMI2TVINPUTSERVICE" -> 2 (TV.inputToInt), and leaves with switchToMM =
     * switchInputSource(0); the proxy writes one int (IGmTvManager$Stub$Proxy, transact 3, readBoolean). The
     * Z9X IGmpf 698 twin (GMPF 25 switchInputSourceWithCallingPid) is not implemented by the HB vendor
     * (device tests, HDMI black screen) and is never sent.
     */
    public boolean switchInputSource(int source) throws RemoteException {
        if (source != SOURCE_MEDIA && source != SOURCE_HDMI1 && source != SOURCE_HDMI2) {
            throw new IllegalArgumentException("source " + source);
        }
        return callI32RetBool(Call.SWITCH_INPUT_SOURCE, source);
    }

    // ---- HDMI settings of the current input (quick panel Picture page, HDMI only)
    /** TV 64 getHdmiRange() -> 0 auto / 1 full / 2 limited [Z9X 634]. */
    public int getHdmiRange() throws RemoteException { return callRetI8(Call.GET_HDMI_RANGE); }
    /** TV 63 setHdmiRange(byte r) [Z9X 635]. Caller checks HDMI is on screen. */
    public boolean setHdmiRange(HdmiRange r) throws RemoteException { return callU8RetBool(Call.SET_HDMI_RANGE, r.wire); }

    /** TV 39 getEdidVersion() -> EDID_* [Z9X 661]. */
    public int getEdidVersion() throws RemoteException { return callRetI32(Call.GET_EDID_VERSION); }
    /** TV 40 setEdidVersion(EDID_14 | EDID_20) [Z9X 660]: the two stock G0130 values only (0 / 1). */
    public boolean setEdidVersion(int v) throws RemoteException {
        if (v != EDID_14 && v != EDID_20) throw new IllegalArgumentException("edid " + v + ": not a stock HB value");
        return callI32RetBool(Call.SET_EDID_VERSION, v);
    }

    // ------------------------------------------------------------------ 3D (support_3D=0: nothing is sent)
    /** [Z9X 180] isVideoPlaying: only the Z9X 3D module asked; not on the HB. */
    public boolean isVideoPlaying() throws RemoteException { throw new NotOnHbException("isVideoPlaying (3D only)"); }

    /** [Z9X 190] enable3D: not on the HB (support_3D=0). */
    public boolean set3DMode(Mode3D m) throws RemoteException { throw new NotOnHbException("enable3D"); }

    /** [Z9X 191] enable3DTo2D: not on the HB (support_3D=0). */
    public boolean set3DTo2D(boolean topBottom) throws RemoteException { throw new NotOnHbException("enable3DTo2D"); }

    /** [Z9X 192] Always 1 (2D) on the HB, without a call: shared code treats an unknown 3D state as active. */
    public int getCurrent3DFormat() throws RemoteException { return 1; }
    /** [Z9X 193] Always false on the HB, without a call. */
    public boolean is3DTo2DEnabled() throws RemoteException { return false; }

    // ------------------------------------------------------------------ zoom (optical since hb-t5, digital PENDING)
    /**
     * Optical zoom guard state (hb-t5). 95 is sent only inside a session: 185(true, 2) sent (Gmpf2Client), 93 sent,
     * a 97 range and a 98 step read in it; the step must be the neighbour of that 98 read, and every 95 needs a new
     * 98 read (stock KeyStoneManager.zoomControl reads getCurrentZoomStep before every setOpticalZoomStep).
     */
    private static final Object ZOOM_LOCK = new Object();
    private static boolean sZoomPrepared, sZoomSession;
    private static int sOptMin = -1, sOptMax = -1, sOptCur = -1;
    /** hb-t6 limit position: 63(12,10) count and the last 190 read in this session (LIM_UNKNOWN = none). */
    private static final int LIM_UNKNOWN = Integer.MIN_VALUE;
    private static int sLimTotal = -1, sLimCur = LIM_UNKNOWN;

    /** Gmpf2Client after it sent 185(enter, 2). */
    static void noteZoomPrepare(boolean enter) {
        synchronized (ZOOM_LOCK) {
            sZoomPrepared = enter;
            if (!enter) clearZoomReads();
        }
    }

    private static void clearZoomReads() {
        sOptMin = sOptMax = sOptCur = -1;
        sLimTotal = -1;
        sLimCur = LIM_UNKNOWN;
    }

    /**
     * DISPLAY 63 zoomControl(5, 10) -> int [Z9X 153]: the lens to its home position (stock resetKeyStone /
     * resetOpticalZoomAndZoom / SystemUI curtain reset, newsettings classes3 1e6804 +0x33). Only (5, 10).
     */
    public int zoomHome() throws RemoteException {
        HbFeatures.require(HbFeatures.F.OPTICAL_ZOOM, "zoomControl(5, 10)");
        int r = callI32I32RetI32(Call.ZOOM_CONTROL, ZOOM_HOME_TYPE, ZOOM_HOME_STEP);
        synchronized (ZOOM_LOCK) { sOptCur = -1; sLimCur = LIM_UNKNOWN; }
        return r;
    }

    /**
     * DISPLAY 63 zoomControl(12, 10) -> int (hb-t6): the number of zoom "limit" positions beyond optical index 0
     * (stock ZoomStateView.getLimitStep; 1 on the HB, vendor getZoomLimitTotalStep). A read. Remembered for the
     * 189 guard when a session is open.
     */
    public int getZoomLimitTotal() throws RemoteException {
        HbFeatures.require(HbFeatures.F.OPTICAL_ZOOM, "zoomControl(12, 10)");
        int r = callI32I32RetI32(Call.ZOOM_CONTROL, ZOOM_LIMIT_TOTAL_TYPE, ZOOM_HOME_STEP);
        synchronized (ZOOM_LOCK) { sLimTotal = sZoomSession && r >= 0 && r <= MAX_LIMIT_STEPS ? r : -1; }
        return r;
    }

    private static final int ZOOM_LIMIT_TOTAL_TYPE = 12, MAX_LIMIT_STEPS = 4;

    /** DISPLAY 190 GetCurrentZoomLimitStep(out st_ZoomStep) -> {ret, limit, digital} (hb-t6): 0 or -1..-total. */
    public int[] getZoomLimitStep() throws RemoteException {
        HbFeatures.require(HbFeatures.F.OPTICAL_ZOOM, "GetCurrentZoomLimitStep");
        int[] v = call(Call.GET_CURRENT_ZOOM_LIMIT_STEP, null, r -> {
            int ret = r.i32();
            int[] o = r.out(q -> new int[] {q.i32(), q.i32()});
            return new int[] {ret, o[0], o[1]};
        });
        synchronized (ZOOM_LOCK) { sLimCur = sZoomSession ? v[1] : LIM_UNKNOWN; }
        return v;
    }

    /**
     * DISPLAY 189 SetOpticalZoomLimitStep(step) -> int (hb-t6, EXCEPTION row): stock ZoomUtils.setOpticalZoomLimitStep,
     * GetCurrentZoomLimitStep() - 1 bigger / + 1 smaller. Refused unless inside a 185(true, 2) + 93 session, a 63(12,10)
     * count, a fresh 190 read and a fresh 98 read at optical index 0 are known, and step is next to that 190 read
     * inside [-count, 0]. Uses up both reads.
     */
    public int setOpticalZoomLimitStep(int step) throws RemoteException {
        HbFeatures.require(HbFeatures.F.OPTICAL_ZOOM, "SetOpticalZoomLimitStep");
        checkRange("zoom limit step", step, -MAX_LIMIT_STEPS, 0);
        synchronized (ZOOM_LOCK) {
            if (!sZoomPrepared || !sZoomSession) throw new IllegalStateException("189 outside a 185(true,2) + 93 session");
            if (sLimTotal <= 0 || sLimCur == LIM_UNKNOWN) throw new IllegalStateException("189 without a 63(12,10) count and a 190 read");
            if (sOptCur != 0) throw new IllegalStateException("189 away from optical index 0 (98 read " + sOptCur + ")");
            checkRange("zoom limit step", step, -sLimTotal, 0);
            if (Math.abs(step - sLimCur) != 1) {
                throw new IllegalArgumentException("189(" + step + ") is not next to the 190 position " + sLimCur);
            }
            sLimCur = LIM_UNKNOWN;
            sOptCur = -1;
        }
        return callI32RetI32(Call.SET_OPTICAL_ZOOM_LIMIT_STEP, step);
    }

    private static final int ZOOM_HOME_TYPE = 5, ZOOM_HOME_STEP = 10;

    /** DISPLAY 93 zoomStart(0) -> int [Z9X 121]: opens the session. */
    public int zoomSessionStart() throws RemoteException {
        synchronized (ZOOM_LOCK) { clearZoomReads(); }
        int r = callI32RetI32(Call.ZOOM_START, 0);
        synchronized (ZOOM_LOCK) { sZoomSession = true; }
        return r;
    }

    /** DISPLAY 94 zoomFinish(0) -> int [Z9X 120]: closes the session; always after 93, also on errors. */
    public int zoomSessionFinish() throws RemoteException {
        synchronized (ZOOM_LOCK) {
            sZoomSession = false;
            clearZoomReads();
        }
        return callI32RetI32(Call.ZOOM_FINISH, 0);
    }

    /**
     * DISPLAY 97 getZoomStepRange(out st_ZoomStepRange) -> {ret, opticalMin, opticalMax, digitalMin, digitalMax}
     * [Z9X 117]. Reply: int return, then the out parcelable (zoomInOpticalMinNum, zoomOutOpticalMaxNum,
     * zoomInDigtalMinNum, zoomOutDigtalMaxNum). Callers validate.
     */
    public int[] getZoomStepRange() throws RemoteException {
        int[] v = call(Call.GET_ZOOM_STEP_RANGE, null, r -> {
            int ret = r.i32();
            int[] o = r.out(q -> new int[] {q.i32(), q.i32(), q.i32(), q.i32()});
            return new int[] {ret, o[0], o[1], o[2], o[3]};
        });
        synchronized (ZOOM_LOCK) {
            if (sZoomSession && v[1] >= 0 && v[2] > v[1] && v[2] <= MAX_OPTICAL_STEP) {
                sOptMin = v[1];
                sOptMax = v[2];
            } else {
                sOptMin = sOptMax = -1;
            }
        }
        return v;
    }

    /** Sanity bound for the 97 optical range (as OpticalZoomPanel.MAX_POSITIONS and the old range check). */
    private static final int MAX_OPTICAL_STEP = 64;

    /** DISPLAY 98 getCurrentZoomStep2(out st_ZoomStep) -> {ret, optical, digital} [Z9X 116]. Callers validate. */
    public int[] getZoomStep() throws RemoteException {
        int[] v = call(Call.GET_CURRENT_ZOOM_STEP2, null, r -> {
            int ret = r.i32();
            int[] o = r.out(q -> new int[] {q.i32(), q.i32()});
            return new int[] {ret, o[0], o[1]};
        });
        synchronized (ZOOM_LOCK) { sOptCur = sZoomSession ? v[1] : -1; }
        return v;
    }

    /**
     * DISPLAY 95 setOpticalZoomStep(step) -> int [Z9X 119]: one optical zoom position (stock KeyStoneManager.zoomControl,
     * newsettings classes3 1e74c8: cur + 1 smaller, cur - 1 bigger). Refused (nothing sent) unless 185(true, 2) and 93
     * were sent, 97 gave a range and 98 a position in this session, step is inside that range and exactly one away
     * from that 98 read; a 190 read in this session must say 0 (not in the limit zone, hb-t6). Each 95 uses up the 98 read.
     */
    public int setOpticalZoomStep(int step) throws RemoteException {
        HbFeatures.require(HbFeatures.F.OPTICAL_ZOOM, "setOpticalZoomStep");
        checkRange("optical zoom step", step, 0, MAX_OPTICAL_STEP);
        synchronized (ZOOM_LOCK) {
            if (!sZoomPrepared || !sZoomSession) {
                throw new IllegalStateException("95 outside a 185(true,2) + 93 session");
            }
            if (sOptMax < 0 || sOptCur < 0) throw new IllegalStateException("95 without a 97 range and a fresh 98 read");
            checkRange("optical zoom step", step, sOptMin, sOptMax);
            if (Math.abs(step - sOptCur) != 1) {
                throw new IllegalArgumentException("95(" + step + ") is not next to the 98 position " + sOptCur);
            }
            if (sLimCur != 0) throw new IllegalStateException("95 while the lens is in the limit zone (190 " + sLimCur + ")");
            sOptCur = -1;
        }
        return callI32RetI32(Call.SET_OPTICAL_ZOOM_STEP, step);
    }

    /** DISPLAY 96 setDigitalZoomStep(step) -> int [Z9X 118], the stock digital zoom (no motor). */
    public int setDigitalZoomStep(int step) throws RemoteException {
        HbFeatures.require(HbFeatures.F.ZOOM_SHIFT, "setDigitalZoomStep");
        return callI32RetI32(Call.SET_DIGITAL_ZOOM_STEP, checkRange("digital zoom step", step, -200, 200));
    }

    // ------------------------------------------------------------------ HDMI / game
    /** TV 36 getAspectRatio() -> int [Z9X 664]. */
    public int getAspectRatioRaw() throws RemoteException { return callRetI32(Call.GET_ASPECT_RATIO); }
    /** TV 35 setAspectRatio(int) [Z9X 665]. HDMI only. */
    public boolean setAspectRatio(AspectRatio a) throws RemoteException { return callI32RetBool(Call.SET_ASPECT_RATIO, a.wire); }

    /** DISPLAY 77 getGameModeOption() -> 0..3 (null otherwise) [Z9X 647]. */
    public GameModeOption getGameModeOption() throws RemoteException {
        GameModeOption w = sWrittenOpt;
        if (w != null && SystemClock.uptimeMillis() - sWrittenAt < OPTION_LAG_MS) return w;
        return GameModeOption.fromWire(callRetI32(Call.GET_GAME_MODE_OPTION));
    }

    /** Z9X 1.0.4: the getter lags a write by seconds; for OPTION_LAG_MS after a write that answered true, reads return it. */
    private static final long OPTION_LAG_MS = 8_000;
    private static volatile GameModeOption sWrittenOpt;
    private static volatile long sWrittenAt;
    /** DISPLAY 76 setGameModeOption(0..3) [Z9X 648]. HDMI only. */
    public boolean setGameModeOption(GameModeOption o) throws RemoteException {
        boolean r = callI32RetBool(Call.SET_GAME_MODE_OPTION, o.wire);
        if (r) {
            sWrittenOpt = o;
            sWrittenAt = SystemClock.uptimeMillis();
        }
        return r;
    }

    /**
     * Decoded IGmTvType.stHdmiInfo of TV 38 getHdmiInfo (stable parcelable: int u32Width, int u32Height, boolean
     * bInterlace, boolean bIsHdmiMode, int u32FrameRate; the field order is the parcelable's own, so proven).
     * Frame rate unit 1/100 Hz. Callers still gate every action on {@link #plausible()}.
     */
    public static final class HdmiSignal {
        public final int width, height, frameRateCenti;
        public final boolean interlace, hdmiMode;
        public final String rawHex;

        HdmiSignal(int width, int height, boolean interlace, boolean hdmiMode, int frameRateCenti) {
            this.width = width;
            this.height = height;
            this.interlace = interlace;
            this.hdmiMode = hdmiMode;
            this.frameRateCenti = frameRateCenti;
            this.rawHex = width + "," + height + "," + interlace + "," + hdmiMode + "," + frameRateCenti;
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

    /** TV 38 getHdmiInfo() -> stHdmiInfo [Z9X 662]: current HDMI input timing. Read-only. */
    public HdmiSignal getHdmiInfo() throws RemoteException {
        return call(Call.GET_HDMI_INFO, null, r -> r.object(q -> new HdmiSignal(q.i32(), q.i32(), q.bool(), q.bool(), q.i32())));
    }

    /** [Z9X 161] setOutputTiming (Ultra 120 Hz): not in the HB tree. Nothing sent. */
    public boolean setOutputTiming(OutputTiming t) throws RemoteException {
        throw new NotOnHbException("setOutputTiming (Ultra 120 Hz)");
    }

    /**
     * The Z9X 1.0.8 IGmpf::OutputTimingInfo (157), kept so the byte-identical panel.Snapshot compiles; never built on
     * the HB because {@link #getOutputTimingInfo} sends nothing (hb-t7 fork sync).
     */
    public static final class OutputTimingInfo {
        public final int userTiming;

        private OutputTimingInfo(int userTiming) { this.userTiming = userTiming; }

        /** Stock V3DisplayManager: userTiming == 4 is "Ultra 120 Hz on". */
        public boolean isUltra120() { return userTiming == OutputTiming.ULTRA_120_ON.wire; }

        @Override
        public String toString() { return "user=" + userTiming; }
    }

    /** [Z9X 157] getOutputTimingInfo (Ultra 120 Hz state): not in the HB tree (ULTRA_120 OFF). Nothing sent. */
    public OutputTimingInfo getOutputTimingInfo() throws RemoteException {
        throw new NotOnHbException("getOutputTimingInfo (Ultra 120 Hz)");
    }

    // ------------------------------------------------------------------ sound
    /** AUDIO 7 getSoundeffect() -> byte (null if not a known mode) [Z9X 62]. */
    public SoundEffect getSoundEffect() throws RemoteException { return SoundEffect.fromWire(getSoundEffectRaw()); }
    /** AUDIO 7 getSoundeffect() -> byte, raw 0..255 [Z9X 62]. */
    public int getSoundEffectRaw() throws RemoteException { return callRetU8(Call.GET_SOUNDEFFECT) & 0xff; }
    /** AUDIO 6 setSoundeffect(byte) [Z9X 61]. Only AI / MOVIE / MUSIC / SPORTS / KARAOKE are sent. */
    public void setSoundEffect(SoundEffect e) throws RemoteException {
        if (e != SoundEffect.AI && e != SoundEffect.MOVIE && e != SoundEffect.MUSIC
                && e != SoundEffect.SPORTS && e != SoundEffect.KARAOKE) {
            throw new IllegalArgumentException("sound mode " + e + " has no amp table: not sent");
        }
        noteSoundWrite();
        try { callU8(Call.SET_SOUNDEFFECT, e.wire); } finally { noteSoundWrite(); }
    }

    /** elapsedRealtime of our last sound-mode call, before and after the transaction (audio.VendorSoundWatch). */
    public static volatile long lastSoundWriteAt = Long.MIN_VALUE / 2;

    private static void noteSoundWrite() { lastSoundWriteAt = android.os.SystemClock.elapsedRealtime(); }

    /** AUDIO 8 setAutoSoundeffect(byte sub-mode) [Z9X 63]: AI-sound sub-mode, MOVIE / MUSIC / SPORTS / KARAOKE only. */
    public void setAutoSoundEffect(SoundEffect sub) throws RemoteException {
        if (sub != SoundEffect.MOVIE && sub != SoundEffect.MUSIC && sub != SoundEffect.SPORTS
                && sub != SoundEffect.KARAOKE) {
            throw new IllegalArgumentException("AI sub-mode " + sub + " has no amp table: not sent");
        }
        noteSoundWrite();
        try { callU8(Call.SET_AUTO_SOUNDEFFECT, sub.wire); } finally { noteSoundWrite(); }
    }

    // ------------------------------------------------------------------ SoC PEQ (equalizer)
    /** Whitelist of SetPEQ: bands 1..13. */
    public static final int PEQ_BANDS = 13;
    /** Our gain limits for SetPEQ, in 0.1 dB: boosts up to +6 dB, cuts down to -12 dB. */
    public static final int PEQ_GAIN_MIN = -120, PEQ_GAIN_MAX = 60;

    /** AUDIO 16 SetPEQ(band, gainTenthDb, fcHz, qTenth) -> int [Z9X 50]. Range-checked as on the Z9X. */
    public int setPeqBand(int band, int gainTenthDb, int fcHz, int qTenth) throws RemoteException {
        return callI32x4RetI32(Call.SET_PEQ, checkRange("peq band", band, 1, PEQ_BANDS),
                checkRange("peq gain", gainTenthDb, PEQ_GAIN_MIN, PEQ_GAIN_MAX),
                checkRange("peq fc", fcHz, 50, 20_000), checkRange("peq q", qTenth, 5, 160));
    }

    /** AUDIO 15 SetPEQEnable(bool) -> int [Z9X 51]. */
    public int setPeqEnabled(boolean on) throws RemoteException { return callBoolRetI32(Call.SET_PEQ_ENABLE, on); }

    /** AUDIO 2 setAudioOutput(byte path) [Z9X 59]: the audioserver report of the current media output. */
    public void setAudioOutput(AudioPath p) throws RemoteException { callU8(Call.SET_AUDIO_OUTPUT, p.wire); }

    /** AUDIO 3 getAudioOutput() -> byte [Z9X 60]. */
    public int getAudioOutputRaw() throws RemoteException { return callRetU8(Call.GET_AUDIO_OUTPUT) & 0xff; }

    /** [Z9X 43] getDtsEffectsEnable: not on the HB (support_dts_effect=false). Nothing sent. */
    public boolean getDtsEffects() throws RemoteException { throw new NotOnHbException("getDtsEffectsEnable"); }
    /** [Z9X 26] getSoundProcessType: no Harman / DTS:X on the HB. Nothing sent. */
    public int getSoundProcessRaw() throws RemoteException { throw new NotOnHbException("getSoundProcessType"); }
    /** [Z9X 44 + 27] Sound enhancement: no Harman / DTS:X on the HB. Nothing sent. */
    public boolean setSoundEnhancement(SoundProcess p) throws RemoteException {
        throw new NotOnHbException("sound enhancement " + p);
    }

    /** [Z9X 41] getVolumeBalanceEnable: volume levelling is not on the HB. Nothing sent. */
    public boolean getVolumeBalance() throws RemoteException { throw new NotOnHbException("getVolumeBalanceEnable"); }

    /** [Z9X 42] setVolumeBalanceEnable(false): not on the HB. Nothing sent. */
    public boolean disableVolumeBalance() throws RemoteException { throw new NotOnHbException("setVolumeBalanceEnable"); }

    /** [Z9X 42] setVolumeBalanceEnable(on): not on the HB. Nothing sent. */
    public boolean setVolumeBalance(boolean on) throws RemoteException { throw new NotOnHbException("setVolumeBalanceEnable"); }

    /** TV 66 getEARCEnableState() [Z9X 632] (true = Auto, false = Off). */
    public boolean getEarc() throws RemoteException { return callRetBool(Call.GET_EARC_ENABLE_STATE); }
    /**
     * TV 65 setEARCEnable(bool) [Z9X 633]: the vendor's runtime eARC flag and its database value (audio/HbEarc: once per
     * boot with the stored value, and the panel's eARC row).
     */
    public boolean setEarc(boolean on) throws RemoteException { return callBoolRetBool(Call.SET_EARC_ENABLE, on); }
    /** TV 79 getEARCStatus() -> vendor EN_CEC_EarcStatus, 3 = eARC linked (stock XRMService; audio/HbEarc). */
    public int getEarcStatus() throws RemoteException { return callRetI32(Call.GET_EARC_STATUS); }

    /** SYSTEM 6 / 5 [Z9X 420 / 419]: the stock "Power-on music". */
    public boolean getPowerOnMusic() throws RemoteException { return callRetBool(Call.IS_POWER_ON_MUSIC_ENABLED); }
    public void setPowerOnMusic(boolean on) throws RemoteException { callBool(Call.ENABLE_POWER_ON_MUSIC, on); }

    // ------------------------------------------------------------------ power diagnostics (read-only)
    /** [Z9X 230] POWER 11 getBootReason is a REVIEW row with no HB stock caller (9.6): refused, nothing sent. */
    public int getBootReason() throws RemoteException { throw new NotOnHbException("getBootReason (REVIEW row, 9.6)"); }
    /** TV 58 getWakeUpSource() -> int [Z9X 639]. Logging only. */
    public int getWakeUpSource() throws RemoteException { return callRetI32(Call.GET_WAKE_UP_SOURCE); }
}
