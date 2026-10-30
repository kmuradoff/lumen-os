package org.lumen.projector.panel;

import android.util.Log;

import org.lumen.projector.hal.Gmpf2Client;
import org.lumen.projector.hal.GmpfClient;

/**
 * One read of every value the quick panel shows, taken on the "z9x-hal" thread (Hal.query) each time
 * a panel page becomes visible and after every change (read-back). Every field is null when its
 * getter failed; the row then shows "—" and is disabled.
 *
 * Getters used (all plain reads of the typed whitelist; codes verified by the foundation against
 * gmpf_IGmpf_codes.tsv / gmpf_IGmpf2_codes.tsv):
 *   IGmpf 696 source, 620 hdr type, 666 picture mode, 177 lamp level, 175 colour temperature,
 *   668 MEMC, 692/694/690/688 basic picture, 192/193 3D, 647 game option, 664 aspect ratio,
 *   62 sound mode, 43/26 sound enhancement (+ 60 via AudioPathReporter), 151 eye protection, 289/291 fit/obstacle, 553 power-on
 *   keystone, 586 real-time keystone, 588 AF after moving, 303 AF at power-on, 173 put mode,
 *   579 auto flip;
 *   IGmpf2 51 Boost, 287 AI contrast, 321 super resolution, 311 ALLM auto switch, 90 smart AF,
 *   283 original frame rate (Lumen OS 1.0.4, the motion smoothing rule; ProPicture reads its own pages).
 * Lumen 1.0.8 (parity game 2.2 / 2.13, picture 29 / 30; layouts verified on the owner's Z9X 2026-10-30,
 * docs/z9x/parity/probe.md): IGmpf 157 output timing (Ultra 120 Hz state), IGmpf 662 HDMI signal (Ultra 120 Hz
 * below 62 Hz), IGmpf2 55 game mode of the current source.
 * Deliberately NOT read: 623 Dolby Vision (unreachable on this SKU).
 */
final class Snapshot {
    private static final String TAG = "Z9xPanel";

    Integer source, hdr, pictureMode, lamp, colorTemp, memc, fmt3d, gameOption, aspect, soundEffect,
            soundProcess, putMode;
    final Integer[] pq = new Integer[4];   // GmpfClient.PqItem order: BRIGHTNESS, CONTRAST, SATURATION, SHARPNESS
    /** Lumen OS 1.0.4: IGmpf2 283 original frame rate (stock blocks motion smoothing while it is on). */
    Boolean nativeFps;
    /** Lumen OS 1.0.4: IGmpf 41 volume leveling, 632 eARC; 634 HDMI colour range, 661 EDID (shown on HDMI only). */
    Boolean volumeBalance, earc;
    Integer hdmiRange, edid;
    Boolean boost, aiContrast, superRes, is3dTo2d, allm, dtsOn, eye, curtain, obstacle, powerOnAk,
            moveAk, moveAf, powerOnAf, smartAf, autoReverse;
    /** IGmpf 157: Ultra 120 Hz on (user timing 4); null = read failed. */
    Boolean ultra120;
    /** IGmpf 662: the HDMI signal (the last one when no HDMI is on screen); null = read failed. */
    GmpfClient.HdmiSignal hdmiSignal;
    /** IGmpf2 55 for the current source, only when it fits 647: 0 off, 1 on, 2 auto (null = unknown). */
    Integer gameMode55;
    /** IGmpf2 55 state field on (0) for the current source, only when 55 fits 647 (null = unknown). */
    Boolean gameActive55;
    /** org.lumen.tvinput: an HDMI TIF session is open (TifHdmiState.read; null = unknown). Not a HAL read. */
    Boolean tifHdmi;
    /** v6.3.1 AudioPathReporter.isActive: output-path reporting is active (DTS can run). Not a plain getter. */
    boolean soundPathActive;
    /** v6.3.1 AudioPathReporter.confirmedPath: 0 speaker, 2 ARC, 3 BT, 4 wired, 6 USB, -1 none. */
    int soundPath = -1;

    private interface Get<T> { T get() throws Exception; }

    private static <T> T opt(String what, Get<T> g) {
        try {
            return g.get();
        } catch (Throwable t) {
            Log.d(TAG, "read " + what + ": " + t);
            return null;
        }
    }

    /** HAL thread only. Never throws. */
    static Snapshot read(GmpfClient g, Gmpf2Client g2) {
        Snapshot s = new Snapshot();
        s.source = opt("696", g::getCurrentInputSource);
        s.hdr = opt("620", g::getHdrType);
        s.pictureMode = opt("666", g::getPictureModeRaw);
        s.lamp = opt("177", g::getLampLevel);
        s.colorTemp = opt("175", () -> {
            GmpfClient.ColorTemp c = g.getColorTemp();
            return c == null ? null : c.wire;
        });
        s.memc = opt("668", g::getMemcLevel);
        GmpfClient.PqItem[] items = GmpfClient.PqItem.values();
        for (int i = 0; i < items.length && i < s.pq.length; i++) {
            final GmpfClient.PqItem it = items[i];
            s.pq[i] = opt("pq " + it, () -> g.getPq(it));
        }
        s.fmt3d = opt("192", g::getCurrent3DFormat);
        s.is3dTo2d = opt("193", g::is3DTo2DEnabled);
        s.gameOption = opt("647", () -> {
            GmpfClient.GameModeOption o = g.getGameModeOption();
            return o == null ? null : o.wire;
        });
        s.aspect = opt("664", g::getAspectRatioRaw);
        s.soundEffect = opt("62", () -> {
            GmpfClient.SoundEffect e = g.getSoundEffect();
            return e == null ? null : e.wire;
        });
        s.dtsOn = opt("43", g::getDtsEffects);
        s.soundProcess = opt("26", g::getSoundProcessRaw);
        s.eye = opt("151", g::getEyeProtection);
        s.curtain = opt("289", g::getCurtainFit);
        s.obstacle = opt("291", g::getObstacleAvoid);
        s.powerOnAk = opt("553", g::getPowerOnAk);
        s.moveAk = opt("586", g::getMoveAk);
        s.moveAf = opt("588", g::getMoveAf);
        s.powerOnAf = opt("303", g::getPowerOnAf);
        s.putMode = opt("173", () -> {
            GmpfClient.PutMode m = g.getPutMode();
            return m == null ? null : m.wire;
        });
        s.autoReverse = opt("579", g::getAutoReverse);
        s.volumeBalance = opt("41", g::getVolumeBalance);
        s.earc = opt("632", g::getEarc);
        s.hdmiRange = opt("634", g::getHdmiRange);
        s.edid = opt("661", g::getEdidVersion);
        s.ultra120 = opt("157", () -> {
            GmpfClient.OutputTimingInfo t = g.getOutputTimingInfo();
            return t == null ? null : t.isUltra120();
        });
        s.hdmiSignal = opt("662", g::getHdmiInfo);

        // IGmpf2 connects lazily; if the first read fails the interface is not there: skip the rest.
        s.boost = opt("g2 51", g2::isBoost);
        if (s.boost != null) {
            s.aiContrast = opt("g2 287", g2::getAiContrast);
            s.superRes = opt("g2 321", g2::getSuperResolution);
            s.nativeFps = opt("g2 283", g2::getNativeFrameRate);
            s.allm = opt("g2 311", g2::getAllmAutoSwitch);
            s.smartAf = opt("g2 90", g2::getSmartAutofocus);
            if (s.gameOption != null) {
                int[] p = opt("g2 55", g2::getGameModeProp);
                int m = Gmpf2Client.gameModeOf(p, s.gameOption);
                if (m >= 0) {
                    s.gameMode55 = m;
                    s.gameActive55 = p[0] == 0;
                }
            }
        }
        return s;
    }

    /** 3D (or 3D-to-2D) active. Stock is3dMode: getCurrent3DFormat() != 1. Unknown counts as active. */
    boolean is3dActive() {
        if (fmt3d == null) return true;
        return fmt3d != 1 || Boolean.TRUE.equals(is3dTo2d);
    }

    boolean isHdmiSource() {
        return source != null && (source == GmpfClient.SOURCE_HDMI1 || source == GmpfClient.SOURCE_HDMI2);
    }

    /** HDMI confirmed by 696 (1/2) or by an open org.lumen.tvinput HDMI session. */
    boolean hdmiConfirmed() {
        return isHdmiSource() || Boolean.TRUE.equals(tifHdmi);
    }

    /** No HDMI by BOTH signals: 696 == 0 and tvinput confirms no HDMI session (unknown = false). */
    boolean noHdmiConfirmed() {
        return source != null && source == GmpfClient.SOURCE_MEDIA && Boolean.FALSE.equals(tifHdmi);
    }

    boolean isCeiling() {
        return putMode != null && (putMode == 1 || putMode == 3);
    }

    /**
     * Top speed really runs: 647 is 2 / 3 and 55 says game mode is on for this source (stock: 55 state on and
     * option 2 / 3). When 55 is unknown, the option alone (as 1.0.7).
     */
    boolean isTopSpeedGame() {
        return gameOption != null && (gameOption == 2 || gameOption == 3) && !Boolean.FALSE.equals(gameActive55);
    }

    /** Stock Ultra 120 Hz rule on HDMI (PictureViewModel: refused at getHDMIFrameRate() >= 6200): 662 plausible and below 62 Hz. */
    boolean hdmiBelow62Hz() {
        return hdmiSignal != null && hdmiSignal.plausible() && hdmiSignal.frameRateCenti < 6200;
    }
}
