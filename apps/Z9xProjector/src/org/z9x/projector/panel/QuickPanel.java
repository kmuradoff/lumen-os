package org.z9x.projector.panel;

import android.content.Context;
import android.util.Log;

import org.z9x.projector.ui.Page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MODULE "panel" (owner: quick-settings agent). XGIMI-style quick settings drawn over the running
 * video: right-side PagedPanel (org.z9x.projector.ui), opened by a short gear press.
 *
 * v6.2 layout (V62_REQUIREMENTS 3): the top level is a grid of icon tiles (Focus, Keystone,
 * Manual keystone (v6.3), Brightness, Picture, Sound, Input, Projection, Game mode, Eye protection, All settings); each
 * tile opens its section page (the v6.1 rows, regrouped), Input opens the Source overlay. The panel
 * remembers the last tile. Open/close: 200/160 ms scale + fade; tiles stagger in; focus cross-fades
 * and scales the tile in 160 ms.
 *
 * Fixed entry points (called by org.z9x.projector.KeyReceiver on the MAIN thread, cheap, must not
 * throw - KeyReceiver wraps them anyway):
 *  - {@link #toggle(Context)}: gear short press (KEYCODE_SETTINGS UP without a long press).
 *  - {@link #show(Context, String)}: open at a section page (IR KEYSTONE/LENS key ->
 *    SECTION_PROJECTION = the Keystone page). null / unknown = the tile grid.
 *
 * For other modules (v6.2): {@link #addExtension(String, Extension)} adds rows to a section page
 * (e.g. the game module's console options on SECTION_GAME, a screensaver or sleep-timer row on
 * SECTION_GENERAL) without editing panel files.
 *
 * For the power module: {@link #quickWakeMinutes(Context)} is the standby window (v6.5: minutes of
 * lamp-only standby / sleep before the real power-off; 0 = power off at once, -1 = never), chosen in
 * the panel's All settings page ("Standby, then power off after"). Stored and applied by PowerPolicy /
 * StandbyController (single store, prefs z9x_power); the panel row only calls PowerPolicy.setQuickWakeMinutes.
 *
 * Implementation: {@link QuickPanelController} (pages, rows, tiles, HAL read-back), {@link TileGrid}
 * / {@link Tile} (the top level), {@link Snapshot} (one HAL read of every value),
 * {@link ManualKeystonePanel} (4-corner keystone card).
 * Spec: RESULT_quicksettings.json decisions (verdict corrected_decisions win), RESULT_display.json,
 * FEATURE_SPEC.md sections 1.3 and 2-5, V61_REQUIREMENTS "Decisions", V62_REQUIREMENTS 3/7.
 */
public final class QuickPanel {
    private static final String TAG = "Z9xPanel";

    // ---- section pages (tile keys). The v6.1 names keep their callers working.
    public static final String SECTION_FOCUS = "focus";
    /** Keystone / image correction page (auto keystone, fit, manual keystone, keystone settings). */
    public static final String SECTION_KEYSTONE = "keystone";
    /**
     * v6.3: not a page. The "Manual keystone" tile opens {@link ManualKeystonePanel} directly (4 corners,
     * live vendor warp); show(SECTION_MANUAL_KEYSTONE) does the same.
     */
    public static final String SECTION_MANUAL_KEYSTONE = "manual_keystone";
    /** v6.1 name used by the IR KEYSTONE/LENS key: the Keystone page. */
    public static final String SECTION_PROJECTION = "projection";
    /** Brightness page (lamp level 1..10 + Boost). */
    public static final String SECTION_LAMP = "lamp";
    public static final String SECTION_PICTURE = "picture";
    public static final String SECTION_SOUND = "sound";
    /** Not a page: the Input tile opens the Source overlay. */
    public static final String SECTION_INPUT = "input";
    /** Projection mode page (auto / table / ceiling / rear). */
    public static final String SECTION_PROJECTION_MODE = "projection_mode";
    /** Game mode page (HDMI only). SECTION_HDMI is the v6.1 name of the same page. */
    public static final String SECTION_GAME = "hdmi";
    public static final String SECTION_HDMI = SECTION_GAME;
    public static final String SECTION_EYE = "eye";
    /** "All settings" page: system settings, projector settings, pair remote, quick wake. */
    public static final String SECTION_GENERAL = "general";

    /** SharedPreferences file of the panel module (org.z9x.projector process). */
    public static final String PREFS = "z9x_panel";
    /**
     * int in prefs {@link #PREFS}: the game mode last set (0 off, 1 on, 2 auto; IGmpf2 55 is not read).
     * The panel shows it on the Game tile and page; the v6.2 console profile should write it too
     * when it switches game mode, so both show one state.
     */
    public static final String KEY_GAME_MODE = "game_mode";
    /** int minutes; see {@link #quickWakeMinutes}. */
    public static final String KEY_QUICK_WAKE_MIN = "quick_wake_min";
    /** V61_REQUIREMENTS decisions: "default 30 min, set in the panel". */
    public static final int DEFAULT_QUICK_WAKE_MIN = 30;
    /** The choices offered in the panel: PowerPolicy's (minutes; 0 = off, -1 = always). */
    static final int[] QUICK_WAKE_CHOICES = org.z9x.projector.power.PowerPolicy.quickWakeChoices();

    /**
     * Rows another module adds to a section page. Both methods run on the MAIN thread and must be
     * cheap and must not block; HAL reads go through Hal.query, writes through Hal.run (V61_ARCH 3).
     * Exceptions are caught and logged by the panel.
     */
    public interface Extension {
        /**
         * Called once when the page is built (again after a locale change, because the panel is
         * then rebuilt with new texts). Add rows with {@code page.add(...)}; keep references to
         * update them later.
         */
        void addRows(Context app, Page page);

        /** Each time the page becomes visible: load the values of your rows here. */
        default void onPageShown() {}
    }

    private static final Map<String, List<Extension>> EXTENSIONS = new LinkedHashMap<>();
    private static QuickPanelController sCtl;

    private QuickPanel() {}

    /** Gear short press: show the panel, or close it if it is the panel currently shown. */
    public static void toggle(Context ctx) {
        try {
            ctl(ctx).toggle();
        } catch (Throwable t) {
            Log.e(TAG, "toggle failed", t);
        }
    }

    /** Shows the panel at the {@code section} page (SECTION_*); unknown/null = the tile grid. */
    public static void show(Context ctx, String section) {
        try {
            ctl(ctx).show(section);
        } catch (Throwable t) {
            Log.e(TAG, "show failed", t);
        }
    }

    /**
     * Re-reads the values of an open quick panel (one HAL query; no-op when it is closed). For modules
     * that change a value the panel shows, e.g. the game profile. MAIN thread.
     */
    public static void refreshIfShowing() {
        QuickPanelController c = sCtl;
        if (c == null) return;
        try {
            c.refreshIfShowing();
        } catch (Throwable t) {
            Log.e(TAG, "refreshIfShowing failed", t);
        }
    }

    /**
     * Adds rows to a section page (SECTION_FOCUS, SECTION_KEYSTONE, SECTION_LAMP, SECTION_PICTURE,
     * SECTION_SOUND, SECTION_PROJECTION_MODE, SECTION_GAME, SECTION_EYE, SECTION_GENERAL). The rows
     * go after the panel's own rows, in registration order. MAIN thread; call once per process
     * (e.g. from the module's install()). Unknown sections are logged and ignored.
     */
    public static void addExtension(String section, Extension ext) {
        if (section == null || ext == null) return;
        String key = SECTION_PROJECTION.equals(section) ? SECTION_KEYSTONE : section;
        EXTENSIONS.computeIfAbsent(key, k -> new ArrayList<>()).add(ext);
        QuickPanelController c = sCtl;
        if (c != null) {
            try {
                c.addExtension(key, ext);
            } catch (Throwable t) {
                Log.e(TAG, "extension for " + key + " failed", t);
            }
        }
    }

    /** The registered extensions of {@code section} (controller use; main thread). */
    static List<Extension> extensions(String section) {
        List<Extension> l = EXTENSIONS.get(section);
        return l == null ? java.util.Collections.emptyList() : l;
    }

    /**
     * Quick-wake window in minutes (0 = off) chosen by the user; {@link #DEFAULT_QUICK_WAKE_MIN} until
     * set. Any thread; reads this process's SharedPreferences (cheap after the first load).
     */
    public static int quickWakeMinutes(Context ctx) {
        // Single store: PowerPolicy owns the value (prefs z9x_power) and applies it at SCREEN_OFF.
        try {
            return org.z9x.projector.power.PowerPolicy.getQuickWakeMinutes(ctx);
        } catch (Throwable t) {
            return DEFAULT_QUICK_WAKE_MIN;
        }
    }

    /**
     * Locale changed (App.onConfigurationChanged, main thread): every row title was built with the
     * old language, so close the quick panel (if it is the panel shown) and drop the controller; the
     * next gear press builds it again. Other panels build their texts per show.
     */
    public static void onLocaleChanged(Context ctx) {
        QuickPanelController c = sCtl;
        sCtl = null;
        if (c != null) c.close();
    }

    private static QuickPanelController ctl(Context ctx) {
        // Main thread only (KeyReceiver), so no locking beyond this.
        if (sCtl == null) sCtl = new QuickPanelController(ctx.getApplicationContext());
        return sCtl;
    }
}
