/*
 * Gmpf2Client: whitelisted, typed Java hwbinder client for
 *   xgimi.hardware.gmpf@1.0::IGmpf2/default
 * A SEPARATE interface from IGmpf with its own code table (research/focus/gmpf_IGmpf2_codes.tsv,
 * class BpHwGmpf2). Registered by the same vendor service gmpfHw (vendor/etc/vintf/manifest.xml:97-102)
 * and labelled hal_gmpf_hwservice like IGmpf (vendor_hwservice_contexts:40-41), so platform_app may
 * find and call it (HAL verified_facts 20/22). IGmpf2 codes overlap IGmpf numbers with a different
 * meaning (e.g. IGmpf 289-292 curtain/obstacle vs IGmpf2 289/290 3D LUT, IGmpf 207 vs IGmpf2 207):
 * never mix the two clients.
 *
 * Every code/wire type below is quoted from gmpf_IGmpf2_codes.tsv ("TSV: W=... R=..."). Same wire
 * rules and safety as GmpfClient (HidlCaller): two-way, Status first, refused on the main thread,
 * no generic transact, fixed/range-checked arguments only.
 *
 * NOT here on purpose: 76 cleanAllCalibrationData (factory), 236/237 radar, 303 enableAiPq (stub on
 * Z9X, FS 0.2), 203-206 pan-tilt / auto projection, 279 getExtDecInfo (string query, not needed).
 * Lumen OS 1.0.4 added 194 / 199 / 207 for the picture rotation screen (stock RotateWind), with a check of the
 * rotation struct before any move.
 * Lumen OS 1.0.4 added 281/282 multi-point white balance and 324/325 HSY colour correction for the
 * professional picture settings, with the stock app's values (research/z9x_stock_decompiled) and a
 * read-back check of the struct before anything is written (see those methods).
 * Removed in v6.1 review: 300 getVRREnableState / 301 setVRRModeEnable (state values UNVERIFIED,
 * no UI offers VRR); add back only after a device test fixes the values.
 * Methods without a reviewed caller are parked (not compiled) at the end of the class, and the
 * build fails on a new one (gsi/apps/check_hal_callers.py, run by build_apk.sh).
 *
 * Connection: lazy getService on first use (no death recipient); a failed transact drops the binder
 * so the next call reconnects. HalController also drops it when IGmpf dies (same process).
 */
package org.lumen.projector.hal;

import android.os.HwParcel;
import android.os.RemoteException;

public final class Gmpf2Client extends HidlCaller {
    public static final String IGMPF2 = "xgimi.hardware.gmpf@1.0::IGmpf2";

    // ------------------------------------------------ IGmpf2 transaction codes (private, VERIFIED TSV)
    private static final int SET_KST_PREPARE = 18;             // (bool,i32) -> bool  [v6.3 manual keystone]
    private static final int GET_DYNAMIC_BLACK_TYPE = 29;      // ()        -> i32
    private static final int SET_DYNAMIC_BLACK_TYPE = 31;      // (i32)     -> bool
    private static final int DO_KEYSTONE_RATIO = 46;           // (i32)     -> bool
    private static final int GET_LENS_DISP_MODE = 51;          // ()        -> i32
    private static final int SET_LENS_DISP_MODE = 52;          // (i32)     -> bool
    private static final int SET_AE_ON_OFF = 53;               // (bool)    -> i32
    private static final int GET_GAME_MODE_PROP = 55;          // ()        -> cb(bool, GameModeProp_st 12 bytes)  [v6.2 game]
    private static final int SET_GAME_MODE_TYPE = 56;          // (i32)     -> bool
    private static final int SET_GAME_MODE_STATE = 57;         // (i32)     -> bool
    private static final int GET_POWER_ON_AF_USE_DISTANCE = 90;// ()        -> bool
    private static final int SET_POWER_ON_AF_USE_DISTANCE = 91;// (bool)    -> bool
    private static final int NOTIFY_AUTOKST = 192;             // (i32)     -> bool
    private static final int SET_PUSH_PULL_SCREEN_STATE = 194; // (i32)     -> bool  [1.0.4 zoom / shift / rotation screens]
    private static final int GET_SCREEN_ROTATION_INFO = 199;   // ()        -> cb(bool, ScreenRotationInfo_st 16 bytes)
    private static final int SET_PUSH_PULL_SCREEN = 201;       // (bool)    -> bool
    private static final int SET_SCREEN_ROTATION = 207;        // (i32 dir, float deg) -> bool  [NOT IGmpf 207]
    private static final int GET_HDR_DY_TONE_MAPPING = 269;    // (i32)     -> i32
    private static final int TOGGLE_HDR_DY_TONE_MAPPING = 270; // (i32,i32) -> bool
    private static final int RESET_PICTURE_TO_DEFAULT = 278;   // (i32,i32,i32) -> bool
    private static final int GET_ONE_OF_MULTI_POINT_WB = 281;  // (i32 segment) -> cb(bool, stOneOfMultiPointWB 16 bytes)
    private static final int SET_ONE_OF_MULTI_POINT_WB = 282;  // (i32 segment, stOneOfMultiPointWB) -> bool
    private static final int TOGGLE_AUTO_MODE = 280;           // (i32,bool) -> bool
    private static final int GET_FD_BOOST = 283;               // (i32)     -> i32
    private static final int TOGGLE_FD_BOOST = 284;            // (i32,i32) -> bool
    private static final int GET_AI_CONTRAST_STATUS = 287;     // (i32)     -> i32
    private static final int TOGGLE_AI_CONTRAST = 288;         // (i32,i32) -> bool
    private static final int GET_CUSTOMIZED_3D_LUT = 289;      // ()        -> hidl_string (cb)
    private static final int SET_CUSTOMIZED_3D_LUT = 290;      // (hidl_string) -> bool
    private static final int GET_ALLM_STATUS = 310;            // (i32)     -> bool
    private static final int GET_ALLM_ENABLE_STATE = 311;      // ()        -> bool
    private static final int SET_ALLM_ENABLE = 312;            // (bool)    -> bool
    private static final int GET_AISR_TYPE = 321;              // ()        -> i32
    private static final int SET_AISR_TYPE = 322;              // (i32)     -> bool
    private static final int SET_HSY_PICTURE_MODE = 323;       // (i32,bool) -> bool
    private static final int GET_HSY_COLOR_MODEL = 324;        // (i32,i32) -> cb(bool, int8[7])
    private static final int SET_HSY_COLOR_MODEL = 325;        // (i32,i32,int8[7]) -> bool
    private static final int GET_TV_GAMMA_LEVEL = 326;         // (i32)     -> i32
    private static final int SET_TV_GAMMA_LEVEL = 327;         // (i32,i32) -> bool
    private static final int GET_TV_DYNAMIC_CONTRAST = 328;    // (i32)     -> bool
    private static final int SET_TV_DYNAMIC_CONTRAST = 329;    // (i32,bool) -> bool
    private static final int GET_HSY_PICTURE_MODE = 330;       // (i32)     -> bool

    private static final int WINDOW_MAIN = 0;

    /** The only 3D LUT files offered (FS 3.6 built-ins; vendor files, read by the HAL in place). */
    public static final String[] LUT_FILES = {
            "/mnt/vendor/xgimiconfig/G0082/HDR/3dlut0.cube",   // "Off"
            "/mnt/vendor/xgimiconfig/G0082/HDR/3dlut1.cube",
            "/mnt/vendor/xgimiconfig/G0082/HDR/3dlut2.cube",
            "/mnt/vendor/xgimiconfig/G0082/HDR/3dlut3.cube",
            "/mnt/vendor/xgimiconfig/G0082/HDR/3dlut4.cube",
    };

    /** ResetPictureToDefault triples used by stock (FS 3.4/3.5); no other combination is sent. */
    public enum PictureReset {
        BASIC(0, 0, 1), PROFESSIONAL(0, 0, 2), ALL(0, -8, 0);
        final int a, b, c;
        PictureReset(int a, int b, int c) { this.a = a; this.b = b; this.c = c; }
    }

    /** Game mode type (DS / QS gmuiapi DisplayManager.setGameMode): 0 manual, 1 auto. */
    public enum GameModeType {
        MANUAL(0), AUTO(1);
        final int wire;
        GameModeType(int v) { wire = v; }
    }

    /**
     * Gamma (FS 3.5): BT.1886 10, ST.2084 11, HLG 12, DICOM 100..800 = 13..17, and the same call takes
     * the gamma index 1.8..2.6 = 0..8 (stock "manual" is 4). Allowed: 0..8 and 10..17.
     */
    public static boolean isValidGamma(int g) { return (g >= 0 && g <= 8) || (g >= 10 && g <= 17); }

    public Gmpf2Client() {
        super(IGMPF2);
    }

    @Override boolean onTransactFailed() { return true; }

    private void need() throws RemoteException {
        if (!ensureConnected()) throw new RemoteException(IGMPF2 + " not available");
    }

    // ------------------------------------------------------------------ brightness Boost
    /** 51 getLensDispMode() -> 1 = Boost. TSV W= R=readInt32. */
    public boolean isBoost() throws RemoteException { need(); return callRetI32(GET_LENS_DISP_MODE) == 1; }
    /**
     * 52 setLensDispMode(1 Boost | 0 normal). Raises the laser current: keep the stock two-press
     * confirmation, refuse in 3D and when hot (438, threshold UNVERIFIED). Never the "*ForTest" path.
     * TSV W=writeInt32 R=readBool.
     */
    public boolean setBoost(boolean on) throws RemoteException { need(); return callI32RetBool(SET_LENS_DISP_MODE, on ? 1 : 0); }


    // ------------------------------------------------------------------ game mode / ALLM
    /** 56 setGameModeType(0 manual | 1 auto). TSV W=writeInt32 R=readBool. Read-back 55 not implemented: persist our value. */
    public boolean setGameModeType(GameModeType t) throws RemoteException { need(); return callI32RetBool(SET_GAME_MODE_TYPE, t.wire); }
    /** 57 setGameModeState(0 on | 1 off). TSV W=writeInt32 R=readBool. */
    public boolean setGameModeState(boolean on) throws RemoteException { need(); return callI32RetBool(SET_GAME_MODE_STATE, on ? 0 : 1); }

    // ------------------------------------------------------------------ v6.2 game module (BEGIN)
    /**
     * 310 getAllmStatus(source 1|2): ALLM flag currently received on that HDMI input (EN_INPUT_SOURCE_HDMI1=1,
     * HDMI2=2 VERIFIED from stock com.xgimi.gmpf.api.GmTvManager). Read-only. Caller: game.GameProfile
     * (polled only while an HDMI session is on screen). TSV W=writeInt32 R=readBool.
     */
    public boolean getAllmStatus(int hdmiSource) throws RemoteException {
        need();
        return callI32RetBool(GET_ALLM_STATUS, checkRange("hdmi source", hdmiSource, 1, 2));
    }

    /**
     * 55 getGameModeProp() -> {state, type, gameModeOpt}. Wire VERIFIED (reply lambda 0x25d820:
     * Status, readBool, then readBuffer(12)); the field ORDER inside the 12 bytes is UNVERIFIED (AIDL twin
     * GameModeProp_st: state, type, gameModeOpt at 0/4/8; state 0 = on / 1 = off, type 0 manual / 1 auto).
     * Read-only. Callers must self-validate before trusting it (opt == 647, state/type in {0,1});
     * game.GameProfile does, and otherwise falls back to the persisted value. A reply of another size
     * fails only this call (ReplyException).
     */
    public int[] getGameModeProp() throws RemoteException {
        need();
        return call(GET_GAME_MODE_PROP, request(), r -> {
            if (!r.readBool()) throw new IllegalStateException("55 returned false");
            android.os.HwBlob b = r.readBuffer(12);
            return new int[] {b.getInt32(0), b.getInt32(4), b.getInt32(8)};
        });
    }
    // ------------------------------------------------------------------ v6.2 game module (END)

    /** 311 getAllmEnableState(). TSV R=readBool. */
    public boolean getAllmAutoSwitch() throws RemoteException { need(); return callRetBool(GET_ALLM_ENABLE_STATE); }
    /** 312 setAllmEnable(bool): true = switch to game mode on ALLM content (DS). TSV W=writeBool R=readBool. */
    public boolean setAllmAutoSwitch(boolean on) throws RemoteException { need(); return callBoolRetBool(SET_ALLM_ENABLE, on); }


    // ------------------------------------------------------------------ focus / keystone
    /** 90 / 91 smart autofocus (FS 2.7 VERIFIED FocusRepository.setSmartAutoFocus). TSV 91 W=writeBool R=readBool. */
    public boolean getSmartAutofocus() throws RemoteException { need(); return callRetBool(GET_POWER_ON_AF_USE_DISTANCE); }
    public boolean setSmartAutofocus(boolean on) throws RemoteException { need(); return callBoolRetBool(SET_POWER_ON_AF_USE_DISTANCE, on); }


    /**
     * 18 setKstPrepare(enter, 0): stock MiddleWareApi.notifyKeystone(isEnter, 0) around the manual
     * keystone screen (KeyStoneWind: (true,0) before the first corner move, (false,0) when it closes;
     * vendor Gmhal_DisplayCtrl::setKstPrepare -> KstAdjustModuleAKMode::setKstPrepareMode, the software
     * keystone scene). Type is always 0, as stock. Caller: panel.ManualKeystonePanel only. Result
     * semantics UNVERIFIED (logged). TSV W=writeBool,writeInt32 R=readBool.
     */
    public boolean setKstPrepare(boolean enter) throws RemoteException {
        need();
        HwParcel q = request();
        q.writeBool(enter);
        q.writeInt32(0);
        return call(SET_KST_PREPARE, q, RET_BOOL);
    }

    /**
     * 18 setKstPrepare(enter, 2): stock ZoomStateView around an optical zoom session ((true,2) before the
     * first step, (false,2) when it closes). Caller: panel.OpticalZoomPanel only. TSV W=writeBool,writeInt32 R=readBool.
     */
    public boolean setKstPrepareForZoom(boolean enter) throws RemoteException {
        need();
        HwParcel q = request();
        q.writeBool(enter);
        q.writeInt32(2);
        return call(SET_KST_PREPARE, q, RET_BOOL);
    }

    /**
     * Lumen OS 1.0.4: 194 setPushPullScreenState(1) while a zoom / shift / rotation screen is open, (0) when it
     * closes, as stock ZoomMoveWind / RotateWind (GmSystemManager.setPushPullScreenState). TSV W=writeInt32 R=readBool.
     */
    public boolean setAdjustScreenOpen(boolean open) throws RemoteException { need(); return callI32RetBool(SET_PUSH_PULL_SCREEN_STATE, open ? 1 : 0); }

    /**
     * Lumen OS 1.0.4: 199 getScreenRotationInfo() -> {minAng, maxAng, curAng, startAng} in degrees (stock
     * ScreenRotationInfo_st, 4 floats). Wire: Status, bool, readBuffer(16). Fails unless the reply is
     * plausible (bool true, -45 <= min < 0 < max <= 45, min <= cur <= max): nothing rotates on a misread.
     */
    public float[] getScreenRotation() throws RemoteException {
        need();
        return call(GET_SCREEN_ROTATION_INFO, request(), r -> {
            if (!r.readBool()) throw new IllegalStateException("199 returned false");
            android.os.HwBlob b = r.readBuffer(16);
            float min = b.getFloat(0), max = b.getFloat(4), cur = b.getFloat(8), start = b.getFloat(12);
            if (!(min >= -45f && min < 0f && max > 0f && max <= 45f && cur >= min - 0.01f && cur <= max + 0.01f)) {
                throw new IllegalStateException("199 struct " + min + "," + max + "," + cur + "," + start);
            }
            return new float[] {min, max, cur, start};
        });
    }

    /**
     * Lumen OS 1.0.4: 207 setScreenRotation(5 clockwise | 6 counter-clockwise, degrees > 0, relative), as stock
     * RotateWind (one 0.5 degree step per key). TSV W=writeInt32,writeFloat R=readBool.
     */
    public boolean rotateScreen(boolean clockwise, float degrees) throws RemoteException {
        if (!(degrees > 0f && degrees <= 10f)) throw new IllegalArgumentException("rotation step " + degrees);
        need();
        HwParcel q = request();
        q.writeInt32(clockwise ? 5 : 6);
        q.writeFloat(degrees);
        return call(SET_SCREEN_ROTATION, q, RET_BOOL);
    }

    /** 201 setPushPullScreenOnOff(FALSE) only, when switching to ceiling mount (FS 2.9). TSV W=writeBool R=readBool. */
    public boolean disablePushPullScreenForCeiling() throws RemoteException { need(); return callBoolRetBool(SET_PUSH_PULL_SCREEN, false); }


    // ------------------------------------------------------------------ AI / picture extras (window 0)
    /** 321 getAisrType() -> 0 off / 1 on (super resolution). TSV R=readInt32. */
    public boolean getSuperResolution() throws RemoteException { need(); return callRetI32(GET_AISR_TYPE) == 1; }
    /** 322 setAisrType(0|1). TSV W=writeInt32 R=readBool. */
    public boolean setSuperResolution(boolean on) throws RemoteException { need(); return callI32RetBool(SET_AISR_TYPE, on ? 1 : 0); }

    /** 287 getAIContrastStatus(0) -> 0/1. TSV W=writeInt32 R=readInt32. */
    public boolean getAiContrast() throws RemoteException { need(); return callI32RetI32(GET_AI_CONTRAST_STATUS, WINDOW_MAIN) == 1; }
    /** 288 toggleAIContrast(0, 0|1). SDR only (FS 3.5; real impl Gmhal_TvCtrl::toggleAIContrast). TSV W=writeInt32,writeInt32 R=readBool. */
    public boolean setAiContrast(boolean on) throws RemoteException { need(); return callI32I32RetBool(TOGGLE_AI_CONTRAST, WINDOW_MAIN, on ? 1 : 0); }








    // ------------------------------------------------------------------ Lumen OS 1.0.4: professional picture settings
    // Stock image mode 3.0 "专业设置" (PictureViewModel / GmDisplayManager, research/z9x_stock_decompiled): window 0,
    // values as the stock app sends them, every setter followed by a read-back in the panel. Caller: panel.ProPicture.

    /** 326 getTvGammaLevel(0) -> 0..8 (gamma 1.8..2.6) or 10..17 (curves). TSV W=writeInt32 R=readInt32. */
    public int getGamma() throws RemoteException { need(); return callI32RetI32(GET_TV_GAMMA_LEVEL, WINDOW_MAIN); }
    /** 327 setTvGammaLevel(0, g), g per {@link #isValidGamma}. TSV W=writeInt32,writeInt32 R=readBool. */
    public boolean setGamma(int g) throws RemoteException {
        if (!isValidGamma(g)) throw new IllegalArgumentException("gamma " + g);
        need();
        return callI32I32RetBool(SET_TV_GAMMA_LEVEL, WINDOW_MAIN, g);
    }

    /** 269 GetHDRDyToneMappingStatus(0) == 1. Shown only while HDR10 plays (stock). TSV W=writeInt32 R=readInt32. */
    public boolean getHdrDynamicToneMapping() throws RemoteException { need(); return callI32RetI32(GET_HDR_DY_TONE_MAPPING, WINDOW_MAIN) == 1; }
    /** 270 ToggleHDRDyToneMapping(0, 0|1). TSV W=writeInt32,writeInt32 R=readBool. */
    public boolean setHdrDynamicToneMapping(boolean on) throws RemoteException { need(); return callI32I32RetBool(TOGGLE_HDR_DY_TONE_MAPPING, WINDOW_MAIN, on ? 1 : 0); }

    /** 283 getFDBoostStatus(0) == 1 ("original frame rate"). TSV W=writeInt32 R=readInt32. */
    public boolean getNativeFrameRate() throws RemoteException { need(); return callI32RetI32(GET_FD_BOOST, WINDOW_MAIN) == 1; }
    /**
     * 284 toggleFDBoost(0, 0|1). Stock turns Ultra 120 Hz off with it (161 setOutputTiming(6)) and refuses
     * motion smoothing while it is on; the panel does both. TSV W=writeInt32,writeInt32 R=readBool.
     */
    public boolean setNativeFrameRate(boolean on) throws RemoteException { need(); return callI32I32RetBool(TOGGLE_FD_BOOST, WINDOW_MAIN, on ? 1 : 0); }

    /** 29 getDynamicBlackType() == 1 ("dynamic black enhancement"). TSV R=readInt32. */
    public boolean getDynamicBlack() throws RemoteException { need(); return callRetI32(GET_DYNAMIC_BLACK_TYPE) == 1; }
    /** 31 setDynamicBlackType(0|1). TSV W=writeInt32 R=readBool. */
    public boolean setDynamicBlack(boolean on) throws RemoteException { need(); return callI32RetBool(SET_DYNAMIC_BLACK_TYPE, on ? 1 : 0); }

    /** 328 getTvDynamicContrastEnable(0). TSV W=writeInt32 R=readBool. */
    public boolean getDynamicContrast() throws RemoteException { need(); return callI32RetBool(GET_TV_DYNAMIC_CONTRAST, WINDOW_MAIN); }
    /** 329 setTvDynamicContrastEnable(0, bool). TSV W=writeInt32,writeBool R=readBool. */
    public boolean setDynamicContrast(boolean on) throws RemoteException { need(); return callI32BoolRetBool(SET_TV_DYNAMIC_CONTRAST, WINDOW_MAIN, on); }

    /** 330 getHsyPictureModeEnable(0) ("colour optimisation"). TSV W=writeInt32 R=readBool. */
    public boolean getColorOptimization() throws RemoteException { need(); return callI32RetBool(GET_HSY_PICTURE_MODE, WINDOW_MAIN); }
    /** 323 setHsyPictureModeEnable(0, bool). TSV W=writeInt32,writeBool R=readBool. */
    public boolean setColorOptimization(boolean on) throws RemoteException { need(); return callI32BoolRetBool(SET_HSY_PICTURE_MODE, WINDOW_MAIN, on); }

    /** HSY colour correction: what one 7-colour array holds (stock ColorCorrection: 0 hue, 1 saturation, 2 lightness). */
    public enum HsyType {
        HUE(0), SATURATION(1), LIGHTNESS(2);
        final int wire;
        HsyType(int v) { wire = v; }
    }
    /** Colours of the HSY arrays, in array order (stock ColorCorrection): red, green, blue, cyan, magenta, yellow, skin. */
    public static final int HSY_COLORS = 7;

    /**
     * 324 getHsyColorModel(0, type) -> the 7 values (0..100, 50 = unchanged) of red, green, blue, cyan,
     * magenta, yellow, skin. Wire: Status, bool, then the hidl_array<int8,7> as a 7-byte buffer (the proxy's
     * callback reads it so). A false or a value outside 0..100 fails the call: the panel then hides colour
     * correction rather than write over values it cannot read. TSV W=writeInt32,writeInt32 (cb).
     */
    public int[] getHsyColorModel(HsyType t) throws RemoteException {
        need();
        HwParcel q = request();
        q.writeInt32(WINDOW_MAIN);
        q.writeInt32(t.wire);
        return call(GET_HSY_COLOR_MODEL, q, r -> {
            if (!r.readBool()) throw new IllegalStateException("324 returned false");
            android.os.HwBlob b = r.readBuffer(HSY_COLORS);
            int[] v = new int[HSY_COLORS];
            for (int i = 0; i < HSY_COLORS; i++) {
                v[i] = b.getInt8(i);
                if (v[i] < 0 || v[i] > 100) throw new IllegalStateException("324 value " + v[i] + " at " + i);
            }
            return v;
        });
    }

    /**
     * 325 setHsyColorModel(0, type, 7 values 0..100): the whole array, as stock (one changed colour, the
     * other six as read). TSV W=writeInt32,writeInt32,writeBuffer R=readBool.
     */
    public boolean setHsyColorModel(HsyType t, int[] values) throws RemoteException {
        if (values == null || values.length != HSY_COLORS) throw new IllegalArgumentException("hsy array");
        android.os.HwBlob b = new android.os.HwBlob(HSY_COLORS);
        for (int i = 0; i < HSY_COLORS; i++) b.putInt8(i, (byte) checkRange("hsy value", values[i], 0, 100));
        need();
        HwParcel q = request();
        q.writeInt32(WINDOW_MAIN);
        q.writeInt32(t.wire);
        q.writeBuffer(b);
        return call(SET_HSY_COLOR_MODEL, q, RET_BOOL);
    }

    /** The 11 points of multi-point white balance (stock image_segmentation): 5, 10, 20 ... 100 (% of white). */
    public static final int[] WB_POINTS = {5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};

    private static boolean isWbPoint(int p) {
        for (int x : WB_POINTS) if (x == p) return true;
        return false;
    }

    /**
     * 281 getOneOfMultiPointWB(point) -> {red, green, blue} 0..100. Struct stOneOfMultiPointWB = 4 x int32
     * {per_gain, red, green, blue} (XGIMI's AIDL twin; the HIDL order is checked here, not assumed): the reply
     * must carry per_gain == point and three values 0..100, else the call fails and the panel hides 11-point
     * white balance. Wire: Status, bool, readBuffer(16).
     */
    public int[] getMultiPointWb(int point) throws RemoteException {
        if (!isWbPoint(point)) throw new IllegalArgumentException("wb point " + point);
        need();
        HwParcel q = request();
        q.writeInt32(point);
        return call(GET_ONE_OF_MULTI_POINT_WB, q, r -> {
            if (!r.readBool()) throw new IllegalStateException("281 returned false");
            android.os.HwBlob b = r.readBuffer(16);
            int pg = b.getInt32(0), red = b.getInt32(4), green = b.getInt32(8), blue = b.getInt32(12);
            if (pg != point || red < 0 || red > 100 || green < 0 || green > 100 || blue < 0 || blue > 100) {
                throw new IllegalStateException("281 struct " + pg + "," + red + "," + green + "," + blue + " for " + point);
            }
            return new int[] {red, green, blue};
        });
    }

    /** 282 setOneOfMultiPointWB(point, {point, red, green, blue}), as stock. TSV W=writeInt32,writeBuffer R=readBool. */
    public boolean setMultiPointWb(int point, int red, int green, int blue) throws RemoteException {
        if (!isWbPoint(point)) throw new IllegalArgumentException("wb point " + point);
        android.os.HwBlob b = new android.os.HwBlob(16);
        b.putInt32(0, point);
        b.putInt32(4, checkRange("wb red", red, 0, 100));
        b.putInt32(8, checkRange("wb green", green, 0, 100));
        b.putInt32(12, checkRange("wb blue", blue, 0, 100));
        need();
        HwParcel q = request();
        q.writeInt32(point);
        q.writeBuffer(b);
        return call(SET_ONE_OF_MULTI_POINT_WB, q, RET_BOOL);
    }

    /** 278 ResetPictureToDefault(one of three stock triples). TSV W=writeInt32 x3 R=readBool. */
    public boolean resetPicture(PictureReset r) throws RemoteException {
        need();
        HwParcel q = request();
        q.writeInt32(r.a);
        q.writeInt32(r.b);
        q.writeInt32(r.c);
        return call(RESET_PICTURE_TO_DEFAULT, q, RET_BOOL);
    }

    // ------------------------------------------------------------------ style filters (3D LUT)

    // ================================================================== PARKED (not compiled)
    // No reviewed caller outside hal/ (check_hal_callers.py fails the build on a transacting
    // method without one). Kept as reference: re-enable a method only together with its caller,
    // and only after a device test where its argument values are marked UNVERIFIED.
    //
    // /** 53 setAEOnOff(FALSE) only, as the first step of wall-colour adaptation (FS 2.8). TSV W=writeBool R=readInt32. */
    // public int disableAutoExposureForWallColor() throws RemoteException { need(); return callBoolRetI32(SET_AE_ON_OFF, false); }
    //
    // (v6.2: 310 getAllmStatus moved up, caller game.GameProfile.)
    //
    // /** 192 notifyAutokst(0): a key press cancelled a curtain notice (FS 2.6). Only 0. TSV W=writeInt32 R=readBool. */
    // public boolean notifyAutokstCancelled() throws RemoteException { need(); return callI32RetBool(NOTIFY_AUTOKST, 0); }
    //
    // /** 46 doKeystoneRatio(3) only: ratio reset (FS 2.5 reset step 5). TSV W=writeInt32 R=readBool. */
    // public boolean resetKeystoneRatio() throws RemoteException { need(); return callI32RetBool(DO_KEYSTONE_RATIO, 3); }
    //
    // (269/270, 283/284, 29/31, 328/329, 330/323, 326/327: un-parked in Lumen OS 1.0.4 for the professional
    // picture settings, see that section.)
    //
    // /** 289 getCustomized3DLut() -> path (reply Status + hidl_string). TSV W= (cb hidl_string). */
    // public String get3dLut() throws RemoteException { need(); return call(GET_CUSTOMIZED_3D_LUT, request(), HwParcel::readString); }
    //
    // /**
    //  * 290 setCustomized3DLut(path): ONLY one of {@link #LUT_FILES} (index 0 = off). Our own LUTs are
    //  * not supported (SELinux readability by hal_gmpf UNVERIFIED). TSV W=writeBuffer+embedded hidl_string R=readBool.
    //  */
    // public boolean set3dLut(int index) throws RemoteException {
    //     checkRange("lut index", index, 0, LUT_FILES.length - 1);
    //     need();
    //     HwParcel q = request();
    //     q.writeString(LUT_FILES[index]);
    //     return call(SET_CUSTOMIZED_3D_LUT, q, RET_BOOL);
    // }
}
