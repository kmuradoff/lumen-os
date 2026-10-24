package org.z9x.projector.panel;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

import org.z9x.projector.Device;
import org.z9x.projector.Hal;
import org.z9x.projector.KeyReceiver;
import org.z9x.projector.R;
import org.z9x.projector.Ui;
import org.z9x.projector.game.GameMode;
import org.z9x.projector.hal.Play6Gmpf;
import org.z9x.projector.source.SourceOverlay;
import org.z9x.projector.source.TifHdmiState;
import org.z9x.projector.ui.CheckRow;
import org.z9x.projector.ui.ChoiceRow;
import org.z9x.projector.ui.DialogPanel;
import org.z9x.projector.ui.HeaderRow;
import org.z9x.projector.ui.NavRow;
import org.z9x.projector.ui.Notify;
import org.z9x.projector.ui.OverlayHost;
import org.z9x.projector.ui.Page;
import org.z9x.projector.ui.PagedPanel;
import org.z9x.projector.ui.SliderRow;
import org.z9x.projector.ui.TextRow;
import org.z9x.projector.ui.Theme;
import org.z9x.projector.ui.ToggleRow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The quick panel of the XGIMI Play 6 (one instance per open panel, main thread only;
 * docs/play6/SETTINGS_PLAN.md 1.1 to 1.8, section 4 and section 5 step 16). The Play 6 counterpart of
 * Z9xProjector panel/QuickPanelController.java: the same shell (info line, tile grid, section pages,
 * confirmations inside the panel, last tile remembered), the Play 6 rows and HAL values.
 *
 * Memory (plan section 4): built when the panel opens, released when it closes (QuickPanel drops this
 * object), a section page built only when it is first shown; on open only the first page's values are
 * read ({@link Play6Snapshot#ROOT}), every page reads its own part when shown, a change reads its part
 * again (read-back). No polling, no wake lock.
 *
 * Owner rule (research battery_sound_features.md 5 and 6): on the Play 6 nothing unsupported is ever
 * shown, not even disabled. A row the current mode or input does not have is GONE, not disabled; a row
 * stays disabled only while its value is unknown (the HAL did not answer yet).
 *
 * Tiles and pages:
 *   Focus:       Autofocus now (307(44) through Hal), Manual focus (the overlay, 300), Smart autofocus
 *                (IGmpf2 64 then IGmpf 587 in stock order, state from IGmpf2 63; hidden while the IGmpf2
 *                guard is closed) and what it does
 *   Keystone:    Auto keystone now (326 on p6-long), Manual keystone (ManualKeystonePanel; hidden while
 *                the guard is closed), real-time keystone (585 / 586; no ceiling rule on the Play 6, OQ 14),
 *                auto keystone at power-on (552 / 553), Restore default picture (207 setIChipReset, O3
 *                approved), no fit to screen or obstacle avoidance (no hardware feature)
 *   Brightness:  light 1..10 (176 / 177; the row shows the read-back: the vendor lowers it on battery),
 *                Power saving (327 / 328, stock eco_mode_switch, first-time confirmations), what the
 *                vendor does on battery. Vendor power events 3 and 5 refresh the open page at once
 *                (battery/Battery, QuickPanel.refreshIfShowing), so the slider shows the vendor's level
 *   Picture:     picture mode AI / Movie / Sport / TV / Custom / Office / Performance (667 / 666, warning
 *                before Performance; while 666 reads 4 the row shows "Game" and is locked; 666 values
 *                10..17, the AI scene sub-modes, show as AI), brightness, contrast, saturation, sharpness
 *                (688-695, only in Custom, read only in Custom), motion smoothing (668 / 669, only in
 *                Movie, TV and Custom)
 *   Sound:       sound mode AI / Music / Movie / Karaoke / Sports / Standard (61 / 62)
 *   Input:       (no page) the Source overlay
 *   Projection:  Auto / Table / Ceiling / Rear projection (580 / 579, then 172 / 173; no 585 coupling)
 *   Game mode:   Game mode Off / On / Auto (game/GameMode, shown from IGmpf2 33) and Aspect ratio (665 / 664
 *                with the vendor values 4:3 0, 16:9 1, Auto 2 (full screen), Original 4; Original only for
 *                a signal smaller than the panel, IGmpf 662), only while HDMI is the current source (696
 *                reads 1, re-checked on p6-hal before every write; the vendor keeps both per source); off
 *                HDMI only the "HDMI only" line. No Auto game mode (ALLM) row: stock has none
 *   Eye protection: no tile and no page until the NUI fifo reader exists (Device.hasEyeProtection, plan
 *                step 18); Lumen Home is told to hide its tile (device/DeviceStateProvider "caps")
 *   All settings: System settings, Projector settings, Recent apps, Home screen
 * No Battery tile, page or info line part (research 6.1 B8): the battery moved to Lumen Home, which reads
 * device/DeviceStateProvider; this app keeps the power listener, the cache and the cards (battery/*).
 * Other modules append rows through {@link QuickPanel#addExtension}.
 *
 * Rules kept here: every HAL call goes through Hal.run / Hal.query (never the main thread; the facade
 * refuses everything until the start gate is open); setters run only on a user action; every listener is
 * wrapped in try/catch (row commits run from plain Handlers in the persistent process).
 */
final class Play6PanelController {
    private static final String TAG = "P6Panel";

    private static final long CHOICE_COMMIT_MS = 500;
    private static final long PICTURE_MODE_COMMIT_MS = 700;
    private static final long SLIDER_GUARD_MS = 800;
    /** Section key of the tile focused last (restored at the next open). */
    private static final String KEY_LAST_TILE = "last_tile";
    /** boolean: the user confirmed turning power saving on (off) once; stock asks the first time only. */
    private static final String KEY_ECO_ON_CONFIRMED = "eco_on_confirmed";
    private static final String KEY_ECO_OFF_CONFIRMED = "eco_off_confirmed";

    /** Picture modes of the Play 6 in the stock dayao order (plan 1.4). */
    private static final int[] PM_ORDER = {Play6Gmpf.PICTURE_AI, Play6Gmpf.PICTURE_MOVIE, Play6Gmpf.PICTURE_SPORT,
            Play6Gmpf.PICTURE_TV, Play6Gmpf.PICTURE_CUSTOM, Play6Gmpf.PICTURE_OFFICE, Play6Gmpf.PICTURE_PERFORMANCE};
    /** Sound modes of the Play 6 (plan 1.6), Standard included (stock offers it). */
    private static final int[] SOUND_ORDER = {Play6Gmpf.SOUND_AI, Play6Gmpf.SOUND_MUSIC, Play6Gmpf.SOUND_MOVIE,
            Play6Gmpf.SOUND_KARAOKE, Play6Gmpf.SOUND_SPORTS, Play6Gmpf.SOUND_STANDARD};
    /**
     * Aspect ratio rows -> 665 vendor values (t15, research hdmi_volume_game.md 4: Auto 2 = full screen,
     * 16:9 1, 4:3 0, Original 4); without Original while the signal is not smaller than the panel.
     */
    private static final int[] ASPECT_ORDER = {Play6Gmpf.ASPECT_AUTO, Play6Gmpf.ASPECT_16_9, Play6Gmpf.ASPECT_4_3,
            Play6Gmpf.ASPECT_ORIGINAL};
    private static final int[] ASPECT_ORDER_NO_ORIGINAL = {Play6Gmpf.ASPECT_AUTO, Play6Gmpf.ASPECT_16_9,
            Play6Gmpf.ASPECT_4_3};
    private static final int PROJ_AUTO = 0, PROJ_TABLE = 1, PROJ_CEILING = 2;
    /**
     * P3 item 21 + stock parity: ONE tile "Speaker" for the stock ambient speaker (氛围音箱): the Bluetooth speaker
     * mode (speaker/SpeakerMode) and the ambient light (ambient/AmbientLight) on one page, so the tile grid keeps
     * three rows (device test 2026-10-21: a tenth tile pushed "All settings" off the panel).
     */
    static final String SECTION_SPEAKER = "speaker";
    /**
     * Owner 2026-10-21: the "Night light" tile. OK turns the ambient light on at once (last colour, the night light's
     * effect, default "Always on"; ambient/AmbientLight.openNight); MENU or a held OK on the tile opens its page
     * (colour, effect, brightness). "All settings" became a square tile so that the 11 tiles fit in 4 rows.
     */
    static final String SECTION_NIGHT = "night";

    private final Context app;
    private final SharedPreferences prefs;
    private QuickPanelView panel;
    /** Every value read since the panel opened, merged per part (Play6Snapshot.merge). */
    private Play6Snapshot snap = new Play6Snapshot();
    /** The last read failed as a whole (HAL not ready). */
    private boolean notReady;
    /** Section pages built so far, by QuickPanel.SECTION_* key (SECTION_INPUT has none). */
    private final Map<String, Page> pages = new HashMap<>();
    private boolean released;

    // root page: info line + tile grid
    private final Page root;
    private TextRow info;
    private TileGrid grid;
    /** Index of the grid in the root page's rows (PagedPanel focus restore). */
    private int gridRow;

    // section rows (null until their page is built)
    private ToggleRow smartAf, rtKst, powerOnKst, eco, eye;
    private TextRow smartAfDesc, picGameHint, gameHint;
    private NavRow manualKst, zoomShift, rotation;
    private SliderRow lamp;
    private final SliderRow[] pq = new SliderRow[4];
    private ChoiceRow pictureMode, memc, soundMode, gameMode, aspect, launcherRow;
    private boolean pmShowsGame;
    /** The aspect row's options right now (ASPECT_ORDER or ASPECT_ORDER_NO_ORIGINAL). */
    private int[] aspectOrder = ASPECT_ORDER_NO_ORIGINAL;
    private CheckRow pAuto, pTable, pCeiling;
    private ChoiceRow ambColor, ambEffect, ambLevel, lidRow;
    private NavRow spkOn;
    private TextRow ambDesc;
    // stock parity picture rows (Custom items, local contrast, HDR) and HDMI rows (2026-10-21)
    private ChoiceRow eqRow;
    private P6ChoiceRow soundOut;
    private java.util.List<org.z9x.projector.audio.AudioOutputChoice.Option> outOptions;
    private ToggleRow earcRow;
    private ChoiceRow nrRow, ctRow, gammaRow, lcRow, styleRow, hdmiRangeRow, edidRow;
    private final SliderRow[] wbRows = new SliderRow[6];
    private ToggleRow dcRow, hdrRow;
    private NavRow picReset;
    /** Picture style Custom: R / G / B light levels 1..10 (IGmpf 179 / 178) and stock "Restore default" (176(10)). */
    private final SliderRow[] rgbRows = new SliderRow[3];
    private NavRow rgbReset;
    /** Keystone page: stock "Restore default picture" (207 reset, owner O3). */
    private NavRow kstReset;
    /** Picture styles in row order (174 values, stock color_style_*). */
    private static final int[] STYLE_ORDER = {Play6Gmpf.STYLE_STANDARD, Play6Gmpf.STYLE_MOVIE_D65, Play6Gmpf.STYLE_OFFICE,
            Play6Gmpf.STYLE_CUSTOM};
    private ToggleRow pRear;

    Play6PanelController(Context app) {
        this.app = app.getApplicationContext();
        this.prefs = this.app.getSharedPreferences(QuickPanel.PREFS, Context.MODE_PRIVATE);
        root = buildRoot();
        String last = prefs.getString(KEY_LAST_TILE, null);
        if (QuickPanel.SECTION_MANUAL_KEYSTONE.equals(last)) last = QuickPanel.SECTION_KEYSTONE;
        if (last != null) grid.remember(last);
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
            if (panel == null || !panel.isShowing()) release();   // no panel of ours on screen: nothing to keep
            return;
        }
        if (QuickPanel.SECTION_MANUAL_KEYSTONE.equals(key)) {
            grid.remember(QuickPanel.SECTION_KEYSTONE);      // its entry in the panel is the Keystone page
            prefs.edit().putString(KEY_LAST_TILE, QuickPanel.SECTION_KEYSTONE).apply();
            ManualKeystonePanel.open(app, null);             // not from the panel: BACK just closes
            if (panel == null || !panel.isShowing()) release();
            return;
        }
        Page target = key == null ? null : page(key);
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
        if (!host.show(v)) {
            Log.w(TAG, "quick panel window could not be added");
            release();
        }
    }

    /** Locale change (QuickPanel): close the quick panel if it is the panel shown. */
    void close() {
        if (panel != null && panel.isShowing() && OverlayHost.get(app).current() == panel) panel.dismiss();
        else release();
    }

    /**
     * {@code v} finished closing. A view of an earlier open may finish its exit animation after this
     * controller opened a new view (gear pressed twice quickly, a SHOW_PANEL during the close): that
     * late call must not release the controller of the panel now on screen.
     */
    void onPanelClosed(QuickPanelView v) {
        if (v != panel) {
            Log.i(TAG, "an earlier panel view closed after a new open: controller kept");
            return;
        }
        grid.settle();
        String last = grid.lastSection();
        if (last != null && !last.equals(prefs.getString(KEY_LAST_TILE, null))) {
            prefs.edit().putString(KEY_LAST_TILE, last).apply();
        }
        release();
    }

    /** Releases this controller (QuickPanel builds a new one at the next open). */
    private void release() {
        if (released) return;
        released = true;
        QuickPanel.released(this);
    }

    /** Open animation of the page shown first (QuickPanelView.onShown). */
    void animateIn(Page page) {
        if (page == root) {
            info.setAlpha(0f);
            info.animate().alpha(1f).setStartDelay(60).setDuration(200).start();
            grid.staggerIn(40);
            return;
        }
        // A section page opened directly (KEYSTONE key, a Home tile): rows slide in, 16 ms apart.
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
        if (SECTION_SPEAKER.equals(section)) {               // one press: the ambient speaker at once (stock)
            if (setupDone("ambient speaker")) closeThen(() -> org.z9x.projector.speaker.SpeakerMode.openAmbient(app));
            return;
        }
        if (QuickPanel.SECTION_INPUT.equals(section)) {
            // replaces the quick panel; BACK there returns to the tile grid (Input tile remembered)
            if (setupDone("input")) SourceOverlay.showFromQuickPanel(app);
            return;
        }
        Page p = page(section);
        if (p == null) return;
        grid.settle();                                       // a stagger still running ends now
        p.setInitialFocus(-1);                               // a page always opens at its first row
        panel.push(p);
    }

    /**
     * The section page, built on first use (with the extensions of other modules); null = no page (an
     * unsupported section: Lumen Home's eye tile then opens the tile grid).
     */
    private Page page(String key) {
        Page p = pages.get(key);
        if (p != null) return p;
        switch (key) {
            case QuickPanel.SECTION_FOCUS: p = buildFocusPage(); break;
            case QuickPanel.SECTION_KEYSTONE: p = buildKeystonePage(); break;
            case QuickPanel.SECTION_LAMP: p = buildLampPage(); break;
            case QuickPanel.SECTION_PICTURE: p = buildPicturePage(); break;
            case QuickPanel.SECTION_SOUND: p = buildSoundPage(); break;
            case QuickPanel.SECTION_PROJECTION_MODE: p = buildProjectionPage(); break;
            case QuickPanel.SECTION_GAME: p = buildGamePage(); break;
            case QuickPanel.SECTION_EYE: p = Device.hasEyeProtection() ? buildEyePage() : null; break;
            case QuickPanel.SECTION_GENERAL: p = buildSettingsPage(); break;
            case SECTION_SPEAKER: p = buildSpeakerLightPage(); break;
            default: p = null; break;
        }
        if (p == null) return null;
        for (QuickPanel.Extension x : QuickPanel.extensions(key)) addRows(key, p, x);
        apply();                                             // known values at once, the read follows
        return p;
    }

    private Page newPage(String key, int titleRes) {
        Page p = new Page(s(titleRes));
        pages.put(key, p);
        p.setOnShown(() -> onPageShown(key));
        return p;
    }

    private void onPageShown(String key) {
        refresh(partOf(key));
        for (QuickPanel.Extension x : QuickPanel.extensions(key)) {
            try {
                x.onPageShown();
            } catch (Throwable t) {
                Log.e(TAG, "extension onPageShown " + key, t);
            }
        }
    }

    /** The Play6Snapshot part a section page reads when it is shown (0 = no HAL value). */
    private static int partOf(String key) {
        switch (key) {
            case QuickPanel.SECTION_FOCUS: return Play6Snapshot.FOCUS;
            case QuickPanel.SECTION_KEYSTONE: return Play6Snapshot.KEYSTONE;
            case QuickPanel.SECTION_LAMP: return Play6Snapshot.LAMP;
            case QuickPanel.SECTION_PICTURE: return Play6Snapshot.PICTURE;
            case QuickPanel.SECTION_SOUND: return Play6Snapshot.SOUND;
            case QuickPanel.SECTION_PROJECTION_MODE: return Play6Snapshot.PROJECTION;
            case QuickPanel.SECTION_GAME: return Play6Snapshot.GAME;
            case QuickPanel.SECTION_EYE: return Play6Snapshot.EYE;
            case SECTION_SPEAKER: return Play6Snapshot.AMBIENT;
            default: return 0;
        }
    }

    /** QuickPanel.addExtension while this controller exists (main thread). */
    void addExtension(String key, QuickPanel.Extension x) {
        Page p = pages.get(key);
        if (p == null) return;                               // added when the page is built
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

    private static int indexOf(int[] arr, Integer v) {
        if (v == null) return -1;
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    // ================================================================== HAL helpers
    private boolean ready() {
        if (Hal.isReady()) return true;
        Ui.toast(app, R.string.toast_not_ready);
        return false;
    }

    /** User action: run {@code t} on p6-hal (failure -> "Could not do that"), then read {@code parts} back. */
    private void change(String what, int parts, Hal.Task t) {
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
        refresh(parts);
    }

    /**
     * p6-hal, before every HDMI-only setter (game mode, 665): true when IGmpf 696 reports the HDMI port as
     * the current source right now (t15: the vendor applies both to the current source, and since t14 our
     * IGmpf 698 makes HDMI the source while it is shown). Otherwise "HDMI only" and false.
     */
    private boolean hdmiNow(Play6Gmpf g, String what) {
        int src = -1;
        try { src = g.getCurrentInputSource(); } catch (Throwable t) { Log.w(TAG, what + ": 696 " + t); }
        if (src == Play6Gmpf.SOURCE_HDMI1) return true;
        Log.i(TAG, what + " refused: HDMI is not the current source (696=" + src + ")");
        Notify.show(app, s(R.string.panel_game_hdmi_only));
        return false;
    }

    /** QuickPanel.refreshIfShowing: the current page's values, only while the quick panel is on screen. */
    void refreshIfShowing() {
        if (panel == null || !panel.isShowing() || OverlayHost.get(app).current() != panel) return;
        Page cur = panel.currentPage();
        if (cur == root) { refresh(Play6Snapshot.ROOT); return; }
        for (Map.Entry<String, Page> e : pages.entrySet()) {
            if (e.getValue() == cur) refresh(partOf(e.getKey()));
        }
    }

    /** Reads {@code parts} (one HAL task) and updates the rows on the main thread. 0 = rows only. */
    private void refresh(final int parts) {
        if (parts == 0) {
            apply();
            return;
        }
        Hal.query((g, g2) -> {
            Play6Snapshot sn = Play6Snapshot.read(g, g2, parts);
            if ((parts & (Play6Snapshot.ROOT | Play6Snapshot.GAME)) != 0) sn.tifHdmi = TifHdmiState.read(app);
            return sn;
        }, v -> {
            if (released) return;
            if (v == null || !Hal.isReady()) {
                notReady = true;
                snap = new Play6Snapshot();                  // nothing is known now
            } else {
                notReady = false;
                snap.merge(v);
            }
            apply();
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

    private boolean setupDone(String what) {
        if (KeyReceiver.isSetupComplete(app)) return true;
        Log.i(TAG, "setup not complete: " + what + " row ignored");
        Ui.toast(app, R.string.toast_setup_running);
        return false;
    }

    // ================================================================== root: the tile grid
    private Page buildRoot() {
        Page p = new Page(s(R.string.panel_title));
        info = p.add(new TextRow(app, ""));
        grid = p.add(new TileGrid(app));
        gridRow = p.rows().indexOf(grid);
        addTile(QuickPanel.SECTION_FOCUS, R.drawable.ic_tile_focus, R.string.panel_tile_focus, false);
        addTile(QuickPanel.SECTION_KEYSTONE, R.drawable.ic_tile_keystone, R.string.panel_tile_keystone, false);
        addTile(QuickPanel.SECTION_LAMP, R.drawable.ic_tile_brightness, R.string.panel_lamp, false);
        addTile(QuickPanel.SECTION_PICTURE, R.drawable.ic_tile_picture, R.string.panel_sec_picture, false);
        addTile(QuickPanel.SECTION_SOUND, R.drawable.ic_tile_sound, R.string.panel_sec_sound, false);
        addTile(QuickPanel.SECTION_INPUT, R.drawable.ic_tile_input, R.string.panel_tile_input, false);
        addTile(QuickPanel.SECTION_PROJECTION_MODE, R.drawable.ic_tile_projection, R.string.panel_tile_projection, false);
        addTile(QuickPanel.SECTION_GAME, R.drawable.ic_tile_game, R.string.panel_tile_game, false);
        addTile(SECTION_SPEAKER, R.drawable.ic_tile_speaker, R.string.p6_tile_speaker, false);
        grid.tile(SECTION_SPEAKER).setVisibility(View.GONE);          // A2DP sink role or ambient light (apply)
        if (Device.hasEyeProtection()) {
            addTile(QuickPanel.SECTION_EYE, R.drawable.ic_tile_eye, R.string.panel_tile_eye, false);
        }
        addTile(QuickPanel.SECTION_GENERAL, R.drawable.ic_tile_settings, R.string.panel_all_settings, false);
        p.setInitialFocus(gridRow);
        p.setOnShown(() -> refresh(Play6Snapshot.ROOT));
        return p;
    }

    // ================================================================== focus page (plan 1.2)
    private Page buildFocusPage() {
        Page p = newPage(QuickPanel.SECTION_FOCUS, R.string.panel_tile_focus);
        p.add(new NavRow(app, s(R.string.panel_af_now),
                () -> closeThen(() -> Hal.requestAutofocus(app))).setChevron(false));
        p.add(new NavRow(app, s(R.string.panel_manual_focus),
                () -> closeThen(() -> Hal.openManualFocus(app))));
        smartAf = p.add(new ToggleRow(app, s(R.string.panel_smart_af), (row, w) -> safe("smart af", () ->
                change("smart autofocus " + w, Play6Snapshot.FOCUS, (g, g2) -> Hal.setSmartAutofocus(g, g2, w))).run()));
        smartAfDesc = p.add(new TextRow(app, s(R.string.p6_smart_af_desc)));
        return p;
    }

    // ================================================================== keystone page (plan 1.3)
    private Page buildKeystonePage() {
        Page p = newPage(QuickPanel.SECTION_KEYSTONE, R.string.panel_tile_keystone);
        p.add(new NavRow(app, s(R.string.panel_ak_now),
                () -> closeThen(() -> Hal.requestKeystone(app))).setChevron(false));
        // ManualKeystonePanel checks everything on p6-hal and says exactly why it refuses
        manualKst = p.add(new NavRow(app, s(R.string.panel_manual_keystone), safe("manual keystone",
                () -> ManualKeystonePanel.open(app, () -> QuickPanel.show(app, QuickPanel.SECTION_KEYSTONE)))));
        // P3 item 21 (stock Picture correction): shown only when their replies parse (Play6Snapshot probes)
        zoomShift = p.add(new NavRow(app, s(R.string.zs_title), safe("zoom and shift",
                () -> Play6ZoomShiftPanel.open(app, () -> QuickPanel.show(app, QuickPanel.SECTION_KEYSTONE)))));
        zoomShift.setVisibility(View.GONE);
        rotation = p.add(new NavRow(app, s(R.string.rot_title), safe("picture rotation",
                () -> Play6RotatePanel.open(app, () -> QuickPanel.show(app, QuickPanel.SECTION_KEYSTONE)))));
        rotation.setVisibility(View.GONE);
        kstReset = p.add(new NavRow(app, s(R.string.panel_reset_keystone), () -> confirm(R.string.panel_reset_keystone,
                s(R.string.panel_reset_keystone_msg), R.string.panel_reset, () -> change("207 keystone reset", Play6Snapshot.KEYSTONE,
                        (g, g2) -> resetKeystone(g))))).setChevron(false);
        p.add(new HeaderRow(app, s(R.string.panel_image_correction_settings)));
        rtKst = p.add(new ToggleRow(app, s(R.string.panel_rt_keystone), (row, w) -> safe("585", () ->
                change("585 real-time keystone " + w, Play6Snapshot.KEYSTONE, (g, g2) -> g.setMoveAk(w))).run()));
        powerOnKst = p.add(new ToggleRow(app, s(R.string.panel_power_on_keystone), (row, w) -> safe("552", () ->
                change("552 power-on keystone " + w, Play6Snapshot.KEYSTONE, (g, g2) -> g.setPowerOnAk(w))).run()));
        return p;
    }

    // ================================================================== brightness page (plan 1.4)
    private Page buildLampPage() {
        Page p = newPage(QuickPanel.SECTION_LAMP, R.string.panel_lamp);
        lamp = p.add(new P6SliderRow(app, s(R.string.panel_lamp), 1, 10, new SliderRow.OnSlide() {
            @Override public void onChanging(SliderRow row, int value) { touch(row); }
            @Override public void onCommit(SliderRow row, int value) {
                safe("lamp", () -> {
                    if (value > 10 || value < 1) return;
                    change("176 lamp " + value, Play6Snapshot.LAMP, (g, g2) -> g.setLampLevel(value));
                }).run();
            }
        }));
        if (Device.hasBattery()) {
            eco = p.add(new ToggleRow(app, s(R.string.p6_eco), (row, w) -> safe("eco", () -> onEco(row, w)).run()));
            p.add(new TextRow(app, s(R.string.p6_lamp_hint)));
        }
        return p;
    }

    /**
     * Power saving (research battery_sound_features.md 1.4 and 6.1 B6; stock Settings > General
     * eco_mode_switch, IGmpf 327 / 328). Written on the user's switch action only; the first time each way
     * the stock confirmation comes first (on: what power saving lowers; off: the consumption risk on the
     * stand or a power bank). The read-back reads 327 and 177: the vendor changes the light with it.
     */
    private void onEco(ToggleRow row, boolean wanted) {
        if (snap.eco == null) {
            refresh(Play6Snapshot.LAMP);
            return;
        }
        final String key = wanted ? KEY_ECO_ON_CONFIRMED : KEY_ECO_OFF_CONFIRMED;
        if (prefs.getBoolean(key, false)) {
            applyEco(wanted);
            return;
        }
        row.setChecked(!wanted);                             // until confirmed
        confirm(wanted ? R.string.p6_eco_on_title : R.string.p6_eco_off_title,
                s(wanted ? R.string.p6_eco_on_msg : R.string.p6_eco_off_msg),
                wanted ? R.string.p6_eco_turn_on : R.string.p6_eco_turn_off, () -> {
                    prefs.edit().putBoolean(key, true).apply();
                    applyEco(wanted);
                });
    }

    /** 328 setEcoState(on), then 327 and 177 read back (Play6Snapshot.LAMP). */
    private void applyEco(boolean on) {
        change("328 power saving " + on, Play6Snapshot.LAMP, (g, g2) -> g.setEcoState(on));
    }

    // ================================================================== picture page (plan 1.4)
    private Page buildPicturePage() {
        Page p = newPage(QuickPanel.SECTION_PICTURE, R.string.panel_sec_picture);
        pictureMode = p.add(new P6ChoiceRow(app, s(R.string.panel_picture_mode), pmLabels(),
                (row, i) -> safe("picture mode", () -> onPictureModeChosen(i)).run()));
        pictureMode.setCommitDelay(PICTURE_MODE_COMMIT_MS);
        pictureMode.setOnOpen(safe("picture mode list", this::openPictureModeList));
        picGameHint = p.add(new TextRow(app, s(R.string.p6_pic_game_hint)));
        picGameHint.setVisibility(View.GONE);
        int[] titles = {R.string.panel_pq_brightness, R.string.panel_pq_contrast, R.string.panel_pq_saturation,
                R.string.panel_pq_sharpness};
        for (int i = 0; i < pq.length; i++) {
            final int item = i;
            pq[i] = p.add(new P6SliderRow(app, s(titles[i]), 0, 100, new SliderRow.OnSlide() {
                @Override public void onChanging(SliderRow row, int value) { touch(row); }
                @Override public void onCommit(SliderRow row, int value) {
                    safe("pq", () -> onPq(item, value)).run();
                }
            }));
            pq[i].setVisibility(View.GONE);                  // only in Custom (apply)
        }
        memc = p.add(new P6ChoiceRow(app, s(R.string.panel_memc),
                labels(app, R.string.panel_off, R.string.panel_low, R.string.panel_medium, R.string.panel_high),
                (row, i) -> safe("memc", () -> {
                    Integer pm = snap.pictureMode;
                    if (snap.memc == null || pm == null || !Play6Snapshot.memcMode(pm) || i < 0 || i > 3) {
                        refresh(Play6Snapshot.PICTURE);
                        return;
                    }
                    change("669 memc " + i, Play6Snapshot.PICTURE, (g, g2) -> {
                        if (!g.setMemcLevel(i)) throw new IllegalStateException("669 returned false");
                    });
                }).run()));
        memc.setCommitDelay(CHOICE_COMMIT_MS);
        memc.setVisibility(View.GONE);                       // only in Movie, TV and Custom (apply)
        addStockPictureRows(p);
        return p;
    }

    /**
     * Stock parity (2026-10-21, dayao tree): Custom = noise reduction, colour temperature with RGB gains / offsets
     * (Custom temperature), gamma, dynamic contrast, picture style, reset; Movie / TV / Custom = local contrast;
     * AI / Movie / Sport / TV / Custom / Office = HDR. A row exists only while its mode has it and its value was read (never disabled).
     */
    private void addStockPictureRows(Page p) {
        nrRow = p.add(new P6ChoiceRow(app, s(R.string.pro_noise), labels(app, R.string.panel_off, R.string.panel_low,
                R.string.panel_medium, R.string.panel_high, R.string.panel_auto), (row, i) -> safe("nr", () -> {
                    if (!custom() || i < 0 || i > 4) { refresh(Play6Snapshot.PICTURE); return; }
                    change("671 noise reduction " + i, Play6Snapshot.PICTURE, (g, g2) -> g.setNoiseReduction(i));
                }).run()));
        ctRow = p.add(new P6ChoiceRow(app, s(R.string.panel_color_temp), labels(app, R.string.p6_ct_cool,
                R.string.panel_pm_standard, R.string.p6_ct_warm, R.string.p6_pm_custom), (row, i) -> safe("ct", () -> {
                    if (!custom() || i < 0 || i > 3) { refresh(Play6Snapshot.PICTURE); return; }
                    change("685 color temperature " + i, Play6Snapshot.PICTURE, (g, g2) -> g.setColorTemperature(i));
                }).run()));
        int[] wbTitles = {R.string.pro_red_gain, R.string.pro_green_gain, R.string.pro_blue_gain, R.string.pro_red_offset,
                R.string.pro_green_offset, R.string.pro_blue_offset};
        for (int k = 0; k < wbRows.length; k++) {
            final int item = k;
            wbRows[k] = p.add(new P6SliderRow(app, s(wbTitles[k]), 0, 100, new SliderRow.OnSlide() {
                @Override public void onChanging(SliderRow row, int value) { touch(row); }
                @Override public void onCommit(SliderRow row, int value) { safe("wb", () -> onWb(item, value)).run(); }
            }));
        }
        CharSequence[] gammas = new CharSequence[9];
        for (int k = 0; k < 9; k++) gammas[k] = String.format(java.util.Locale.ROOT, "%.1f", 1.8f + 0.1f * k);
        gammaRow = p.add(new P6ChoiceRow(app, s(R.string.pro_gamma), gammas, (row, i) -> safe("gamma", () -> {
            if (!custom() || i < 0 || i > 8) { refresh(Play6Snapshot.PICTURE); return; }
            change("IGmpf2 229 gamma " + i, Play6Snapshot.PICTURE, (g, g2) -> g2.setGamma(i));
        }).run()));
        dcRow = p.add(new ToggleRow(app, s(R.string.pro_dynamic_contrast), (row, w) -> safe("dc", () -> {
            if (!custom()) { refresh(Play6Snapshot.PICTURE); return; }
            change("IGmpf2 231 dynamic contrast " + w, Play6Snapshot.PICTURE, (g, g2) -> g2.setDynamicContrast(w));
        }).run()));
        lcRow = p.add(new P6ChoiceRow(app, s(R.string.pro_local_contrast), labels(app, R.string.panel_off, R.string.panel_low,
                R.string.panel_medium, R.string.panel_high), (row, i) -> safe("lc", () -> {
                    Integer pm = Play6Snapshot.shownPictureMode(snap.pictureMode);
                    if (pm == null || !Play6Snapshot.memcMode(pm) || i < 0 || i > 3) { refresh(Play6Snapshot.PICTURE); return; }
                    change("655 local contrast " + i, Play6Snapshot.PICTURE, (g, g2) -> g.setLocalContrast(i));
                }).run()));
        // stock PictureViewModel: no HDR change on the HDMI input in HDMI 1.4 mode (EDID 1), a notice instead
        hdrRow = p.add(new ToggleRow(app, s(R.string.p6_hdr), (row, w) -> safe("hdr", () ->
                change("650 HDR auto " + w, Play6Snapshot.PICTURE, (g, g2) -> {
                    if (g.getCurrentInputSource() == Play6Gmpf.SOURCE_HDMI1 && g.getEdidVersion() == 1) {
                        Log.i(TAG, "650 not sent: HDMI 1.4 mode");
                        Ui.main().post(() -> Ui.toast(app, R.string.p6_hdr_hdmi_compat));
                        return;
                    }
                    g.setHdrAuto(w);
                })).run()));
        styleRow = p.add(new P6ChoiceRow(app, s(R.string.p6_style), labels(app, R.string.panel_pm_standard,
                R.string.p6_style_movie, R.string.panel_pm_office, R.string.p6_pm_custom), (row, i) -> safe("style", () -> {
                    if (!custom() || i < 0 || i >= STYLE_ORDER.length) { refresh(Play6Snapshot.PICTURE); return; }
                    final int st = STYLE_ORDER[i];
                    change("174 picture style " + st, Play6Snapshot.PICTURE | Play6Snapshot.LAMP, (g, g2) -> g.setPictureStyle(st));
                }).run()));
        int[] rgbTitles = {R.string.p6_light_red, R.string.p6_light_green, R.string.p6_light_blue};
        for (int k = 0; k < rgbRows.length; k++) {
            final int item = k;
            rgbRows[k] = p.add(new P6SliderRow(app, s(rgbTitles[k]), 1, 10, new SliderRow.OnSlide() {
                @Override public void onChanging(SliderRow row, int value) { touch(row); }
                @Override public void onCommit(SliderRow row, int value) { safe("rgb", () -> onRgb(item, value)).run(); }
            }));
        }
        rgbReset = p.add(new NavRow(app, s(R.string.p6_light_reset), () -> {
            if (!customStyle()) return;
            change("176 lamp level 10 (stock light reset)", Play6Snapshot.PICTURE | Play6Snapshot.LAMP, (g, g2) -> g.setLampLevel(10));
        }).setChevron(false));
        picReset = p.add(new NavRow(app, s(R.string.panel_picture_reset), () -> {
            if (!custom()) return;
            confirm(R.string.panel_picture_reset, s(R.string.p6_pic_reset_msg), R.string.panel_reset,
                    () -> change("658 reset custom picture", Play6Snapshot.PICTURE, (g, g2) -> g.resetCustomPicture()));
        }).setChevron(false));
        for (ChoiceRow r : new ChoiceRow[] {nrRow, ctRow, gammaRow, lcRow, styleRow}) r.setCommitDelay(CHOICE_COMMIT_MS);
        for (View v : new View[] {nrRow, ctRow, gammaRow, dcRow, lcRow, hdrRow, styleRow, rgbReset, picReset}) v.setVisibility(View.GONE);
        for (SliderRow r : wbRows) r.setVisibility(View.GONE);
        for (SliderRow r : rgbRows) r.setVisibility(View.GONE);
    }

    private boolean custom() {
        return snap.pictureMode != null && snap.pictureMode == Play6Gmpf.PICTURE_CUSTOM;
    }

    /** Custom picture mode with the Custom picture style: the stock led_custom_mode_id page (R / G / B). */
    private boolean customStyle() {
        return custom() && snap.style != null && snap.style == Play6Gmpf.STYLE_CUSTOM && Play6Snapshot.rgbOk(snap.rgb);
    }

    /**
     * One light level (0 red, 1 green, 2 blue): as stock BrightnessManager.setLedRed / Green / Blue, the other two
     * come from a fresh 179 read, then 178 with all three.
     */
    private void onRgb(int item, int value) {
        if (!customStyle() || value < 1 || value > 10) { refresh(Play6Snapshot.PICTURE); return; }
        change("178 light level " + item + " " + value, Play6Snapshot.PICTURE, (g, g2) -> {
            int[] cur = g.getRgbLevels();
            if (!Play6Snapshot.rgbOk(cur)) throw new IllegalStateException("179 reply " + java.util.Arrays.toString(cur));
            int r = item == 0 ? value : cur[0], gr = item == 1 ? value : cur[1], b = item == 2 ? value : cur[2];
            g.setRgbLevels(r, gr, b);
        });
    }

    /**
     * p6-hal. Stock KeyStoneManager.resetKeyStone (setIChipReset) as stock sends it: real-time keystone is not
     * paused around it (stock has no 573 pair here; ours had one without a saved marker, so a process death in
     * between left real-time keystone paused; audit 2026-10-24, A6).
     */
    private void resetKeystone(Play6Gmpf g) throws android.os.RemoteException {
        Log.i(TAG, "keystone reset: 207(0), 130 -> " + g.resetKeystone());
        Notify.show(app, s(R.string.panel_reset_keystone_done));
    }

    /** RGB gain / offset (0 red gain 683, 1 green 681, 2 blue 679, 3 red offset 677, 4 green 675, 5 blue 673). */
    private void onWb(int item, int value) {
        if (!custom() || snap.colorTemp == null || snap.colorTemp != 3 || value < 0 || value > 100) {
            refresh(Play6Snapshot.PICTURE);
            return;
        }
        change("wb " + item + " " + value, Play6Snapshot.PICTURE, (g, g2) -> {
            boolean r;
            switch (item) {
                case 0: r = g.setRedGain(value); break;
                case 1: r = g.setGreenGain(value); break;
                case 2: r = g.setBlueGain(value); break;
                case 3: r = g.setRedOffset(value); break;
                case 4: r = g.setGreenOffset(value); break;
                default: r = g.setBlueOffset(value); break;
            }
            if (!r) throw new IllegalStateException("white balance item " + item + " returned false");
        });
    }

    /** A Custom item (0 brightness 693, 1 contrast 695, 2 saturation 691, 3 sharpness 689); Custom only. */
    private void onPq(int item, int value) {
        if (snap.pictureMode == null || snap.pictureMode != Play6Gmpf.PICTURE_CUSTOM || value < 0 || value > 100) {
            refresh(Play6Snapshot.PICTURE);
            return;
        }
        change("pq " + item + " " + value, Play6Snapshot.PICTURE, (g, g2) -> {
            boolean r;
            switch (item) {
                case 0: r = g.setBrightness(value); break;
                case 1: r = g.setContrast(value); break;
                case 2: r = g.setSaturation(value); break;
                default: r = g.setSharpness(value); break;
            }
            if (!r) throw new IllegalStateException("custom item " + item + " returned false");
        });
    }

    private CharSequence[] pmLabels() {
        CharSequence[] out = new CharSequence[PM_ORDER.length];
        for (int i = 0; i < PM_ORDER.length; i++) out[i] = pmLabel(PM_ORDER[i]);
        return out;
    }

    private String pmLabel(int m) {
        switch (m) {
            case Play6Gmpf.PICTURE_AI: return s(R.string.panel_pm_ai);
            case Play6Gmpf.PICTURE_MOVIE: return s(R.string.p6_pm_movie);
            case Play6Gmpf.PICTURE_SPORT: return s(R.string.panel_pm_sport);
            case Play6Gmpf.PICTURE_TV: return s(R.string.p6_pm_tv);
            case Play6Gmpf.PICTURE_CUSTOM: return s(R.string.p6_pm_custom);
            case Play6Gmpf.PICTURE_OFFICE: return s(R.string.panel_pm_office);
            case Play6Gmpf.PICTURE_PERFORMANCE: return s(R.string.panel_pm_performance);
            case Play6Gmpf.PICTURE_GAME: return s(R.string.p6_pm_game);
            default: return null;
        }
    }

    /** Tile value line: short forms where the full name does not fit a square tile in some languages. */
    private String pmTileLabel(int m) {
        switch (m) {
            case Play6Gmpf.PICTURE_AI: return s(R.string.panel_tileval_pm_ai);
            case Play6Gmpf.PICTURE_PERFORMANCE: return s(R.string.panel_tileval_pm_performance);
            default: return pmLabel(m);
        }
    }

    private void openPictureModeList() {
        if (snap.pictureMode == null || snap.gamePicture()) { ready(); return; }
        Page p = new Page(s(R.string.panel_picture_mode));
        int cur = Play6Snapshot.shownPictureMode(snap.pictureMode);
        int focus = -1;
        for (int i = 0; i < PM_ORDER.length; i++) {
            final int idx = i;
            final int m = PM_ORDER[i];
            if (m == cur) focus = i;
            p.add(new CheckRow(app, pmLabel(m), m == cur, safe("picture mode item", () -> {
                if (panel != null) panel.pop();
                pictureMode.setSelected(idx);
                onPictureModeChosen(idx);
            })));
        }
        p.setInitialFocus(focus);
        if (panel != null && panel.isShowing()) panel.push(p);
    }

    private void onPictureModeChosen(int i) {
        if (snap.pictureMode == null || snap.gamePicture() || i < 0 || i >= PM_ORDER.length) {
            refresh(Play6Snapshot.PICTURE);
            return;
        }
        final int m = PM_ORDER[i];
        if (m == Play6Snapshot.shownPictureMode(snap.pictureMode)) return;   // also AI while 666 reads 10..17
        if (m == Play6Gmpf.PICTURE_PERFORMANCE) {
            // Stock heat warning before Performance.
            confirm(R.string.panel_pm_performance, s(R.string.panel_pm_performance_msg),
                    R.string.panel_switch, () -> applyPictureMode(m));
            refresh(Play6Snapshot.PICTURE);                  // a canceled confirm leaves the row on the real value
            return;
        }
        applyPictureMode(m);
    }

    /** 667 setPictureMode(0, m). The vendor changes linked values: the read-back shows them. */
    private void applyPictureMode(int m) {
        change("667 picture mode " + m, Play6Snapshot.PICTURE, (g, g2) -> {
            if (!g.setPictureMode(m)) throw new IllegalStateException("667 returned false");
        });
    }

    // ================================================================== sound page (plan 1.6)
    private Page buildSoundPage() {
        Page p = newPage(QuickPanel.SECTION_SOUND, R.string.panel_sec_sound);
        final CharSequence[] soundLabels = soundLabels();
        soundMode = p.add(new P6ChoiceRow(app, s(R.string.panel_sound_mode), soundLabels,
                (row, i) -> safe("sound mode", () -> {
                    if (snap.soundEffect == null || i < 0 || i >= SOUND_ORDER.length) { refresh(Play6Snapshot.SOUND); return; }
                    final int m = SOUND_ORDER[i];
                    final CharSequence title = row.getTitle();
                    change("61 sound " + m, Play6Snapshot.SOUND, (g, g2) -> {
                        g.setSoundEffect(m);                       // 61
                        org.z9x.projector.audio.P6SoundEq.afterSoundModeChange(app);
                        Notify.show(app, title + ": " + soundLabels[i]);
                    });
                }).run()));
        soundMode.setCommitDelay(CHOICE_COMMIT_MS);
        // Lumen 1.0.6 (owner 2026-10-24, as on the Z9X): manual sound output, only connected outputs are listed
        soundOut = (P6ChoiceRow) p.add(new P6ChoiceRow(app, s(R.string.sound_output),
                labels(app, R.string.sound_output_auto, R.string.sound_output_speaker),
                (row, i) -> safe("sound output", () -> {
                    java.util.List<org.z9x.projector.audio.AudioOutputChoice.Option> o = outOptions;
                    if (o == null || i < 0 || i >= o.size()) { refresh(Play6Snapshot.SOUND); return; }
                    org.z9x.projector.audio.AudioOutputChoice.Option opt = o.get(i);
                    org.z9x.projector.audio.AudioOutputChoice.choose(app, opt.kind, opt.addr);
                    Notify.show(app, row.getTitle(), opt.label);
                }).run()));
        soundOut.setCommitDelay(CHOICE_COMMIT_MS);
        // owner 2026-10-21: the equalizer of the Z9X on the Play 6 speaker (audio/P6SoundEq), only while it can work
        eqRow = p.add(new P6ChoiceRow(app, s(R.string.eq_title), labels(app, R.string.eq_preset_flat, R.string.eq_preset_bass,
                R.string.eq_preset_voice, R.string.eq_preset_night),
                (row, i) -> safe("eq", () -> {
                    if (!Boolean.TRUE.equals(snap.eqOk)) { refresh(Play6Snapshot.SOUND); return; }
                    org.z9x.projector.audio.P6SoundEq.choose(app, i);
                }).run()));
        eqRow.setCommitDelay(CHOICE_COMMIT_MS);
        eqRow.setVisibility(View.GONE);
        // stock parity (dayao voice_earc_sound_v5, Auto / Off), as the Z9X 1.0.4 row: only where 632 answers
        earcRow = p.add(new ToggleRow(app, "eARC", (row, w) -> safe("633", () ->
                change("633 eARC " + w, Play6Snapshot.SOUND, (g, g2) -> {
                    if (!g.setEarc(w)) throw new IllegalStateException("633 returned false");
                })).run()));
        earcRow.setVisibility(View.GONE);
        return p;
    }

    private CharSequence[] soundLabels() {
        return labels(app, R.string.panel_sound_ai, R.string.panel_sound_music, R.string.panel_sound_movie,
                R.string.panel_sound_karaoke, R.string.panel_sound_sports, R.string.panel_sound_standard);
    }

    /** Tile value of a sound mode (the AI one has a short tile form). */
    private String soundTileLabel(int i) {
        return i == 0 ? s(R.string.panel_tileval_sound_ai) : soundLabels()[i].toString();
    }

    // ================================================================== projection page (plan 1.7)
    private Page buildProjectionPage() {
        Page p = newPage(QuickPanel.SECTION_PROJECTION_MODE, R.string.panel_projection_mode);
        pAuto = p.add(new CheckRow(app, s(R.string.panel_proj_auto), false, safe("proj auto", () -> onProjection(PROJ_AUTO))));
        pTable = p.add(new CheckRow(app, s(R.string.panel_proj_table), false, safe("proj table", () -> onProjection(PROJ_TABLE))));
        pCeiling = p.add(new CheckRow(app, s(R.string.panel_proj_ceiling), false, safe("proj ceiling", () -> onProjection(PROJ_CEILING))));
        pRear = p.add(new ToggleRow(app, s(R.string.panel_proj_rear), (row, w) -> safe("proj rear", () -> onRear(row, w)).run()));
        return p;
    }

    /** Stock sequence (plan 1.7): auto = 580(true); table / ceiling = 580(false) then 172; rear = 172 alone. */
    private void onProjection(int kind) {
        if (snap.putMode == null || snap.autoReverse == null) { ready(); return; }
        final int cur = snap.putMode;
        final boolean auto = snap.autoReverse;
        if (kind == PROJ_AUTO) {
            if (auto) return;
            confirm(R.string.panel_projection_mode, s(R.string.panel_proj_confirm_flip), R.string.panel_switch,
                    () -> change("580 auto projection on", Play6Snapshot.PROJECTION, (g, g2) -> g.setAutoProjection(true)));
            return;
        }
        boolean front = cur == 0 || cur == 1;
        final int target = kind == PROJ_TABLE ? (front ? 0 : 2) : (front ? 1 : 3);
        if (target == cur) {
            if (auto) change("580 auto projection off", Play6Snapshot.PROJECTION, (g, g2) -> g.setAutoProjection(false));
            return;
        }
        askPutMode(target, true);
    }

    /** Rear switch: from 0/2 on -> 2, off -> 0; from 1/3 on -> 3, off -> 1. */
    private void onRear(ToggleRow row, boolean wanted) {
        if (snap.putMode == null) { ready(); return; }
        int cur = snap.putMode;
        boolean table = cur == 0 || cur == 2;
        int target = wanted ? (table ? 2 : 3) : (table ? 0 : 1);
        row.setChecked(!wanted);               // until confirmed
        if (target != cur) askPutMode(target, false);
    }

    /**
     * No 585 or IGmpf2 coupling on the Play 6 (plan 1.7): the confirmation only says the picture flips. Table and
     * Ceiling turn auto flip off first (stock setProjectorPutModeV5: 580(false), 172); the Rear switch sends 172
     * alone and leaves auto flip as it is (stock ProjectionRepository.setRearProjection, audit 2026-10-24).
     */
    private void askPutMode(int target, boolean autoOff) {
        if (target < 0 || target > 3) return;
        confirm(R.string.panel_projection_mode, s(R.string.panel_proj_confirm_flip), R.string.panel_switch,
                () -> change("172 put mode " + target, Play6Snapshot.PROJECTION, (g, g2) -> {
                    if (autoOff) g.setAutoProjection(false);       // 580(false)
                    g.setPutMode(target);                          // 172
                }));
    }

    // ================================================================== game page (plan 1.5)
    private Page buildGamePage() {
        Page p = newPage(QuickPanel.SECTION_GAME, R.string.panel_sec_game);
        gameHint = p.add(new TextRow(app, s(R.string.panel_game_hdmi_only)));
        gameHint.setVisibility(View.GONE);
        gameMode = p.add(new P6ChoiceRow(app, s(R.string.panel_game_mode), gameModeLabels(),
                (row, i) -> safe("game mode", () -> onGameMode(i)).run()));
        gameMode.setCommitDelay(CHOICE_COMMIT_MS);
        gameMode.setVisibility(View.GONE);                   // only on HDMI (apply)
        aspect = p.add(new P6ChoiceRow(app, s(R.string.panel_aspect), aspectLabels(aspectOrder),
                (row, i) -> safe("aspect", () -> {
                    final int[] order = aspectOrder;
                    if (snap.aspect == null || i < 0 || i >= order.length) { refresh(Play6Snapshot.GAME); return; }
                    final int a = order[i];
                    change("665 aspect " + a, Play6Snapshot.GAME, (g, g2) -> {
                        if (hdmiNow(g, "665") && !g.setAspectRatio(a)) throw new IllegalStateException("665 returned false");
                    });
                }).run()));
        aspect.setCommitDelay(CHOICE_COMMIT_MS);
        aspect.setVisibility(View.GONE);                     // only on HDMI (apply)
        // stock HDMI settings (signal_setting, 2026-10-21): colour range 635 / 634, HDMI mode (EDID) 660 / 661
        hdmiRangeRow = p.add(new P6ChoiceRow(app, s(R.string.hdmi_range), labels(app, R.string.panel_auto,
                R.string.hdmi_range_full, R.string.hdmi_range_limited), (row, i) -> safe("hdmi range", () -> {
                    if (snap.hdmiRange == null || i < 0 || i > 2) { refresh(Play6Snapshot.GAME); return; }
                    change("635 hdmi range " + i, Play6Snapshot.GAME, (g, g2) -> {
                        if (hdmiNow(g, "635") && !g.setHdmiRange(i)) throw new IllegalStateException("635 returned false");
                    });
                }).run()));
        hdmiRangeRow.setCommitDelay(CHOICE_COMMIT_MS);
        hdmiRangeRow.setVisibility(View.GONE);
        edidRow = p.add(new P6ChoiceRow(app, s(R.string.hdmi_mode), labels(app, R.string.hdmi_mode_compat,
                R.string.hdmi_mode_enhanced), (row, i) -> safe("edid", () -> onEdid(i)).run()));
        edidRow.setCommitDelay(CHOICE_COMMIT_MS);
        edidRow.setVisibility(View.GONE);
        return p;
    }

    /** HDMI mode: stock asks first (the source reconnects); 660(1 compatible / 2 enhanced), HDMI source only. */
    private void onEdid(int i) {
        if (snap.edid == null || i < 0 || i > 1) { refresh(Play6Snapshot.GAME); return; }
        final int v = i == 0 ? Play6Gmpf.EDID_14 : Play6Gmpf.EDID_20;
        if (v == snap.edid) return;
        confirm(R.string.hdmi_mode, s(R.string.hdmi_mode_note), R.string.panel_switch,
                () -> change("660 edid " + v, Play6Snapshot.GAME, (g, g2) -> {
                    if (hdmiNow(g, "660") && !g.setEdidVersion(v)) throw new IllegalStateException("660 returned false");
                }));
        refresh(Play6Snapshot.GAME);                           // a canceled confirm leaves the row on the real value
    }

    private CharSequence[] gameModeLabels() {
        return labels(app, R.string.panel_off, R.string.panel_on, R.string.panel_auto);
    }

    /** game/GameMode (the game-mode path of Note C), after the HDMI check; the row shows IGmpf2 33 read back. */
    private void onGameMode(int i) {
        if (i < GameMode.OFF || i > GameMode.AUTO) return;
        change("game mode " + i, Play6Snapshot.GAME | Play6Snapshot.PICTURE, (g, g2) -> {
            if (!hdmiNow(g, "game mode")) return;
            if (!GameMode.set(app, g, g2, i)) Ui.toast(app, R.string.toast_not_ready);
        });
    }

    private CharSequence[] aspectLabels(int[] order) {
        CharSequence[] out = new CharSequence[order.length];
        for (int k = 0; k < order.length; k++) {
            switch (order[k]) {
                case Play6Gmpf.ASPECT_AUTO: out[k] = s(R.string.panel_auto); break;
                case Play6Gmpf.ASPECT_16_9: out[k] = s(R.string.panel_aspect_16_9); break;
                case Play6Gmpf.ASPECT_4_3: out[k] = s(R.string.panel_aspect_4_3); break;
                default: out[k] = s(R.string.panel_aspect_original); break;
            }
        }
        return out;
    }

    /**
     * 664 read -> row index of {@code order}: 0 4:3, 1 16:9, 2 Auto (full screen), 3 / 4 Original (shown as
     * Auto when Original is not offered: the vendor then shows the full panel too); above 4 (the t14 5, which
     * source/HdmiSource repairs with 665(2)) as Auto.
     */
    private static int aspectIndex(int[] order, Integer v) {
        if (v == null) return -1;
        int shown = v == Play6Gmpf.ASPECT_POINT ? Play6Gmpf.ASPECT_ORIGINAL : v;
        int i = indexOf(order, shown);
        return i >= 0 ? i : indexOf(order, Play6Gmpf.ASPECT_AUTO);
    }

    // ================================================================== eye protection page (plan 1.7)
    /**
     * Built only when Device.hasEyeProtection() (the image's NUI fifo reader is up, stock parity 2026-10-21);
     * the switch is IGmpf 152 (151 read back), the runtime is eye/EyeGuard.
     */
    private Page buildEyePage() {
        Page p = newPage(QuickPanel.SECTION_EYE, R.string.panel_eye);
        eye = p.add(new ToggleRow(app, s(R.string.panel_eye), (row, wanted) -> safe("152", () ->
                change("152 eye protection " + wanted, Play6Snapshot.EYE, (g, g2) -> {
                    if (!g.setEyeProtection(wanted)) throw new IllegalStateException("152 returned false");
                })).run()));
        p.add(new TextRow(app, s(R.string.panel_eye_desc)));
        return p;
    }

    // ================================================================== night light page (owner 2026-10-21)
    /**
     * QuickPanelView: MENU, or OK held on the focused Ambient speaker tile, opens its page instead of the mode (the
     * tile itself starts the mode with a short OK). True = consumed.
     */
    private boolean nightLong;

    boolean onPanelKey(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (ok && ev.getAction() == KeyEvent.ACTION_UP && (nightLong || nightDown)) {
            // also after the page opened: this release belongs to the tile, not to the page's first row
            boolean shortPress = nightDown && !nightLong;
            nightLong = false;
            nightDown = false;
            if (shortPress && panel != null && panel.currentPage() == root) onTile(SECTION_SPEAKER); // the tile's OK
            return true;
        }
        if (panel == null || panel.currentPage() != root) return false;
        View f = grid.findFocus();
        boolean onNight = f instanceof Tile && SECTION_SPEAKER.equals(((Tile) f).section);
        if (!onNight) return false;
        if (code == KeyEvent.KEYCODE_MENU) {
            if (ev.getAction() == KeyEvent.ACTION_UP) openNightPage();
            return true;
        }
        if (ok && ev.getAction() == KeyEvent.ACTION_DOWN) {
            // The tile acts on its first DOWN; for the night tile the press waits for the release (short = the
            // light) or a hold (long = the page)
            if (ev.getRepeatCount() == 0) { nightDown = true; return true; }
            boolean held = (ev.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0 || ev.getRepeatCount() >= 3;
            if (!nightLong && held) {                          // held about half a second
                nightLong = true;
                openNightPage();
            }
            return true;
        }
        return false;
    }

    private boolean nightDown;

    private void openNightPage() {
        Page p = page(SECTION_SPEAKER);
        if (p == null || panel == null) return;
        grid.settle();
        p.setInitialFocus(-1);
        panel.push(p);
    }

    // ================================================================== speaker and ambient light page
    /**
     * The stock ambient speaker on one page and one tile (P3 item 21; Lumen 1.0.6, owner 2026-10-24: "one pleasant
     * mode, not Speaker and Night light apart"; stock has no night light, its ambient speaker is the light plus the
     * Bluetooth speaker). OK on the tile starts it (SpeakerMode.openAmbient), a held OK or MENU opens this page:
     * Turn on, light colour (incl. None), effect, brightness, When closed. Earlier pages, for the record:
     *  - Bluetooth speaker (speaker/SpeakerMode, only with the image's A2DP sink role): Turn on, the light while a
     *    phone plays (Off / Ambient light, only with the ambient light);
     *  - Ambient light (ambient/AmbientLight, only while IGmpf2 71 reads true, stock's own gate): Turn on, colour
     *    (stock list incl. None), effect, brightness (night light, never brighter);
     *  - When the projector is closed: Shut down / Ambient speaker (IGmpf2 69 / 70, stock SystemUI "Close device",
     *    shown only once 70 was read). Closing it then starts the ambient speaker (IGmpf 227 event, SpeakerMode).
     */
    private Page buildSpeakerLightPage() {
        Page p = newPage(SECTION_SPEAKER, R.string.p6_spk_page);
        spkOn = p.add(new NavRow(app, s(R.string.p6_ambsp_on), () -> {
            if (setupDone("ambient speaker")) closeThen(() -> org.z9x.projector.speaker.SpeakerMode.openAmbient(app));
        }).setChevron(false));
        ambColor = p.add(new P6ChoiceRow(app, s(R.string.p6_amb_color), labels(app, R.string.p6_amb_c_orange,
                R.string.p6_amb_c_blue, R.string.p6_amb_c_yellow, R.string.p6_amb_c_green, R.string.p6_amb_c_pink,
                R.string.p6_amb_c_purple, R.string.p6_amb_c_colorful, R.string.p6_amb_c_none),
                (row, i) -> safe("ambient color", () -> org.z9x.projector.ambient.AmbientLight.setColor(app, i)).run()));
        ambEffect = p.add(new P6ChoiceRow(app, s(R.string.p6_amb_effect), labels(app, R.string.p6_amb_e_standard,
                R.string.p6_amb_e_always, R.string.p6_amb_e_rhythm),
                (row, i) -> safe("ambient effect", () -> org.z9x.projector.ambient.AmbientLight.setMode(app, i)).run()));
        CharSequence[] levels = new CharSequence[11];
        levels[0] = s(R.string.p6_amb_level_same);
        for (int i = 1; i <= 10; i++) levels[i] = Integer.toString(i);
        ambLevel = p.add(new P6ChoiceRow(app, s(R.string.p6_amb_level), levels,
                (row, i) -> safe("ambient level", () -> org.z9x.projector.ambient.AmbientLight.setLevel(app, i)).run()));
        ambLevel.setCommitDelay(CHOICE_COMMIT_MS);
        lidRow = p.add(new P6ChoiceRow(app, s(R.string.p6_lid), labels(app, R.string.p6_lid_off, R.string.p6_lid_speaker),
                (row, i) -> safe("lid", () -> {
                    if (snap.slideFunc == null || i < 0 || i > 1) { refresh(Play6Snapshot.AMBIENT); return; }
                    change("IGmpf2 69 slide function " + i, Play6Snapshot.AMBIENT, (g, g2) -> {
                        if (!g2.setSlideFunction(i)) throw new IllegalStateException("IGmpf2 69 returned false");
                    });
                }).run()));
        lidRow.setCommitDelay(CHOICE_COMMIT_MS);
        ambDesc = p.add(new TextRow(app, s(R.string.p6_amb_desc_one)));
        for (View v : new View[] {spkOn, ambColor, ambEffect, ambLevel, lidRow, ambDesc}) v.setVisibility(View.GONE);
        return p;
    }

    // ================================================================== all settings page
    private Page buildSettingsPage() {
        Page p = newPage(QuickPanel.SECTION_GENERAL, R.string.panel_all_settings);
        // These leave the panel for another window / app: not during the first-run setup.
        p.add(new NavRow(app, s(R.string.panel_system_settings), () -> {
            if (setupDone("system settings")) closeThen(this::openTvSettings);
        }));
        p.add(new NavRow(app, s(R.string.panel_projector_settings), () -> {
            if (setupDone("projector settings")) closeThen(() -> Hal.openProjectorSettings(app, null));
        }));
        p.add(new NavRow(app, s(R.string.recents_title), () -> {
            if (setupDone("recent apps")) closeThen(() -> org.z9x.projector.recents.RecentsActivity.open(app));
        }));
        if (org.z9x.projector.home.LauncherSwitcher.choiceAvailable(app)) {
            launcherRow = p.add(new P6ChoiceRow(app, s(R.string.launcher_row), labels(app,
                    R.string.launcher_lumen, R.string.launcher_classic),
                    (row, i) -> safe("home screen", () -> onLauncherChoice(i)).run()));
        }
        return p;
    }

    /** "Home screen" row: confirm, then LauncherSwitcher; the row shows the real holder after. */
    private void onLauncherChoice(int i) {
        final String mode = i == 1 ? org.z9x.projector.home.LauncherSwitcher.MODE_CLASSIC
                : org.z9x.projector.home.LauncherSwitcher.MODE_LUMEN;
        if (mode.equals(org.z9x.projector.home.LauncherSwitcher.currentMode(app))) return;
        if (!setupDone("home screen")) {
            refreshLauncherRow();
            return;
        }
        confirm(R.string.launcher_row, s(i == 1 ? R.string.launcher_confirm_classic : R.string.launcher_confirm_lumen),
                R.string.launcher_switch, () -> closeThen(() -> org.z9x.projector.home.LauncherSwitcher.apply(app, mode,
                        true, "quick panel", (ok, holder, err) -> Log.i(TAG, "home screen " + mode + " -> " + ok + " " + holder))));
        // a canceled confirm leaves the row on the real value
        Ui.main().postDelayed(this::refreshLauncherRow, 300);
    }

    private void refreshLauncherRow() {
        if (launcherRow == null) return;
        boolean classic = org.z9x.projector.home.LauncherSwitcher.MODE_CLASSIC.equals(
                org.z9x.projector.home.LauncherSwitcher.currentMode(app));
        launcherRow.setSelected(classic ? 1 : 0);
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

    // ================================================================== apply the read-back
    private static void touch(SliderRow r) { r.setTag(SystemClock.uptimeMillis()); }

    /** Do not move a slider under the user's finger (a read-back of an older commit). */
    private static boolean sliderBusy(SliderRow r) {
        Object t = r.getTag();
        return r.isFocused() && t instanceof Long && SystemClock.uptimeMillis() - (Long) t < SLIDER_GUARD_MS;
    }

    private static void setToggle(ToggleRow r, Boolean v, boolean enabled) {
        if (r == null) return;
        r.setChecked(v);
        r.setRowEnabled(v != null && enabled);
    }

    private static void setChoice(ChoiceRow r, int index, boolean known, boolean enabled) {
        if (r == null) return;
        r.setSelected(known ? index : -1);
        r.setRowEnabled(known && enabled);
    }

    private static void visible(View v, boolean show) {
        if (v != null) v.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** Updates every built row and the tile values from {@link #snap}. Main thread. */
    private void apply() {
        if (released) return;
        final Play6Snapshot sn = snap;
        final boolean ok = !notReady;
        boolean g2 = ok && Hal.hasGmpf2();                  // the live guard state (all or nothing)
        info.setText(infoText(sn, ok));

        // ---- focus + keystone pages (IGmpf2 rows are hidden while the guard is closed)
        visible(smartAf, g2);
        visible(smartAfDesc, g2);
        setToggle(smartAf, ok ? sn.smartAf : null, true);
        visible(manualKst, g2);
        visible(zoomShift, g2 && Boolean.TRUE.equals(sn.zoomOk) && !Play6ZoomShiftPanel.isUnsupported());
        visible(rotation, g2 && (Boolean.TRUE.equals(sn.rotationOk) || sn.rotationOk == null
                && Play6RotatePanel.knownSupported(app)) && !Play6RotatePanel.isUnsupported());
        setToggle(rtKst, ok ? sn.moveAk : null, true);
        setToggle(powerOnKst, ok ? sn.powerOnAk : null, true);

        // ---- brightness, power saving
        if (lamp != null) {
            if (!sliderBusy(lamp)) lamp.setValue(ok ? sn.lamp : null);
            lamp.setRowEnabled(ok && sn.lamp != null);
        }
        setToggle(eco, ok ? sn.eco : null, true);

        // ---- picture (stock rules: Custom items in Custom only, motion smoothing in Movie / TV / Custom;
        // a row the mode does not have is GONE; 666 values 10..17 are shown as AI)
        Integer pm = ok ? Play6Snapshot.shownPictureMode(sn.pictureMode) : null;
        boolean game = ok && sn.gamePicture();
        if (pictureMode != null) {
            if (game != pmShowsGame) {
                pmShowsGame = game;
                pictureMode.setOptions(game ? new CharSequence[] {pmLabel(Play6Gmpf.PICTURE_GAME)} : pmLabels());
            }
            if (game) setChoice(pictureMode, 0, true, false);          // read-only "Game" while game mode runs
            else {
                pictureMode.setSelected(indexOf(PM_ORDER, pm));          // no value for a mode outside the list
                pictureMode.setRowEnabled(pm != null);                   // ... which can still be left
            }
            visible(picGameHint, game);
            boolean custom = pm != null && pm == Play6Gmpf.PICTURE_CUSTOM;
            for (int i = 0; i < pq.length; i++) {
                Integer v = ok && custom ? sn.pq[i] : null;
                if (!sliderBusy(pq[i])) pq[i].setValue(v);
                pq[i].setRowEnabled(v != null);
                visible(pq[i], custom);
            }
            boolean memcOk = pm != null && Play6Snapshot.memcMode(pm);
            Integer m = ok && memcOk ? sn.memc : null;
            setChoice(memc, m != null ? m : -1, m != null && m >= 0 && m <= 3, true);
            visible(memc, memcOk);
            applyStockPicture(sn, ok, pm, custom, memcOk, g2);
        }

        // ---- sound
        int sm = ok ? indexOf(SOUND_ORDER, sn.soundEffect) : -1;
        setChoice(soundMode, sm, sm >= 0, true);
        if (soundOut != null && !soundOut.isPending()) {                 // AudioManager only, no HAL read
            outOptions = org.z9x.projector.audio.AudioOutputChoice.options(app);
            CharSequence[] ol = new CharSequence[outOptions.size()];
            for (int i = 0; i < ol.length; i++) ol[i] = outOptions.get(i).label;
            soundOut.setOptions(ol);
            soundOut.setSelected(org.z9x.projector.audio.AudioOutputChoice.selectedIndex(app, outOptions));
        }
        if (eqRow != null) {
            boolean eq = ok && Boolean.TRUE.equals(sn.eqOk);
            visible(eqRow, eq);
            if (eq) setChoice(eqRow, org.z9x.projector.audio.P6SoundEq.preset(app), true, true);
        }
        if (earcRow != null) {
            Boolean ea = ok ? sn.earc : null;
            visible(earcRow, ea != null);
            if (ea != null) setToggle(earcRow, ea, true);
        }

        // ---- projection page
        boolean auto = ok && Boolean.TRUE.equals(sn.autoReverse);
        boolean known = ok && sn.putMode != null && sn.autoReverse != null;
        if (pAuto != null) {
            pAuto.setChecked(known && auto);
            pTable.setChecked(known && !auto && (sn.putMode == 0 || sn.putMode == 2));
            pCeiling.setChecked(known && !auto && (sn.putMode == 1 || sn.putMode == 3));
            pAuto.setRowEnabled(known);
            pTable.setRowEnabled(known);
            pCeiling.setRowEnabled(known);
            setToggle(pRear, known ? (Boolean) (sn.putMode == 2 || sn.putMode == 3) : null, true);
        }

        // ---- game page: game mode and aspect ratio only while HDMI is the current source (game mode also
        // needs IGmpf2); off HDMI only the "HDMI only" line; Original only for a signal smaller than the panel
        boolean hdmi = ok && sn.hdmiSource();
        int gm = ok ? GameMode.modeOf(sn.gameProp) : -1;
        visible(gameHint, !hdmi);
        visible(gameMode, hdmi && g2);
        visible(aspect, hdmi);
        if (gameMode != null) {
            gameMode.setSelected(gm);
            gameMode.setRowEnabled(hdmi && g2 && gm >= 0);
        }
        if (aspect != null) {
            int[] order = ok && sn.originalFits() ? ASPECT_ORDER : ASPECT_ORDER_NO_ORIGINAL;
            if (order != aspectOrder) {
                aspectOrder = order;
                aspect.setOptions(aspectLabels(order));
            }
            int as = ok ? aspectIndex(order, sn.aspect) : -1;
            setChoice(aspect, as, as >= 0, hdmi);
        }
        if (hdmiRangeRow != null) {
            choiceIf(hdmiRangeRow, ok && hdmi ? sn.hdmiRange : null, 2);
            Integer e = ok && hdmi ? sn.edid : null;
            boolean edidKnown = e != null && (e == Play6Gmpf.EDID_14 || e == Play6Gmpf.EDID_20);
            visible(edidRow, edidKnown);
            if (edidKnown) setChoice(edidRow, e == Play6Gmpf.EDID_14 ? 0 : 1, true, true);
        }

        // ---- speaker and ambient light (P3 item 21, stock parity): one tile; the speaker rows with the image's
        // A2DP sink role, the ambient rows while IGmpf2 71 reads true, the lid row once IGmpf2 70 was read
        boolean amb = g2 && Boolean.TRUE.equals(sn.ambientOk);
        boolean spk = ok && org.z9x.projector.speaker.SpeakerMode.supported();
        Tile spkTile = grid.tile(SECTION_SPEAKER);
        if (spkTile != null && (spkTile.getVisibility() == View.VISIBLE) != (spk || amb)) {
            spkTile.setVisibility(spk || amb ? View.VISIBLE : View.GONE);
            grid.requestLayout();
        }
        if (spkOn != null) {
            visible(spkOn, spk || amb);
            visible(ambDesc, spk || amb);
            for (View v : new View[] {ambColor, ambEffect, ambLevel}) visible(v, amb);
            ambColor.setSelected(org.z9x.projector.ambient.AmbientLight.colorIndex(app));
            ambEffect.setSelected(org.z9x.projector.ambient.AmbientLight.modeIndex(app));
            ambLevel.setSelected(org.z9x.projector.ambient.AmbientLight.level(app));
            Integer lid = amb ? sn.slideFunc : null;
            visible(lidRow, lid != null);
            if (lid != null) setChoice(lidRow, lid == 0 ? 0 : 1, true, true);
        }

        // ---- eye protection: only built with the NUI reader (Device.hasEyeProtection)
        setToggle(eye, ok ? sn.eye : null, Device.hasEyeProtection());

        refreshLauncherRow();

        // ---- tile values (top level): one short line each
        tileValue(QuickPanel.SECTION_LAMP, ok && sn.lamp != null ? Integer.toString(sn.lamp) : null);
        tileValue(QuickPanel.SECTION_PICTURE, pm == null ? null : game ? pmLabel(Play6Gmpf.PICTURE_GAME)
                : indexOf(PM_ORDER, pm) >= 0 ? pmTileLabel(pm) : null);
        tileValue(QuickPanel.SECTION_SOUND, sm >= 0 ? soundTileLabel(sm) : null);
        tileValue(QuickPanel.SECTION_INPUT, ok ? inputLabel(sn) : null);
        tileValue(QuickPanel.SECTION_PROJECTION_MODE, ok ? projectionLabel(sn) : null);
        tileValue(QuickPanel.SECTION_GAME, !ok ? null : !hdmi ? s(R.string.panel_tile_hdmi_only)
                : gm >= 0 && gm <= 2 ? gameModeLabels()[gm] : null);
        int nc = org.z9x.projector.ambient.AmbientLight.colorIndex(app);
        int[] colorRes = {R.string.p6_amb_c_orange, R.string.p6_amb_c_blue, R.string.p6_amb_c_yellow, R.string.p6_amb_c_green,
                R.string.p6_amb_c_pink, R.string.p6_amb_c_purple, R.string.p6_amb_c_colorful, R.string.p6_amb_c_none};
        tileValue(SECTION_SPEAKER, nc >= 0 && nc < colorRes.length ? s(colorRes[nc]) : null);
        tileValue(QuickPanel.SECTION_EYE, ok && sn.eye != null ? s(sn.eye ? R.string.panel_on : R.string.panel_off) : null);
    }

    /** The stock parity picture rows: shown only in their modes and only with a value read (owner rule). */
    private void applyStockPicture(Play6Snapshot sn, boolean ok, Integer pm, boolean custom, boolean memcOk, boolean g2) {
        if (nrRow == null) return;
        choiceIf(nrRow, ok && custom ? sn.nr : null, 4);
        choiceIf(ctRow, ok && custom ? sn.colorTemp : null, 3);
        boolean ctCustom = ok && custom && sn.colorTemp != null && sn.colorTemp == 3;
        for (int k = 0; k < wbRows.length; k++) {
            Integer v = ctCustom ? sn.wb[k] : null;
            if (!sliderBusy(wbRows[k])) wbRows[k].setValue(v);
            wbRows[k].setRowEnabled(v != null);
            visible(wbRows[k], v != null);
        }
        choiceIf(gammaRow, ok && custom && g2 ? sn.gamma : null, 8);
        Boolean dc = ok && custom && g2 ? sn.dynContrast : null;
        visible(dcRow, dc != null);
        if (dc != null) setToggle(dcRow, dc, true);
        choiceIf(lcRow, ok && memcOk ? sn.localContrast : null, 3);
        Boolean hdr = ok && pm != null ? sn.hdrAuto : null;
        visible(hdrRow, hdr != null);
        if (hdr != null) setToggle(hdrRow, hdr, true);
        int st = ok && custom && sn.style != null ? indexOf(STYLE_ORDER, sn.style) : -1;
        visible(styleRow, ok && custom && sn.style != null);
        styleRow.setSelected(st);
        styleRow.setRowEnabled(st >= 0 || (ok && custom && sn.style != null));
        visible(picReset, ok && custom);
        boolean rgb = ok && custom && sn.style != null && sn.style == Play6Gmpf.STYLE_CUSTOM && Play6Snapshot.rgbOk(sn.rgb);
        for (int k = 0; k < rgbRows.length; k++) {
            Integer v = rgb ? sn.rgb[k] : null;
            if (!sliderBusy(rgbRows[k])) rgbRows[k].setValue(v);
            rgbRows[k].setRowEnabled(v != null);
            visible(rgbRows[k], v != null);
        }
        visible(rgbReset, rgb);
    }

    /** A choice row shown with its value 0..max, or GONE (unknown value or not offered). */
    private static void choiceIf(ChoiceRow r, Integer v, int max) {
        boolean show = v != null && v >= 0 && v <= max;
        visible(r, show);
        if (show) setChoice(r, v, true, true);
    }

    private void tileValue(String section, CharSequence v) {
        Tile t = grid.tile(section);
        if (t != null) t.setValue(v);
    }

    private String inputLabel(Play6Snapshot sn) {
        if (sn.hdmiConfirmed()) return s(R.string.panel_src_hdmi);
        return sn.source == null ? null : s(R.string.panel_src_android);
    }

    /**
     * "HDMI  ·  HDR10": input and HDR type (620: 0 SDR, 1 HDR10, 4 HLG; no Dolby Vision). The battery part
     * is gone (research 6.1 B8: Lumen Home shows the battery).
     */
    private CharSequence infoText(Play6Snapshot sn, boolean ok) {
        if (!ok) return s(R.string.toast_not_ready);
        SpannableStringBuilder out = new SpannableStringBuilder();
        String src = inputLabel(sn);
        if (src != null) out.append(src);
        if (sn.hdr != null) {
            String hdr = sn.hdr == 0 ? "SDR" : sn.hdr == 1 ? "HDR10" : sn.hdr == 4 ? "HLG" : "HDR";
            if (out.length() > 0) out.append("  ·  ");
            out.append(hdr);
        }
        return out;
    }

    private String projectionLabel(Play6Snapshot sn) {
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
     * The quick panel window: PagedPanel + motion + close hook (as the Z9X). Open: the card grows out of
     * the right edge (scale 0.96 -> 1, +48 px -> 0, alpha) in 200 ms, then the tiles stagger in. Close:
     * 160 ms the other way. Page switches keep the foundation's 140 ms slide (PagedPanel).
     */
    static final class QuickPanelView extends PagedPanel {
        private static final long OPEN_MS = 200, CLOSE_MS = 160;
        private final Play6PanelController ctl;

        QuickPanelView(Page root, Play6PanelController ctl) {
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
            // No hardware layer on the root: the info row and the tiles animate inside it (each tile on
            // its own layer); the root's alpha is applied per child while it fades in.
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
            try { return ctl.onPanelKey(ev); } catch (Throwable t) { Log.w(TAG, "panel key: " + t); return false; }
        }

        @Override
        protected void onDismissed() {
            super.onDismissed();
            try { ctl.onPanelClosed(this); } catch (Throwable t) { Log.w(TAG, "closed: " + t); }
        }
    }
}
