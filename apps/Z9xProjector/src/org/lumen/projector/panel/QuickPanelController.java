package org.lumen.projector.panel;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

import org.lumen.projector.Hal;
import org.lumen.projector.KeyReceiver;
import org.lumen.projector.R;
import org.lumen.projector.Ui;
import org.lumen.projector.hal.Gmpf2Client;
import org.lumen.projector.hal.GmpfClient;
import org.lumen.projector.hal.KstPoint;
import org.lumen.projector.ak.AkOverlay;
import org.lumen.projector.audio.AiSoundEngine;
import org.lumen.projector.audio.AudioPathReporter;
import org.lumen.projector.audio.EqCurve;
import org.lumen.projector.audio.SoundEq;
import org.lumen.projector.source.SourceOverlay;
import org.lumen.projector.source.TifHdmiState;
import org.lumen.projector.ui.CheckRow;
import org.lumen.projector.ui.ChoiceRow;
import org.lumen.projector.ui.DialogPanel;
import org.lumen.projector.ui.EqBandsRow;
import org.lumen.projector.ui.HeaderRow;
import org.lumen.projector.ui.NavRow;
import org.lumen.projector.ui.Notify;
import org.lumen.projector.ui.OverlayHost;
import org.lumen.projector.ui.Page;
import org.lumen.projector.ui.PagedPanel;
import org.lumen.projector.ui.SliderRow;
import org.lumen.projector.ui.TextRow;
import org.lumen.projector.ui.Theme;
import org.lumen.projector.ui.ToggleRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The quick panel itself (one instance per process, main thread only). Builds the tile grid and the
 * section pages once, keeps the rows, and on every page show / after every change reads all values
 * in one HAL task ({@link Snapshot#read}) and updates the rows and the tile values from that
 * read-back.
 *
 * v6.2 layout (V62_REQUIREMENTS 3): top level = info line (input + HDR type, or "not ready") and a
 * {@link TileGrid} of 10 icon tiles in the requirement's order (Lumen OS 1.0: ONE Keystone tile again;
 * the v6.3 "Manual keystone" tile is gone, the Keystone page offers auto and manual correction); each
 * opens its section page with the v6.1 rows (stock dapeng grouping, FEATURE_SPEC 2-5 +
 * RESULT_quicksettings decisions):
 *   Focus:       Autofocus now, Manual focus, AF after moving, AF at power-on, smart AF
 *   Keystone:    Auto keystone now, Fit to screen now, Manual keystone (4 corners), Reset keystone,
 *                real-time keystone, keystone at power-on, fit to screen, obstacle avoidance
 *   Brightness:  lamp 1..10 (+ Boost), Boost hint
 *   Picture:     Picture mode, brightness/contrast/saturation/sharpness, colour temperature,
 *                motion smoothing, AI contrast, super resolution, Ultra 120 Hz, professional, resets,
 *                HDMI rows (colour range, HDMI mode, aspect ratio: HDMI only)
 *   Sound:       Sound enhancement (v6.3.1: Harman Kardon / DTS Virtual:X, SoundProfiles), a
 *                hint line, Sound mode (v6.4: AI sound "AI · <sub-mode>" first, then Movie / Music /
 *                Sports / Karaoke: real amp tables only), Equalizer (v6.4, own page: preset, 7 bands,
 *                reset; AMP mode only, saved per sound mode, audio.SoundEq)
 *   Input:       (no page) the Source overlay
 *   Projection:  Auto / Table / Ceiling / Rear projection
 *   Game mode:   Game mode, Game speed, Auto game mode (ALLM) - enabled only while
 *                696 reports HDMI 1/2 or org.lumen.tvinput reports an open HDMI session
 *                (TifHdmiState), and re-checked on the HAL thread before every
 *                648/207/56/57/312/665 write
 *   Eye protection: the toggle and what it does
 *   All settings: System settings (TvSettings), Projector settings, HDMI-CEC, Recent apps, Home
 *                screen (Lumen OS 1.0: no "Pair remote": RemoteAutoPair pairs by itself, the setup
 *                and the remote-lost prompt ask for Back + Home)
 * Other modules append rows through {@link QuickPanel#addExtension}. The last focused tile is kept
 * (prefs {@link #KEY_LAST_TILE}) and focused at the next open; BACK on a page returns to its tile.
 *
 * Rules kept here: every HAL call goes through Hal.run / Hal.query (never the main thread);
 * setters run only on a user action; confirmations for Performance mode, eye protection,
 * projection flips, Ultra 120 Hz, keystone reset and picture reset; every listener is wrapped in
 * try/catch (row commits run from plain Handlers in the persistent process).
 * Not offered here (verdicts): Dolby Vision (no DV on this SKU), VRR (IGmpf2 301 values
 * UNVERIFIED), digital zoom, 3D on this page (it is on the Picture page).
 */
final class QuickPanelController {
    private static final String TAG = "Z9xPanel";

    private static final int BOOST_LEVEL = 11;
    private static final long BOOST_PRESS_WINDOW_MS = 3_000;
    private static final long CHOICE_COMMIT_MS = 500;
    private static final long PICTURE_MODE_COMMIT_MS = 700;
    private static final long SLIDER_GUARD_MS = 800;

    private static final String KEY_GAME_MODE = QuickPanel.KEY_GAME_MODE;   // 0 off, 1 on, 2 auto (last set)
    static final String KEY_ULTRA_120 = "ultra_120";                // last value set, synced from 157 on read; also ProPicture
    /** v6.2: section key of the tile focused last (restored at the next open). */
    private static final String KEY_LAST_TILE = "last_tile";

    /** Picture modes in stock order (FEATURE_SPEC 3.2). */
    private static final GmpfClient.PictureMode[] PM_ORDER = {
            GmpfClient.PictureMode.AI, GmpfClient.PictureMode.STANDARD, GmpfClient.PictureMode.SPORT,
            GmpfClient.PictureMode.HDR_VIVID, GmpfClient.PictureMode.COLOR_ACCURACY,
            GmpfClient.PictureMode.OFFICE, GmpfClient.PictureMode.PERFORMANCE};
    /** v6.4: AI sound first (per-app sub-mode, AiSoundEngine), then the real amp tables (no Standard = mode err). */
    private static final GmpfClient.SoundEffect[] SOUND_ORDER = SoundProfiles.MODES;
    /** v6.4 equalizer presets in EqCurve.PRESET_* order (Custom last). */
    private static final int[] EQ_PRESET_LABELS = {R.string.eq_preset_flat, R.string.eq_preset_bass,
            R.string.eq_preset_voice, R.string.eq_preset_night, R.string.eq_preset_custom};
    private static final long EQ_LIMIT_NOTE_GAP_MS = 2_000;
    /** Game speed rows -> 648 values (0 basic, 1 basic+HFR, 3 top speed, 2 top speed+HFR). */
    private static final GmpfClient.GameModeOption[] SPEED_ORDER = {
            GmpfClient.GameModeOption.BASIC, GmpfClient.GameModeOption.BASIC_HFR,
            GmpfClient.GameModeOption.TOP_SPEED, GmpfClient.GameModeOption.TOP_SPEED_HFR};
    private static final GmpfClient.AspectRatio[] ASPECT_ORDER = {
            GmpfClient.AspectRatio.AUTO, GmpfClient.AspectRatio.R16_9, GmpfClient.AspectRatio.R4_3,
            GmpfClient.AspectRatio.ORIGINAL};
    private static final GmpfClient.MemcLevel[] MEMC_ORDER = GmpfClient.MemcLevel.values();
    private static final GmpfClient.ColorTemp[] CT_ORDER = GmpfClient.ColorTemp.values();
    private static final GmpfClient.PqItem[] PQ_ORDER = GmpfClient.PqItem.values();

    private final Context app;
    private final SharedPreferences prefs;
    private QuickPanelView panel;
    private Snapshot snap;
    private int refreshGen;
    /** Section pages by QuickPanel.SECTION_* key (SECTION_INPUT has none). */
    private final Map<String, Page> pages = new HashMap<>();

    // root page: info line + tile grid
    private final Page root;
    private TextRow info;
    private TileGrid grid;
    /** Index of the grid in the root page's rows (PagedPanel focus restore). */
    private int gridRow;

    // section rows
    private ChoiceRow pictureMode;
    private SliderRow lamp;
    private ToggleRow ultra120;
    private ChoiceRow gameMode, gameSpeed, allm, aspect, soundMode, soundEnh, launcherRow;
    private TextRow gameHint, soundHint;
    // v6.4 equalizer page (pushed from the Sound page; not a tile section)
    private Page eqPage;
    private NavRow eqNav;
    private TextRow eqHint;
    private ChoiceRow eqPreset;
    private EqBandsRow eqBands;
    private NavRow eqReset;
    /** The sound mode (62) the equalizer rows show and edit; -1 = none (rows disabled). */
    private int eqMode = -1;
    private long eqLimitNoteAt;
    private ToggleRow eye;
    private GmpfClient.PictureMode[] pmOptions = PM_ORDER;
    private ToggleRow rtKst, powerOnKst, curtain, obstacle, moveAf, powerOnAf, smartAf;
    private CheckRow pAuto, pTable, pCeiling;
    private ToggleRow pRear;
    private TextRow picHint;
    private final SliderRow[] pq = new SliderRow[PQ_ORDER.length];
    private ChoiceRow colorTemp, memc;
    private ToggleRow aiContrast, superRes;
    private ProPicture pro;
    // Lumen OS 1.0.4: HDMI settings of the input on screen (Picture page, HDMI only), sound extras
    private HeaderRow hdmiHeader;
    private ChoiceRow hdmiRange, hdmiMode;
    private ToggleRow volumeLevel, earc;
    // 1.0.4: manual sound output and the digital audio format (stock voice_output / voice_arc_format_auto_sound_v5)
    private ChoiceRow soundOut, digitalFormat;
    private java.util.List<org.lumen.projector.audio.AudioOutputChoice.Option> outOptions;
    /** The "Enhanced" EDID value this projector uses (1 = HDMI 2.0, or 3 once 661 has read 3). */
    private static final String KEY_EDID_ENHANCED = "edid_enhanced";

    // Boost two-press state
    private boolean boostOn;
    private int boostArmDir;
    private long boostArmAt;

    QuickPanelController(Context app) {
        this.app = app.getApplicationContext();
        this.prefs = this.app.getSharedPreferences(QuickPanel.PREFS, Context.MODE_PRIVATE);
        buildFocusPage();
        buildKeystonePage();
        buildLampPage();
        buildPicturePage();
        buildSoundPage();
        buildProjectionPage();
        buildGamePage();
        buildEyePage();
        buildSettingsPage();
        root = buildRoot();
        String last = prefs.getString(KEY_LAST_TILE, null);
        // the v6.3 "Manual keystone" tile is part of the Keystone tile now
        if (QuickPanel.SECTION_MANUAL_KEYSTONE.equals(last)) last = QuickPanel.SECTION_KEYSTONE;
        if (last != null) grid.remember(last);
        for (Map.Entry<String, Page> e : pages.entrySet()) {
            for (QuickPanel.Extension x : QuickPanel.extensions(e.getKey())) addRows(e.getKey(), e.getValue(), x);
        }
    }

    // ================================================================== show / toggle
    void toggle() {
        if (panel != null && panel.isShowing() && OverlayHost.get(app).current() == panel) {
            panel.dismiss();
            return;
        }
        show(null);
    }

    void show(String section) {
        String key = QuickPanel.SECTION_PROJECTION.equals(section) ? QuickPanel.SECTION_KEYSTONE : section;
        if (QuickPanel.SECTION_INPUT.equals(key)) {
            if (setupDone("input")) SourceOverlay.toggle(app);
            return;
        }
        if (QuickPanel.SECTION_MANUAL_KEYSTONE.equals(key)) {
            grid.remember(QuickPanel.SECTION_KEYSTONE);      // its entry in the panel is the Keystone page
            ManualKeystonePanel.open(app, null);             // not from the panel: BACK just closes
            return;
        }
        Page target = key == null ? null : pages.get(key);
        if (target != null) {
            grid.remember(key);                              // BACK from the page lands on its tile
            target.setInitialFocus(-1);
        }
        OverlayHost host = OverlayHost.get(app);
        if (panel != null && panel.isShowing() && host.current() == panel) {
            while (panel.pop()) { /* back to the tile grid */ }
            if (target != null) panel.push(target);
            panel.resetAutoHide();
            return;
        }
        QuickPanelView v = new QuickPanelView(root, this);   // fresh page stack each open; rows are reused
        if (target != null) v.push(target);                  // before the window exists: no slide
        root.setInitialFocus(gridRow);
        grid.settle();
        panel = v;
        if (!host.show(v)) Log.w(TAG, "quick panel window could not be added");
    }

    /** Every key of the quick panel window, before the rows (QuickPanelView.onKeyEvent). */
    boolean onKey(KeyEvent ev) {
        // v6.4: BACK while an equalizer band is being adjusted only ends adjusting.
        if (eqBands != null && eqBands.isEditing()
                && (ev.getKeyCode() == KeyEvent.KEYCODE_BACK || ev.getKeyCode() == KeyEvent.KEYCODE_ESCAPE)) {
            if (ev.getAction() == KeyEvent.ACTION_UP && !ev.isCanceled()) eqBands.stopEditing();
            return true;
        }
        // Leaving Boost: at "Boost" a LEFT press arms, a second LEFT within 3 s turns it off (FS 3.1).
        // "Towards the start" is RIGHT in RTL (the slider is mirrored, Row.onKeyDown).
        int towardsStart = lamp != null && lamp.isRtl() ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        if (boostOn && lamp != null && lamp.isFocused() && ev.getKeyCode() == towardsStart) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) boostPress(-1);
            return true;
        }
        return false;
    }

    /** Locale change: close the quick panel if it is the panel shown (QuickPanel drops us). */
    void close() {
        if (panel != null && panel.isShowing() && OverlayHost.get(app).current() == panel) panel.dismiss();
    }

    void onPanelClosed() {
        boostArmDir = 0;
        grid.settle();
        String last = grid.lastSection();
        if (last != null && !last.equals(prefs.getString(KEY_LAST_TILE, null))) {
            prefs.edit().putString(KEY_LAST_TILE, last).apply();
        }
    }

    /** Open animation of the page shown first (QuickPanelView.onShown). */
    void animateIn(Page page) {
        if (page == root) {
            info.setAlpha(0f);
            info.animate().alpha(1f).setStartDelay(60).setDuration(200).start();
            grid.staggerIn(40);
            return;
        }
        // A section page opened directly (KEYSTONE key): rows slide in, 16 ms apart. Translation
        // only: disabled rows carry alpha 0.4 (Row.setRowEnabled).
        float dx = Theme.pxf(app, 36);
        List<View> rows = page.rows();
        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            r.setTranslationX(dx);
            r.animate().translationX(0f).setStartDelay(i * Theme.ROW_STAGGER_MS).setDuration(220)
                    .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        }
    }

    // ================================================================== tiles, pages, extensions
    private void addTile(String section, int icon, int label, boolean wide) {
        grid.addTile(new Tile(app, section, icon, s(label), wide, () -> onTile(section)));
    }

    /** Tile OK (after its press animation). */
    private void onTile(String section) {
        if (panel == null || !panel.isShowing()) return;     // closed during the press
        grid.remember(section);
        if (QuickPanel.SECTION_INPUT.equals(section)) {
            // replaces the quick panel; BACK there returns to this grid (Input tile remembered)
            if (setupDone("input")) SourceOverlay.showFromQuickPanel(app);
            return;
        }
        Page p = pages.get(section);
        if (p == null) return;
        grid.settle();                                       // a stagger still running ends now
        p.setInitialFocus(-1);                               // a page always opens at its first row
        panel.push(p);
    }

    /**
     * v6.3: opens the manual keystone overlay (it replaces the quick panel). BACK there saves and comes
     * back here: to {@code backToSection}'s page, or to the tile grid when null.
     */
    private void openManualKeystone(String backToSection) {
        ManualKeystonePanel.open(app, () -> show(backToSection));
    }

    private Page page(String key, int titleRes) {
        Page p = new Page(s(titleRes));
        pages.put(key, p);
        p.setOnShown(() -> onPageShown(key));
        return p;
    }

    private void onPageShown(String key) {
        refresh();
        for (QuickPanel.Extension x : QuickPanel.extensions(key)) {
            try {
                x.onPageShown();
            } catch (Throwable t) {
                Log.e(TAG, "extension onPageShown " + key, t);
            }
        }
    }

    /** QuickPanel.addExtension after this controller was built (main thread). */
    void addExtension(String key, QuickPanel.Extension x) {
        Page p = pages.get(key);
        if (p == null) {
            Log.w(TAG, "extension for unknown section " + key + " ignored");
            return;
        }
        addRows(key, p, x);
        if (panel != null && panel.isShowing() && panel.currentPage() == p) panel.refreshPage();
    }

    private void addRows(String key, Page p, QuickPanel.Extension x) {
        try {
            x.addRows(app, p);
        } catch (Throwable t) {
            Log.e(TAG, "extension addRows " + key, t);
        }
    }

    // ================================================================== helpers
    private String s(int res) { return app.getString(res); }

    private static Runnable safe(String what, Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable t) {
                Log.e(TAG, what + " failed", t);
            }
        };
    }

    private static CharSequence[] labels(Context c, int... res) {
        CharSequence[] out = new CharSequence[res.length];
        for (int i = 0; i < res.length; i++) out[i] = c.getString(res[i]);
        return out;
    }

    // ================================================================== HAL helpers
    private boolean ready() {
        if (Hal.isReady()) return true;
        Ui.toast(app, R.string.toast_not_ready);
        refresh();
        return false;
    }

    /** User action: run {@code t} on the HAL thread (failure -> "Could not do that"), then re-read. */
    private void change(String what, Hal.Task t) {
        if (!ready()) return;
        boolean queued = Hal.run((g, g2) -> {
            try {
                t.run(g, g2);
                Log.i(TAG, what);
            } catch (Throwable e) {
                Log.w(TAG, what + ": " + e);
                Ui.toast(app, R.string.toast_failed);
            }
        });
        if (!queued) Ui.toast(app, R.string.toast_not_ready);
        refresh();
    }

    /**
     * HAL thread, before any HDMI-only setter (648, 207, IGmpf2 56/57/312, 665): true when IGmpf 696
     * reports HDMI 1/2 right now, or org.lumen.tvinput reports an open HDMI session (696 during TIF
     * playback is UNVERIFIED). Otherwise shows "HDMI only" and returns false.
     */
    private boolean hdmiNow(GmpfClient g, String what) {
        int src = -1;
        try { src = g.getCurrentInputSource(); } catch (Throwable t) { Log.w(TAG, what + ": 696 " + t); }
        if (src == GmpfClient.SOURCE_HDMI1 || src == GmpfClient.SOURCE_HDMI2) return true;
        Boolean tif = TifHdmiState.read(app);
        if (Boolean.TRUE.equals(tif)) return true;
        Log.i(TAG, what + " refused: not on HDMI (696=" + src + ", tvinput session=" + tif + ")");
        Notify.show(app, s(R.string.panel_game_hdmi_only));
        return false;
    }

    /** QuickPanel.refreshIfShowing: only while the quick panel is the panel on screen. */
    void refreshIfShowing() {
        if (panel != null && panel.isShowing() && OverlayHost.get(app).current() == panel) refresh();
    }

    /** Re-reads every value (one HAL task) and updates the rows on the main thread. */
    void refresh() {
        final int gen = ++refreshGen;
        Hal.query((g, g2) -> {
            Snapshot sn = Snapshot.read(g, g2);
            sn.tifHdmi = TifHdmiState.read(app);          // org.lumen.tvinput: HDMI session open?
            sn.soundPathActive = AudioPathReporter.isActive(g);   // DTS runs only with the path reported
            sn.soundPath = AudioPathReporter.confirmedPath();
            return sn;
        }, v -> {
            if (gen != refreshGen) return;                       // a newer read is on its way
            apply(Hal.isReady() ? v : null);
        });
    }

    /** Confirmation inside the panel (DialogPanel.confirmPage); focus starts on Cancel. */
    private void confirm(int titleRes, CharSequence msg, int okRes, Runnable onOk) {
        if (panel == null || !panel.isShowing()) return;
        panel.push(DialogPanel.confirmPage(app, panel, s(titleRes), msg, s(okRes), s(R.string.cancel),
                safe("confirm", onOk)));
    }

    private void closeThen(Runnable r) {
        if (panel != null) panel.dismiss();
        Ui.main().postDelayed(safe("after close", r), Theme.PANEL_EXIT_MS + 60);
    }

    // ================================================================== root: the tile grid
    private Page buildRoot() {
        Page p = new Page(s(R.string.panel_title));
        info = p.add(new TextRow(app, ""));
        grid = p.add(new TileGrid(app));
        gridRow = p.rows().indexOf(grid);
        // V62_REQUIREMENTS 3 order: focus, keystone, brightness, picture, sound, input, projection
        // mode, game mode, eye protection, all settings (wide, last row).
        addTile(QuickPanel.SECTION_FOCUS, R.drawable.ic_tile_focus, R.string.panel_tile_focus, false);
        // Lumen OS 1.0: ONE Keystone tile (auto and manual correction inside); the v6.3 separate
        // "Manual keystone" tile duplicated it (owner's decision 2026-10-08).
        addTile(QuickPanel.SECTION_KEYSTONE, R.drawable.ic_tile_keystone, R.string.panel_tile_keystone, false);
        addTile(QuickPanel.SECTION_LAMP, R.drawable.ic_tile_brightness, R.string.panel_lamp, false);
        addTile(QuickPanel.SECTION_PICTURE, R.drawable.ic_tile_picture, R.string.panel_sec_picture, false);
        addTile(QuickPanel.SECTION_SOUND, R.drawable.ic_tile_sound, R.string.panel_sec_sound, false);
        // v6.2 translate phase: panel_tile_input/_game/_eye = the section names, shortened only in
        // the languages where the full name would ellipsize on a tile (ru/uk/be/fr/es/it).
        addTile(QuickPanel.SECTION_INPUT, R.drawable.ic_tile_input, R.string.panel_tile_input, false);
        addTile(QuickPanel.SECTION_PROJECTION_MODE, R.drawable.ic_tile_projection, R.string.panel_tile_projection, false);
        addTile(QuickPanel.SECTION_GAME, R.drawable.ic_tile_game, R.string.panel_tile_game, false);
        addTile(QuickPanel.SECTION_EYE, R.drawable.ic_tile_eye, R.string.panel_tile_eye, false);
        addTile(QuickPanel.SECTION_GENERAL, R.drawable.ic_tile_settings, R.string.panel_all_settings, true);
        p.setInitialFocus(gridRow);
        p.setOnShown(this::refresh);
        return p;
    }

    // ================================================================== focus page (FEATURE_SPEC 2.7)
    private void buildFocusPage() {
        Page p = page(QuickPanel.SECTION_FOCUS, R.string.panel_tile_focus);
        p.add(new NavRow(app, s(R.string.panel_af_now),
                () -> closeThen(() -> Hal.requestAutofocus(app))).setChevron(false));
        p.add(new NavRow(app, s(R.string.panel_manual_focus),
                () -> closeThen(() -> Hal.openManualFocus(app))));
        // 1.0.8 (parity focus item 4): stock order (Autofocus, Manual focus, Smart autofocus), then our two extra
        // rows. Stock's one smart AF switch sets IGmpf2 91 setPowerOnAFUseDistance AND IGmpf 587
        // setDisplacementFocus (settings FocusRepository.java:166-184), so it also turns "AF after moving" on / off.
        smartAf = p.add(new ToggleRow(app, s(R.string.panel_smart_af), (row, w) -> safe("g2 91", () -> {
            org.lumen.projector.power.StandbyController.noteUserMoveAf();
            change("IGmpf2 91 + 587 smart AF " + w, (g, g2) -> {
                g2.setSmartAutofocus(w);
                g.setMoveAf(w);
            });
        }).run()));
        moveAf = p.add(new ToggleRow(app, s(R.string.panel_move_af), (row, w) -> safe("587", () -> {
            org.lumen.projector.power.StandbyController.noteUserMoveAf();
            change("587 AF after moving " + w, (g, g2) -> g.setMoveAf(w));
        }).run()));
        powerOnAf = p.add(new ToggleRow(app, s(R.string.panel_power_on_af), (row, w) -> safe("302", () ->
                change("302 AF at power-on " + w, (g, g2) -> g.setPowerOnAf(w))).run()));
    }

    // ================================================================== keystone page (FEATURE_SPEC 2.1-2.5)
    private void buildKeystonePage() {
        Page p = page(QuickPanel.SECTION_KEYSTONE, R.string.panel_tile_keystone);
        p.add(new NavRow(app, s(R.string.panel_ak_now),
                () -> closeThen(() -> Hal.requestKeystone(app, false))).setChevron(false));
        p.add(new NavRow(app, s(R.string.panel_fit_now),
                () -> closeThen(() -> Hal.requestKeystone(app, true))).setChevron(false));
        // v6.3: no snapshot pre-check here; ManualKeystonePanel checks everything on the HAL thread
        // and says exactly why it refuses (a stale 647 read used to block it silently on Android).
        p.add(new NavRow(app, s(R.string.panel_manual_keystone), safe("manual keystone",
                () -> openManualKeystone(QuickPanel.SECTION_KEYSTONE))));
        // Lumen OS 1.0.2: the zoom lens (OpticalZoomPanel replaces the panel; BACK comes back here). Back in 1.0.4
        // (owner, 2026-10-14: the optical zoom is what he wants, the digital ZoomShiftPanel is not shown).
        p.add(new NavRow(app, s(R.string.panel_optical_zoom), safe("optical zoom",
                () -> OpticalZoomPanel.open(app, () -> show(QuickPanel.SECTION_KEYSTONE)))));
        p.add(new TextRow(app, s(R.string.panel_optical_zoom_summary)));
        // Fit to my screen size (docs/fit_screen/DESIGN.md): only when this build supports it, never a disabled row
        // (Z9X: shown after the owner decisions O1 / O2 of 2026-10-30, hidden by the owner 2026-10-31: Z9xFitDevice
        // SUPPORTED = false, so this row and its summary are not built)
        if (org.lumen.projector.fit.FitDevices.supported(app)) {
            p.add(new NavRow(app, s(R.string.fit_row), safe("fit to screen", () -> {
                if (snap != null && snap.isTopSpeedGame()) { Notify.show(app, s(R.string.panel_kst_game_mode)); return; }
                push(fitPage());
            })));
            p.add(new TextRow(app, s(R.string.fit_row_summary)));
        }
        // Lumen OS 1.0.4: picture rotation (stock RotateWind)
        p.add(new NavRow(app, s(R.string.rot_title), safe("rotation", () -> {
            if (snap != null && snap.isTopSpeedGame()) { Notify.show(app, s(R.string.panel_kst_game_mode)); return; }
            RotatePanel.open(app, () -> show(QuickPanel.SECTION_KEYSTONE));        // replaces the quick panel
        })));
        p.add(new HeaderRow(app, s(R.string.panel_image_correction_settings)));
        rtKst = p.add(new ToggleRow(app, s(R.string.panel_rt_keystone), (row, w) -> safe("585", () ->
                change("585 real-time keystone " + w, (g, g2) -> {
                    if (!Hal.setMoveAkChecked(g, w)) Notify.show(app, s(R.string.toast_rt_kst_ceiling));
                })).run()));
        powerOnKst = p.add(new ToggleRow(app, s(R.string.panel_power_on_keystone), (row, w) -> safe("552", () ->
                change("552 power-on keystone " + w, (g, g2) -> g.setPowerOnAk(w))).run()));
        // FEATURE_SPEC 2.3: fit to screen ON also turns obstacle avoidance on; obstacle avoidance
        // OFF also turns fit to screen off (mirrors the stock UI). Shared with SettingsActivity.
        curtain = p.add(new ToggleRow(app, s(R.string.panel_fit_screen), (row, w) -> safe("290", () ->
                change("290 fit to screen " + w, (g, g2) -> Hal.setCurtainFitCoupled(g, w))).run()));
        obstacle = p.add(new ToggleRow(app, s(R.string.panel_obstacle), (row, w) -> safe("292", () ->
                change("292 obstacle avoidance " + w, (g, g2) -> Hal.setObstacleAvoidCoupled(g, w))).run()));
        // 2026-10-30 (stock HB videos, the same KeyStoneManager on the Z9X, keystone.md items 7 and 8): the last
        // section is the stock title "More" (brightness_more_title) with "Restore default picture" (reset_key_stone),
        // the full stock reset with the lens home (resetKeystone). No confirmation, as stock KeyStoneViewModel
        // RESET_KEYSTONE (:555-559, the reset at once, then keystone_reset_toast). The corners-only reset is the
        // manual keystone screen's (hold OK). Z9X-only strings (strings_kst_restore.xml): the shared panel_reset_keystone
        // of strings_panel.xml stays as it is for the Play 6.
        p.add(new HeaderRow(app, s(R.string.kst_more)));
        p.add(new NavRow(app, s(R.string.kst_restore), safe("restore picture", () -> {
            if (snap != null && snap.isTopSpeedGame()) { Notify.show(app, s(R.string.panel_kst_game_mode)); return; }
            resetKeystone();
        })).setChevron(false));
    }

    // ================================================================== brightness page (FEATURE_SPEC 3.1)
    private void buildLampPage() {
        Page p = page(QuickPanel.SECTION_LAMP, R.string.panel_lamp);
        lamp = p.add(new SliderRow(app, s(R.string.panel_lamp), 1, 10, new SliderRow.OnSlide() {
            @Override public void onChanging(SliderRow row, int value) { touch(row); }
            @Override public void onCommit(SliderRow row, int value) {
                safe("lamp", () -> {
                    if (boostOn || value > 10 || value < 1) return;
                    change("176 lamp " + value, (g, g2) -> g.setLampLevel(value));
                }).run();
            }
        }));
        lamp.setFormatter(v -> v >= BOOST_LEVEL ? s(R.string.panel_boost) : Integer.toString(v));
        lamp.setEdgeListener((row, dir) -> {
            if (dir > 0 && !boostOn) safe("boost", () -> boostPress(1)).run();
            return true;
        });
        p.add(new TextRow(app, s(R.string.panel_lamp_hint)));
        // 1.0.8 (parity brightness item 9): what Boost gives and costs, as the stock boost_title / boost_close_desc
        p.add(new TextRow(app, s(R.string.boost_effect)));
    }

    // ================================================================== picture page (FEATURE_SPEC 3.2-3.5)
    private void buildPicturePage() {
        Page p = page(QuickPanel.SECTION_PICTURE, R.string.panel_sec_picture);
        pictureMode = p.add(new ChoiceRow(app, s(R.string.panel_picture_mode), pmLabels(PM_ORDER),
                (row, i) -> safe("picture mode", () -> onPictureModeChosen(i)).run()));
        pictureMode.setCommitDelay(PICTURE_MODE_COMMIT_MS);
        pictureMode.setOnOpen(safe("picture mode list", this::openPictureModeList));
        picHint = p.add(new TextRow(app, ""));
        picHint.setVisibility(View.GONE);
        int[] titles = {R.string.panel_pq_brightness, R.string.panel_pq_contrast, R.string.panel_pq_saturation,
                R.string.panel_pq_sharpness};
        for (int i = 0; i < PQ_ORDER.length && i < titles.length; i++) {
            final GmpfClient.PqItem item = PQ_ORDER[i];
            pq[i] = p.add(new SliderRow(app, s(titles[i]), 0, 100, new SliderRow.OnSlide() {
                @Override public void onChanging(SliderRow row, int value) { touch(row); }
                @Override public void onCommit(SliderRow row, int value) {
                    safe("pq", () -> change("pq " + item + " " + value, (g, g2) -> g.setPq(item, value))).run();
                }
            }));
        }
        colorTemp = p.add(new ChoiceRow(app, s(R.string.panel_color_temp),
                labels(app, R.string.panel_ct_d65, R.string.panel_ct_1),
                (row, i) -> safe("color temp", () -> {
                    if (snap == null || snap.colorTemp == null || i < 0 || i >= CT_ORDER.length) { refresh(); return; }
                    final GmpfClient.ColorTemp c = CT_ORDER[i];
                    // 174; the lumens mode carries the lamp level, the refresh re-reads 177 (QS).
                    change("174 color temp " + c, (g, g2) -> g.setColorTemp(c));
                }).run()));
        colorTemp.setCommitDelay(CHOICE_COMMIT_MS);
        memc = p.add(new ChoiceRow(app, s(R.string.panel_memc),
                labels(app, R.string.panel_off, R.string.panel_low, R.string.panel_medium, R.string.panel_high),
                (row, i) -> safe("memc", () -> {
                    if (snap == null || snap.memc == null || i < 0 || i >= MEMC_ORDER.length) { refresh(); return; }
                    if (Boolean.TRUE.equals(snap.nativeFps)) {            // stock: original frame rate blocks MEMC
                        Notify.show(app, s(R.string.panel_memc), s(R.string.pro_memc_blocked));
                        refresh();
                        return;
                    }
                    final GmpfClient.MemcLevel l = MEMC_ORDER[i];
                    change("669 memc " + l, (g, g2) -> g.setMemcLevel(l));
                }).run()));
        memc.setCommitDelay(CHOICE_COMMIT_MS);
        aiContrast = p.add(new ToggleRow(app, s(R.string.panel_ai_contrast),
                (row, wanted) -> safe("ai contrast", () ->
                        change("IGmpf2 288 ai contrast " + wanted, (g, g2) -> g2.setAiContrast(wanted))).run()));
        superRes = p.add(new ToggleRow(app, s(R.string.panel_super_resolution),
                (row, wanted) -> safe("super resolution", () ->
                        change("IGmpf2 322 super resolution " + wanted, (g, g2) -> g2.setSuperResolution(wanted))).run()));
        ultra120 = p.add(new ToggleRow(app, s(R.string.panel_ultra120),
                (row, wanted) -> safe("ultra 120", () -> onUltra120(row, wanted)).run()));
        // Lumen OS 1.0.4: the stock "Professional settings" (ProPicture: white balance, colour, gamma, contrast,
        // original frame rate, noise reduction), its own pages
        pro = new ProPicture(app, prefs, new ProPicture.Host() {
            @Override public void push(Page pg) { QuickPanelController.this.push(pg); }
            @Override public void confirm(int titleRes, CharSequence msg, int okRes, Runnable onOk) {
                QuickPanelController.this.confirm(titleRes, msg, okRes, onOk);
            }
        });
        p.add(new NavRow(app, s(R.string.pro_title), safe("pro page", () -> push(pro.page))));
        p.add(new NavRow(app, s(R.string.panel_picture_reset), safe("picture reset", () ->
                confirm(R.string.panel_picture_reset, s(R.string.panel_picture_reset_msg), R.string.panel_reset,
                        () -> change("IGmpf2 278 reset basic",
                                (g, g2) -> g2.resetPicture(Gmpf2Client.PictureReset.BASIC)))))).setChevron(false);
        // 1.0.8 (parity picture item 34): stock "Reset all picture settings" (reset_image_mode_all_setting in
        // Brightness and display): a confirmation, then ResetPictureToDefault(0, -8, 0) = IGmpf2 278 and a re-read
        // (PictureViewModel.java:2788-2813). What exactly the vendor resets is logged by the read-back.
        p.add(new NavRow(app, s(R.string.picture_reset_all), safe("picture reset all", () ->
                confirm(R.string.picture_reset_all, s(R.string.picture_reset_all_msg), R.string.panel_reset,
                        () -> change("IGmpf2 278 reset all", (g, g2) -> {
                            if (!g2.resetPicture(Gmpf2Client.PictureReset.ALL)) throw new IllegalStateException("278 returned false");
                        }))))).setChevron(false);
        // Lumen OS 1.0.4: the stock HDMI settings of the input on screen (SignalRepository), shown on HDMI only
        hdmiHeader = p.add(new HeaderRow(app, "HDMI"));
        final GmpfClient.HdmiRange[] ranges = GmpfClient.HdmiRange.values();
        hdmiRange = p.add(new ChoiceRow(app, s(R.string.hdmi_range),
                labels(app, R.string.panel_auto, R.string.hdmi_range_full, R.string.hdmi_range_limited),
                (row, i) -> safe("hdmi range", () -> {
                    if (i < 0 || i >= ranges.length) return;
                    final GmpfClient.HdmiRange r = ranges[i];
                    change("635 hdmi range " + r, (g, g2) -> {
                        if (!hdmiNow(g, "635")) return;
                        if (!g.setHdmiRange(r)) throw new IllegalStateException("635 returned false");
                    });
                }).run()));
        hdmiRange.setCommitDelay(CHOICE_COMMIT_MS);
        hdmiMode = p.add(new ChoiceRow(app, s(R.string.hdmi_mode),
                labels(app, R.string.hdmi_mode_compat, R.string.hdmi_mode_enhanced),
                (row, i) -> safe("hdmi mode", () -> {
                    if (i < 0 || i > 1) return;
                    final int wire = i == 0 ? GmpfClient.EDID_14 : prefs.getInt(KEY_EDID_ENHANCED, GmpfClient.EDID_20);
                    final CharSequence title = row.getTitle();
                    change("660 edid " + wire, (g, g2) -> {
                        if (!hdmiNow(g, "660")) return;
                        if (!g.setEdidVersion(wire)) throw new IllegalStateException("660 returned false");
                        Notify.show(app, title, s(R.string.hdmi_mode_note));
                    });
                }).run()));
        hdmiMode.setCommitDelay(CHOICE_COMMIT_MS);
        // 1.0.8: aspect ratio of the HDMI input on screen (was on the Game page); stock SettingMutexManager refuses it
        // while game mode runs (parity game item 2.15)
        aspect = p.add(new ChoiceRow(app, s(R.string.panel_aspect),
                labels(app, R.string.panel_auto, R.string.panel_aspect_16_9, R.string.panel_aspect_4_3,
                        R.string.panel_aspect_original),
                (row, i) -> safe("aspect", () -> {
                    if (snap == null || snap.aspect == null || i < 0 || i >= ASPECT_ORDER.length) { refresh(); return; }
                    if (gameModeOn(snap)) {
                        Notify.show(app, row.getTitle(), s(R.string.blocked_game));
                        refresh();
                        return;
                    }
                    final GmpfClient.AspectRatio a = ASPECT_ORDER[i];
                    change("665 aspect " + a, (g, g2) -> {
                        if (hdmiNow(g, "665")) g.setAspectRatio(a);
                    });
                }).run()));
        aspect.setCommitDelay(CHOICE_COMMIT_MS);
        aspect.setVisibility(View.GONE);
    }

    // ================================================================== sound page (FEATURE_SPEC 4, v6.3 profiles)
    /**
     * v6.3.1 (research/v63/ampeq, SoundProfiles): "Sound enhancement" = Harman Kardon / DTS
     * Virtual:X, first; then a hint line (DTS stored but not running: output not reported yet or not
     * the speaker; or "state unknown, choose to re-apply" while 43 is on or a getter failed); then
     * "Sound mode" = Movie / Music / Sports / Karaoke.
     */
    private void buildSoundPage() {
        Page p = page(QuickPanel.SECTION_SOUND, R.string.panel_sec_sound);
        final CharSequence[] enhLabels = labels(app, R.string.panel_harman, R.string.panel_dts_vx);
        soundEnh = p.add(new ChoiceRow(app, s(R.string.panel_sound_enhancement), enhLabels,
                (row, i) -> safe("sound enhancement", () -> {
                    if (snap == null || snap.soundProcess == null || i < 0 || i >= SoundProfiles.ORDER.length) { refresh(); return; }
                    final SoundProfiles.Profile prof = SoundProfiles.ORDER[i];
                    final CharSequence title = row.getTitle();
                    final CharSequence label = enhLabels[i];
                    change("sound profile " + prof, (g, g2) -> {
                        SoundProfiles.Result r = SoundProfiles.apply(app, g, prof);
                        if (r == SoundProfiles.Result.APPLIED) {
                            Notify.show(app, title, label);
                        } else if (r == SoundProfiles.Result.SPEAKER_ONLY) {
                            Notify.show(app, title, app.getString(R.string.sound_speaker_only, label));
                        } else if (r == SoundProfiles.Result.OUTPUT_NOT_READY) {
                            Notify.show(app, title, app.getString(R.string.sound_output_not_ready, label));
                        } else {
                            Log.w(TAG, "sound profile " + prof + ": read-back differs");
                            Notify.show(app, title, s(R.string.sound_not_confirmed));
                        }
                    });
                }).run()));
        soundEnh.setCommitDelay(CHOICE_COMMIT_MS);
        soundHint = p.add(new TextRow(app, ""));
        soundHint.setVisibility(View.GONE);
        final CharSequence[] soundLabels = soundLabels();
        soundMode = p.add(new ChoiceRow(app, s(R.string.panel_sound_mode), soundLabels,
                (row, i) -> safe("sound mode", () -> {
                    if (snap == null || snap.soundEffect == null || i < 0 || i >= SOUND_ORDER.length) { refresh(); return; }
                    final GmpfClient.SoundEffect e = SOUND_ORDER[i];
                    final CharSequence title = row.getTitle();
                    change("61 sound " + e, (g, g2) -> {
                        if (e == GmpfClient.SoundEffect.AI) {
                            // property = foreground category, then 61(3); the sub-mode follows the app
                            AiSoundEngine.userChoseAi(g);
                        } else {
                            g.setSoundEffect(e);                              // 61
                            AiSoundEngine.noteMode(e.wire, "user");
                        }
                        SoundProfiles.rememberMode(app, e);
                        // a user mode change rewrites this mode's curve in full (repairs a PEQ the
                        // vendor turned off unseen; the 61 table switch already mutes the amp briefly)
                        SoundEq.afterUserModeChange(g, "61(" + e.wire + ")");
                        Notify.show(app, title + ": " + soundLabel(e));
                    });
                }).run()));
        soundMode.setCommitDelay(CHOICE_COMMIT_MS);
        eqNav = p.add(new NavRow(app, s(R.string.eq_title), safe("equalizer", () -> {
            if (panel == null || !panel.isShowing() || eqPage == null) return;
            eqPage.setInitialFocus(-1);
            panel.push(eqPage);
        })));
        buildEqPage();
        // Lumen OS 1.0.4: the stock "Volume balance" (IGmpf 42) as a switch; sound profiles keep the choice
        volumeLevel = p.add(new ToggleRow(app, s(R.string.sound_volume_leveling), (row, w) -> safe("42", () -> {
            prefs.edit().putBoolean(SoundProfiles.KEY_VOLUME_LEVELING, w).apply();
            change("42 volume leveling " + w, (g, g2) -> {
                boolean ok = g.setVolumeBalance(w);
                SoundEq.afterVendorCall(g, "42(" + w + ")");          // the PEQ of AMP mode, as after 27
                if (!ok) throw new IllegalStateException("42 returned false");
            });
        }).run()));
        // Lumen OS 1.0.4: the stock eARC mode (Auto = on, Off), only where 632 answers
        earc = p.add(new ToggleRow(app, "eARC", (row, w) -> safe("633", () ->
                change("633 eARC " + w, (g, g2) -> {
                    if (!g.setEarc(w)) throw new IllegalStateException("633 returned false");
                })).run()));
        // 1.0.4: manual sound output (stock Sound > Sound output). Only connected outputs are listed; the choice is a
        // preferred device of the media strategy, so AudioPathReporter moves the vendor path (HDMI input sound) too.
        soundOut = p.add(new ChoiceRow(app, s(R.string.sound_output),
                labels(app, R.string.sound_output_auto, R.string.sound_output_speaker),
                (row, i) -> safe("sound output", () -> {
                    java.util.List<org.lumen.projector.audio.AudioOutputChoice.Option> o = outOptions;
                    if (o == null || i < 0 || i >= o.size()) { refresh(); return; }
                    org.lumen.projector.audio.AudioOutputChoice.Option opt = o.get(i);
                    org.lumen.projector.audio.AudioOutputChoice.choose(app, opt.kind, opt.addr);
                    Notify.show(app, row.getTitle(), opt.label);
                }).run()));
        soundOut.setCommitDelay(CHOICE_COMMIT_MS);
        // stock "Audio output format" (digital_output_format): only while an ARC / eARC output is connected
        final CharSequence[] fmtLabels = labels(app, R.string.sound_format_auto, R.string.sound_format_pcm,
                R.string.sound_format_passthrough);
        digitalFormat = p.add(new ChoiceRow(app, s(R.string.sound_format), fmtLabels,
                (row, i) -> safe("digital format", () -> {
                    org.lumen.projector.audio.AudioOutputChoice.Format[] f =
                            org.lumen.projector.audio.AudioOutputChoice.Format.values();
                    if (i < 0 || i >= f.length) { refresh(); return; }
                    org.lumen.projector.audio.AudioOutputChoice.chooseFormat(app, f[i]);
                    Notify.show(app, row.getTitle(), fmtLabels[i]);
                }).run()));
        digitalFormat.setCommitDelay(CHOICE_COMMIT_MS);
        digitalFormat.setVisibility(View.GONE);
    }

    // ================================================================== equalizer page (v6.4, not in stock)
    /**
     * Equalizer for the CURRENT sound mode (62): a hint line (what it applies to / why it is off),
     * the preset (Flat / Bass / Voice / Night / Custom), the 7 bands (EqBandsRow: LEFT / RIGHT band,
     * OK adjust, UP / DOWN +-0.5 dB) and Reset. Every change is saved per sound mode and applied
     * ~200 ms later (SoundEq). Enabled only in AMP (Harman) mode: in DTS Virtual:X the vendor's own
     * DTS tuning uses the same PEQ.
     */
    private void buildEqPage() {
        eqPage = new Page(s(R.string.eq_title));
        eqPage.setOnShown(this::refresh);
        eqHint = eqPage.add(new TextRow(app, ""));
        CharSequence[] presetLabels = new CharSequence[EQ_PRESET_LABELS.length];
        for (int i = 0; i < presetLabels.length; i++) presetLabels[i] = s(EQ_PRESET_LABELS[i]);
        eqPreset = eqPage.add(new ChoiceRow(app, s(R.string.eq_preset), presetLabels,
                (row, i) -> safe("eq preset", () -> {
                    if (!SoundEq.validMode(eqMode) || i < 0 || i > EqCurve.PRESET_CUSTOM) return;
                    SoundEq.savePreset(app, eqMode, i);
                    eqBands.setValues(SoundEq.load(app, eqMode).gains());
                    SoundEq.requestUserApply();
                }).run()));
        eqPreset.setCommitDelay(300);
        String[] bandLabels = new String[EqCurve.BANDS];
        for (int b = 0; b < EqCurve.BANDS; b++) bandLabels[b] = EqCurve.freqLabel(b);
        eqBands = eqPage.add(new EqBandsRow(app, bandLabels, EqCurve.MIN_HALF, EqCurve.MAX_HALF, new EqBandsRow.Listener() {
            @Override public int onChange(EqBandsRow row, int band, int wanted) {
                int[] v = row.getValues();
                int old = v[band];
                v[band] = wanted;
                // the COMBINED curve stays <= +6 dB (7 bands at +6 each would add up to +8.7 dB)
                if (wanted > old && !EqCurve.withinCap(v)) {
                    long now = SystemClock.uptimeMillis();
                    if (now - eqLimitNoteAt > EQ_LIMIT_NOTE_GAP_MS) {
                        eqLimitNoteAt = now;
                        Notify.show(app, s(R.string.eq_title), s(R.string.eq_limit));
                    }
                    return old;
                }
                eqPreset.setSelected(EqCurve.matchPreset(v));
                return wanted;
            }
            @Override public void onLimit(EqBandsRow row, int band, boolean up) {
                // 60 Hz stops at +3 dB (not +6): say why instead of silently ignoring UP
                if (!up || band != 0 || row.getValues()[0] < EqCurve.MAX_HALF[0]) return;
                long now = SystemClock.uptimeMillis();
                if (now - eqLimitNoteAt > EQ_LIMIT_NOTE_GAP_MS) {
                    eqLimitNoteAt = now;
                    Notify.show(app, s(R.string.eq_title), s(R.string.eq_bass_limit));
                }
            }
            @Override public void onCommit(EqBandsRow row) {
                safe("eq commit", () -> {
                    if (!SoundEq.validMode(eqMode)) return;
                    SoundEq.saveCustom(app, eqMode, row.getValues());
                    eqPreset.setSelected(SoundEq.load(app, eqMode).preset);
                    SoundEq.requestUserApply();
                }).run();
            }
        }));
        eqBands.setFormatter(v -> EqCurve.label(v) + " " + s(R.string.eq_db));
        eqBands.setHints(s(R.string.eq_hint_select), s(R.string.eq_hint_adjust));
        eqReset = eqPage.add(new NavRow(app, s(R.string.eq_reset), safe("eq reset", () -> {
            if (!SoundEq.validMode(eqMode)) return;
            SoundEq.saveCustom(app, eqMode, new int[EqCurve.BANDS]);
            SoundEq.savePreset(app, eqMode, EqCurve.PRESET_FLAT);
            eqBands.setValues(new int[EqCurve.BANDS]);
            eqPreset.setSelected(EqCurve.PRESET_FLAT);
            SoundEq.requestUserApply();
            Notify.show(app, s(R.string.eq_title), s(R.string.eq_reset_done));
        })));
        eqReset.setChevron(false);
    }

    /** Main thread, from apply(): the equalizer rows for the current sound mode and process. */
    private void applyEq(Snapshot sn, boolean ok) {
        int mode = ok && sn.soundEffect != null && SoundEq.validMode(sn.soundEffect) ? sn.soundEffect : -1;
        boolean amp = ok && Boolean.FALSE.equals(sn.dtsOn) && sn.soundProcess != null
                && sn.soundProcess == GmpfClient.SoundProcess.HARMAN.wire;
        boolean dts = ok && SoundProfiles.dtsStored(sn.soundProcess, sn.dtsOn);
        boolean usable = amp && mode >= 0;
        if (eqBands.isBusy() && mode == eqMode) {
            // the user is adjusting: keep the rows as they are
        } else {
            eqMode = usable ? mode : -1;
            SoundEq.Setting st = usable ? SoundEq.load(app, mode) : null;
            eqPreset.setSelected(st != null ? st.preset : -1);
            eqBands.setValues(st != null ? st.gains() : null);
        }
        eqPreset.setRowEnabled(usable);
        eqBands.setRowEnabled(usable);
        eqReset.setRowEnabled(usable);
        CharSequence hint;
        if (!ok) hint = s(R.string.toast_not_ready);
        else if (dts) hint = s(R.string.eq_dts_note);
        else if (!amp) hint = s(R.string.sound_unknown_hint);
        else if (mode < 0) hint = s(R.string.eq_no_mode);
        else if (sn.soundPathActive && sn.soundPath != GmpfClient.AudioPath.SPEAKER.wire) hint = s(R.string.eq_speaker_only);
        else hint = app.getString(R.string.eq_hint_mode, s(soundLabelRes(GmpfClient.SoundEffect.fromWire(mode))));
        eqHint.setText(hint);
        eqNav.setValue(!usable ? (dts ? s(R.string.eq_off_dts) : null)
                : s(EQ_PRESET_LABELS[Math.max(0, Math.min(EQ_PRESET_LABELS.length - 1, SoundEq.load(app, mode).preset))]));
        eqNav.setRowEnabled(ok);
    }

    private CharSequence[] soundLabels() {
        CharSequence[] out = new CharSequence[SOUND_ORDER.length];
        for (int i = 0; i < SOUND_ORDER.length; i++) out[i] = soundLabel(SOUND_ORDER[i]);
        return out;
    }

    /** v6.4: "AI · Music" for AI sound (the sub-mode requested last), the plain name otherwise. */
    private String soundLabel(GmpfClient.SoundEffect e) {
        if (e == null) return "";
        if (e == GmpfClient.SoundEffect.AI) {
            return app.getString(R.string.sound_ai_sub, s(soundLabelRes(AiSoundEngine.shownSubMode())));
        }
        return s(soundLabelRes(e));
    }

    private static int soundLabelRes(GmpfClient.SoundEffect e) {
        switch (e) {
            case AI: return R.string.panel_sound_ai;
            case MOVIE: return R.string.panel_sound_movie;
            case MUSIC: return R.string.panel_sound_music;
            case SPORTS: return R.string.panel_sound_sports;
            case KARAOKE: return R.string.panel_sound_karaoke;
            default: return R.string.panel_sound_standard;
        }
    }

    // ================================================================== projection mode page (FEATURE_SPEC 2.9)
    private void buildProjectionPage() {
        Page p = page(QuickPanel.SECTION_PROJECTION_MODE, R.string.panel_projection_mode);
        pAuto = p.add(new CheckRow(app, s(R.string.panel_proj_auto), false, safe("proj auto", () -> onProjection(PROJ_AUTO))));
        pTable = p.add(new CheckRow(app, s(R.string.panel_proj_table), false, safe("proj table", () -> onProjection(PROJ_TABLE))));
        pCeiling = p.add(new CheckRow(app, s(R.string.panel_proj_ceiling), false, safe("proj ceiling", () -> onProjection(PROJ_CEILING))));
        pRear = p.add(new ToggleRow(app, s(R.string.panel_proj_rear), (row, w) -> safe("proj rear", () -> onRear(row, w)).run()));
    }

    // ================================================================== game page (RESULT_display decisions)
    private void buildGamePage() {
        Page p = page(QuickPanel.SECTION_GAME, R.string.panel_sec_game);
        gameHint = p.add(new TextRow(app, s(R.string.panel_game_hdmi_only)));
        gameHint.setVisibility(View.GONE);
        gameMode = p.add(new ChoiceRow(app, s(R.string.panel_game_mode), gameModeLabels(),
                (row, i) -> safe("game mode", () -> onGameMode(i)).run()));
        gameMode.setCommitDelay(CHOICE_COMMIT_MS);
        gameSpeed = p.add(new ChoiceRow(app, s(R.string.panel_game_speed),
                labels(app, R.string.panel_speed_standard, R.string.panel_speed_standard_hfr,
                        R.string.panel_speed_top, R.string.panel_speed_top_hfr),
                (row, i) -> safe("game speed", () -> onGameSpeed(i)).run()));
        gameSpeed.setCommitDelay(CHOICE_COMMIT_MS);
        allm = p.add(new ChoiceRow(app, s(R.string.panel_allm),
                labels(app, R.string.panel_allm_signal, R.string.panel_allm_device),
                (row, i) -> safe("allm", () -> {
                    if (snap == null || snap.allm == null) { refresh(); return; }
                    final boolean onAllm = i == 0;
                    change("IGmpf2 312 allm " + onAllm, (g, g2) -> {
                        if (hdmiNow(g, "312")) g2.setAllmAutoSwitch(onAllm);
                    });
                }).run()));
        allm.setCommitDelay(CHOICE_COMMIT_MS);
        // 1.0.8 (parity game item 2.15): the aspect ratio is not a game setting; it moved to the HDMI rows of the
        // Picture page (buildPicturePage), where stock keeps it, and it is refused while game mode is on
    }

    private CharSequence[] gameModeLabels() {
        return labels(app, R.string.panel_off, R.string.panel_on, R.string.panel_auto);
    }

    // ================================================================== eye protection page (FEATURE_SPEC 5.1)
    private void buildEyePage() {
        Page p = page(QuickPanel.SECTION_EYE, R.string.panel_eye);
        eye = p.add(new ToggleRow(app, s(R.string.panel_eye),
                (row, wanted) -> safe("eye", () -> onEye(row, wanted)).run()));
        p.add(new TextRow(app, s(R.string.panel_eye_desc)));
    }

    // ================================================================== all settings page
    private void buildSettingsPage() {
        Page p = page(QuickPanel.SECTION_GENERAL, R.string.panel_all_settings);
        // These leave the panel for another window / app: not during the first-run setup (the
        // Source key, app keys and gear long press are gated the same way in KeyReceiver).
        p.add(new NavRow(app, s(R.string.panel_system_settings), () -> {
            if (setupDone("system settings")) closeThen(this::openTvSettings);
        }));
        // v6.2: the Projector app has no launcher tile any more; this row is its entry point.
        p.add(new NavRow(app, s(R.string.panel_projector_settings), () -> {
            if (setupDone("projector settings")) closeThen(() -> Hal.openProjectorSettings(app, null));
        }));
        // Lumen OS 1.0 (cec module): the HDMI-CEC page (power on / off with HDMI devices, night guard,
        // soundbar, connected devices); org.lumen.tvinput's own HDMI options (auto switch, return home,
        // open at power-on: its SettingsActivity, exported to HDMI_STATE holders) are a row inside it.
        p.add(new NavRow(app, s(R.string.cec_entry), () -> {
            if (setupDone("hdmi-cec")) push(cecPage());
        }));
        // Lumen OS 1.0: no "Pair remote" row here any more (owner's decision 2026-10-08): pairing is
        // automatic (RemoteAutoPair), the setup's first step and the remote-lost prompt ask for
        // Back + Home. A second remote can still be paired from Projector settings.
        // Lumen OS 1.0: Recent apps (also on long-press HOME) and the home screen choice (PLAN C1)
        p.add(new NavRow(app, s(R.string.recents_title), () -> {
            if (setupDone("recent apps")) closeThen(() -> org.lumen.projector.recents.RecentsActivity.open(app));
        }));
        if (org.lumen.projector.home.LauncherSwitcher.choiceAvailable(app)) {
            launcherRow = p.add(new ChoiceRow(app, s(R.string.launcher_row), labels(app,
                    R.string.launcher_lumen, R.string.launcher_classic),
                    (row, i) -> safe("home screen", () -> onLauncherChoice(i)).run()));
        }
        // Lumen OS 1.0: a short POWER press = light off, then sleep (STR); no standby window any more
        p.add(new TextRow(app, s(R.string.power_sleep_summary)));
    }

    /** "Home screen" row: confirm, then LauncherSwitcher (PLAN C1); the row shows the real holder after. */
    private void onLauncherChoice(int i) {
        final String mode = i == 1 ? org.lumen.projector.home.LauncherSwitcher.MODE_CLASSIC
                : org.lumen.projector.home.LauncherSwitcher.MODE_LUMEN;
        if (mode.equals(org.lumen.projector.home.LauncherSwitcher.currentMode(app))) return;
        if (!setupDone("home screen")) {
            refreshLauncherRow();
            return;
        }
        confirm(R.string.launcher_row, s(i == 1 ? R.string.launcher_confirm_classic : R.string.launcher_confirm_lumen),
                R.string.launcher_switch, () -> closeThen(() -> org.lumen.projector.home.LauncherSwitcher.apply(app, mode,
                        true, "quick panel", (ok, holder, err) -> Log.i(TAG, "home screen " + mode + " -> " + ok + " " + holder))));
        // a canceled confirm leaves the row on the real value
        Ui.main().postDelayed(this::refreshLauncherRow, 300);
    }

    private void refreshLauncherRow() {
        if (launcherRow == null) return;
        boolean classic = org.lumen.projector.home.LauncherSwitcher.MODE_CLASSIC.equals(
                org.lumen.projector.home.LauncherSwitcher.currentMode(app));
        launcherRow.setSelected(classic ? 1 : 0);
    }

    private boolean setupDone(String what) {
        if (KeyReceiver.isSetupComplete(app)) return true;
        Log.i(TAG, "setup not complete: " + what + " row ignored");
        Ui.toast(app, R.string.toast_setup_running);
        return false;
    }

    private void push(Page p) {
        if (panel != null && panel.isShowing()) panel.push(p);
    }

    private void openTvSettings() {
        try {
            Ui.wakeFromDream(app);
            app.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Log.w(TAG, "TvSettings: " + t);
            Ui.toast(app, R.string.toast_failed);
        }
    }

    /** The fit page (fit.FitScreenPage), built at every open so it shows the stored values. */
    private Page fitPage() {
        return org.lumen.projector.fit.FitScreenPage.build(app, new org.lumen.projector.fit.FitScreenPage.Host() {
            @Override public void push(Page page) { QuickPanelController.this.push(page); }
            @Override public void refreshPage(Page page) {
                if (panel != null && panel.isShowing() && panel.currentPage() == page) panel.refreshPage();
            }
            @Override public void closeThen(Runnable r) { QuickPanelController.this.closeThen(r); }
        });
    }

    /** The cec module's page, built once per controller (texts follow the locale with the controller). */
    private Page cecPage;

    private Page cecPage() {
        if (cecPage == null) {
            cecPage = org.lumen.projector.cec.CecPage.build(app, new org.lumen.projector.cec.CecPage.Host() {
                @Override public void refreshPage(Page page) {
                    if (panel != null && panel.isShowing() && panel.currentPage() == page) panel.refreshPage();
                }
                @Override public void closeThen(Runnable r) { QuickPanelController.this.closeThen(r); }
                @Override public void openHdmiOptions() { openHdmiSettings(); }
                @Override public boolean setupDone(String what) { return QuickPanelController.this.setupDone(what); }
            });
        }
        return cecPage;
    }

    private void openHdmiSettings() {
        try {
            Ui.wakeFromDream(app);
            app.startActivity(new Intent().setClassName("org.lumen.tvinput", "org.lumen.tvinput.SettingsActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Log.w(TAG, "HDMI settings: " + t);
            Ui.toast(app, R.string.toast_failed);
        }
    }

    // ================================================================== picture
    private CharSequence[] pmLabels(GmpfClient.PictureMode[] modes) {
        CharSequence[] out = new CharSequence[modes.length];
        for (int i = 0; i < modes.length; i++) out[i] = pmLabel(modes[i]);
        return out;
    }

    private String pmLabel(GmpfClient.PictureMode m) {
        switch (m) {
            case AI: return s(R.string.panel_pm_ai);
            case STANDARD: return s(R.string.panel_pm_standard);
            case SPORT: return s(R.string.panel_pm_sport);
            case HDR_VIVID: return s(R.string.panel_pm_hdr_vivid);
            case COLOR_ACCURACY: return s(R.string.panel_pm_color_accuracy);
            case OFFICE: return s(R.string.panel_pm_office);
            case PERFORMANCE: return s(R.string.panel_pm_performance);
            default: return m.name();
        }
    }

    /** Tile value line: short forms where the full name does not fit a square tile in some languages. */
    private String pmTileLabel(GmpfClient.PictureMode m) {
        switch (m) {
            case AI: return s(R.string.panel_tileval_pm_ai);
            case COLOR_ACCURACY: return s(R.string.panel_tileval_pm_color_accuracy);
            case PERFORMANCE: return s(R.string.panel_tileval_pm_performance);
            default: return pmLabel(m);
        }
    }

    /**
     * Stock visibility (FEATURE_SPEC 3.2): HDR Vivid only while hdrType==6. Colour accuracy: the stock tree shows it
     * for SDR, HDR10 and "HDR" (PictureUtils: every other HDR type, so HLG and HDR10+ too), not for Dolby Vision or
     * HDR Vivid (1.0.8, parity picture item 2; 1.0.7 showed it for SDR and HDR10 only).
     */
    private GmpfClient.PictureMode[] visibleModes(Snapshot sn) {
        int hdr = sn == null || sn.hdr == null ? GmpfClient.HDR_SDR : sn.hdr;
        GmpfClient.PictureMode cur = sn == null || sn.pictureMode == null ? null
                : GmpfClient.PictureMode.fromWire(sn.pictureMode);
        List<GmpfClient.PictureMode> l = new ArrayList<>();
        for (GmpfClient.PictureMode m : PM_ORDER) {
            boolean show = true;
            if (m == GmpfClient.PictureMode.HDR_VIVID) show = hdr == GmpfClient.HDR_VIVID;
            if (m == GmpfClient.PictureMode.COLOR_ACCURACY) show = hdr != GmpfClient.HDR_VIVID && hdr != GmpfClient.HDR_DOLBY_VISION;
            if (show || m == cur) l.add(m);
        }
        return l.toArray(new GmpfClient.PictureMode[0]);
    }

    private void openPictureModeList() {
        if (snap == null || snap.pictureMode == null) { ready(); return; }
        Page p = new Page(s(R.string.panel_picture_mode));
        GmpfClient.PictureMode cur = GmpfClient.PictureMode.fromWire(snap.pictureMode);
        int focus = -1;
        for (int i = 0; i < pmOptions.length; i++) {
            final int idx = i;
            final GmpfClient.PictureMode m = pmOptions[i];
            if (m == cur) focus = i;
            p.add(new CheckRow(app, pmLabel(m), m == cur, safe("picture mode item", () -> {
                if (panel != null) panel.pop();
                pictureMode.setSelected(idx);
                onPictureModeChosen(idx);
            })));
        }
        p.setInitialFocus(focus);
        push(p);
    }

    private void onPictureModeChosen(int i) {
        if (snap == null || snap.pictureMode == null || i < 0 || i >= pmOptions.length) { refresh(); return; }
        final GmpfClient.PictureMode m = pmOptions[i];
        GmpfClient.PictureMode cur = GmpfClient.PictureMode.fromWire(snap.pictureMode);
        if (m == cur) return;
        // 1.0.8, stock SettingMutexManager rules (common.json pictureMode): no picture mode change in 3D
        // (pictureMutexThreeDimension) or in game mode (gameModeBasic), and no Colour accuracy in Boost
        // (lumenLevelBoost). Parity picture items 3 and 4, brightness item 11.
        CharSequence title = s(R.string.panel_picture_mode);
        if (snap.fmt3d != null && snap.is3dActive()) {
            Notify.show(app, title, s(R.string.panel_boost_3d));
            refresh();
            return;
        }
        if (gameModeOn(snap)) {
            Notify.show(app, title, s(R.string.blocked_game));
            refresh();
            return;
        }
        if (m == GmpfClient.PictureMode.COLOR_ACCURACY && Boolean.TRUE.equals(snap.boost)) {
            Notify.show(app, title, s(R.string.boost_blocks_change));
            refresh();
            return;
        }
        if (m == GmpfClient.PictureMode.PERFORMANCE) {
            // Stock heat warning before Performance (FEATURE_SPEC 3.2).
            confirm(R.string.panel_pm_performance, s(R.string.panel_pm_performance_msg),
                    R.string.panel_switch, () -> applyPictureMode(m));
            return;
        }
        applyPictureMode(m);
    }

    /**
     * Game mode is on for the picture rules. Stock DisplayHelper.isGameMode: getPictureMode() == 4 (the vendor's game
     * picture) or IGmpf2 55 state on. 1.0.8: 55 as stock when it fits 647; else "On" set by us (KEY_GAME_MODE 1)
     * while an HDMI input is on screen.
     */
    private boolean gameModeOn(Snapshot sn) {
        if (sn == null) return false;
        if (sn.pictureMode != null && sn.pictureMode == PICTURE_MODE_GAME) return true;
        if (sn.gameActive55 != null) return sn.gameActive55 && sn.hdmiConfirmed();
        return prefs.getInt(KEY_GAME_MODE, -1) == 1 && sn.hdmiConfirmed();
    }

    /** IGmpf 666 value of the vendor's game picture mode (stock DisplayHelper: getPictureMode() == 4). */
    private static final int PICTURE_MODE_GAME = 4;

    /**
     * 667 setPictureMode(0, m). For AI also 667(0, 16) "film", the stock XRMService default scene
     * (FEATURE_SPEC 3.3 step 1; whether the scene step is needed without XRMService is UNVERIFIED).
     * The vendor changes linked values (lumens mode, colour space...): the refresh re-reads them.
     */
    private void applyPictureMode(GmpfClient.PictureMode m) {
        change("667 picture mode " + m, (g, g2) -> {
            if (!g.setPictureMode(m)) throw new IllegalStateException("667 returned false");
            if (m == GmpfClient.PictureMode.AI) g.setAiScene(GmpfClient.AiScene.FILM);
        });
    }

    /** Boost (FEATURE_SPEC 3.1): two presses beyond 10 / below Boost. dir +1 enter, -1 leave. */
    private void boostPress(int dir) {
        long now = SystemClock.uptimeMillis();
        if (boostArmDir == dir && now - boostArmAt < BOOST_PRESS_WINDOW_MS) {
            boostArmDir = 0;
            setBoost(dir > 0);
            return;
        }
        boostArmDir = dir;
        boostArmAt = now;
        Notify.show(app, s(dir > 0 ? R.string.panel_boost_press_again : R.string.panel_boost_leave_press_again));
    }

    private void setBoost(boolean on) {
        if (snap == null || snap.boost == null) { ready(); return; }
        change("IGmpf2 52 boost " + on, (g, g2) -> {
            if (on) {
                int f = g.getCurrent3DFormat();
                if (f != 1 || g.is3DTo2DEnabled()) {               // stock: Boost blocked in 3D
                    Notify.show(app, s(R.string.panel_boost_3d));
                    return;
                }
                // 1.0.8 (parity brightness item 8): no temperature refusal any more. Stock has none
                // (BrightnessManager.isHighTemperature is never called); the vendor lowers the current itself.
            }
            if (!g2.setBoost(on)) throw new IllegalStateException("52 returned false");
        });
    }

    /** Ultra 120 Hz, IGmpf 161 setOutputTiming(4 on / 6 off) (RESULT_display decisions). */
    private void onUltra120(ToggleRow row, boolean wanted) {
        if (!ready()) return;
        if (!wanted) {
            setUltra120(false);
            return;
        }
        // 1.0.8 (parity picture item 30): on HDMI as stock, allowed below 62 Hz (662); the HAL task checks again
        if (snap != null && !snap.noHdmiConfirmed() && !snap.hdmiBelow62Hz()) {
            Notify.show(app, row.getTitle(), s(ultra120Refusal(snap.hdmiSignal)));
            row.setChecked(false);
            return;
        }
        // 161(4) / 161(6) and the 157 read-back tested on the owner's Z9X 2026-10-30: no "not yet tested" line
        confirm(R.string.panel_ultra120, s(R.string.panel_ultra120_msg), R.string.panel_switch, () -> setUltra120(true));
    }

    private void setUltra120(boolean on) {
        change("161 output timing " + (on ? 4 : 6), (g, g2) -> {
            if (on) {
                int f = g.getCurrent3DFormat();
                if (f != 1 || g.is3DTo2DEnabled()) {               // stock: refused in 3D
                    Notify.show(app, s(R.string.panel_ultra120_3d));
                    return;
                }
                // Stock refuses when the HDMI input runs at >= 62 Hz (662 frame rate, layout verified
                // 2026-10-30). No HDMI = 696 == 0 AND org.lumen.tvinput confirms no open HDMI session;
                // otherwise 662 must read a plausible signal below 62 Hz (unreadable = refuse).
                int src = g.getCurrentInputSource();
                Boolean tif = TifHdmiState.read(app);
                if (src != GmpfClient.SOURCE_MEDIA || !Boolean.FALSE.equals(tif)) {
                    GmpfClient.HdmiSignal sig = null;
                    try { sig = g.getHdmiInfo(); } catch (Throwable t) { Log.d(TAG, "662: " + t); }
                    if (sig == null || !sig.plausible() || sig.frameRateCenti >= 6200) {
                        Log.i(TAG, "161(4) refused: 696=" + src + " tvinput session=" + tif + " 662=" + sig);
                        Notify.show(app, s(ultra120Refusal(sig)));
                        return;
                    }
                }
            }
            boolean ok = g.setOutputTiming(on ? GmpfClient.OutputTiming.ULTRA_120_ON : GmpfClient.OutputTiming.ULTRA_120_OFF);
            if (!ok) {
                Notify.show(app, s(R.string.panel_ultra120_refused));
                return;
            }
            boolean now = on;
            try {
                GmpfClient.OutputTimingInfo t = g.getOutputTimingInfo();
                if (t != null) now = t.isUltra120();
                Log.i(TAG, "161(" + (on ? 4 : 6) + ") -> 157 " + t);
            } catch (Throwable t) {
                Log.d(TAG, "157: " + t);
            }
            prefs.edit().putBoolean(KEY_ULTRA_120, now).apply();
        });
    }

    /** The note for a refused Ultra 120 Hz on HDMI: stock's "not supported" at >= 62 Hz, else "HDMI". */
    private static int ultra120Refusal(GmpfClient.HdmiSignal sig) {
        return sig != null && sig.plausible() && sig.frameRateCenti >= 6200
                ? R.string.panel_ultra120_refused : R.string.panel_ultra120_hdmi;
    }

    // ================================================================== game
    /**
     * Stock gmuiapi DisplayManager.setGameMode(level) (VERIFIED ns_jadx): 0 off = 56(0) + 57(1);
     * 1 on = 648(current 647) + 56(0) + 57(0); 2 auto = 56(1). The state cannot be read back
     * (IGmpf2 55 struct UNVERIFIED, probe pending), so the row shows the last value set here.
     * Lumen 1.0.8 (parity game items 2.3, 2.16, 2.17): On / Auto are refused in 3D and with Dolby Vision content, as
     * the stock mutex rules (the vendor refuses game mode in 3D itself: "can not enable Game mode when 3D is
     * enabled"); the 56 / 57 results are checked and the row keeps its old value when one says false.
     */
    private void onGameMode(int i) {
        if (i < 0 || i > 2) return;
        final CharSequence title = s(R.string.panel_game_mode);
        change("game mode " + i, (g, g2) -> {
            if (!hdmiNow(g, "game mode")) return;
            if (i != 0) {
                int f = g.getCurrent3DFormat();
                if (f != 1 || g.is3DTo2DEnabled()) {               // stock: game mode and 3D exclude each other
                    Notify.show(app, title, s(R.string.panel_boost_3d));
                    return;
                }
                if (g.getHdrType() == GmpfClient.HDR_DOLBY_VISION) {
                    Notify.show(app, title, s(R.string.game_blocked_dv));
                    return;
                }
            }
            boolean ok;
            if (i == 0) {
                ok = g2.setGameModeType(Gmpf2Client.GameModeType.MANUAL);
                ok &= g2.setGameModeState(false);
            } else if (i == 1) {
                GmpfClient.GameModeOption o = g.getGameModeOption();
                if (o != null) g.setGameModeOption(o);
                ok = g2.setGameModeType(Gmpf2Client.GameModeType.MANUAL);
                ok &= g2.setGameModeState(true);
            } else {
                ok = g2.setGameModeType(Gmpf2Client.GameModeType.AUTO);
            }
            if (!ok) throw new IllegalStateException("56 / 57 returned false");
            prefs.edit().putInt(KEY_GAME_MODE, i).apply();
            pendingGame = i;
            pendingGameUntil = SystemClock.uptimeMillis() + PENDING_SPEED_MS;
        });
    }

    /**
     * 648 setGameModeOption; top speed also 207 correctKeystoneReset(0) as stock switchGameModeSpeed.
     * Lumen OS 1.0.4, from the owner's log (2026-10-15 02:32:57): the vendor stores the option at once (648 -> true,
     * m_eGameOption and its DB updated), but 647 keeps reading the old option until the picture mode is applied again,
     * ~4.3 s later ("GameOpt=0->1"); 1.0.3 also saw 648 answer false for a switch it carried out. So: 648 true =
     * done; false = done only if 647 reads the option within GAME_OPTION_WAIT_MS. The row keeps showing the chosen
     * speed until 647 agrees (pendingSpeed). Top speed: 207 waits, re-polled on the HAL thread without blocking it,
     * until 647 reads it, because 207's guard reads 647.
     */
    private void onGameSpeed(int i) {
        if (snap == null || snap.gameOption == null || i < 0 || i >= SPEED_ORDER.length) { refresh(); return; }
        final GmpfClient.GameModeOption o = SPEED_ORDER[i];
        if (!o.isTopSpeed() || snap.isTopSpeedGame()) {     // already top speed: the keystone is reset already
            applyGameSpeed(o);
            return;
        }
        // 1.0.8 (parity game item 2.6): stock asks before top speed resets a keystone that is not the full frame
        if (!ready()) return;
        Hal.query((g, g2) -> isFullFrame(g.getKeystonePoints()), full -> safe("top speed", () -> {
            if (Boolean.TRUE.equals(full)) {
                applyGameSpeed(o);
                return;
            }
            confirm(R.string.panel_speed_top, s(R.string.game_top_speed_confirm), R.string.panel_switch,
                    () -> applyGameSpeed(o));
            refresh();                                       // until confirmed the row shows the real option
        }).run());
    }

    private void applyGameSpeed(GmpfClient.GameModeOption o) {
        change("648 game option " + o, (g, g2) -> {
            if (!hdmiNow(g, "648")) return;                  // 207 below would reset the normal picture
            boolean r = g.setGameModeOption(o);
            GmpfClient.GameModeOption now = g.getGameModeOption();
            Log.i(TAG, "648(" + o + ") -> " + r + ", 647 reads " + now);
            if (!r && now != o) {
                now = waitGameOption(g, o);
                Log.i(TAG, "648 false: 647 reads " + now + " after the wait");
                if (now != o) throw new IllegalStateException("648 -> false, 647 reads " + now);
            }
            pendingSpeed = o;
            pendingSpeedUntil = SystemClock.uptimeMillis() + PENDING_SPEED_MS;
            if (o.isTopSpeed()) topSpeedKeystone(o, SystemClock.uptimeMillis() + PENDING_SPEED_MS);
        });
    }

    private static final long GAME_OPTION_WAIT_MS = 6_000, GAME_OPTION_POLL_MS = 100;
    /** 647 follows 648 after the picture is applied again (~4.3 s seen); the row shows the choice meanwhile. */
    private static final long PENDING_SPEED_MS = 10_000;
    private volatile GmpfClient.GameModeOption pendingSpeed;
    /** Game mode just set (0..2, -1 none): shown until 55 agrees or PENDING_SPEED_MS passed. */
    private volatile int pendingGame = -1;
    private volatile long pendingGameUntil;
    private volatile long pendingSpeedUntil;

    /** HAL thread: 647 until it reads {@code want}, at most GAME_OPTION_WAIT_MS; the last value read (null = failed). */
    private static GmpfClient.GameModeOption waitGameOption(GmpfClient g, GmpfClient.GameModeOption want) {
        long end = SystemClock.uptimeMillis() + GAME_OPTION_WAIT_MS;
        GmpfClient.GameModeOption now = null;
        while (true) {
            try { now = g.getGameModeOption(); } catch (Throwable t) { Log.w(TAG, "647: " + t); }
            if (now == want || SystemClock.uptimeMillis() >= end) return now;
            SystemClock.sleep(GAME_OPTION_POLL_MS);
        }
    }

    /** 207 for top speed once 647 reads it (re-polled every 300 ms on the HAL thread, never blocking it). */
    private void topSpeedKeystone(GmpfClient.GameModeOption o, long deadline) {
        Hal.runDelayed((g, g2) -> {
            GmpfClient.GameModeOption now = g.getGameModeOption();
            if (now == o) {
                g.resetKeystoneForTopSpeedGame();
                Log.i(TAG, "207 after 647 reads " + o);
                Ui.main().post(() -> Notify.show(app, s(R.string.panel_speed_top_note)));
            } else if (SystemClock.uptimeMillis() < deadline) {
                topSpeedKeystone(o, deadline);
            } else {
                Log.w(TAG, "207 skipped: 647 still reads " + now + " instead of " + o);
            }
        }, 300);
    }

    // ================================================================== eye protection
    /** 152 setHumanDetectOnOff with the stock confirmation both ways (FEATURE_SPEC 5.1). */
    private void onEye(ToggleRow row, boolean wanted) {
        if (!ready()) return;
        if (!GmpfClient.nuiReaderReady()) {
            // v6.2b: no z9x_nui fifo reader -> 152 is not sent (GmpfClient refuses it too)
            Log.w(TAG, "152 eye protection " + wanted + " refused: " + GmpfClient.NUI_READY_PROP + " != ready");
            Notify.show(app, s(R.string.eye_unavailable));
            row.setChecked(!wanted);
            return;
        }
        confirm(R.string.panel_eye, s(wanted ? R.string.panel_eye_on_msg : R.string.panel_eye_off_msg),
                wanted ? R.string.panel_turn_on : R.string.panel_turn_off,
                () -> change("152 eye protection " + wanted, (g, g2) -> {
                    if (!g.setEyeProtection(wanted)) throw new IllegalStateException("152 returned false");
                }));
        row.setChecked(!wanted);              // until confirmed; the read-back after OK shows the result
    }

    // ================================================================== keystone reset
    /**
     * Keystone reset, the stock sequence (Lumen OS 1.0.8, parity keystone item 8; settings KeyStoneViewModel.java:555-559
     * -> KeyStoneManager.java:254-276, V3DisplayManager.java:185-190, 318-320): 207 correctKeystoneReset(0), 129(0,0)
     * when 130 reads 6, 181 correctKeystone(50), 153 zoomControl(5,10) (the lens home, the biggest picture; the saved
     * zoom position is forgotten, OpticalZoomPanel.onLensHome), IGmpf2 46 doKeystoneRatio(3); then 158(0). The lens
     * home is back since 2026-10-30 evening: the stock evidence of the HB (Vyacheslav's videos) shows that the
     * corners-only reset is the manual keystone screen's (ManualKeystonePanel, hold OK: stock 207(0), 186 read back,
     * 150 -> 185 full frame only as a fallback, no lens move) and that "Restore default picture" is the full stock reset with the lens home, which the Z9X stock
     * KeyStoneManager.resetKeyStone (:254-276) sends as well. MENU in OpticalZoomPanel still resets only the zoom.
     * No confirmation (stock has none). Since 2026-10-30 night the row is "Restore default picture" (kst_restore) under
     * the title "More" at the end of the keystone page, the toast kst_restore_done ("Экран восстановлен"), as stock;
     * the manual keystone screen's hold OK sends the corners-only 207 path (GmpfClient.resetKeystoneCorners).
     * Wrapped in 573(2,false/true) only when real-time keystone is on (586, stock mAkStatus; the shared persisted
     * marker of ManualKeystonePanel covers a process death). Same guarded entry as before, checked on the HAL
     * thread right before the writes: 647 not top speed, no auto keystone (ours or the vendor's overlay).
     * Safety net: if 186 does not read the full frame afterwards (what 207 restores on this model is UNVERIFIED),
     * the 1.0.7 path runs: the full frame through 150 -> 185.
     */
    private void resetKeystone() {
        if (Hal.isKeystoneRunning() || AkOverlay.isActive()) { Ui.toast(app, R.string.toast_kst_busy); return; }
        change("keystone reset (stock)", (g, g2) -> {
            GmpfClient.GameModeOption o = g.getGameModeOption();          // 647
            if (o != null && o.isTopSpeed()) {
                Notify.show(app, s(R.string.panel_kst_game_mode));
                return;
            }
            if (Hal.isKeystoneRunning() || AkOverlay.isActive()) {
                Notify.show(app, s(R.string.toast_kst_busy));
                return;
            }
            boolean paused = ManualKeystonePanel.pauseRealtime(app, g, "panel reset");
            try {
                int r181 = g.resetKeystoneStock();                         // 207, 130 (129), 181(50)
                boolean r46 = false;
                int r153 = Integer.MIN_VALUE;
                try {
                    r153 = g.zoomHome();                                   // 153(5,10), stock order: before 46
                    OpticalZoomPanel.onLensHome(app);
                } catch (Throwable t) { Log.w(TAG, "153(5,10): " + t); }
                try { r46 = g2.resetKeystoneRatio(); } catch (Throwable t) { Log.w(TAG, "IGmpf2 46: " + t); }
                Log.i(TAG, "reset: 207/130/181(50) -> " + r181 + ", 153(5,10) -> " + r153 + ", 46(3) -> " + r46);
                KstPoint after = null;
                try { after = g.getKeystonePoints(); } catch (Throwable t) { Log.w(TAG, "186 after reset: " + t); }
                if (after == null || !isFullFrame(after)) {
                    Log.w(TAG, "reset: 186 reads " + after + " after the stock sequence: full frame through 150 -> 185");
                    KstPoint full = KstPoint.fullFrame();
                    if (!g.checkKeystonePoints(full)) throw new IllegalStateException("150 rejected the full frame");
                    Log.i(TAG, "185 full frame -> " + g.applyKeystonePoints(full));
                }
                org.lumen.projector.game.GameProfile.noteKeystoneFullFrame(app, "quick panel reset");
                try { g.regenerateBootLogo(); } catch (Throwable t) { Log.w(TAG, "158: " + t); }
                Notify.show(app, s(R.string.kst_restore_done));
            } finally {
                if (paused) ManualKeystonePanel.resumeRealtime(app, g, "panel reset");
            }
        });
    }

    /** 186 corners within a few panel px of the full frame. */
    private static boolean isFullFrame(KstPoint p) {
        KstPoint f = KstPoint.fullFrame();
        for (int c = KstPoint.TL; c <= KstPoint.BL; c++) {
            if (Math.abs(p.x(c) - f.x(c)) > FULL_FRAME_TOLERANCE || Math.abs(p.y(c) - f.y(c)) > FULL_FRAME_TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    private static final int FULL_FRAME_TOLERANCE = 4;

    // ================================================================== projection mode
    private static final int PROJ_AUTO = 0, PROJ_TABLE = 1, PROJ_CEILING = 2;


    /** FEATURE_SPEC 2.9 (VERIFIED ProjectionRepository): radio Auto / Table / Ceiling. */
    private void onProjection(int kind) {
        if (snap == null || snap.putMode == null || snap.autoReverse == null) { ready(); return; }
        final int cur = snap.putMode;
        final boolean auto = snap.autoReverse;
        if (kind == PROJ_AUTO) {
            if (auto) return;
            confirm(R.string.panel_projection_mode, s(R.string.panel_proj_confirm_flip), R.string.panel_switch,
                    () -> change("580 auto flip on", (g, g2) -> g.setAutoReverse(true)));
            return;
        }
        boolean front = cur == 0 || cur == 1;
        final int target = kind == PROJ_TABLE ? (front ? 0 : 2) : (front ? 1 : 3);
        if (target == cur) {
            if (auto) change("580 auto flip off", (g, g2) -> g.setAutoReverse(false));   // no flip
            return;
        }
        askPutMode(cur, target);
    }

    /** Rear switch: from 0/2 on -> 2, off -> 0; from 1/3 on -> 3, off -> 1. */
    private void onRear(ToggleRow row, boolean wanted) {
        if (snap == null || snap.putMode == null) { ready(); return; }
        int cur = snap.putMode;
        boolean table = cur == 0 || cur == 2;
        int target = wanted ? (table ? 2 : 3) : (table ? 0 : 1);
        row.setChecked(!wanted);               // until confirmed
        if (target != cur) askPutMode(cur, target);
    }

    private void askPutMode(int cur, int target) {
        final GmpfClient.PutMode m = GmpfClient.PutMode.fromWire(target);
        if (m == null) return;
        final boolean toCeiling = (target == 1 || target == 3) && (cur == 0 || cur == 2);
        confirm(R.string.panel_projection_mode,
                s(toCeiling ? R.string.panel_proj_confirm_ceiling : R.string.panel_proj_confirm_flip),
                R.string.panel_switch,
                () -> change("172 put mode " + m, (g, g2) -> {
                    g.setAutoReverse(false);
                    g.setPutMode(m);
                    if (toCeiling) {
                        // Stock on switching to ceiling: push-pull screen off. Real-time keystone stays as it is
                        // (1.0.8, parity keystone item 2: G0082 supports it when hoisted, stock sends no 585(false)).
                        try { g2.disablePushPullScreenForCeiling(); } catch (Throwable t) { Log.w(TAG, "IGmpf2 201: " + t); }
                    }
                }));
    }

    // ================================================================== apply the read-back
    private static void touch(SliderRow r) { r.setTag(SystemClock.uptimeMillis()); }

    /** Do not move a slider under the user's finger (a read-back of an older commit). */
    private static boolean sliderBusy(SliderRow r) {
        Object t = r.getTag();
        return r.isFocused() && t instanceof Long && SystemClock.uptimeMillis() - (Long) t < SLIDER_GUARD_MS;
    }

    private static void setToggle(ToggleRow r, Boolean v, boolean enabled) {
        r.setChecked(v);
        r.setRowEnabled(v != null && enabled);
    }

    private static void setChoice(ChoiceRow r, int index, boolean known, boolean enabled) {
        r.setSelected(known ? index : -1);
        r.setRowEnabled(known && enabled);
    }

    private static int indexOf(Object[] arr, Object v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    private static int indexOfWire(int[] wires, Integer v) {
        if (v == null) return -1;
        for (int i = 0; i < wires.length; i++) if (wires[i] == v) return i;
        return -1;
    }

    private void apply(Snapshot sn) {
        snap = sn;
        boolean ok = sn != null;
        info.setText(infoText(sn));

        // ---- picture mode (options depend on the content's HDR type)
        GmpfClient.PictureMode[] modes = visibleModes(sn);
        if (modes.length != pmOptions.length || !java.util.Arrays.equals(modes, pmOptions)) {
            pmOptions = modes;
            pictureMode.setOptions(pmLabels(modes));
        }
        GmpfClient.PictureMode pm = ok && sn.pictureMode != null ? GmpfClient.PictureMode.fromWire(sn.pictureMode) : null;
        setChoice(pictureMode, indexOf(pmOptions, pm), pm != null, true);

        // ---- lamp + Boost
        boostOn = ok && Boolean.TRUE.equals(sn.boost);
        if (!sliderBusy(lamp)) {
            if (boostOn) lamp.setRange(1, BOOST_LEVEL).setValue(BOOST_LEVEL);
            else lamp.setRange(1, 10).setValue(ok ? sn.lamp : null);
        }
        lamp.setRowEnabled(ok && (sn.lamp != null || boostOn));

        // 1.0.8 (parity picture item 29): the real state from 157 (stock userTiming == 4); the last value set
        // only when 157 failed. The stored value follows 157 (GameProfile reads it around game sessions).
        boolean u120 = prefs.getBoolean(KEY_ULTRA_120, false);
        if (ok && sn.ultra120 != null) {
            if (sn.ultra120 != u120) prefs.edit().putBoolean(KEY_ULTRA_120, sn.ultra120).apply();
            u120 = sn.ultra120;
        }
        ultra120.setChecked(ok ? u120 : null);
        // 1.0.8 (parity picture item 37): never grey. Turning it ON on HDMI at >= 62 Hz (662) is refused with a
        // note on the press, as stock (onUltra120 / setUltra120).
        ultra120.setRowEnabled(ok);

        // ---- game
        // Last value set by us ("—" until then; still selectable). Needs IGmpf2 (56/57).
        // HDMI only (RESULT_quicksettings): enabled while 696 reports HDMI 1/2 or org.lumen.tvinput
        // reports an open HDMI session; otherwise disabled with a "HDMI only" note. The HAL tasks
        // re-check right before every write (hdmiNow).
        boolean hdmi = ok && sn.hdmiConfirmed();
        gameHint.setVisibility(ok && !hdmi ? View.VISIBLE : View.GONE);
        // 1.0.8 (parity game item 2.2): the current input's game mode from IGmpf2 55 when it fits 647 (verified
        // 2026-10-30); the last value set while a change is pending or when 55 is unknown.
        int gm = prefs.getInt(KEY_GAME_MODE, -1);
        if (hdmi && sn.gameMode55 != null) {
            int pg = pendingGame;
            if (pg >= 0 && pg != sn.gameMode55 && SystemClock.uptimeMillis() < pendingGameUntil) gm = pg;
            else {
                pendingGame = -1;
                gm = sn.gameMode55;
            }
        }
        gameMode.setSelected(gm);
        gameMode.setRowEnabled(hdmi && sn.boost != null);
        int sp = -1;
        if (ok && sn.gameOption != null) {
            GmpfClient.GameModeOption pend = pendingSpeed;
            int shown = sn.gameOption;
            if (pend != null && shown != pend.wire && SystemClock.uptimeMillis() < pendingSpeedUntil) shown = pend.wire;
            else pendingSpeed = null;                        // 647 agrees (or the wait is over)
            for (int i = 0; i < SPEED_ORDER.length; i++) if (SPEED_ORDER[i].wire == shown) sp = i;
        }
        setChoice(gameSpeed, sp, ok && sn.gameOption != null, hdmi);
        setChoice(allm, ok && sn.allm != null ? (sn.allm ? 0 : 1) : -1, ok && sn.allm != null, hdmi);

        // ---- sound (v6.3.1: SoundProfiles state mapping, DTS only with the output path reported)
        SoundProfiles.Profile prof = ok ? SoundProfiles.fromState(sn.soundProcess, sn.dtsOn, sn.soundPathActive) : null;
        // "—" while unknown (43 on, another process type, a getter failed); still selectable = re-apply.
        setChoice(soundEnh, SoundProfiles.indexOf(prof), ok && sn.soundProcess != null, true);
        boolean unknown = ok && sn.soundProcess != null && prof == null;
        boolean dtsStored = ok && SoundProfiles.dtsStored(sn.soundProcess, sn.dtsOn);
        CharSequence dtsLabel = s(R.string.panel_dts_vx);
        CharSequence sh = dtsStored && !sn.soundPathActive ? app.getString(R.string.sound_output_not_ready, dtsLabel)
                : dtsStored && sn.soundPath != GmpfClient.AudioPath.SPEAKER.wire ? app.getString(R.string.sound_speaker_only, dtsLabel)
                : unknown ? s(R.string.sound_unknown_hint) : null;
        soundHint.setText(sh);
        soundHint.setVisibility(sh == null ? View.GONE : View.VISIBLE);
        int sm = ok ? SoundProfiles.modeIndex(sn.soundEffect) : -1;
        if (ok) AiSoundEngine.noteMode(sn.soundEffect, "panel read-back");
        CharSequence[] sl = soundLabels();                   // v6.4: "AI · <sub-mode>" changes with the app
        soundMode.setOptions(sl);
        setChoice(soundMode, sm, ok && sn.soundEffect != null, true);
        applyEq(sn, ok);

        // ---- eye protection, all settings
        setToggle(eye, ok ? sn.eye : null, true);
        refreshLauncherRow();

        // ---- focus + keystone pages
        setToggle(rtKst, ok ? sn.moveAk : null, true);           // every mount on G0082 (1.0.8, parity keystone item 2)
        setToggle(powerOnKst, ok ? sn.powerOnAk : null, true);
        setToggle(curtain, ok ? sn.curtain : null, true);
        setToggle(obstacle, ok ? sn.obstacle : null, true);
        setToggle(moveAf, ok ? sn.moveAf : null, true);
        setToggle(powerOnAf, ok ? sn.powerOnAf : null, true);
        setToggle(smartAf, ok ? sn.smartAf : null, true);

        // ---- projection page
        boolean auto = ok && Boolean.TRUE.equals(sn.autoReverse);
        boolean known = ok && sn.putMode != null && sn.autoReverse != null;
        pAuto.setChecked(known && auto);
        pTable.setChecked(known && !auto && (sn.putMode == 0 || sn.putMode == 2));
        pCeiling.setChecked(known && !auto && (sn.putMode == 1 || sn.putMode == 3));
        pAuto.setRowEnabled(known);
        pTable.setRowEnabled(known);
        pCeiling.setRowEnabled(known);
        setToggle(pRear, known ? (Boolean) (sn.putMode == 2 || sn.putMode == 3) : null, true);

        // ---- picture page. Lumen 1.0.8 (parity picture items 12, 13, 26, 37): no grey rows any more. Stock does not
        // block the basic sliders in AI / Performance (isAiImageMode / isPerformanceImageMode are never called), the
        // colour temperature in Office or AI contrast outside SDR, so neither do we; a row whose getter failed is
        // hidden (owner rule: hide, never disable). While the whole read failed (HAL not ready, transient) the rows
        // stay as they were, disabled.
        picHint.setText(null);
        picHint.setVisibility(View.GONE);
        for (int i = 0; i < pq.length; i++) {
            Integer v = ok ? sn.pq[i] : null;
            if (!sliderBusy(pq[i])) pq[i].setValue(v);
            pq[i].setRowEnabled(v != null);
            pq[i].setVisibility(ok && v == null ? View.GONE : View.VISIBLE);
        }
        int ct = -1;
        if (ok && sn.colorTemp != null) for (int i = 0; i < CT_ORDER.length; i++) if (CT_ORDER[i].wire == sn.colorTemp) ct = i;
        setChoice(colorTemp, ct, ok && sn.colorTemp != null, true);
        colorTemp.setVisibility(ok && sn.colorTemp == null ? View.GONE : View.VISIBLE);
        boolean memcKnown = ok && sn.memc != null && sn.memc >= 0 && sn.memc <= 3;
        setChoice(memc, memcKnown ? sn.memc : -1, memcKnown, true);
        memc.setVisibility(ok && !memcKnown ? View.GONE : View.VISIBLE);
        setToggle(aiContrast, ok ? sn.aiContrast : null, true);
        aiContrast.setVisibility(ok && sn.aiContrast == null ? View.GONE : View.VISIBLE);
        setToggle(superRes, ok ? sn.superRes : null, true);
        superRes.setVisibility(ok && sn.superRes == null ? View.GONE : View.VISIBLE);
        // Lumen OS 1.0.4 HDMI settings: only while HDMI is on screen (they apply to that input)
        boolean onHdmi = ok && sn.hdmiConfirmed();
        if (ok && sn.edid != null && (sn.edid == GmpfClient.EDID_20 || sn.edid == GmpfClient.EDID_21)
                && prefs.getInt(KEY_EDID_ENHANCED, -1) != sn.edid) {
            prefs.edit().putInt(KEY_EDID_ENHANCED, sn.edid).apply();     // this projector's "Enhanced" value
        }
        boolean showRange = onHdmi && sn.hdmiRange != null && sn.hdmiRange >= 0 && sn.hdmiRange <= 2;
        boolean showEdid = onHdmi && sn.edid != null;
        int as = -1;
        if (ok && sn.aspect != null) for (int i = 0; i < ASPECT_ORDER.length; i++) if (ASPECT_ORDER[i].wire == sn.aspect) as = i;
        boolean showAspect = onHdmi && as >= 0;
        aspect.setSelected(showAspect ? as : -1);
        aspect.setVisibility(showAspect ? View.VISIBLE : View.GONE);
        hdmiHeader.setVisibility(showRange || showEdid || showAspect ? View.VISIBLE : View.GONE);
        hdmiRange.setSelected(showRange ? sn.hdmiRange : -1);
        hdmiRange.setVisibility(showRange ? View.VISIBLE : View.GONE);
        // stock: Enhanced for 1 (2.0) and 3 (2.1-style), every other read shows Compatible
        hdmiMode.setSelected(showEdid ? (sn.edid == GmpfClient.EDID_20 || sn.edid == GmpfClient.EDID_21 ? 1 : 0) : -1);
        hdmiMode.setVisibility(showEdid ? View.VISIBLE : View.GONE);
        volumeLevel.setChecked(ok ? sn.volumeBalance : null);
        volumeLevel.setVisibility(ok && sn.volumeBalance != null ? View.VISIBLE : View.GONE);
        earc.setChecked(ok ? sn.earc : null);
        earc.setVisibility(ok && sn.earc != null ? View.VISIBLE : View.GONE);
        // 1.0.4: sound output rows from AudioManager (no HAL read); Auto and Speaker always exist
        outOptions = org.lumen.projector.audio.AudioOutputChoice.options(app);
        CharSequence[] ol = new CharSequence[outOptions.size()];
        for (int i = 0; i < ol.length; i++) ol[i] = outOptions.get(i).label;
        soundOut.setOptions(ol);
        soundOut.setSelected(org.lumen.projector.audio.AudioOutputChoice.selectedIndex(app, outOptions));
        boolean arcOut = org.lumen.projector.audio.AudioOutputChoice.arcConnected(app);
        digitalFormat.setSelected(arcOut ? org.lumen.projector.audio.AudioOutputChoice.formatIndex(app) : -1);
        digitalFormat.setVisibility(arcOut ? View.VISIBLE : View.GONE);

        // ---- tile values (top level): the same read-back, one short line each
        tileValue(QuickPanel.SECTION_LAMP, !ok ? null : boostOn ? s(R.string.panel_boost)
                : sn.lamp != null ? Integer.toString(sn.lamp) : null);
        tileValue(QuickPanel.SECTION_PICTURE, pm != null ? pmTileLabel(pm) : null);
        tileValue(QuickPanel.SECTION_SOUND, sm >= 0 ? sl[sm] : null);
        tileValue(QuickPanel.SECTION_INPUT, ok ? inputLabel(sn) : null);
        tileValue(QuickPanel.SECTION_PROJECTION_MODE, ok ? projectionLabel(sn) : null);
        tileValue(QuickPanel.SECTION_GAME, !ok ? null : !hdmi ? s(R.string.panel_tile_hdmi_only)
                : gm >= 0 && gm <= 2 ? gameModeLabels()[gm] : null);
        tileValue(QuickPanel.SECTION_EYE, ok && sn.eye != null ? s(sn.eye ? R.string.panel_on : R.string.panel_off) : null);
    }

    private void tileValue(String section, CharSequence v) {
        Tile t = grid.tile(section);
        if (t != null) t.setValue(v);
    }

    private String inputLabel(Snapshot sn) {
        if (sn.source != null && sn.source == GmpfClient.SOURCE_HDMI1) return s(R.string.panel_src_hdmi1);
        if (sn.source != null && sn.source == GmpfClient.SOURCE_HDMI2) return s(R.string.panel_src_hdmi2);
        if (Boolean.TRUE.equals(sn.tifHdmi)) return s(R.string.panel_src_hdmi);   // 696 during TIF: UNVERIFIED
        return sn.source == null ? null : s(R.string.panel_src_android);
    }

    private CharSequence infoText(Snapshot sn) {
        if (sn == null) return s(R.string.toast_not_ready);
        String src = sn.source == null ? null
                : sn.source == GmpfClient.SOURCE_HDMI1 ? s(R.string.panel_src_hdmi1)
                : sn.source == GmpfClient.SOURCE_HDMI2 ? s(R.string.panel_src_hdmi2)
                : s(R.string.panel_src_android);
        String hdr = null;
        if (sn.hdr != null) {
            switch (sn.hdr) {
                case GmpfClient.HDR_SDR: hdr = "SDR"; break;
                case GmpfClient.HDR_HDR10: hdr = "HDR10"; break;
                case GmpfClient.HDR_HLG: hdr = "HLG"; break;
                case GmpfClient.HDR_HDR10_PLUS: hdr = "HDR10+"; break;
                case GmpfClient.HDR_VIVID: hdr = "HDR Vivid"; break;
                default: hdr = "HDR"; break;                       // never advertise Dolby Vision
            }
        }
        if (src == null && hdr == null) return "";
        if (src == null) return hdr;
        if (hdr == null) return src;
        return src + "  ·  " + hdr;
    }

    private String projectionLabel(Snapshot sn) {
        if (Boolean.TRUE.equals(sn.autoReverse)) return s(R.string.panel_proj_auto);
        if (sn.putMode == null) return null;
        switch (sn.putMode) {
            case 0: return s(R.string.panel_proj_front_table);
            case 1: return s(R.string.panel_proj_front_ceiling);
            case 2: return s(R.string.panel_proj_rear_table);
            case 3: return s(R.string.panel_proj_rear_ceiling);
            default: return null;
        }
    }

    // ================================================================== the panel window
    /**
     * The quick panel window: PagedPanel + Boost key handling + v6.2 motion + close hook.
     * Open: the card grows out of the right edge (scale 0.96 -> 1, +48 px -> 0, alpha) in 200 ms
     * with PathInterpolator(0.2,0,0,1), then the tiles stagger in. Close: 160 ms the other way with
     * PathInterpolator(0.4,0,1,1). The close runs on a hardware layer; the open does not (its tiles
 * animate on their own layers inside it). Page switches keep the foundation's
     * 140 ms slide (PagedPanel).
     */
    static final class QuickPanelView extends PagedPanel {
        private static final long OPEN_MS = 200, CLOSE_MS = 160;
        private final QuickPanelController ctl;

        QuickPanelView(Page root, QuickPanelController ctl) {
            super(root);
            this.ctl = ctl;
        }

        private static void pivotAtEdge(View root) {
            boolean rtl = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            root.setPivotX(rtl ? 0f : Math.max(root.getWidth(), 1));
            root.setPivotY(root.getHeight() / 2f);
        }

        @Override
        protected void onShown(View root) {
            float dx = Theme.pxf(root.getContext(), 48);
            pivotAtEdge(root);
            root.setAlpha(0f);
            root.setTranslationX(dx);
            root.setScaleX(0.96f);
            root.setScaleY(0.96f);
            // No hardware layer on the root here: the info row and the tiles animate inside it (each
            // tile on its own layer), so a root layer would be re-rendered every frame anyway. One
            // layer level only; the root's alpha is applied per child (no offscreen buffer) while it
            // fades in.
            root.forceHasOverlappingRendering(false);
            root.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setStartDelay(0)
                    .setDuration(OPEN_MS).setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f))
                    .withEndAction(() -> root.forceHasOverlappingRendering(true)).start();
            try { ctl.animateIn(currentPage()); } catch (Throwable t) { Log.w(TAG, "animateIn: " + t); }
        }

        @Override
        protected void animateOut(View root, Runnable end) {
            root.animate().cancel();
            pivotAtEdge(root);
            root.animate().alpha(0f).translationX(Theme.pxf(root.getContext(), 40)).scaleX(0.97f).scaleY(0.97f)
                    .setStartDelay(0).setDuration(CLOSE_MS).setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f))
                    .withLayer().withEndAction(end).start();
        }

        @Override
        protected boolean onKeyEvent(KeyEvent ev) {
            try {
                return ctl.onKey(ev);
            } catch (Throwable t) {
                Log.w(TAG, "key: " + t);
                return false;
            }
        }

        @Override
        protected void onDismissed() {
            super.onDismissed();
            try { ctl.onPanelClosed(); } catch (Throwable t) { Log.w(TAG, "closed: " + t); }
        }
    }
}
