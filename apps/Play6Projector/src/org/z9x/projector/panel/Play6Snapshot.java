package org.z9x.projector.panel;

import android.util.Log;

import org.z9x.projector.Device;
import org.z9x.projector.battery.Battery;
import org.z9x.projector.hal.Play6Gmpf;
import org.z9x.projector.hal.Play6Gmpf2;

/**
 * The values the Play 6 quick panel shows, read on the p6-hal thread (Hal.query) in parts, so the panel
 * reads only what it shows (docs/play6/SETTINGS_PLAN.md section 4): on open the first page ({@link
 * #ROOT}: one task with 696, 620, 666, 177, 62, 553, 586, 173, 579 and IGmpf2 63; 151 only while the eye
 * protection tile exists, Device.hasEyeProtection; IGmpf2 33 only while HDMI is the current source), every other
 * page its own part when it is shown, and the part of a row again after every change (read-back). The
 * controller merges each read into one snapshot ({@link #merge}). Every field is null when its getter
 * failed or was not read; the row then shows no value and is disabled.
 *
 * Rules kept here: the Custom items 688 / 690 / 692 / 694 are read only while 666 reads 3 (Custom), the
 * motion smoothing level 668 only while 666 reads 1, 7 or 3; IGmpf2 values only while the identity guard
 * is open (Play6Gmpf2.isIdentityOpen: no call is even tried otherwise, all or nothing, plan 2.1). Nothing
 * here writes.
 *
 * Battery (plan 3): not read here (battery/Battery, events and watchdog). The Brightness page reads power
 * saving (327) with the light level and hands it to Battery.noteEco, so Lumen Home's provider sees it
 * without another call.
 */
final class Play6Snapshot {
    private static final String TAG = "P6Panel";

    /** Parts (bit flags) of a read. */
    static final int ROOT = 1, FOCUS = 1 << 1, KEYSTONE = 1 << 2, LAMP = 1 << 3, PICTURE = 1 << 4, SOUND = 1 << 5,
            PROJECTION = 1 << 6, GAME = 1 << 7, EYE = 1 << 8, AMBIENT = 1 << 9;

    /** The parts this snapshot holds values for. */
    int parts;
    // ROOT (the first page); some of these are also part of a section page
    Integer source, hdr, pictureMode, lamp, soundEffect, putMode;
    Boolean eye, powerOnAk, moveAk, autoReverse, smartAf;
    // LAMP
    /** IGmpf 327 power saving. */
    Boolean eco;
    /** org.z9x.tvinput: an HDMI TIF session is open (TifHdmiState.read; null = unknown). Not a HAL read. */
    Boolean tifHdmi;
    // PICTURE
    Integer memc;
    /** IGmpf 179 {R, G, B, C} light levels, read only with the Custom picture style; see rgbOk. */
    int[] rgb;
    /** Custom items in row order: brightness 692, contrast 694, saturation 690, sharpness 688. */
    final Integer[] pq = new Integer[4];
    // GAME (t15: read only while HDMI is the current source, 696 == 1)
    Integer aspect;
    /** IGmpf2 33 {state, type, option} (also on the first page, for the Game tile); null = not read. */
    int[] gameProp;
    /** IGmpf 662 {width, height, interlace, isHdmiMode, frameRate x 100}; null = not read. */
    int[] hdmiInfo;
    /** The IGmpf2 identity guard was open at the read. */
    boolean gmpf2;
    // KEYSTONE, P3 item 21: the replies of the adjustment cards parse as expected (parse probes, no value is
    // used; null = not read or not delivered, false = delivered but unexpected: the row stays hidden)
    /** IGmpf 117 + 116 (digital zoom and shift). */
    Boolean zoomOk;
    /** IGmpf2 130 (picture rotation). */
    Boolean rotationOk;
    // PICTURE, stock parity (2026-10-21): read only in the modes whose stock page has the row
    /** 670 noise reduction, 684 colour temperature, 175 picture style (Custom only). */
    Integer nr, colorTemp, style;
    /** Gains 682 / 680 / 678 and offsets 676 / 674 / 672 (Custom with colour temperature Custom only). */
    final Integer[] wb = new Integer[6];
    /** IGmpf2 228 gamma index, 230 dynamic contrast (Custom only, guard open). */
    Integer gamma;
    Boolean dynContrast;
    /** 651 local contrast (Movie, TV, Custom), 649 HDR auto (all modes). */
    Integer localContrast;
    Boolean hdrAuto;
    // GAME (HDMI only): 634 colour range, 661 EDID version
    Integer hdmiRange, edid;
    // SOUND: the equalizer can work now (26 AMP, 43 no DTS, 60 speaker; audio/P6SoundEq)
    Boolean eqOk;
    /** IGmpf 632 eARC mode (stock voice_earc_sound_v5: true = Auto, false = Off); null = not read. */
    Boolean earc;
    // AMBIENT: IGmpf2 70 slide function (what closing the projector does)
    Integer slideFunc;
    // ROOT | AMBIENT, P3 item 21
    /** IGmpf2 71 getIsAutoAtmoSphereLight: the stock gate of the ambient light settings (tile and page). */
    Boolean ambientOk;

    /** 666 values 10..17: the AI scene sub-modes (research battery_sound_features.md 3.3). */
    private static final int AI_SCENE_FIRST = 10, AI_SCENE_LAST = 17;

    private interface Get<T> { T get() throws Exception; }

    private static <T> T opt(String what, Get<T> g) {
        try {
            return g.get();
        } catch (Throwable t) {
            Log.d(TAG, "read " + what + ": " + t);
            return null;
        }
    }

    /** p6-hal only. Never throws. */
    static Play6Snapshot read(Play6Gmpf g, Play6Gmpf2 g2, int parts) {
        Play6Snapshot s = new Play6Snapshot();
        s.parts = parts;
        s.gmpf2 = g2.isIdentityOpen();
        if (any(parts, ROOT | GAME)) s.source = opt("696", g::getCurrentInputSource);
        if (any(parts, ROOT)) s.hdr = opt("620", g::getHdrType);
        if (any(parts, ROOT | PICTURE | GAME)) s.pictureMode = opt("666", g::getPictureMode);
        if (any(parts, ROOT | LAMP)) s.lamp = opt("177", g::getLampLevel);
        if (any(parts, LAMP) && Device.hasBattery()) {
            s.eco = opt("327", g::getEcoState);
            Battery.noteEco(s.eco);                     // p6-hal, as Battery's own reads
        }
        if (any(parts, ROOT | SOUND)) s.soundEffect = opt("62", g::getSoundEffect);
        if (any(parts, SOUND)) s.eqOk = org.z9x.projector.audio.P6SoundEq.usable(g);
        if (any(parts, SOUND)) s.earc = opt("632", g::getEarc);
        if (any(parts, ROOT | EYE) && Device.hasEyeProtection()) s.eye = opt("151", g::getEyeProtection);
        if (any(parts, ROOT | KEYSTONE)) {
            s.powerOnAk = opt("553", g::getPowerOnAk);
            s.moveAk = opt("586", g::getMoveAk);
        }
        if (any(parts, ROOT | PROJECTION)) {
            s.putMode = opt("173", g::getPutMode);
            s.autoReverse = opt("579", g::getAutoProjection);
        }
        if (any(parts, PICTURE) && s.pictureMode != null) {
            int pm = s.pictureMode;
            if (memcMode(pm)) s.memc = opt("668", g::getMemcLevel);
            if (memcMode(pm)) s.localContrast = opt("651", g::getLocalContrast);
            s.hdrAuto = opt("649", g::getHdrAuto);
            if (pm == Play6Gmpf.PICTURE_CUSTOM) {
                s.nr = opt("670", g::getNoiseReduction);
                s.colorTemp = opt("684", g::getColorTemperature);
                s.style = opt("175", g::getPictureStyle);
                if (s.style != null && s.style == Play6Gmpf.STYLE_CUSTOM) s.rgb = opt("179", g::getRgbLevels);
                if (s.colorTemp != null && s.colorTemp == 3) {
                    s.wb[0] = opt("682", g::getRedGain);
                    s.wb[1] = opt("680", g::getGreenGain);
                    s.wb[2] = opt("678", g::getBlueGain);
                    s.wb[3] = opt("676", g::getRedOffset);
                    s.wb[4] = opt("674", g::getGreenOffset);
                    s.wb[5] = opt("672", g::getBlueOffset);
                }
                if (s.gmpf2) {
                    s.gamma = opt("g2 228", g2::getGamma);
                    s.dynContrast = opt("g2 230", g2::getDynamicContrast);
                }
                s.pq[0] = opt("692", g::getBrightness);
                s.pq[1] = opt("694", g::getContrast);
                s.pq[2] = opt("690", g::getSaturation);
                s.pq[3] = opt("688", g::getSharpness);
            }
        }
        boolean hdmiSource = s.source != null && s.source == Play6Gmpf.SOURCE_HDMI1;
        if (any(parts, GAME) && hdmiSource) {
            s.aspect = opt("664", g::getAspectRatio);
            s.hdmiInfo = opt("662", g::getHdmiInfo);
            s.hdmiRange = opt("634", g::getHdmiRange);
            s.edid = opt("661", g::getEdidVersion);
        }
        if (any(parts, KEYSTONE) && s.gmpf2) {
            s.zoomOk = probeZoom(g);
            s.rotationOk = probeRotation(g2);
        }
        if (s.gmpf2) {
            if (any(parts, ROOT | FOCUS)) s.smartAf = opt("g2 63", g2::getSmartAutofocus);
            if (any(parts, ROOT | AMBIENT)) s.ambientOk = opt("g2 71", g2::getAmbientLightSupported);
            if (any(parts, AMBIENT)) s.slideFunc = opt("g2 70", g2::getSlideFunction);
            if (any(parts, ROOT | GAME) && hdmiSource) s.gameProp = opt("g2 33", g2::getGameModeProp);
        }
        return s;
    }

    /**
     * IGmpf 117 and 116 parse as the stock structs (16 and 8 bytes) and 117's numbers are sane: true. A delivered
     * reply that does not parse: false (logged with the reason, the row hidden for this process); not delivered or
     * a card is open: null. Getters only (stock reads them inside its zoom session; the values are read again
     * there). Skipped once found unsupported.
     */
    private static Boolean probeZoom(Play6Gmpf g) {
        if (Play6ZoomShiftPanel.isUnsupported()) return false;
        if (Play6AdjustSession.anyOpen()) return null;
        try {
            int[] range = g.getZoomStepRange();
            int[] step = g.getZoomStep();
            Log.i(TAG, "probe 117 raw " + java.util.Arrays.toString(range) + ", 116 raw " + java.util.Arrays.toString(step));
            for (int i = 1; i < range.length; i++) {
                if (range[i] < Play6Gmpf.ZOOM_STEP_MIN || range[i] > Play6Gmpf.ZOOM_STEP_MAX) {
                    Play6ZoomShiftPanel.markUnsupported("117 value " + range[i] + " outside the sanity bound");
                    return false;
                }
            }
            return true;
        } catch (Play6Gmpf.ReplyException e) {
            Play6ZoomShiftPanel.markUnsupported("probe: " + e.getMessage());
            return false;
        } catch (Throwable t) {
            Log.d(TAG, "probe zoom: " + t);
            return null;
        }
    }

    /** IGmpf2 130 parses and is plausible (Play6Gmpf2.getScreenRotation checks it): true; as probeZoom otherwise. */
    private static Boolean probeRotation(Play6Gmpf2 g2) {
        if (Play6RotatePanel.isUnsupported()) return false;
        if (Play6AdjustSession.anyOpen()) return null;
        try {
            float[] r = g2.getScreenRotation();
            Log.i(TAG, "probe 130 -> " + java.util.Arrays.toString(r));
            return true;
        } catch (Play6Gmpf2.ReplyException | Play6Gmpf2.AbsentException e) {
            Play6RotatePanel.markUnsupported("probe: " + e.getMessage());
            return false;
        } catch (Throwable t) {
            Log.d(TAG, "probe rotation: " + t);
            return null;
        }
    }

    private static boolean any(int parts, int mask) {
        return (parts & mask) != 0;
    }

    /**
     * The picture mode the panel shows for a 666 read: 10..17 (the AI scene sub-modes, by the order of the
     * EN_PICTURE_MODE table in libxgimi.so: 10 AI_TV_PLAY .. 16 AI_MOVIE, 17 AI_FOOTBALL; UNVERIFIED on the
     * device, research 3.3) as AI (10); every other value as read. Choosing AI still sends 667(0, 10).
     */
    static Integer shownPictureMode(Integer pm) {
        // Integer.valueOf: a bare int in the ternary would unbox a null pm (NPE seen 2026-10-21 when a page opened
        // before the first read)
        return pm != null && pm >= AI_SCENE_FIRST && pm <= AI_SCENE_LAST ? Integer.valueOf(Play6Gmpf.PICTURE_AI) : pm;
    }

    /** Motion smoothing (668 / 669) exists only in Movie (1), TV (7) and Custom (3) (stock dayao tree). */
    static boolean memcMode(int pm) {
        return pm == Play6Gmpf.PICTURE_MOVIE || pm == Play6Gmpf.PICTURE_TV || pm == Play6Gmpf.PICTURE_CUSTOM;
    }

    /**
     * Takes the values of the parts {@code n} read into this snapshot (main thread, controller). The fields
     * of a part that was read replace the old ones, also with null (failed now = unknown now).
     */
    void merge(Play6Snapshot n) {
        int p = n.parts;
        parts |= p;
        gmpf2 = n.gmpf2;
        if (any(p, ROOT | GAME)) { source = n.source; tifHdmi = n.tifHdmi; }
        if (any(p, ROOT)) hdr = n.hdr;
        if (any(p, ROOT | PICTURE | GAME)) pictureMode = n.pictureMode;
        if (any(p, ROOT | LAMP)) lamp = n.lamp;
        if (any(p, LAMP)) eco = n.eco;
        if (any(p, ROOT | SOUND)) soundEffect = n.soundEffect;
        if (any(p, SOUND)) { eqOk = n.eqOk; earc = n.earc; }
        if (any(p, ROOT | EYE)) eye = n.eye;
        if (any(p, ROOT | KEYSTONE)) { powerOnAk = n.powerOnAk; moveAk = n.moveAk; }
        if (any(p, KEYSTONE)) { zoomOk = n.zoomOk; rotationOk = n.rotationOk; }
        if (any(p, ROOT | PROJECTION)) { putMode = n.putMode; autoReverse = n.autoReverse; }
        if (any(p, PICTURE)) {
            nr = n.nr; colorTemp = n.colorTemp; style = n.style; gamma = n.gamma; dynContrast = n.dynContrast;
            localContrast = n.localContrast; hdrAuto = n.hdrAuto; rgb = n.rgb;
            System.arraycopy(n.wb, 0, wb, 0, wb.length);
            memc = n.memc;
            System.arraycopy(n.pq, 0, pq, 0, pq.length);
        }
        if (any(p, GAME)) { aspect = n.aspect; hdmiInfo = n.hdmiInfo; hdmiRange = n.hdmiRange; edid = n.edid; }
        if (any(p, AMBIENT)) slideFunc = n.slideFunc;
        if (any(p, ROOT | GAME)) gameProp = n.gameProp;
        if (any(p, ROOT | FOCUS)) smartAf = n.smartAf;
        if (any(p, ROOT | AMBIENT)) ambientOk = n.ambientOk;
    }

    /** HDMI confirmed by 696 (the one HDMI port) or by an open org.z9x.tvinput HDMI session (info line). */
    boolean hdmiConfirmed() {
        return hdmiSource() || Boolean.TRUE.equals(tifHdmi);
    }

    /**
     * HDMI is the current source (696 == 1, set by our IGmpf 698 since t14): the vendor keeps game mode and
     * the aspect ratio per source, so the Game page rows exist only then (t15).
     */
    boolean hdmiSource() {
        return source != null && source == Play6Gmpf.SOURCE_HDMI1;
    }

    /**
     * "Original" changes the picture only for a signal smaller than the 1920 x 1080 panel in both directions,
     * with valid video info (the vendor needs width >= 600 and height >= 448, else "ERROR VIDEO INFOR";
     * research hdmi_volume_game.md 4.1). Unknown (662 not read or failed): false.
     */
    boolean originalFits() {
        if (hdmiInfo == null || hdmiInfo.length < 2) return false;
        int w = hdmiInfo[0], h = hdmiInfo[1];
        return w >= 600 && h >= 448 && w < 1920 && h < 1080;
    }

    /** 666 reads 4: the MStar Game picture mode that game mode sets (plan 1.4). */
    boolean gamePicture() {
        return pictureMode != null && pictureMode == Play6Gmpf.PICTURE_GAME;
    }

    /** A 179 reply the rows can show: R, G and B each 1..10 (stock brightness.json range). */
    static boolean rgbOk(int[] v) {
        return v != null && v.length >= 3 && v[0] >= 1 && v[0] <= 10 && v[1] >= 1 && v[1] <= 10 && v[2] >= 1 && v[2] <= 10;
    }
}
