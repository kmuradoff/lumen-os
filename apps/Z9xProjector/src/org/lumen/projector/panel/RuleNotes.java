package org.lumen.projector.panel;

import android.content.Context;
import android.util.Log;

import org.lumen.projector.R;
import org.lumen.projector.hal.Gmpf2Client;
import org.lumen.projector.hal.GmpfClient;
import org.lumen.projector.source.TifHdmiState;
import org.lumen.projector.ui.Notify;

/**
 * Lumen OS 1.0.8 Z9X sweep: the note of each {@link PanelRules.Why} (our words, stock meaning). Stock has two 3D
 * texts: "turn off 3D and try again" (picture mode, colour temperature, Ultra 120, game, brightness) and "not
 * supported in 3D" (keystone family, aspect ratio, original frame rate); the caller says which one.
 */
public final class RuleNotes {
    private RuleNotes() {}

    public static int note(PanelRules.Why w, boolean turnOff3d) {
        switch (w) {
            case GAME: return R.string.blocked_game;
            case TOP_SPEED: return R.string.rules_not_top_speed;
            case AI: return R.string.rules_not_ai;
            case PERFORMANCE: return R.string.rules_not_performance;
            case OFFICE: return R.string.rules_not_office;
            case THREE_D: return turnOff3d ? R.string.panel_boost_3d : R.string.panel_ultra120_3d;
            case BOOST: return R.string.rules_not_boost;
            case NATIVE_FPS: return R.string.rules_not_native_fps;
            case ULTRA_120: return R.string.rules_not_ultra120;
            case DOLBY_VISION: return R.string.rules_dv_active;
            case HDR: return R.string.rules_not_hdr;
            case NO_VIDEO: return R.string.panel_3d_need_video;
            case HIGH_FRAME_RATE: return R.string.rules_not_hfr;
            case CLOSE_HFR: return R.string.rules_close_hfr;
            case NOT_HDMI: return R.string.panel_game_hdmi_only;
            default: return 0;
        }
    }

    /** Shows the note under {@code title} (any thread: Notify posts to the main thread). True = refused. */
    public static boolean show(Context app, CharSequence title, PanelRules.Why w, boolean turnOff3d) {
        if (w == null || w == PanelRules.Why.NONE) return false;
        int res = note(w, turnOff3d);
        if (res == 0) return false;
        if (title == null) Notify.show(app, app.getString(res));
        else Notify.show(app, title, app.getString(res));
        return true;
    }

    /**
     * HAL thread: the reads of the keystone family rules (PanelRules.keystone): 647, HDMI (696 or a tvinput session),
     * 55 when it fits 647, 666, 192 / 193. A failed read stays null (never refuses). Never throws.
     */
    public static PanelRules.In readKeystone(Context app, GmpfClient g, Gmpf2Client g2) {
        PanelRules.In in = new PanelRules.In();
        try {
            GmpfClient.GameModeOption o = g.getGameModeOption();
            in.opt647 = o == null ? null : o.wire;
        } catch (Throwable t) { Log.d(TAG, "647: " + t); }
        int src = -1;
        try { src = g.getCurrentInputSource(); } catch (Throwable t) { Log.d(TAG, "696: " + t); }
        in.hdmi = src == GmpfClient.SOURCE_HDMI1 || src == GmpfClient.SOURCE_HDMI2 || Boolean.TRUE.equals(TifHdmiState.read(app));
        if (in.hdmi && in.opt647 != null && g2 != null) {
            try {
                int[] p = g2.getGameModeProp();
                if (Gmpf2Client.gameModeOf(p, in.opt647) >= 0) in.game55 = p[0] == 0;
            } catch (Throwable t) { Log.d(TAG, "g2 55: " + t); }
        }
        try { in.pic666 = g.getPictureModeRaw(); } catch (Throwable t) { Log.d(TAG, "666: " + t); }
        try { in.fmt192 = g.getCurrent3DFormat(); } catch (Throwable t) { Log.d(TAG, "192: " + t); }
        try { in.to2d193 = g.is3DTo2DEnabled(); } catch (Throwable t) { Log.d(TAG, "193: " + t); }
        return in;
    }

    private static final String TAG = "Z9xRules";
}
