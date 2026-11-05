/*
 * AidlCaller: the single transact path from HbProjector to XGIMI's vendor service on the Z9X HB (goat_elegant,
 * G0130). HB-only file (the design notes to 6), the HB twin of HidlCaller.
 *
 * The vendor process /vendor/bin/hw/xgimi.hardware.gmpf-service registers 14 stable AIDL services
 * "xgimi.hardware.gmpf.I<Name>/default" (frozen V1, hash HASH). This class talks to nine of them with raw Binder
 * transactions laid out exactly as the stock Java proxies do it (com.xgimi.middle.jar,
 * xgimi.hardware.gmpf.I<Name>$Stub$Proxy):
 *   writeInterfaceToken(DESCRIPTOR) + arguments -> transact(code, data, reply, 0)
 *   -> false means "unimplemented"; else reply.readException() + return value + out parcelables.
 *
 * HARD ALLOWLIST: only the members of enum Call can ever be transacted. Every Call is a row of
 * devices/goat_elegant/research/allowed.tsv, of aidl_codes.tsv (same service, code and stub method) and not a
 * BANNED or REVIEW row of banned.tsv (tools/devices/check_aidl_hal.py, rules 1 to 7, with --dex in every build).
 * No IGmpf / IGmpf2 HIDL code number ever reaches this file. The typed clients (GmpfClient, Gmpf2Client, both
 * subclasses) fix or range-check every argument before anything is sent.
 *
 * VERSION GUARD (HbBoot rule): before the first real call on an interface, getInterfaceVersion must return 1 and
 * getInterfaceHash must return HASH. A mismatch or a failed meta call latches that interface closed for the
 * process: nothing else is sent to it, and every typed method on it throws ReplyException at once.
 *
 * Connection: ServiceManager.checkService, never the blocking getService. Every connected interface gets a death
 * recipient; all 14 services live in one vendor process, so the first death notice drops every binder, resets
 * every guard (a restarted service runs the guard again) and calls the death listener once.
 *
 * Errors (as HidlCaller): calls on the main thread are refused before sending (RemoteException); a failure
 * inside transact is a RemoteException (not delivered; that binder is dropped, the next call reconnects);
 * transact returning false, an exception code in the reply or a reply that does not parse is a ReplyException
 * (delivered: one-shot callers never repeat it). NotOnHbException: a feature the HB does not have, nothing sent.
 *
 * Parcels are touched only here (check_aidl_hal rule 1): KstPoint and the clients see Wire.In / Wire.Out.
 */
package org.lumen.projector.hal;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

abstract class AidlCaller {
    static final String TAG = "Z9xGmpf";

    /** Frozen hash of xgimi.hardware.gmpf V1 (one copy per stub in com.xgimi.middle.jar). */
    static final String HASH = "908de964b7da427ad21df846e629b80ffdc183ab";
    static final int VERSION = 1;
    private static final String PKG = "xgimi.hardware.gmpf.";

    /** One stable AIDL service of the vendor process, with its own guard state (guarded by AidlCaller.class). */
    enum Iface {
        SYSTEM("xgimi.hardware.gmpf.ISystemManager"),
        POWER("xgimi.hardware.gmpf.IPowerManager"),
        FOCUS("xgimi.hardware.gmpf.IProjectorFocusManager"),
        DISPLAY("xgimi.hardware.gmpf.IDisplayManager"),
        TV("xgimi.hardware.gmpf.IGmTvManager"),
        AUDIO("xgimi.hardware.gmpf.IGmAudioManager"),
        MOTION("xgimi.hardware.gmpf.IMotionDetectionManager"),
        TEMPERATURE("xgimi.hardware.gmpf.ITemperatureManager"),
        FACTORY("xgimi.hardware.gmpf.IGmFactoryManager");

        /** Interface descriptor, as the stub's DESCRIPTOR. */
        final String descriptor;
        /** servicemanager name (vendor manifest xgimi.hardware.gmpf-service.xml). */
        final String instance;

        IBinder binder;
        boolean verified;      // version and hash checked and equal for this binder
        boolean refused;       // latched: never send anything to this interface again (until a vendor death)
        boolean refusedLogged;

        Iface(String descriptor) {
            this.descriptor = descriptor;
            this.instance = descriptor + "/default";
        }
    }

    /**
     * The complete list of transactions this app can produce: (interface, code, stub method). Codes are the
     * absolute transaction codes of the stock proxies (aidl_codes.tsv). null interface = meta call, valid on any.
     */
    enum Call {
        GET_INTERFACE_VERSION(null, 0x00FFFFFF, "getInterfaceVersion"),
        GET_INTERFACE_HASH(null, 0x00FFFFFE, "getInterfaceHash"),
        SET_PROJECTOR_PUT_MODE(Iface.DISPLAY, 2, "setProjectorPutMode"),
        GET_PROJECTOR_PUT_MODE(Iface.DISPLAY, 3, "getProjectorPutMode"),
        SET_DLP_LUMENS_MODE(Iface.DISPLAY, 4, "setDlpLumensMode"),
        GET_DLP_LUMENS_MODE(Iface.DISPLAY, 5, "getDlpLumensMode"),
        SET_DLP_LUMENS_LEVEL(Iface.DISPLAY, 6, "setDlpLumensLevel"),
        GET_DLP_LUMENS_LEVEL(Iface.DISPLAY, 7, "getDlpLumensLevel"),
        SET_CORRECT_KEYSTONE_FULL_PIONT(Iface.DISPLAY, 15, "setCorrectKeystoneFullPiont"),
        GET_CORRECT_KEYSTONE_FULL_PIONT(Iface.DISPLAY, 16, "getCorrectKeystoneFullPiont"),
        SET_SCREEN_ON_OFF(Iface.DISPLAY, 27, "setScreenOnOff"),
        GET_SCREEN_ON_OFF(Iface.DISPLAY, 28, "getScreenOnOff"),
        CORRECT_KEYSTONE_RESET(Iface.DISPLAY, 38, "correctKeystoneReset"),
        SET_HUMAN_DETECT_ON_OFF(Iface.DISPLAY, 61, "setHumanDetectOnOff"),
        GET_HUMAN_DETECT_ON_OFF(Iface.DISPLAY, 62, "getHumanDetectOnOff"),
        ZOOM_CONTROL(Iface.DISPLAY, 63, "zoomControl"),
        CHECK_TRAPEZOID_COORDINATE(Iface.DISPLAY, 64, "checkTrapezoidCoordinate"),
        SET_HUMAN_DETECT_SCREEN_ON_OFF(Iface.DISPLAY, 66, "setHumanDetectScreenOnOff"),
        GET_HUMAN_DETECT_SCREEN_ON_OFF(Iface.DISPLAY, 67, "getHumanDetectScreenOnOff"),
        UI_AK_DISPLAY(Iface.DISPLAY, 68, "uiAkDisplay"),
        TRANSLATION_KST(Iface.DISPLAY, 72, "translationKst"),
        SET_GAME_MODE_OPTION(Iface.DISPLAY, 76, "setGameModeOption"),
        GET_GAME_MODE_OPTION(Iface.DISPLAY, 77, "getGameModeOption"),
        GET_CURRENT_KEYSTONE_MODE(Iface.DISPLAY, 84, "getCurrentKeystoneMode"),
        SWITCH_KEYSTONE_MODE(Iface.DISPLAY, 85, "switchKeystoneMode"),
        ZOOM_START(Iface.DISPLAY, 93, "zoomStart"),
        ZOOM_FINISH(Iface.DISPLAY, 94, "zoomFinish"),
        SET_OPTICAL_ZOOM_STEP(Iface.DISPLAY, 95, "setOpticalZoomStep"),
        SET_DIGITAL_ZOOM_STEP(Iface.DISPLAY, 96, "setDigitalZoomStep"),
        GET_ZOOM_STEP_RANGE(Iface.DISPLAY, 97, "getZoomStepRange"),
        GET_CURRENT_ZOOM_STEP2(Iface.DISPLAY, 98, "getCurrentZoomStep2"),
        SWITCH_DISP_COLOR_SPACE(Iface.DISPLAY, 102, "switchDispColorSpace"),
        GET_DISP_COLOR_SPACE(Iface.DISPLAY, 103, "getDispColorSpace"),
        SET_GAME_MODE_STATE(Iface.DISPLAY, 146, "setGameModeState"),
        SET_GAME_MODE_TYPE(Iface.DISPLAY, 147, "setGameModeType"),
        GET_GAME_MODE_PROP(Iface.DISPLAY, 148, "getGameModeProp"),
        SET_LENS_DISP_MODE(Iface.DISPLAY, 151, "setLensDispMode"),
        GET_LENS_DISP_MODE(Iface.DISPLAY, 152, "getLensDispMode"),
        SET_DYNAMIC_BLACK_TYPE(Iface.DISPLAY, 172, "setDynamicBlackType"),
        GET_DYNAMIC_BLACK_TYPE(Iface.DISPLAY, 174, "getDynamicBlackType"),
        GET_DISP_OUTPUT_FPS(Iface.DISPLAY, 179, "getDispOutputFps"),           // game bar FPS overlay with VRR on
        SET_KST_PREPARE(Iface.DISPLAY, 185, "setKstPrepare"),
        SET_OPTICAL_ZOOM_LIMIT_STEP(Iface.DISPLAY, 189, "SetOpticalZoomLimitStep"),
        GET_CURRENT_ZOOM_LIMIT_STEP(Iface.DISPLAY, 190, "GetCurrentZoomLimitStep"),
        SET_AUDIO_OUTPUT(Iface.AUDIO, 2, "setAudioOutput"),
        GET_AUDIO_OUTPUT(Iface.AUDIO, 3, "getAudioOutput"),
        SET_SOUNDEFFECT(Iface.AUDIO, 6, "setSoundeffect"),
        GET_SOUNDEFFECT(Iface.AUDIO, 7, "getSoundeffect"),
        SET_AUTO_SOUNDEFFECT(Iface.AUDIO, 8, "setAutoSoundeffect"),
        SET_PEQ_ENABLE(Iface.AUDIO, 15, "SetPEQEnable"),
        SET_PEQ(Iface.AUDIO, 16, "SetPEQ"),
        SAVE_POWER_ON_AK_FLAG(Iface.FACTORY, 45, "savePowerOnAKFlag"),
        GET_POWER_ON_AK_FLAG(Iface.FACTORY, 46, "getPowerOnAKFlag"),
        SWITCH_INPUT_SOURCE(Iface.TV, 3, "switchInputSource"),
        GET_CURRENT_INPUT_SOURCE(Iface.TV, 4, "getCurrentInputSource"),
        SET_CONTRAST(Iface.TV, 5, "setContrast"),
        GET_CONTRAST(Iface.TV, 6, "getContrast"),
        SET_BRIGHTNESS(Iface.TV, 7, "setBrightness"),
        GET_BRIGHTNESS(Iface.TV, 8, "getBrightness"),
        SET_SATURATION(Iface.TV, 9, "setSaturation"),
        GET_SATURATION(Iface.TV, 10, "getSaturation"),
        SET_SHARPNESS(Iface.TV, 11, "setSharpness"),
        GET_SHARPNESS(Iface.TV, 12, "getSharpness"),
        SET_COLOR_TEMP_RED_GAIN(Iface.TV, 17, "setColorTempRedGain"),
        GET_COLOR_TEMP_RED_GAIN(Iface.TV, 18, "getColorTempRedGain"),
        SET_COLOR_TEMP_GREEN_GAIN(Iface.TV, 19, "setColorTempGreenGain"),
        GET_COLOR_TEMP_GREEN_GAIN(Iface.TV, 20, "getColorTempGreenGain"),
        SET_COLOR_TEMP_BLUE_GAIN(Iface.TV, 21, "setColorTempBlueGain"),
        GET_COLOR_TEMP_BLUE_GAIN(Iface.TV, 22, "getColorTempBlueGain"),
        SET_COLOR_TEMP_RED_OFFSET(Iface.TV, 23, "setColorTempRedOffset"),
        GET_COLOR_TEMP_RED_OFFSET(Iface.TV, 24, "getColorTempRedOffset"),
        SET_COLOR_TEMP_GREEN_OFFSET(Iface.TV, 25, "setColorTempGreenOffset"),
        GET_COLOR_TEMP_GREEN_OFFSET(Iface.TV, 26, "getColorTempGreenOffset"),
        SET_COLOR_TEMP_BLUE_OFFSET(Iface.TV, 27, "setColorTempBlueOffset"),
        GET_COLOR_TEMP_BLUE_OFFSET(Iface.TV, 28, "getColorTempBlueOffset"),
        SET_3DNR(Iface.TV, 29, "set3DNR"),
        GET_3DNR(Iface.TV, 30, "get3DNR"),
        SET_MEMC_LEVEL(Iface.TV, 31, "setMemcLevel"),
        GET_MEMC_LEVEL(Iface.TV, 32, "getMemcLevel"),
        SET_PICTURE_MODE(Iface.TV, 33, "setPictureMode"),
        GET_PICTURE_MODE(Iface.TV, 34, "getPictureMode"),
        SET_ASPECT_RATIO(Iface.TV, 35, "setAspectRatio"),
        GET_ASPECT_RATIO(Iface.TV, 36, "getAspectRatio"),
        GET_HDMI_INFO(Iface.TV, 38, "getHdmiInfo"),
        GET_EDID_VERSION(Iface.TV, 39, "getEdidVersion"),
        SET_EDID_VERSION(Iface.TV, 40, "setEdidVersion"),
        SET_UCD_LEVEL(Iface.TV, 45, "setUcdLevel"),
        GET_UCD_LEVEL(Iface.TV, 49, "getUcdLevel"),
        GET_WAKE_UP_SOURCE(Iface.TV, 58, "getWakeUpSource"),
        SET_HDMI_RANGE(Iface.TV, 63, "setHdmiRange"),
        GET_HDMI_RANGE(Iface.TV, 64, "getHdmiRange"),
        SET_EARC_ENABLE(Iface.TV, 65, "setEARCEnable"),
        GET_EARC_ENABLE_STATE(Iface.TV, 66, "getEARCEnableState"),
        GET_HDR_TYPE(Iface.TV, 78, "getHdrType"),
        GET_EARC_STATUS(Iface.TV, 79, "getEARCStatus"),
        GET_HSY_PICTURE_MODE_ENABLE(Iface.TV, 112, "getHsyPictureModeEnable"),
        SET_TV_DYNAMIC_CONTRAST_ENABLE(Iface.TV, 113, "setTvDynamicContrastEnable"),
        GET_TV_DYNAMIC_CONTRAST_ENABLE(Iface.TV, 114, "getTvDynamicContrastEnable"),
        SET_TV_GAMMA_LEVEL(Iface.TV, 115, "setTvGammaLevel"),
        GET_TV_GAMMA_LEVEL(Iface.TV, 116, "getTvGammaLevel"),
        SET_HSY_COLOR_MODEL(Iface.TV, 117, "setHsyColorModel"),
        GET_HSY_COLOR_MODEL(Iface.TV, 118, "getHsyColorModel"),
        SET_HSY_PICTURE_MODE_ENABLE(Iface.TV, 119, "setHsyPictureModeEnable"),
        SET_ALLM_ENABLE(Iface.TV, 129, "setAllmEnable"),
        GET_ALLM_ENABLE_STATE(Iface.TV, 130, "getAllmEnableState"),
        GET_ALLM_STATUS(Iface.TV, 131, "getAllmStatus"),
        SET_VRR_MODE_ENABLE(Iface.TV, 145, "setVRRModeEnable"),              // game bar
        GET_VRR_ENABLE_STATE(Iface.TV, 146, "getVRREnableState"),
        SET_GAME_PICTURE_MODE(Iface.TV, 147, "setGamePictureMode"),
        GET_GAME_PICTURE_MODE(Iface.TV, 148, "getGamePictureMode"),
        SET_DARK_BOOST_ONOFF(Iface.TV, 149, "setDarkBoostOnoff"),
        GET_DARK_BOOST_STATE(Iface.TV, 150, "getDarkBoostState"),
        RESET_PICTURE_TO_DEFAULT(Iface.TV, 164, "ResetPictureToDefault"),
        SET_ONE_OF_MULTI_POINT_WB(Iface.TV, 165, "setOneOfMultiPointWB"),
        GET_ONE_OF_MULTI_POINT_WB(Iface.TV, 166, "getOneOfMultiPointWB"),
        TOGGLE_HDR_DY_TONE_MAPPING(Iface.TV, 174, "ToggleHDRDyToneMapping"),
        GET_HDR_DY_TONE_MAPPING_STATUS(Iface.TV, 175, "GetHDRDyToneMappingStatus"),
        SET_HGIG_MODE(Iface.TV, 191, "setHGiGMode"),                            // game bar HDR settings
        GET_HGIG_MODE(Iface.TV, 192, "getHGiGMode"),
        GET_MOTION_DETECTION(Iface.MOTION, 2, "getMotionDetection"),   // fit to screen size: the tilt (read)
        SET_ANG_TRIGGER_AF(Iface.MOTION, 4, "setAngTriggerAF"),
        GET_ANG_TRIGGER_AF(Iface.MOTION, 5, "getAngTriggerAF"),
        SET_ACC_TRIGGER_AK(Iface.MOTION, 6, "setAccTriggerAK"),
        GET_ACC_TRIGGER_AK(Iface.MOTION, 7, "getAccTriggerAK"),
        SET_AUTO_REVERSE(Iface.MOTION, 8, "setAutoReverse"),
        GET_AUTO_REVERSE(Iface.MOTION, 9, "getAutoReverse"),
        TEMPORARY_SWITCH_TRIGGER(Iface.MOTION, 15, "temporarySwitchTrigger"),
        GO_TO_BOOT_COMPLETED(Iface.POWER, 2, "goToBootCompleted"),
        SET_FOCUS_CALLBACK(Iface.FOCUS, 1, "setFocusCallback"),
        MANUAL_FOCUS(Iface.FOCUS, 2, "manualFocus"),
        POWER_ON_AF_ENABLE(Iface.FOCUS, 4, "powerOnAFEnable"),
        IS_POWER_ON_AF_ENABLED(Iface.FOCUS, 5, "isPowerOnAFEnabled"),
        SEND_FOCUS_STATUS(Iface.FOCUS, 8, "sendFocusStatus"),
        AUTO_KST(Iface.FOCUS, 27, "autoKst"),
        SET_PROJ_SCREEN_ADP(Iface.FOCUS, 33, "setProjScreenAdp"),
        GET_PROJ_SCREEN_ADP(Iface.FOCUS, 34, "getProjScreenAdp"),
        SET_PROJ_CURTAIN_ADP(Iface.FOCUS, 35, "setProjCurtainAdp"),
        GET_PROJ_CURTAIN_ADP(Iface.FOCUS, 36, "getProjCurtainAdp"),
        GET_TOF_DISTANCE(Iface.FOCUS, 46, "getTofDistance"),          // fit to screen size: the distance (read)
        NEW_AUTO_KST(Iface.FOCUS, 53, "newAutoKst"),          // 2026-10-29 (9.1): mode 9 / 10 / 11, + 12 hb-t7 (GmpfClient)
        SET_POWER_ON_AF_USE_DISTANCE(Iface.FOCUS, 85, "setPowerOnAFUseDistance"),
        GET_POWER_ON_AF_USE_DISTANCE(Iface.FOCUS, 86, "getPowerOnAFUseDistance"),
        ENABLE_POWER_ON_MUSIC(Iface.SYSTEM, 5, "enablePowerOnMusic"),
        IS_POWER_ON_MUSIC_ENABLED(Iface.SYSTEM, 6, "isPowerOnMusicEnabled"),
        SET_BOOT_ANIM_STATE(Iface.SYSTEM, 32, "setBootAnimState"),
        GET_BOOT_ANIM_STATE(Iface.SYSTEM, 33, "getBootAnimState"),
        SET_SYSTEM_UI_STATE(Iface.SYSTEM, 36, "setSystemUIState"),
        GET_SYSTEM_UI_STATE(Iface.SYSTEM, 37, "getSystemUIState"),
        SET_SCREEN_ROTATION(Iface.SYSTEM, 134, "setScreenRotation"),
        SET_PUSH_PULL_SCREEN_ON_OFF(Iface.SYSTEM, 140, "setPushPullScreenOnOff"),
        GET_SCREEN_ROTATION_INFO(Iface.SYSTEM, 142, "getScreenRotationInfo"),
        SET_PUSH_PULL_SCREEN_STATE(Iface.SYSTEM, 147, "setPushPullScreenState"),
        GET_SYSTEM_TEMPERATURE(Iface.TEMPERATURE, 6, "getSystemTemperature");

        final Iface iface;   // null: meta call, valid on any Iface
        final int code;
        final String method;

        Call(Iface iface, int code, String method) {
            this.iface = iface;
            this.code = code;
            this.method = method;
        }
    }

    // ---------------------------------------------------------------- errors
    /** Request reached the vendor (transact returned) but the reply was bad. Do not repeat one-shot calls. */
    public static class ReplyException extends RemoteException {
        ReplyException(String msg) { super(msg); }
    }

    /** A feature the HB does not have: nothing was sent. */
    public static final class NotOnHbException extends RemoteException {
        NotOnHbException(String what) { super(what + ": not on the Z9X HB, nothing sent"); }
    }

    // ---------------------------------------------------------------- connection state (one per process)
    private static Runnable sDeathListener;
    private static int sGeneration;

    /** Called by the vendor's death notice of any linked interface; only the first one of a generation acts. */
    private static final class Death implements IBinder.DeathRecipient {
        private final int generation;
        Death(int generation) { this.generation = generation; }
        @Override public void binderDied() { onVendorDied(generation); }
    }

    private static void onVendorDied(int generation) {
        Runnable l;
        synchronized (AidlCaller.class) {
            if (generation != sGeneration) return;            // a second notice of the same death, or stale
            sGeneration++;
            for (Iface i : Iface.values()) {
                i.binder = null;
                i.verified = false;
                i.refused = false;                             // a restarted service runs the guard again
                i.refusedLogged = false;
            }
            l = sDeathListener;
        }
        Log.w(TAG, "vendor gmpf service died: every interface dropped");
        if (l != null) {
            try { l.run(); } catch (Throwable t) { Log.w(TAG, "death listener: " + t); }
        }
    }

    static synchronized void setDeathListener(Runnable l) {
        sDeathListener = l;
    }

    /** checkService + linkToDeath, without waiting. Never throws. true when the interface has a live binder. */
    private static boolean connectIface(Iface i) {
        synchronized (AidlCaller.class) {
            if (i.refused) return false;
            if (i.binder != null && i.binder.isBinderAlive()) return true;
            i.binder = null;
            i.verified = false;
            try {
                IBinder b = ServiceManager.checkService(i.instance);
                if (b == null) return false;
                b.linkToDeath(new Death(sGeneration), 0);
                i.binder = b;
                Log.i(TAG, "connected to " + i.instance);
                return true;
            } catch (Throwable t) {                          // RemoteException (already dead), SecurityException
                Log.w(TAG, i.instance + " not available: " + t);
                return false;
            }
        }
    }

    /** Connect and run the version guard of one interface. ReplyException when the guard refuses it. */
    private static void need(Iface i) throws RemoteException {
        synchronized (AidlCaller.class) {
            if (i.refused) {
                if (!i.refusedLogged) {
                    i.refusedLogged = true;
                    Log.e(TAG, "guard: " + i.descriptor + " is closed for this process (version or hash)");
                }
                throw new ReplyException(i.descriptor + " refused earlier (version or hash guard)");
            }
            if (!connectIface(i)) throw new RemoteException(i.instance + " not available");
            if (i.verified) return;
            int version;
            String hash;
            try {
                version = sendCall(i, Call.GET_INTERFACE_VERSION, null, In::i32);
                hash = sendCall(i, Call.GET_INTERFACE_HASH, null, In::str);
            } catch (ReplyException e) {
                i.refused = true;
                i.binder = null;
                i.refusedLogged = true;
                Log.e(TAG, "guard: " + i.descriptor + " meta call failed, interface closed: " + e.getMessage());
                throw e;
            }
            if (version != VERSION || !HASH.equals(hash)) {
                i.refused = true;
                i.binder = null;
                i.refusedLogged = true;
                String msg = "guard: " + i.descriptor + " version " + version + " hash " + hash + ", expected "
                        + VERSION + " " + HASH + "; interface closed, nothing sent";
                Log.e(TAG, msg);
                throw new ReplyException(msg);
            }
            i.verified = true;
            Log.i(TAG, "guard: " + i.descriptor + " V" + version + " hash ok");
        }
    }

    /** Connect and verify the three handshake interfaces (SYSTEM, POWER, FOCUS). Never throws. */
    static boolean connectHandshake(Runnable deathListener) {
        setDeathListener(deathListener);
        boolean ok = true;
        for (Iface i : new Iface[] {Iface.SYSTEM, Iface.POWER, Iface.FOCUS}) {
            try {
                need(i);
            } catch (Throwable t) {
                ok = false;
            }
        }
        return ok;
    }

    /** SYSTEM, POWER and FOCUS connected and verified. */
    public boolean isConnected() {
        synchronized (AidlCaller.class) {
            for (Iface i : new Iface[] {Iface.SYSTEM, Iface.POWER, Iface.FOCUS}) {
                if (i.binder == null || !i.verified || !i.binder.isBinderAlive()) return false;
            }
            return true;
        }
    }

    /** Forget every binder after a death notice (no vendor call). The guards run again at the next connect. */
    public void dropConnection() {
        synchronized (AidlCaller.class) {
            for (Iface i : Iface.values()) {
                i.binder = null;
                i.verified = false;
            }
        }
    }

    // ---------------------------------------------------------------- wire
    /** Writes the arguments after the interface token. */
    interface Args { void write(Out o); }
    /** Reads the reply after readException. */
    interface Ret<T> { T read(In r); }
    /** One stable parcelable's fields, inside its size frame. */
    interface Writer { void write(Out o); }
    interface Reader<T> { T read(In r); }

    static final Ret<Void> RET_VOID = r -> null;
    static final Ret<Boolean> RET_BOOL = In::bool;
    static final Ret<Byte> RET_I8 = In::i8;
    static final Ret<Integer> RET_I32 = In::i32;
    /** AIDL byte reply as 0..255 (the vendor's u8 values). */
    static final Ret<Integer> RET_U8 = r -> r.i8() & 0xff;

    /**
     * The request after writeInterfaceToken, with the Java Parcel rules of the stock proxies
     *: byte = writeByte (one int32), boolean = writeBoolean (int32 0 / 1).
     */
    static final class Out {
        private final Parcel p;
        private Out(Parcel p) { this.p = p; }
        void i32(int v) { p.writeInt(v); }
        void bool(boolean v) { p.writeBoolean(v); }
        void i8(byte v) { p.writeByte(v); }
        void f32(float v) { p.writeFloat(v); }
        void str(String v) { p.writeString(v); }
        /** in byte[]: writeByteArray (length, then the bytes). */
        void bytes(byte[] v) { p.writeByteArray(v); }
        /** out byte[]: only the length the reply must fill (never null, never negative). */
        void outBytes(int length) {
            if (length < 0) throw new IllegalArgumentException("out array length " + length);
            p.writeInt(length);
        }
        /** in parcelable: writeTypedObject(x, 0), i.e. int32 1, then the framed parcelable. */
        void object(Writer w) {
            p.writeInt(1);
            framed(w);
        }
        /** writeTypedArray of n framed parcelables: int32 n, then per element int32 1 and the element. */
        void typedArray(int n, ElementWriter w) {
            p.writeInt(n);
            for (int k = 0; k < n; k++) {
                final int idx = k;
                p.writeInt(1);
                framed(o -> w.write(o, idx));
            }
        }
        /** Stable parcelable frame: int32 size (patched after the fields), the fields. */
        void framed(Writer w) {
            int start = p.dataPosition();
            p.writeInt(0);
            w.write(this);
            int end = p.dataPosition();
            p.setDataPosition(start);
            p.writeInt(end - start);
            p.setDataPosition(end);
        }
    }

    interface ElementWriter { void write(Out o, int index); }
    interface ElementReader { void read(In r, int index); }

    /** The reply after readException. Every parse error is an IllegalStateException (-> ReplyException). */
    static final class In {
        private final Parcel p;
        private In(Parcel p) { this.p = p; }
        int i32() { need(4); return p.readInt(); }
        boolean bool() { need(4); return p.readBoolean(); }
        byte i8() { need(4); return p.readByte(); }
        float f32() { need(4); return p.readFloat(); }
        String str() { return p.readString(); }
        /** out byte[]: readByteArray, the length must equal the array's. */
        void bytes(byte[] dst) {
            int pos = p.dataPosition();
            need(4);
            int n = p.readInt();
            if (n != dst.length) throw new IllegalStateException("byte array of " + n + ", expected " + dst.length);
            p.setDataPosition(pos);
            p.readByteArray(dst);
        }
        /** createIntArray: int32 n (negative = null: fails, more than max fails), then n int32. */
        int[] ints(int max) {
            int n = i32();
            if (n < 0 || n > max) throw new IllegalStateException("int array of " + n + " (at most " + max + ")");
            int[] a = new int[n];
            for (int k = 0; k < n; k++) a[k] = i32();
            return a;
        }
        /** Returned parcelable (readTypedObject): int32 non-zero, then the framed parcelable. Zero (null) fails. */
        <T> T object(Reader<T> r) {
            if (i32() == 0) throw new IllegalStateException("null parcelable in the reply");
            return framed(r);
        }
        /** Out parcelable after the return value: int32 non-zero, then readFromParcel. Zero fails. */
        <T> T out(Reader<T> r) {
            return object(r);
        }
        /** createTypedArray: int32 n (negative = null: fails), per element int32 present + framed element. */
        int typedArray(int max, ElementReader r) {
            int n = i32();
            if (n < 0 || n > max) throw new IllegalStateException("typed array of " + n + " (at most " + max + ")");
            for (int k = 0; k < n; k++) {
                if (i32() == 0) throw new IllegalStateException("null element " + k);
                final int idx = k;
                framed(x -> { r.read(x, idx); return null; });
            }
            return n;
        }
        /**
         * Stable parcelable frame: int32 size (at least 4), the fields, then the position moves to start + size
         * (fields of a newer version are skipped). Fields read past the frame fail (the vendor sent fewer).
         */
        <T> T framed(Reader<T> r) {
            int start = p.dataPosition();
            int size = i32();
            if (size < 4) throw new IllegalStateException("parcelable too small (" + size + ")");
            if (size > p.dataSize() - start) throw new IllegalStateException("parcelable size " + size + " past the reply");
            T v = r.read(this);
            if (p.dataPosition() - start > size) {
                throw new IllegalStateException("parcelable of " + size + " bytes is shorter than its fields");
            }
            p.setDataPosition(start + size);
            return v;
        }
        private void need(int n) {
            if (p.dataAvail() < n) throw new IllegalStateException("reply too short");
        }
    }

    // ---------------------------------------------------------------- calls
    /** Guarded call of one Call: verify its interface first, then send. Package-private: no raw code anywhere. */
    static <T> T call(Call c, Args a, Ret<T> r) throws RemoteException {
        if (c.iface == null) throw new IllegalStateException(c.method + " is a meta call");
        need(c.iface);
        return sendCall(c.iface, c, a, r);
    }

    /** The only transact. RemoteException: not delivered (the binder is dropped); ReplyException: delivered. */
    private static <T> T sendCall(Iface i, Call c, Args a, Ret<T> r) throws RemoteException {
        if (c.iface != null && c.iface != i) throw new IllegalStateException(c.method + " is not on " + i.descriptor);
        if (Looper.getMainLooper().isCurrentThread()) {
            Log.e(TAG, i.descriptor + " " + c.method + " refused: called on the main thread");
            throw new RemoteException("HAL call on the main thread refused");
        }
        IBinder b;
        synchronized (AidlCaller.class) {
            // a real call goes only to a binder the guard passed: connectIface clears verified with every new
            // binder, and a refused interface has no binder (need() drops it), so a call that passed need()
            // before a vendor restart never reaches the new binder before or after its guard ran
            if (c.iface != null && (!i.verified || i.refused)) throw new RemoteException(i.descriptor + " not verified");
            b = i.binder;
        }
        if (b == null) throw new RemoteException(i.instance + " not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(i.descriptor);
            if (a != null) a.write(new Out(data));
            boolean done;
            try {
                done = b.transact(c.code, data, reply, 0);
            } catch (RemoteException e) {
                dropIfSame(i, b);
                throw e;
            } catch (RuntimeException e) {
                dropIfSame(i, b);
                throw new RemoteException(c.method + ": " + e);
            }
            if (!done) throw new ReplyException("Method " + c.method + " is unimplemented (code " + c.code + ")");
            try {
                reply.readException();
                return r.read(new In(reply));
            } catch (RuntimeException e) {
                throw new ReplyException("reply of " + i.descriptor + " " + c.method + ": " + e);
            }
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void dropIfSame(Iface i, IBinder b) {
        synchronized (AidlCaller.class) {
            if (i.binder == b) {
                i.binder = null;
                i.verified = false;
            }
        }
    }

    // ---------------------------------------------------------------- typed helpers (as HidlCaller)
    /** An AIDL byte argument: the Z9X u8 values, 0..127 only (nothing larger is ever sent). */
    private static byte u8(int v) {
        if (v < 0 || v > 127) throw new IllegalArgumentException("byte argument " + v + " not in 0..127");
        return (byte) v;
    }

    static void callU8(Call c, int v) throws RemoteException {
        final byte b = u8(v);
        call(c, o -> o.i8(b), RET_VOID);
    }

    static boolean callU8RetBool(Call c, int v) throws RemoteException {
        final byte b = u8(v);
        return call(c, o -> o.i8(b), RET_BOOL);
    }

    static void callI32(Call c, int v) throws RemoteException { call(c, o -> o.i32(v), RET_VOID); }

    static boolean callI32RetBool(Call c, int v) throws RemoteException { return call(c, o -> o.i32(v), RET_BOOL); }

    static int callI32RetI32(Call c, int v) throws RemoteException { return call(c, o -> o.i32(v), RET_I32); }

    static boolean callI32I32RetBool(Call c, int a, int b) throws RemoteException {
        return call(c, o -> { o.i32(a); o.i32(b); }, RET_BOOL);
    }

    static int callI32I32RetI32(Call c, int a, int b) throws RemoteException {
        return call(c, o -> { o.i32(a); o.i32(b); }, RET_I32);
    }

    static int callBoolI32I32RetI32(Call c, boolean a, int b, int d) throws RemoteException {
        return call(c, o -> { o.bool(a); o.i32(b); o.i32(d); }, RET_I32);
    }

    static boolean callI32x3RetBool(Call c, int a, int b, int d) throws RemoteException {
        return call(c, o -> { o.i32(a); o.i32(b); o.i32(d); }, RET_BOOL);
    }

    static boolean callI32BoolRetBool(Call c, int a, boolean b) throws RemoteException {
        return call(c, o -> { o.i32(a); o.bool(b); }, RET_BOOL);
    }

    static boolean callBoolI32RetBool(Call c, boolean a, int b) throws RemoteException {
        return call(c, o -> { o.bool(a); o.i32(b); }, RET_BOOL);
    }

    static int callI32BoolRetI32(Call c, int a, boolean b) throws RemoteException {
        return call(c, o -> { o.i32(a); o.bool(b); }, RET_I32);
    }

    static boolean callI32F32RetBool(Call c, int a, float f) throws RemoteException {
        return call(c, o -> { o.i32(a); o.f32(f); }, RET_BOOL);
    }

    static void callBool(Call c, boolean v) throws RemoteException { call(c, o -> o.bool(v), RET_VOID); }

    static boolean callBoolRetBool(Call c, boolean v) throws RemoteException { return call(c, o -> o.bool(v), RET_BOOL); }

    static int callBoolRetI32(Call c, boolean v) throws RemoteException { return call(c, o -> o.bool(v), RET_I32); }

    static int callI32x4RetI32(Call c, int a, int b, int d, int e) throws RemoteException {
        return call(c, o -> { o.i32(a); o.i32(b); o.i32(d); o.i32(e); }, RET_I32);
    }

    static boolean callRetBool(Call c) throws RemoteException { return call(c, null, RET_BOOL); }

    static int callRetI32(Call c) throws RemoteException { return call(c, null, RET_I32); }

    static int callRetU8(Call c) throws RemoteException { return call(c, null, RET_U8); }

    static byte callRetI8(Call c) throws RemoteException { return call(c, null, RET_I8); }

    // ---------------------------------------------------------------- the focus callback
    /** Receives (type, value) of IProjectorFocusCallback.focusEvent on a binder thread: only post. */
    interface FocusSink { void onFocusEvent(int type, String value); }

    private static volatile FocusSink sFocusSink;
    /** Strong static reference: the vendor keeps only a binder reference to our callback. */
    private static FocusCallback sCallback;

    static void setFocusSink(FocusSink s) {
        sFocusSink = s;
    }

    /** FOCUS 1 setFocusCallback(cb), the stock registration (ProjectorFocusManagerWrapper.initCallback). */
    static void callRegisterFocusCallback(Call c) throws RemoteException {
        final FocusCallback cb;
        synchronized (AidlCaller.class) {
            if (sCallback == null) sCallback = new FocusCallback();
            cb = sCallback;
        }
        call(c, o -> o.p.writeStrongBinder(cb), RET_VOID);
    }

    /**
     * Java xgimi.hardware.gmpf.IProjectorFocusCallback, answered as the stock $Stub (com.xgimi.middle.jar):
     * markVintfStability in the constructor (the vendor process may only call a VINTF binder), code 1
     * oneway focusEvent(int, String) with enforceInterface and enforceNoDataAvail, 0x00FFFFFF version 1,
     * 0x00FFFFFE the frozen hash, everything else to Binder.onTransact (descriptor, dump, shell).
     * Never calls the HAL, never blocks: the event goes to the sink, which posts it.
     */
    private static final class FocusCallback extends Binder implements IInterface {
        static final String DESCRIPTOR = PKG + "IProjectorFocusCallback";
        static final int FOCUS_EVENT = 1;
        static final int META_VERSION = 0x00FFFFFF;
        static final int META_HASH = 0x00FFFFFE;

        FocusCallback() {
            markVintfStability();
            attachInterface(this, DESCRIPTOR);
        }

        @Override public IBinder asBinder() { return this; }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == META_VERSION || code == META_HASH) {
                data.enforceInterface(DESCRIPTOR);
                reply.writeNoException();
                if (code == META_VERSION) reply.writeInt(VERSION); else reply.writeString(HASH);
                return true;
            }
            if (code != FOCUS_EVENT) return super.onTransact(code, data, reply, flags);
            int type;
            String value;
            try {
                data.enforceInterface(DESCRIPTOR);
                type = data.readInt();
                value = data.readString();
                data.enforceNoDataAvail();
            } catch (Throwable t) {
                Log.w(TAG, "focusEvent parse: " + t);   // oneway: nothing to answer, the event is dropped
                return true;
            }
            FocusSink s = sFocusSink;
            if (s != null) {
                try { s.onFocusEvent(type, value); } catch (Throwable t) { Log.w(TAG, "sink: " + t); }
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- argument checks
    static int checkRange(String what, int v, int min, int max) {
        if (v < min || v > max) throw new IllegalArgumentException(what + " " + v + " not in " + min + ".." + max);
        return v;
    }
}
