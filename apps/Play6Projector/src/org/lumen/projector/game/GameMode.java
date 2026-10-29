package org.lumen.projector.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.lumen.projector.Play6App;
import org.lumen.projector.R;
import org.lumen.projector.hal.Play6Gmpf;
import org.lumen.projector.hal.Play6Gmpf2;
import org.lumen.projector.ui.Notify;

/**
 * Game mode Off / On / Auto of the XGIMI Play 6 on HDMI (docs/play6/SETTINGS_PLAN.md 1.5, corrected by
 * docs/play6/research/hdmi_volume_game.md 3; check H Note C: this is the "game-mode path", the only place
 * that may send IGmpf 648). For stock the Play 6 (ro.board.platform mt5889) is a GMPF2 platform: gmuiapi
 * GmDeviceInfo.isGMPF2() is false only for maxim, m7221, mst918, monaco, maserati, mainz, mst628, mst638 and
 * tl1, so stock DisplayManager.setGameMode never calls IGmpf 667 for game mode here (the t14 667(0,4) was
 * refused by the vendor: "Independent Game mode should NOT use this API"). IGmpf2 35 setGameModeState(0)
 * itself switches the picture to Game (666 reads 4) and 35(1) gives the source's own picture back.
 *
 * Stock calls (gmuiapi DisplayManager.setGameMode), sent only while HDMI is the current source (IGmpf 696
 * reads 1; the vendor keeps type, state and option per source):
 *  - On:   648(the value 647 reports), IGmpf2 34(0) manual, IGmpf2 35(0) open;
 *  - Off:  IGmpf2 34(0), IGmpf2 35(1) closed;
 *  - Auto: IGmpf2 34(1), then Lumen's console check: a console (game/GameConsole: CEC OSD name with
 *          playstation / xbox / switch / steam deck, or the Sony CEC vendor id 0x080046) -> 35(0), any other
 *          device -> 35(1). Stock's HDMI player makes the same decision by name after signal lock; the vendor
 *          does nothing by itself in Auto. Auto is the default (the vendor's and, once per data, ours: a type
 *          left on manual by t14 goes back to Auto at the first HDMI session).
 * Read back: IGmpf2 33 = {state, type, option} (IDisplayType GameModeProp_st, confirmed by the vendor's own
 * log on t14): type 1 -> Auto; else state 0 -> On; else Off. The latency option (648 / 637) is not offered
 * by the panel, so 637 is not used. IGmpf2 is all or nothing (plan 2.1): with the identity guard closed
 * nothing is sent at all. p6-hal only (inside a Hal task of the quick panel after its HDMI check, or from
 * source/HdmiSource at an HDMI session start and after a CEC device change).
 *
 * Notices (t18, the shared capsule ui/Notify): when Auto's own console check (from HdmiSource, not the panel)
 * opens the state, "Game mode on" with the console's CEC name below it when known ("PlayStation 5"); when it
 * closes the state again because the console has gone, "Game mode off", but only after that "on" notice. Only
 * a 35 call that returned true counts, so a check that finds the state already right (at boot, at each HDMI
 * session start with the same console, after a CEC event) shows nothing. A change made in the panel (On, Off,
 * or Auto chosen there) shows no notice, since the panel shows the value; On or Off there also ends the
 * pairing, so a later Auto "off" stays silent. The two notices alternate, so neither repeats.
 */
public final class GameMode {
    private static final String TAG = "P6Game";
    public static final int OFF = 0, ON = 1, AUTO = 2;
    /** Device-protected flag (Play6App.PREFS): the HDMI game type was set to Auto once (t15 default). */
    private static final String K_AUTO_DEFAULT = "game_auto_default";
    /**
     * p6-hal (t18): the "Game mode on" notice was the last game notice and the state has not been closed or
     * set in the panel since; the "Game mode off" notice needs it. Not kept across a restart.
     */
    private static boolean sOnNotice;

    private GameMode() {}

    /**
     * Sets game mode {@code mode} (OFF / ON / AUTO) for the current source, which the caller checked to be
     * HDMI. Returns false when nothing was sent (the IGmpf2 guard is closed). Throws when a call of the
     * sequence failed (the caller shows "Could not do that").
     */
    public static boolean set(Context app, Play6Gmpf g, Play6Gmpf2 g2, int mode) throws Exception {
        if (!g2.isIdentityOpen()) {
            Log.w(TAG, "game mode " + mode + " not sent: IGmpf2 guard closed");
            return false;
        }
        switch (mode) {
            case ON: {
                sOnNotice = false;                                    // the user took over
                boolean r648 = g.applyGameModeOption();               // 648(647)
                boolean r34 = g2.setGameModeType(0);                  // IGmpf2 34(0) manual
                boolean r35 = g2.setGameModeState(true);              // IGmpf2 35(0) open
                Log.i(TAG, "on: 648 -> " + r648 + ", 34(0) -> " + r34 + ", 35(0) -> " + r35);
                return true;
            }
            case OFF: {
                sOnNotice = false;
                boolean r34 = g2.setGameModeType(0);                  // IGmpf2 34(0)
                boolean r35 = g2.setGameModeState(false);             // IGmpf2 35(1) closed
                Log.i(TAG, "off: 34(0) -> " + r34 + ", 35(1) -> " + r35);
                return true;
            }
            case AUTO: {
                boolean r34 = g2.setGameModeType(1);                  // IGmpf2 34(1)
                Log.i(TAG, "auto: 34(1) -> " + r34);
                autoState(app, g2, read33(g2), "Auto chosen", false);      // the panel's change: no notice
                return true;
            }
            default:
                return false;
        }
    }

    /**
     * source/HdmiSource on p6-hal: HDMI became (or still is) the current source, or a CEC device changed.
     * Once per data the HDMI type goes to Auto; then, in Auto, the console check sets the state.
     */
    public static void onHdmiShown(Context app, Play6Gmpf g, Play6Gmpf2 g2, String why) {
        try {
            if (!g2.isIdentityOpen()) return;
            if (g.getCurrentInputSource() != Play6Gmpf.SOURCE_HDMI1) {               // 696
                Log.i(TAG, "HDMI not the current source (" + why + "): game mode left alone");
                return;
            }
            int[] p = read33(g2);
            if (p == null) return;
            SharedPreferences dp = Play6App.prefs(app);
            if (!dp.getBoolean(K_AUTO_DEFAULT, false)) {
                if (p[1] != 1) {
                    boolean r = g2.setGameModeType(1);                               // IGmpf2 34(1)
                    Log.i(TAG, "default Auto (once): 33 was " + raw(p) + ", 34(1) -> " + r);
                    p = read33(g2);
                    if (p == null) return;
                }
                dp.edit().putBoolean(K_AUTO_DEFAULT, true).apply();
            }
            if (p[1] == 1) autoState(app, g2, p, why, true);
        } catch (Throwable t) {
            Log.w(TAG, "HDMI game check (" + why + "): " + t);
        }
    }

    /**
     * Auto: a console -> 35(0), else 35(1); sent only when 33's state differs. {@code notice}: Auto's own check
     * (HdmiSource), which may show the t18 notice; false for the panel's "Auto chosen". p6-hal.
     */
    private static void autoState(Context app, Play6Gmpf2 g2, int[] p, String why, boolean notice) throws Exception {
        GameConsole.Result c = GameConsole.find(app);
        if (c.console == null && !c.settled) {
            Log.i(TAG, "auto (" + why + "): " + c.describe() + ": state left, checked again at the rename");
            return;
        }
        boolean open = c.console != null;
        int state = p == null ? -1 : p[0];
        if (state == (open ? 0 : 1)) {
            Log.i(TAG, "auto (" + why + "): " + c.describe() + ", state already " + state);
            return;
        }
        boolean r = g2.setGameModeState(open);                                      // IGmpf2 35(0 / 1)
        Log.i(TAG, "auto (" + why + "): " + c.describe() + " -> 35(" + (open ? 0 : 1) + ") -> " + r);
        if (r) notice(app, open, c.console, notice);
    }

    /**
     * After a 35 that Auto sent and the vendor accepted (t18): {@code open} -> "Game mode on" (the console's
     * name as the description when known) unless that notice is already the last one; closed -> "Game mode
     * off" only after it. {@code show} false (the panel's Auto): nothing is shown, a close still ends the
     * pairing. p6-hal; Notify posts to the main thread.
     */
    private static void notice(Context app, boolean open, String console, boolean show) {
        if (!open) {
            boolean paired = sOnNotice;
            sOnNotice = false;
            if (!show || !paired) return;
            Log.i(TAG, "notice: game mode off");
            Notify.show(app, app.getString(R.string.p6_game_off));
            return;
        }
        if (!show || sOnNotice) return;
        sOnNotice = true;
        String name = GameConsole.knownName(console);
        Log.i(TAG, "notice: game mode on" + (name != null ? " (" + name + ")" : ""));
        Notify.show(app, app.getString(R.string.p6_game_on), name);
    }

    /** IGmpf2 33 {state, type, option}, or null when unreadable. p6-hal. */
    private static int[] read33(Play6Gmpf2 g2) {
        try {
            int[] p = g2.getGameModeProp();                                          // IGmpf2 33
            return p != null && p.length >= 3 ? p : null;
        } catch (Throwable t) {
            Log.w(TAG, "33: " + t);
            return null;
        }
    }

    /** The panel's row for a 33 reply: type 1 -> AUTO, state 0 -> ON, else OFF; -1 when unknown. */
    public static int modeOf(int[] prop33) {
        if (prop33 == null || prop33.length < 3) return -1;
        if (prop33[1] == 1) return AUTO;
        return prop33[0] == 0 ? ON : OFF;
    }

    /**
     * Whether game mode is on, for the manual keystone mutex (stock isGameKeystoneMutex), from values read on
     * p6-hal: 666 reads 4 (the Game picture that 35(0) sets), or 33's state (field 0) is 0. The order of 33
     * is confirmed (research hdmi_volume_game.md 3.1), so {@code option647} is no longer needed to decide it.
     */
    public static boolean isOn(int picture666, int[] prop33, int option647) {
        if (picture666 == Play6Gmpf.PICTURE_GAME) return true;
        return prop33 != null && prop33.length >= 3 && prop33[0] == 0;
    }

    /** The raw 33 reply for a log line ("-" when unreadable). */
    public static String raw(int[] p) {
        return p == null || p.length < 3 ? "-" : p[0] + "," + p[1] + "," + p[2];
    }
}
