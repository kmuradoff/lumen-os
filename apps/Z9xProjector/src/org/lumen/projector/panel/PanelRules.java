package org.lumen.projector.panel;

/**
 * Lumen OS 1.0.8 Z9X sweep (2026-11-04): the stock refusal rules of the Z9X settings (SettingMutexManager.mutex per
 * node, read from the dexdump because jadx did not decompile it, plus common.json rule lists; Z9X G0082 feature.xml:
 * support_Trapezoid_3D=0, display_gamemode_version=3, display_picture_mode_version=2). Each rule returns the FIRST
 * reason in the stock order, or {@link Why#NONE}. Raw vendor values, tested exactly as stock tests them (666 == 10 for
 * AI, not the 10..17 scene range). A value that could not be read never refuses. Plain Java, no Android, no HAL: the
 * callers read the values (Snapshot on the main thread, or fresh reads on the HAL thread), this class only decides.
 * sh test/rules/run.sh
 * <p>
 * Z9X only in effect: the Z9X QuickPanelController / ProPicture / keystone screens (HB forks of their own) and
 * display.ThreeD while {@code ThreeD.useStockRules()} was called (Z9X App only).
 */
public final class PanelRules {
    /** Why a change is refused. The caller picks the note (stock meaning, our own words). */
    public enum Why {
        NONE, GAME, TOP_SPEED, AI, PERFORMANCE, OFFICE, THREE_D, BOOST, NATIVE_FPS, ULTRA_120, DOLBY_VISION, HDR,
        NO_VIDEO, HIGH_FRAME_RATE, CLOSE_HFR, NOT_HDMI
    }

    /** IGmpf 666 raw picture modes stock tests (PictureUtils / BrightnessManager / DisplayHelper). */
    public static final int PIC_GAME = 4, PIC_PERFORMANCE = 5, PIC_OFFICE_6 = 6, PIC_AI = 10, PIC_OFFICE = 25;
    /** IGmpf 620: Dolby Vision (stock PictureUtils.isDolbyVision), SDR. */
    public static final int HDR_SDR = 0, HDR_DOLBY_VISION = 2;
    /** IGmpf 175 lumens modes that lock brightness: Custom, Office, Performance (stock DisplayHelper:47-57). */
    public static final int LUMENS_CUSTOM = 6, LUMENS_OFFICE = 12, LUMENS_PERFORMANCE = 15;
    /** IGmpf 662 frame rate (centi-Hz) taken as stock's "HDMI 120 Hz" for 3D (INFERRED proxy). */
    public static final int HFR_CENTI = 11_900;

    /** One set of raw reads; null = not read / failed. */
    public static final class In {
        public Integer pic666, hdr620, fmt192, lumens175, opt647, hdmiFpsCenti;
        public Boolean game55, to2d193, boost51, nativeFps283, ultra120, videoPlaying;
        public boolean hdmi;

        public In pic(Integer v) { pic666 = v; return this; }
        public In hdr(Integer v) { hdr620 = v; return this; }
        public In fmt(Integer v) { fmt192 = v; return this; }
        public In to2d(Boolean v) { to2d193 = v; return this; }
        public In lumens(Integer v) { lumens175 = v; return this; }
        public In opt(Integer v) { opt647 = v; return this; }
        public In game(Boolean v) { game55 = v; return this; }
        public In boost(Boolean v) { boost51 = v; return this; }
        public In nativeFps(Boolean v) { nativeFps283 = v; return this; }
        public In u120(Boolean v) { ultra120 = v; return this; }
        public In video(Boolean v) { videoPlaying = v; return this; }
        public In hdmi(boolean v) { hdmi = v; return this; }
        public In fps(Integer v) { hdmiFpsCenti = v; return this; }
    }

    private PanelRules() {}

    private static boolean is(Integer v, int want) { return v != null && v == want; }

    /** Stock get3dMode() != 1: 192 != 1 or 193 on. A failed 192 read does not refuse (as Lamp3d). */
    public static boolean is3d(In in) {
        return in.fmt192 != null && (in.fmt192 != 1 || Boolean.TRUE.equals(in.to2d193));
    }

    /** Stock DisplayHelper.isGameMode: 666 == 4 or 55 state on. */
    public static boolean gameRuns(In in) {
        return is(in.pic666, PIC_GAME) || Boolean.TRUE.equals(in.game55);
    }

    /** Stock isGameKeystoneMutex (KstGameLock): top speed with a game running. */
    public static boolean topSpeedRuns(In in) {
        return in.opt647 != null && KstGameLock.refuse(in.opt647, in.hdmi, in.game55, in.pic666);
    }

    private static boolean dv(In in) { return is(in.hdr620, HDR_DOLBY_VISION); }

    /**
     * Basic picture (image_mode_basic_setting) and professional picture (image_mode_prof_setting), refused on entry
     * and on every step: 55 state on, then 666 == 10 (AI), then 666 == 5 (Performance); AI / Performance not with
     * Dolby Vision content.
     */
    public static Why basicOrPro(In in) {
        if (Boolean.TRUE.equals(in.game55)) return Why.GAME;
        if (is(in.pic666, PIC_AI) && !dv(in)) return Why.AI;
        if (is(in.pic666, PIC_PERFORMANCE) && !dv(in)) return Why.PERFORMANCE;
        return Why.NONE;
    }

    /** Colour temperature (image_mode_3_color_temp_dapeng): Office (666 == 25), then 3D. */
    public static Why colorTemp(In in) {
        if (is(in.pic666, PIC_OFFICE)) return Why.OFFICE;
        if (is3d(in)) return Why.THREE_D;
        return Why.NONE;
    }

    /**
     * Auto keystone, manual keystone, zoom and move, rotation (common.json autoKeystone / manualKeystone / pictureZoom
     * / screenRotation): top-speed game first, then 3D (support_Trapezoid_3D=0).
     */
    public static Why keystone(In in) {
        if (topSpeedRuns(in)) return Why.TOP_SPEED;
        if (is3d(in)) return Why.THREE_D;
        return Why.NONE;
    }

    /** Real-time keystone (displacementAk): 3D only, no game rule. */
    public static Why realtimeKeystone(In in) {
        return is3d(in) ? Why.THREE_D : Why.NONE;
    }

    /**
     * Turning 3D ON (image_3d_info, then the common.json list): Ultra 120, original frame rate, game, top speed,
     * Performance lumens mode (175 == 15), no video, HDR, Ultra 120 again (stock checks it twice), HDMI 120 Hz.
     * 4K is not checked (no is3DFormatSupported read yet). Turning 3D off is never refused.
     */
    public static Why threeDOn(In in) {
        if (Boolean.TRUE.equals(in.ultra120)) return Why.ULTRA_120;
        if (Boolean.TRUE.equals(in.nativeFps283)) return Why.NATIVE_FPS;
        if (gameRuns(in)) return Why.GAME;
        if (topSpeedRuns(in)) return Why.TOP_SPEED;
        if (is(in.lumens175, LUMENS_PERFORMANCE)) return Why.PERFORMANCE;
        if (Boolean.FALSE.equals(in.videoPlaying)) return Why.NO_VIDEO;
        if (in.hdr620 != null && in.hdr620 != HDR_SDR) return Why.HDR;
        if (Boolean.TRUE.equals(in.ultra120)) return Why.ULTRA_120;
        if (in.hdmi && in.hdmiFpsCenti != null && in.hdmiFpsCenti >= HFR_CENTI) return Why.HIGH_FRAME_RATE;
        return Why.NONE;
    }

    /** HDMI mode / EDID (signal_setting_hdmi_version): a game at a high-frame-rate speed (55 on, 647 1 / 2). */
    public static Why hdmiMode(In in) {
        if (Boolean.TRUE.equals(in.game55) && in.opt647 != null && (in.opt647 == 1 || in.opt647 == 2)) return Why.CLOSE_HFR;
        return Why.NONE;
    }

    /** Aspect ratio (SettingMutexManager.java:989-1006): game, then 3D (HDMI is checked on the HAL thread). */
    public static Why aspect(In in, boolean gameOn) {
        if (gameOn) return Why.GAME;
        if (is3d(in)) return Why.THREE_D;
        return Why.NONE;
    }

    /** Colour space (picture_gamut_athenaultra): Office (666 == 25), then Boost (IGmpf2 51). */
    public static Why colorSpace(In in) {
        if (is(in.pic666, PIC_OFFICE)) return Why.OFFICE;
        if (Boolean.TRUE.equals(in.boost51)) return Why.BOOST;
        return Why.NONE;
    }

    /** Dynamic black (image_dynamic_black_bit_enhancement): stock isOfficeImageMode, 666 6 or 25. */
    public static Why dynamicBlack(In in) {
        return is(in.pic666, PIC_OFFICE_6) || is(in.pic666, PIC_OFFICE) ? Why.OFFICE : Why.NONE;
    }

    /** Original frame rate ON (image_original_needle_display): Ultra 120 on, then 3D. */
    public static Why nativeFpsOn(In in) {
        if (Boolean.TRUE.equals(in.ultra120)) return Why.ULTRA_120;
        if (is3d(in)) return Why.THREE_D;
        return Why.NONE;
    }

    /** Ultra 120 ON (image_mode_prefect_120): original frame rate on, then 3D. */
    public static Why ultra120On(In in) {
        if (Boolean.TRUE.equals(in.nativeFps283)) return Why.NATIVE_FPS;
        if (is3d(in)) return Why.THREE_D;
        return Why.NONE;
    }

    /** Picture mode change (common.json pictureMode, isImageMode2): game, Dolby Vision, 3D. */
    public static Why pictureMode(In in, boolean gameOn) {
        if (gameOn) return Why.GAME;
        if (dv(in)) return Why.DOLBY_VISION;
        if (is3d(in)) return Why.THREE_D;
        return Why.NONE;
    }

    /** {@link #brightness} results besides the lumens lock names of {@link #lockName}. */
    public static final int LAMP_FREE = -1, LAMP_3D = 3;

    /**
     * Brightness (dlpLumensLevel, CJ:3-44): the Custom / Office / Performance lumens modes first (0 / 1 / 2, see
     * {@link #lockName}), then 3D ({@link #LAMP_3D}); {@link #LAMP_FREE} = allowed.
     */
    public static int brightness(In in) {
        int n = lockName(in.lumens175);
        if (n >= 0) return n;
        return is3d(in) ? LAMP_3D : LAMP_FREE;
    }

    /** 0 Custom, 1 Office, 2 Performance, -1 none: the name in the brightness note comes from 175, not from 666. */
    public static int lockName(Integer lumens) {
        if (is(lumens, LUMENS_CUSTOM)) return 0;
        if (is(lumens, LUMENS_OFFICE)) return 1;
        if (is(lumens, LUMENS_PERFORMANCE)) return 2;
        return -1;
    }

    /** Game page and game speed (common.json gameMode on game_enhance_mode_entrance): Dolby Vision, 3D, not HDMI. */
    public static Why gamePage(In in) {
        if (dv(in)) return Why.DOLBY_VISION;
        if (is3d(in)) return Why.THREE_D;
        if (!in.hdmi) return Why.NOT_HDMI;
        return Why.NONE;
    }

    // ---- wire values (Z9X G0082: getHdmi2_1() false, so the stock Enhanced index is raw 1)

    /** HDMI mode row index -> 660 wire: 0 Compatible = 0 (1.4), 1 Enhanced = 1 (2.0). Never 2 or 3 on G0082. */
    public static int edidWire(int index) { return index == 1 ? 1 : 0; }

    /** 661 read -> row index: only raw 1 is Enhanced (stock SignalManager.java:147-151), every other read Compatible. */
    public static int edidIndex(int raw) { return raw == 1 ? 1 : 0; }

    /** Aspect ratio rows in the stock Z9X order Auto, 4:3, 16:9, Original (GMPF2 wires 2, 0, 1, 4). */
    public static final int[] ASPECT_WIRES = {2, 0, 1, 4};

    /**
     * Stock GmTvManager.setGameModeOption on G0082 (display_gamemode_version 3): 648, and only when it returned true,
     * setGameModeState(0) = IGmpf2 57(0). Stock shows the speed rows only while game mode is On, so 57(0) re-applies
     * that state; we send it only then.
     */
    public static boolean resendGameState(boolean r648, boolean modeOn) { return r648 && modeOn; }

    /**
     * Stock XRMService AIPQ.reset (AIPQ.java:401-408), at its start (:133) and at every screen on (XRMService.java:164):
     * while the picture mode reads an AI scene (666 10..17) it writes the default scene again (film 16 on G0082,
     * support_multipq_type 2). Lumen 1.0.8 was seen at scene 10 ("tv") for minutes (diag boot #44).
     */
    public static boolean aiSceneReassert(Integer raw666) { return raw666 != null && raw666 >= 10 && raw666 <= 17; }
}
