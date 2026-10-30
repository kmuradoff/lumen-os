package org.lumen.projector.display;

import android.content.Context;
import android.util.Log;

import org.lumen.projector.Hal;
import org.lumen.projector.R;
import org.lumen.projector.hal.GmpfClient;
import org.lumen.projector.panel.QuickPanel;
import org.lumen.projector.ui.ChoiceRow;
import org.lumen.projector.ui.Notify;
import org.lumen.projector.ui.Page;
import org.lumen.projector.ui.TextRow;

/**
 * Lumen OS 1.0.2: 3D, the stock "3D设置" of XGIMI's settings (com.android.newsettings, Picture page), as a
 * row of the quick panel's Picture page:
 *   3D  < Off | Side by side | Top and bottom | Side by side as 2D | Top and bottom as 2D >
 * Exactly the stock calls (V6.15.58 dump: PictureRepository.setImage3D): Off = IGmpf 190 enable3D(1),
 * side by side = 190(2), top and bottom = 190(3), "as 2D" = 191 enable3DTo2D(2 | 3) (one eye's picture,
 * for watching a 3D file without glasses). Read-back: 192 getCurrent3DFormat, 193 is3DTo2DEnabled. The
 * vendor (gmpf_main Msrv_Display_Control_V4::Enable3D) does the rest: MediaTek 3D panel profile, the DLP
 * controller's 3D mode (DLP-Link glasses sync through the light), pixel shift off, a short black frame.
 *
 * As stock, before a 3D mode: a video must be playing (180 isVideoPlaying: the hardware video path), no
 * HDR video (620 getHdrType == SDR; stock's 194 isPlayingHDRVideo is not used here), picture mode not 4.
 * The vendor checks again (game mode, > 65 fps, non-default output timing) and refuses; the read-back
 * decides what the row shows. The vendor's own "close 3D when the video ends" is off on this model
 * (isAutoClose3DEnable 0), so while 3D is on a watch on the HAL thread turns it off (190(1)) once no
 * video plays any more: otherwise menus and the home screen would stay split.
 *
 * Rows: main thread; HAL work through {@link Hal#run} / {@link Hal#query}.
 */
public final class ThreeD implements QuickPanel.Extension {
    static final String TAG = "Z9x3D";

    /** Row order. */
    private static final int OFF = 0, SBS = 1, TB = 2, SBS_2D = 3, TB_2D = 4;
    /** Picture mode stock refuses 3D in (newsettings: "picture mode 4"). */
    private static final int PICTURE_MODE_NO_3D = 4;
    /** While 3D is on: how often the watch checks that a video still plays. */
    private static final long WATCH_MS = 5_000;
    /** The vendor needs the black frame of a switch before the read-back is meaningful. */
    private static final long READBACK_DELAY_MS = 1_500;

    private static boolean sInstalled;
    private static boolean sWatching;   // HAL thread

    private Context app;
    private ChoiceRow row;

    private ThreeD() {}

    /** App start: adds the row to the Picture page. Main thread, once. */
    public static synchronized void install(Context ctx) {
        if (sInstalled) return;
        sInstalled = true;
        QuickPanel.addExtension(QuickPanel.SECTION_PICTURE, new ThreeD());
    }

    // =================================================================== panel rows (main thread)

    @Override
    public void addRows(Context c, Page page) {
        app = c.getApplicationContext();
        CharSequence[] labels = {
                app.getString(R.string.panel_off), app.getString(R.string.panel_3d_sbs),
                app.getString(R.string.panel_3d_tb), app.getString(R.string.panel_3d_sbs_2d),
                app.getString(R.string.panel_3d_tb_2d)};
        row = page.add(new ChoiceRow(app, app.getString(R.string.panel_3d), labels, (r, i) -> {
            try {
                choose(i);
            } catch (Throwable t) {
                Log.w(TAG, "row: " + t);
            }
        }));
        row.setCommitDelay(600);                              // one switch per pause, not per key press
        page.add(new TextRow(app, app.getString(R.string.panel_3d_summary)));
        refresh();
    }

    @Override
    public void onPageShown() {
        refresh();
    }

    private void refresh() {
        if (row == null) return;
        Hal.query((g, g2) -> current(g), v -> {
            if (row != null && v != null) row.setSelected(v);
        });
    }

    private void choose(int want) {
        if (want < OFF || want > TB_2D) return;
        Log.i(TAG, "chosen " + want);
        Hal.run((g, g2) -> {
            int refusal = apply(g, g2, want);
            if (refusal != 0) {
                Log.i(TAG, "not switched to " + want + " (" + app.getResources().getResourceEntryName(refusal) + ")");
                ui(() -> Notify.show(app, refusal));
            }
            readBackLater(want);
        });
    }

    private void readBackLater(int want) {
        Hal.runDelayed((g, g2) -> {
            int now = current(g);
            Log.i(TAG, "read-back: " + now + " (wanted " + want + ")");
            if (now != OFF) startWatch();
            ui(() -> { if (row != null) row.setSelected(now); });
        }, READBACK_DELAY_MS);
    }

    // =================================================================== HAL thread

    /** 192 / 193 -> row index. */
    static int current(GmpfClient g) throws Exception {
        int fmt = g.getCurrent3DFormat();
        boolean to2d = g.is3DTo2DEnabled();
        if (to2d) return fmt == 3 ? TB_2D : SBS_2D;
        return fmt == 2 ? SBS : fmt == 3 ? TB : OFF;
    }

    /**
     * 0 = sent; else the string of the reason it was not (nothing sent then, or the vendor said no).
     * Lumen 1.0.8 (parity picture item 28): refused while "Original frame rate" (IGmpf2 283) is on, as stock
     * SettingMutexManager.java:228-233 (native_display_not_support_3d).
     */
    private static int apply(GmpfClient g, org.lumen.projector.hal.Gmpf2Client g2, int want) throws Exception {
        if (want == OFF) {
            boolean ok = g.set3DMode(GmpfClient.Mode3D.OFF);
            Log.i(TAG, "190 enable3D(1) -> " + ok);
            return ok ? 0 : R.string.panel_3d_not_now;
        }
        if (!g.isVideoPlaying()) return R.string.panel_3d_need_video;
        if (g.getHdrType() != GmpfClient.HDR_SDR) return R.string.panel_3d_not_now;
        if (g.getPictureModeRaw() == PICTURE_MODE_NO_3D) return R.string.panel_3d_not_now;
        boolean nativeFps = false;
        try { nativeFps = g2.getNativeFrameRate(); } catch (Throwable t) { Log.w(TAG, "IGmpf2 283: " + t); }
        if (nativeFps) return R.string.three_d_native_fps;
        boolean ok;
        switch (want) {
            case SBS: ok = g.set3DMode(GmpfClient.Mode3D.SIDE_BY_SIDE); break;
            case TB: ok = g.set3DMode(GmpfClient.Mode3D.TOP_BOTTOM); break;
            case SBS_2D: ok = g.set3DTo2D(false); break;
            default: ok = g.set3DTo2D(true); break;
        }
        Log.i(TAG, (want >= SBS_2D ? "191 enable3DTo2D(" + (want == TB_2D ? 3 : 2) : "190 enable3D(" + (want + 1)) + ") -> " + ok);
        return ok ? 0 : R.string.panel_3d_not_now;
    }

    /** While 3D is on: turn it off when no video plays any more (the vendor does not, on this model). */
    private static void startWatch() {
        if (sWatching) return;
        sWatching = true;
        Hal.runDelayed(ThreeD::watch, WATCH_MS, () -> sWatching = false);
    }

    private static void watch(GmpfClient g, org.lumen.projector.hal.Gmpf2Client g2) throws Exception {
        boolean again = false;
        try {
            if (current(g) == OFF) return;
            if (g.isVideoPlaying()) {
                again = true;
                return;
            }
            boolean ok = g.set3DMode(GmpfClient.Mode3D.OFF);
            Log.i(TAG, "video ended: 190 enable3D(1) -> " + ok);
            if (!ok) again = true;
            ui(QuickPanel::refreshIfShowing);
        } finally {
            sWatching = again && Hal.runDelayed(ThreeD::watch, WATCH_MS, () -> sWatching = false);
        }
    }

    private static void ui(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
