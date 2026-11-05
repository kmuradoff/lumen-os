/*
 * Gmpf2Client (Z9X HB): HB body of the Z9X IGmpf2 client. Same public members and signatures as the Z9X class, so
 * the panels compile unchanged; every method names one Call of AidlCaller. On the
 * HB there is no separate IGmpf2: the Z9X IGmpf2 methods live in DisplayManager, GmTvManager, SystemManager and
 * ProjectorFocusManager, sharing one connection state with GmpfClient (one vendor process). Comments quote the HB
 * service and code, and the Z9X IGmpf2 code in brackets.
 *
 * The structs the Z9X read as HIDL buffers are stable AIDL parcelables here, read as out parcelables after the
 * return value (int32 non-zero, then the framed fields); their field order is the parcelable's own, so the orders
 * the Z9X marked UNVERIFIED are proven on the HB. The Z9X plausibility checks stay.
 *
 * Not on the HB (NotOnHbException, nothing sent): super resolution, AI contrast, original frame rate (not in the
 * HB tree). setKstPrepare: type 2 (the optical zoom dial) only, hb-t5 (zoom case); type 0
 * (manual keystone) stays refused.
 */
package org.lumen.projector.hal;

import android.os.RemoteException;

public final class Gmpf2Client extends AidlCaller {
    /** Z9X public API (the IGmpf2 descriptor); on the HB the Z9X IGmpf2 methods are spread over the managers. */
    public static final String IGMPF2 = "xgimi.hardware.gmpf.IGmpf2";

    private static final int WINDOW_MAIN = 0;

    /** Z9X public API (3D LUT files of the G0082); never sent on the HB (no Call, the LUT methods are parked). */
    public static final String[] LUT_FILES = {};

    /**
     * ResetPictureToDefault triples used by stock (FS 3.4/3.5); no other combination is sent. Z9X HB adds the three
     * reset buttons of the dapengse tree (newsettings PictureViewModel.postAction): two_point_white_balance_reset_btn
     * (0, 0, 458752) :3283, eleven_point_white_balance_reset_btn (0, 0, 393216) :3558,
     * image_mode_custom_color_correction_reset (0, 0, 851968) :3544.
     */
    public enum PictureReset {
        BASIC(0, 0, 1), PROFESSIONAL(0, 0, 2), ALL(0, -8, 0),
        WB_2_POINT(0, 0, 458752), WB_11_POINT(0, 0, 393216), COLOR_CORRECTION(0, 0, 851968);
        final int a, b, c;
        PictureReset(int a, int b, int c) { this.a = a; this.b = b; this.c = c; }
    }

    /** Game mode type (gmuiapi DisplayManager.setGameMode): 0 manual, 1 auto. */
    public enum GameModeType {
        MANUAL(0), AUTO(1);
        final int wire;
        GameModeType(int v) { wire = v; }
    }

    /** Gamma (FS 3.5): 0..8 (1.8..2.6) and 10..17 (curves). */
    public static boolean isValidGamma(int g) { return (g >= 0 && g <= 8) || (g >= 10 && g <= 17); }

    public Gmpf2Client() {
    }

    // ------------------------------------------------------------------ brightness Boost
    /** DISPLAY 152 getLensDispMode() -> 1 = Boost [IGmpf2 51]. */
    public boolean isBoost() throws RemoteException { return callRetI32(Call.GET_LENS_DISP_MODE) == 1; }
    /** DISPLAY 151 setLensDispMode(1 Boost | 0 normal) [IGmpf2 52]. Keep the stock confirmation, refuse when hot. */
    public boolean setBoost(boolean on) throws RemoteException { return callI32RetBool(Call.SET_LENS_DISP_MODE, on ? 1 : 0); }

    // ------------------------------------------------------------------ game mode / ALLM
    /** DISPLAY 147 setGameModeType(0 manual | 1 auto) [IGmpf2 56]. */
    public boolean setGameModeType(GameModeType t) throws RemoteException { return callI32RetBool(Call.SET_GAME_MODE_TYPE, t.wire); }
    /** DISPLAY 146 setGameModeState(0 on | 1 off) [IGmpf2 57]. */
    public boolean setGameModeState(boolean on) throws RemoteException { return callI32RetBool(Call.SET_GAME_MODE_STATE, on ? 0 : 1); }

    /** TV 131 getAllmStatus(source 1|2) [IGmpf2 310]: ALLM flag currently received on that HDMI input. */
    public boolean getAllmStatus(int hdmiSource) throws RemoteException {
        return callI32RetBool(Call.GET_ALLM_STATUS, checkRange("hdmi source", hdmiSource, 1, 2));
    }

    /**
     * DISPLAY 148 getGameModeProp(out GameModeProp_st) -> {state, type, gameModeOpt} [IGmpf2 55]. Reply: boolean
     * return, then the out parcelable (int state, int type, int gameModeOpt). Callers still self-validate.
     */
    public int[] getGameModeProp() throws RemoteException {
        return call(Call.GET_GAME_MODE_PROP, null, r -> {
            boolean ok = r.bool();
            int[] v = r.out(q -> new int[] {q.i32(), q.i32(), q.i32()});
            if (!ok) throw new IllegalStateException("148 returned false");
            return v;
        });
    }

    /**
     * Game mode of a 148 reply {state, type, opt} (see {@link #getGameModeProp()}): 2 auto (type 1), 1 on (state 0),
     * 0 off (state 1); -1 when it does not fit (null, opt != the 77 read just before, state or type not 0 / 1).
     * Not a HAL call. Same rule as the Z9X 1.0.8 Gmpf2Client.gameModeOf (hb-t7 fork sync: Snapshot, GameProfile).
     */
    public static int gameModeOf(int[] p, int opt647) {
        if (p == null || p.length < 3 || p[2] != opt647 || (p[0] != 0 && p[0] != 1) || (p[1] != 0 && p[1] != 1)) return -1;
        if (p[1] == 1) return 2;
        return p[0] == 0 ? 1 : 0;
    }

    /** TV 130 getAllmEnableState() [IGmpf2 311]. */
    public boolean getAllmAutoSwitch() throws RemoteException { return callRetBool(Call.GET_ALLM_ENABLE_STATE); }
    /** TV 129 setAllmEnable(bool) [IGmpf2 312]: true = switch to game mode on ALLM content. */
    public boolean setAllmAutoSwitch(boolean on) throws RemoteException { return callBoolRetBool(Call.SET_ALLM_ENABLE, on); }

    // ------------------------------------------------------------------ focus / keystone
    /** FOCUS 86 / 85 smart autofocus [IGmpf2 90 / 91]. */
    public boolean getSmartAutofocus() throws RemoteException { return callRetBool(Call.GET_POWER_ON_AF_USE_DISTANCE); }
    public boolean setSmartAutofocus(boolean on) throws RemoteException { return callBoolRetBool(Call.SET_POWER_ON_AF_USE_DISTANCE, on); }

    /** [IGmpf2 18] DISPLAY 185 setKstPrepare(enter, 0) is a REVIEW row: refused, nothing sent. */
    public boolean setKstPrepare(boolean enter) throws RemoteException {
        throw new NotOnHbException("setKstPrepare(" + enter + ", 0) (not used on the HB)");
    }

    /**
     * [IGmpf2 18] DISPLAY 185 setKstPrepare(enter, 2) -> boolean, around the optical zoom session (9.3, zoom case,
     * hb-t5). Stock G0130: newsettings ZoomStateView.dispatchKeyEvent (true, 2), ZoomStateView.release and
     * onZoomPageHide (false, 2), through MiddleWareApi.notifyKeystone (classes4 323ac8). Type 1 is the digital shift
     * screen (DisplacementView), not sent; only 2.
     */
    public boolean setKstPrepareForZoom(boolean enter) throws RemoteException {
        HbFeatures.require(HbFeatures.F.OPTICAL_ZOOM, "setKstPrepare(" + enter + ", 2)");
        if (!enter) GmpfClient.noteZoomPrepare(false);
        boolean r = callBoolI32RetBool(Call.SET_KST_PREPARE, enter, KST_PREPARE_ZOOM);
        if (enter) GmpfClient.noteZoomPrepare(true);
        return r;
    }

    /** The only setKstPrepare type sent on the HB: 2, the optical zoom dial (stock ZoomStateView). */
    private static final int KST_PREPARE_ZOOM = 2;

    /** SYSTEM 147 setPushPullScreenState(1 open | 0 closed) [IGmpf2 194]: around the zoom / shift / rotation screens. */
    public boolean setAdjustScreenOpen(boolean open) throws RemoteException {
        return callI32RetBool(Call.SET_PUSH_PULL_SCREEN_STATE, open ? 1 : 0);
    }

    /**
     * SYSTEM 142 getScreenRotationInfo(out ScreenRotationInfo_st) -> {minAng, maxAng, curAng, startAng} [IGmpf2 199].
     * Reply: boolean return, then the out parcelable (4 floats in this order). Fails unless plausible (true,
     * -45 <= min < 0 < max <= 45, min <= cur <= max): nothing rotates on a misread.
     */
    public float[] getScreenRotation() throws RemoteException {
        HbFeatures.require(HbFeatures.F.ROTATION, "getScreenRotationInfo");
        return call(Call.GET_SCREEN_ROTATION_INFO, null, r -> {
            boolean ok = r.bool();
            float[] v = r.out(q -> new float[] {q.f32(), q.f32(), q.f32(), q.f32()});
            if (!ok) throw new IllegalStateException("142 returned false");
            float min = v[0], max = v[1], cur = v[2], start = v[3];
            if (!(min >= -45f && min < 0f && max > 0f && max <= 45f && cur >= min - 0.01f && cur <= max + 0.01f)) {
                throw new IllegalStateException("142 struct " + min + "," + max + "," + cur + "," + start);
            }
            return v;
        });
    }

    /** SYSTEM 134 setScreenRotation(5 clockwise | 6 counter-clockwise, degrees) [IGmpf2 207], one step per key. */
    public boolean rotateScreen(boolean clockwise, float degrees) throws RemoteException {
        HbFeatures.require(HbFeatures.F.ROTATION, "setScreenRotation");
        if (!(degrees > 0f && degrees <= 10f)) throw new IllegalArgumentException("rotation step " + degrees);
        return callI32F32RetBool(Call.SET_SCREEN_ROTATION, clockwise ? 5 : 6, degrees);
    }

    /** SYSTEM 140 setPushPullScreenOnOff(FALSE) only, when switching to ceiling mount [IGmpf2 201]. */
    public boolean disablePushPullScreenForCeiling() throws RemoteException {
        return callBoolRetBool(Call.SET_PUSH_PULL_SCREEN_ON_OFF, false);
    }

    // ------------------------------------------------------------------ AI / picture extras (not on the HB)
    /** [IGmpf2 321] getAisrType: super resolution is not on the HB. Nothing sent. */
    public boolean getSuperResolution() throws RemoteException { throw new NotOnHbException("getAisrType"); }
    /** [IGmpf2 322] setAisrType: not on the HB. Nothing sent. */
    public boolean setSuperResolution(boolean on) throws RemoteException { throw new NotOnHbException("setAisrType"); }

    /** [IGmpf2 287] getAIContrastStatus: AI contrast is not on the HB. Nothing sent. */
    public boolean getAiContrast() throws RemoteException { throw new NotOnHbException("getAIContrastStatus"); }
    /** [IGmpf2 288] toggleAIContrast: not on the HB. Nothing sent. */
    public boolean setAiContrast(boolean on) throws RemoteException { throw new NotOnHbException("toggleAIContrast"); }

    // ------------------------------------------------------------------ professional picture settings (window 0)
    /** TV 116 getTvGammaLevel(0) [IGmpf2 326]. */
    public int getGamma() throws RemoteException { return callI32RetI32(Call.GET_TV_GAMMA_LEVEL, WINDOW_MAIN); }
    /** TV 115 setTvGammaLevel(0, g), g per {@link #isValidGamma} [IGmpf2 327]. */
    public boolean setGamma(int g) throws RemoteException {
        if (!isValidGamma(g)) throw new IllegalArgumentException("gamma " + g);
        return callI32I32RetBool(Call.SET_TV_GAMMA_LEVEL, WINDOW_MAIN, g);
    }

    /** TV 175 GetHDRDyToneMappingStatus(0) == 1 [IGmpf2 269]. */
    public boolean getHdrDynamicToneMapping() throws RemoteException {
        return callI32RetI32(Call.GET_HDR_DY_TONE_MAPPING_STATUS, WINDOW_MAIN) == 1;
    }
    /** TV 174 ToggleHDRDyToneMapping(0, 0|1) [IGmpf2 270]. */
    public boolean setHdrDynamicToneMapping(boolean on) throws RemoteException {
        return callI32I32RetBool(Call.TOGGLE_HDR_DY_TONE_MAPPING, WINDOW_MAIN, on ? 1 : 0);
    }

    // ------------------------------------------------------------------ game bar (stock analysis)
    // Stock HB xhplayer GameModeApi / OtherViewModel. src = 1 for HDMI 1, 2 for HDMI 2 (stock TV.inputToInt).

    /** TV 146 getVRREnableState(src): 1 on, everything else off (stock reads -1 as 0). */
    public boolean getVrr(int src) throws RemoteException {
        return callI32RetI32(Call.GET_VRR_ENABLE_STATE, checkRange("hdmi", src, 1, 2)) == 1;
    }
    /** TV 145 setVRRModeEnable(0 | 1, src) -> int (logged by the caller). */
    public int setVrr(boolean on, int src) throws RemoteException {
        return callI32I32RetI32(Call.SET_VRR_MODE_ENABLE, on ? 1 : 0, checkRange("hdmi", src, 1, 2));
    }

    /** Game picture of the game bar: 0 Standard, 1 FPS, 2 RPG, 3 RTS, 4 Sports. */
    public static final int GAME_PICTURE_MAX = 4;

    /** TV 148 getGamePictureMode(src) -> 0..4; anything else (stock: -1) reads as 0. */
    public int getGamePicture(int src) throws RemoteException {
        int v = callI32RetI32(Call.GET_GAME_PICTURE_MODE, checkRange("hdmi", src, 1, 2));
        return v < 0 || v > GAME_PICTURE_MAX ? 0 : v;
    }
    /** TV 147 setGamePictureMode(0..4, src) -> int. */
    public int setGamePicture(int mode, int src) throws RemoteException {
        return callI32I32RetI32(Call.SET_GAME_PICTURE_MODE, checkRange("game picture", mode, 0, GAME_PICTURE_MAX),
                checkRange("hdmi", src, 1, 2));
    }

    /** TV 150 getDarkBoostState(game picture, src): the game bar's dark enhance. */
    public boolean getDarkBoost(int gamePicture, int src) throws RemoteException {
        return callI32I32RetBool(Call.GET_DARK_BOOST_STATE, checkRange("game picture", gamePicture, 0, GAME_PICTURE_MAX),
                checkRange("hdmi", src, 1, 2));
    }
    /** TV 149 setDarkBoostOnoff(on, game picture, src) -> int. */
    public int setDarkBoost(boolean on, int gamePicture, int src) throws RemoteException {
        return callBoolI32I32RetI32(Call.SET_DARK_BOOST_ONOFF, on,
                checkRange("game picture", gamePicture, 0, GAME_PICTURE_MAX), checkRange("hdmi", src, 1, 2));
    }

    /** TV 192 getHGiGMode(). */
    public boolean getHgig() throws RemoteException { return callRetBool(Call.GET_HGIG_MODE); }
    /** TV 191 setHGiGMode(bool). */
    public boolean setHgig(boolean on) throws RemoteException { return callBoolRetBool(Call.SET_HGIG_MODE, on); }

    /** DISPLAY 179 getDispOutputFps(): the output frame rate (stock FPS overlay while VRR is on). */
    public int getOutputFps() throws RemoteException { return callRetI32(Call.GET_DISP_OUTPUT_FPS); }

    /** [IGmpf2 283] getFDBoostStatus ("original frame rate"): not in the HB tree. Nothing sent. */
    public boolean getNativeFrameRate() throws RemoteException { throw new NotOnHbException("getFDBoostStatus"); }
    /** [IGmpf2 284] toggleFDBoost: not in the HB tree. Nothing sent. */
    public boolean setNativeFrameRate(boolean on) throws RemoteException { throw new NotOnHbException("toggleFDBoost"); }

    /** DISPLAY 174 getDynamicBlackType() == 1 [IGmpf2 29]. */
    public boolean getDynamicBlack() throws RemoteException { return callRetI32(Call.GET_DYNAMIC_BLACK_TYPE) == 1; }
    /** DISPLAY 172 setDynamicBlackType(0|1) [IGmpf2 31]. */
    public boolean setDynamicBlack(boolean on) throws RemoteException { return callI32RetBool(Call.SET_DYNAMIC_BLACK_TYPE, on ? 1 : 0); }

    /** TV 114 getTvDynamicContrastEnable(0) [IGmpf2 328]. */
    public boolean getDynamicContrast() throws RemoteException { return callI32RetBool(Call.GET_TV_DYNAMIC_CONTRAST_ENABLE, WINDOW_MAIN); }
    /** TV 113 setTvDynamicContrastEnable(0, bool) [IGmpf2 329]. */
    public boolean setDynamicContrast(boolean on) throws RemoteException {
        return callI32BoolRetBool(Call.SET_TV_DYNAMIC_CONTRAST_ENABLE, WINDOW_MAIN, on);
    }

    /** TV 112 getHsyPictureModeEnable(0) ("colour optimisation") [IGmpf2 330]. */
    public boolean getColorOptimization() throws RemoteException { return callI32RetBool(Call.GET_HSY_PICTURE_MODE_ENABLE, WINDOW_MAIN); }
    /** TV 119 setHsyPictureModeEnable(0, bool) [IGmpf2 323]. */
    public boolean setColorOptimization(boolean on) throws RemoteException {
        return callI32BoolRetBool(Call.SET_HSY_PICTURE_MODE_ENABLE, WINDOW_MAIN, on);
    }

    /** HSY colour correction: what one 7-colour array holds (stock ColorCorrection: 0 hue, 1 saturation, 2 lightness). */
    public enum HsyType {
        HUE(0), SATURATION(1), LIGHTNESS(2);
        final int wire;
        HsyType(int v) { wire = v; }
    }
    /** Colours of the HSY arrays, in array order: red, green, blue, cyan, magenta, yellow, skin. */
    public static final int HSY_COLORS = 7;

    /**
     * TV 118 getHsyColorModel(0, type, out byte[7]) [IGmpf2 324]. Request: int, int, int32 7 (the out array length);
     * reply: boolean, then readByteArray (length 7). A false or a value outside 0..100 fails the call.
     */
    public int[] getHsyColorModel(HsyType t) throws RemoteException {
        return call(Call.GET_HSY_COLOR_MODEL, o -> { o.i32(WINDOW_MAIN); o.i32(t.wire); o.outBytes(HSY_COLORS); }, r -> {
            boolean ok = r.bool();
            byte[] b = new byte[HSY_COLORS];
            r.bytes(b);
            if (!ok) throw new IllegalStateException("118 returned false");
            int[] v = new int[HSY_COLORS];
            for (int i = 0; i < HSY_COLORS; i++) {
                v[i] = b[i];
                if (v[i] < 0 || v[i] > 100) throw new IllegalStateException("118 value " + v[i] + " at " + i);
            }
            return v;
        });
    }

    /** TV 117 setHsyColorModel(0, type, byte[7] 0..100) [IGmpf2 325]: the whole array, as stock. */
    public boolean setHsyColorModel(HsyType t, int[] values) throws RemoteException {
        if (values == null || values.length != HSY_COLORS) throw new IllegalArgumentException("hsy array");
        final byte[] b = new byte[HSY_COLORS];
        for (int i = 0; i < HSY_COLORS; i++) b[i] = (byte) checkRange("hsy value", values[i], 0, 100);
        return call(Call.SET_HSY_COLOR_MODEL, o -> { o.i32(WINDOW_MAIN); o.i32(t.wire); o.bytes(b); }, RET_BOOL);
    }

    /** The 11 points of multi-point white balance (stock image_segmentation): 5, 10, 20 ... 100 (% of white). */
    public static final int[] WB_POINTS = {5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};

    private static boolean isWbPoint(int p) {
        for (int x : WB_POINTS) if (x == p) return true;
        return false;
    }

    /**
     * TV 166 getOneOfMultiPointWB(point, out stOneOfMultiPointWB) -> {red, green, blue} 0..100 [IGmpf2 281]. Reply:
     * boolean, then the out parcelable (per_gain, red, green, blue); per_gain must equal the point.
     */
    public int[] getMultiPointWb(int point) throws RemoteException {
        if (!isWbPoint(point)) throw new IllegalArgumentException("wb point " + point);
        return call(Call.GET_ONE_OF_MULTI_POINT_WB, o -> o.i32(point), r -> {
            boolean ok = r.bool();
            int[] v = r.out(q -> new int[] {q.i32(), q.i32(), q.i32(), q.i32()});
            if (!ok) throw new IllegalStateException("166 returned false");
            int pg = v[0], red = v[1], green = v[2], blue = v[3];
            if (pg != point || red < 0 || red > 100 || green < 0 || green > 100 || blue < 0 || blue > 100) {
                throw new IllegalStateException("166 struct " + pg + "," + red + "," + green + "," + blue + " for " + point);
            }
            return new int[] {red, green, blue};
        });
    }

    /** TV 165 setOneOfMultiPointWB(point, {point, red, green, blue}) [IGmpf2 282], as stock. */
    public boolean setMultiPointWb(int point, int red, int green, int blue) throws RemoteException {
        if (!isWbPoint(point)) throw new IllegalArgumentException("wb point " + point);
        final int r = checkRange("wb red", red, 0, 100), g = checkRange("wb green", green, 0, 100),
                b = checkRange("wb blue", blue, 0, 100);
        return call(Call.SET_ONE_OF_MULTI_POINT_WB, o -> {
            o.i32(point);
            o.object(w -> { w.i32(point); w.i32(r); w.i32(g); w.i32(b); });
        }, RET_BOOL);
    }

    /** TV 164 ResetPictureToDefault(one of three stock triples) [IGmpf2 278]. */
    public boolean resetPicture(PictureReset r) throws RemoteException {
        return callI32x3RetBool(Call.RESET_PICTURE_TO_DEFAULT, r.a, r.b, r.c);
    }
}
