package org.lumen.projector.panel;

import android.content.Context;
import android.util.Log;

import org.lumen.projector.ui.Page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The quick panel of the XGIMI Play 6: XGIMI-style quick settings drawn over the running video, a
 * right-side PagedPanel (org.lumen.projector.ui) opened by a short gear press. A fork of Z9xProjector
 * panel/QuickPanel.java (tools/play6/forks.tsv) with the same public API (SECTION_*, show, toggle,
 * refreshIfShowing, addExtension), so the shared PanelRequestReceiver (Lumen Home's Projector tiles:
 * section keystone, picture, sound, eye, general; action autofocus), SourceOverlay and KeyReceiver work
 * unchanged (docs/play6/SETTINGS_PLAN.md 2.3).
 *
 * Top level: a grid of icon tiles (Focus, Keystone, Brightness, Picture, Sound, Input, Projection, Game
 * mode, All settings; Eye protection only once the NUI reader exists, Device.hasEyeProtection; no Battery
 * tile since t13, the battery is shown by Lumen Home); each tile opens its section page, Input opens the
 * Source overlay. The pages and their values are Play 6 ones ({@link Play6PanelController}, {@link
 * Play6Snapshot}, plan 1.2 to 1.8).
 *
 * Low memory (plan section 4): the controller (the tile grid and the pages) is built when the panel
 * opens, a page only when it is first shown, and everything is released when the panel closes; only the
 * last tile is kept (prefs {@link #PREFS}). On open only the first page's values are read; every other
 * page reads its own values when it is shown.
 *
 * Fixed entry points (called on the MAIN thread, cheap, must not throw; the callers wrap them anyway):
 *  - {@link #toggle(Context)}: gear short press.
 *  - {@link #show(Context, String)}: open at a section page (IR KEYSTONE/LENS key -> SECTION_PROJECTION =
 *    the Keystone page). null / unknown = the tile grid.
 * For other modules: {@link #addExtension(String, Extension)} adds rows to a section page.
 *
 * Not on the Play 6: the standby window of the Z9X (quickWakeMinutes, lamp-only standby and STR are not
 * part of this build). A locale change is noticed at the next open (the controller is rebuilt anyway).
 */
public final class QuickPanel {
    private static final String TAG = "P6Panel";

    // ---- section pages (tile keys), the Z9X names
    public static final String SECTION_FOCUS = "focus";
    /** Keystone page (auto keystone, manual keystone, keystone settings). */
    public static final String SECTION_KEYSTONE = "keystone";
    /** Not a page: show(SECTION_MANUAL_KEYSTONE) opens {@link ManualKeystonePanel} directly. */
    public static final String SECTION_MANUAL_KEYSTONE = "manual_keystone";
    /** The name used by the IR KEYSTONE/LENS key: the Keystone page. */
    public static final String SECTION_PROJECTION = "projection";
    /** Brightness page (light level 1..10). */
    public static final String SECTION_LAMP = "lamp";
    public static final String SECTION_PICTURE = "picture";
    public static final String SECTION_SOUND = "sound";
    /** Not a page: the Input tile opens the Source overlay. */
    public static final String SECTION_INPUT = "input";
    /** Projection mode page (auto / table / ceiling / rear). */
    public static final String SECTION_PROJECTION_MODE = "projection_mode";
    /** Game mode page (HDMI only). SECTION_HDMI is the old name of the same page. */
    public static final String SECTION_GAME = "hdmi";
    public static final String SECTION_HDMI = SECTION_GAME;
    /** Eye protection page: not built while Device.hasEyeProtection() is false (the tile grid instead). */
    public static final String SECTION_EYE = "eye";
    /** "All settings" page: system settings, projector settings, recent apps, home screen. */
    public static final String SECTION_GENERAL = "general";

    /** SharedPreferences file of the panel module (org.lumen.projector process). */
    public static final String PREFS = "z9x_panel";

    /**
     * Rows another module adds to a section page. Both methods run on the MAIN thread and must be
     * cheap and must not block; HAL reads go through Hal.query, writes through Hal.run.
     * Exceptions are caught and logged by the panel.
     */
    public interface Extension {
        /** Called when the page is built (each time the panel is built again). Add rows with {@code page.add(...)}. */
        void addRows(Context app, Page page);

        /** Each time the page becomes visible: load the values of your rows here. */
        default void onPageShown() {}
    }

    private static final Map<String, List<Extension>> EXTENSIONS = new LinkedHashMap<>();
    private static Play6PanelController sCtl;
    private static Locale sCtlLocale;

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
     * Re-reads the values of an open quick panel (no-op when it is closed): the page on screen reads its
     * part again (battery/Battery calls it on the vendor power events 3 and 5, so the Brightness page shows
     * the light level and power saving the vendor just set). MAIN thread.
     */
    public static void refreshIfShowing() {
        Play6PanelController c = sCtl;
        if (c == null) return;
        try {
            c.refreshIfShowing();
        } catch (Throwable t) {
            Log.e(TAG, "refreshIfShowing failed", t);
        }
    }

    /**
     * Adds rows to a section page (SECTION_FOCUS, SECTION_KEYSTONE, SECTION_LAMP, SECTION_PICTURE,
     * SECTION_SOUND, SECTION_PROJECTION_MODE, SECTION_GAME, SECTION_EYE, SECTION_GENERAL).
     * The rows go after the panel's own rows, in registration order. MAIN thread; call once per process.
     */
    public static void addExtension(String section, Extension ext) {
        if (section == null || ext == null) return;
        String key = SECTION_PROJECTION.equals(section) ? SECTION_KEYSTONE : section;
        EXTENSIONS.computeIfAbsent(key, k -> new ArrayList<>()).add(ext);
        Play6PanelController c = sCtl;
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

    /** The controller closed its panel: release it (rows, pages, snapshot). Main thread. */
    static void released(Play6PanelController c) {
        if (sCtl == c) {
            sCtl = null;
            sCtlLocale = null;
        }
    }

    private static Play6PanelController ctl(Context ctx) {
        // Main thread only (KeyReceiver, receivers posted to main), so no locking beyond this.
        Context app = ctx.getApplicationContext();
        Locale now = app.getResources().getConfiguration().getLocales().get(0);
        if (sCtl != null && !now.equals(sCtlLocale)) {
            Play6PanelController old = sCtl;             // texts were built in the old language
            sCtl = null;
            old.close();
        }
        if (sCtl == null) {
            sCtl = new Play6PanelController(app);
            sCtlLocale = now;
        }
        return sCtl;
    }
}
