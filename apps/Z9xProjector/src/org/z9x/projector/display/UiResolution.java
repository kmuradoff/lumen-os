package org.z9x.projector.display;

import android.app.DreamManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.DisplayMetrics;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.KeyReceiver;
import org.z9x.projector.R;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.Ui;
import org.z9x.projector.ak.AkOverlay;
import org.z9x.projector.panel.QuickPanel;
import org.z9x.projector.power.StandbyController;
import org.z9x.projector.power.WakeCurtain;
import org.z9x.projector.ui.NavRow;
import org.z9x.projector.ui.Notify;
import org.z9x.projector.ui.OverlayHost;
import org.z9x.projector.ui.Page;

/**
 * Lumen OS 1.0.1: "Interface resolution" (Projector settings > Display, and a row on the quick panel's
 * Picture page): 4K (3840 x 2160, the default: 1 UI px = 1 panel px) or 1080p (as on XGIMI) for the whole
 * Android UI, with the same 960 x 540 dp layout (density 640 / 320); 2K (2560 x 1440 @ 427) only with the
 * debug property {@value #PROP_DEV}=1 ({@link UiRes#offered}). The image's uires lane applies the value
 * at boot ({@link UiRes}: persist.z9x.ui_res) and falls back to 1080p by itself when a mode does not come
 * up correctly (sys.z9x.ui_res.active / .why); this class only asks, writes the choice and restarts.
 *
 * Change ({@link UiResPanel}: choose -> confirm -> "Restart now"): a marker {new, previous, boot id} is
 * committed to device-protected prefs first, then persist.z9x.ui_res (read back: a refused write changes
 * nothing and says so), then a short wait until the uires lane took the value over ({@link #awaitLanePick};
 * a lane that does not take it, e.g. a 2K pick it ignores, restores the old value and restarts nothing),
 * then an orderly reboot with reason {@value #REBOOT_REASON}
 * (StandbyController.noteOrderlyReboot, so never a boot-dark reset; RemoteAutoPair shows no remote-lost
 * prompt after it). A choice that already runs (the boot fallback left 1080p and the user picks 1080p) is
 * only written, without a restart and without a question.
 *
 * First start in the new mode (another boot id, and the marker's resolution really runs): the full-screen
 * "Keep this resolution?" ({@link UiResPanel#showKeep}, {@value UiRes#KEEP_SECONDS} s counted only while
 * it is on screen) as soon as the screen is free: boot completed, setup done, awake, no standby, curtain,
 * screensaver, AK run, eye protection or other panel (checked every {@value #KEEP_POLL_MS} ms, again at
 * SCREEN_ON; given up after {@value #KEEP_GIVE_UP_MS} ms of waiting: the mode stays). Keep -> the marker
 * goes. BACK, "Back to ..." or the end of the countdown -> the previous resolution is written and the
 * projector restarts. Closed by anything else (HOME, screen off, an AK run) -> asked again when the screen
 * is free, with the time that was left (at least 5 s; closed more than {@value UiRes#KEEP_MAX_INTERRUPTS}
 * times = no answer: back). A window that cannot be shown counts as a busy screen. A new choice in the
 * chooser while the question waits answers it (kept). Another resolution runs (the boot fallback) -> the
 * marker goes without a question (the setting's summary says what happened). No marker (the 4K default
 * after an update or a fresh install: persist.z9x.ui_res unset, the user chose nothing) -> never asked.
 * The boot-side fallback protects a mode without any picture even if this question never appears.
 *
 * Threads: "z9x-uires" (prefs, properties, reboot), main (panel, checks). Everything is wrapped.
 */
public final class UiResolution {
    static final String TAG = "Z9xUiRes";

    static final String PROP_WANT = "persist.z9x.ui_res";
    /** Debug only (adb): 1 = the chooser also offers 2K. Never written by the app. */
    static final String PROP_DEV = "persist.z9x.ui_res.dev";
    static final String PROP_ACTIVE = "sys.z9x.ui_res.active";
    static final String PROP_WHY = "sys.z9x.ui_res.why";
    /** The uires lane's view of the next start (its "pick" service sets them after our write). */
    static final String PROP_LANE_WANT = "sys.z9x.ui_res.want";
    static final String PROP_LANE_FAILED = "sys.z9x.ui_res.failed";
    /** PowerManager.reboot reason: init's "reboot,z9x-uires" (an orderly reason, sys.BootReason). */
    static final String REBOOT_REASON = "z9x-uires";

    private static final String PREFS = "z9x_uires";
    private static final String K_NEW = "pending_new";
    private static final String K_PREV = "pending_prev";
    private static final String K_BOOT = "pending_boot";
    /** The last change that did not come up: asked for / ran instead (kept until the next choice). */
    private static final String K_FELL_FROM = "fell_from";
    private static final String K_FELL_TO = "fell_to";

    private static final long KEEP_POLL_MS = 2_000;
    private static final long KEEP_GIVE_UP_MS = 10 * 60_000;
    private static final long KEEP_MIN_LEFT_MS = 5_000;
    /** The restart waits at most this long for the uires lane to take the new value over. */
    private static final long PICK_WAIT_MS = 4_000, PICK_POLL_MS = 100;

    private static Context sApp;
    private static SafeHandler sH;
    /** K_FELL_FROM / K_FELL_TO, loaded on "z9x-uires" at install (0 = none). */
    private static volatile int sFellFrom, sFellTo;

    // ---- main thread: the "Keep this resolution?" question of this boot
    /** != 0: the question is due (the marker's resolution runs). */
    private static int sKeepNew, sKeepPrev;
    private static long sKeepLeftMs = UiRes.KEEP_SECONDS * 1000L;
    /** Times the question was closed without an answer (bounded: {@link UiRes#KEEP_MAX_INTERRUPTS}). */
    private static int sKeepInterrupts;
    /** Uptime of the first check that found the screen busy (0 = not waiting). */
    private static long sKeepWaitSince;
    private static String sKeepLastBlocker;
    private static BroadcastReceiver sScreenOn;
    private static final Runnable KEEP_CHECK = UiResolution::keepCheck;

    private UiResolution() {}

    // =================================================================== install (main)

    /**
     * The image offers a choice of interface resolution only with ro.z9x.uires.allow=1. Lumen OS 1.0.1 ships 0:
     * 4K worked but the UI was too slow on the Z9X, so every start is 1080p and the setting and the quick-panel
     * row are not shown (a value stored earlier is ignored by the image's uires lane).
     */
    public static boolean enabled() {
        return "1".equals(SystemProperties.get("ro.z9x.uires.allow", "0"));
    }

    /** App.onCreate. Cheap: the marker is read on "z9x-uires". Idempotent. */
    public static synchronized void install(Context ctx) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        sH = SafeHandler.newThread("z9x-uires");
        if (!enabled()) return;
        try {
            QuickPanel.addExtension(QuickPanel.SECTION_PICTURE, new PanelRow());
        } catch (Throwable t) {
            Log.w(TAG, "quick panel row: " + t);
        }
        sH.post(UiResolution::checkMarker);
    }

    private static SafeHandler worker() {
        if (sH == null) {
            synchronized (UiResolution.class) {
                if (sH == null) sH = SafeHandler.newThread("z9x-uires");
            }
        }
        return sH;
    }

    // =================================================================== state (any thread)

    /**
     * The current state (cheap property reads). Wanted = the lane's sys.z9x.ui_res.want when published, else
     * persist.z9x.ui_res ({@link UiRes#effectiveWant}: a persist value the lane ignores claims nothing).
     */
    public static UiRes.Status status(Context c) {
        String want = UiRes.effectiveWant(SystemProperties.get(PROP_WANT, ""), SystemProperties.get(PROP_LANE_WANT, ""));
        return UiRes.status(want, SystemProperties.get(PROP_ACTIVE, ""),
                displayRes(c), SystemProperties.get(PROP_WHY, ""), sFellFrom, sFellTo);
    }

    /** The resolution in effect now: the uires lane's property, else the app's display size (0 unknown). */
    static int activeNow(Context c) {
        return UiRes.active(SystemProperties.get(PROP_ACTIVE, ""), displayRes(c));
    }

    /** The app's display (WindowManager size, i.e. the UI size) as a resolution, or 0. */
    private static int displayRes(Context c) {
        try {
            DisplayMetrics m = c.getResources().getDisplayMetrics();
            return UiRes.fromSize(m.widthPixels, m.heightPixels);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The chooser's rows ({@link UiRes#offered}): 4K and 1080p; 2K with {@value #PROP_DEV} or while it is set / runs. */
    static int[] offered(UiRes.Status st) {
        boolean dev = false;
        try {
            dev = UiRes.isOn(SystemProperties.get(PROP_DEV, ""));
        } catch (Throwable ignored) {
        }
        return UiRes.offered(dev, st.wanted, st.active);
    }

    /** "1080p", or "4K (default)" for the default. */
    static String choiceLabel(Context c, int h) {
        String l = UiRes.label(h);
        return h == UiRes.DEFAULT ? c.getString(R.string.uires_default_fmt, l) : l;
    }

    /** The setting's summary: the calm fallback note, or what the choice means. */
    public static String summary(Context c, UiRes.Status s) {
        if (s.fallback && s.active != 0) {
            return c.getString(R.string.uires_summary_fallback, UiRes.label(s.active), UiRes.label(s.fellFrom));
        }
        return c.getString(R.string.uires_summary);
    }

    /** One line for Projector settings > Diagnostics. */
    public static String diagLine(Context c) {
        UiRes.Status s = status(c);
        String size = "?";
        try {
            DisplayMetrics m = c.getResources().getDisplayMetrics();
            size = m.widthPixels + "x" + m.heightPixels + " @ " + m.densityDpi + " dpi";
        } catch (Throwable ignored) {
        }
        String dev = "";
        try {
            if (UiRes.isOn(SystemProperties.get(PROP_DEV, ""))) dev = "; 2K offered (" + PROP_DEV + ")";
        } catch (Throwable ignored) {
        }
        return "UI resolution: " + s + "; window " + size + dev;
    }

    // =================================================================== change (UiResPanel)

    /** Projector settings > Display > Interface resolution: the full-screen chooser. Main thread. */
    public static void openChooser(Context ctx) {
        if (sApp == null) install(ctx);
        UiResPanel.showChooser(sApp, false);
    }

    /**
     * Main thread ("Restart now"). Writes {@code target} and restarts, or only writes it when it already
     * runs. {@code onFailed} (may be null) runs on the main thread when nothing could be changed.
     */
    static void change(Context ctx, int target, Runnable onFailed) {
        if (sApp == null) install(ctx);
        final int active = activeNow(sApp);
        final String wantedRaw = SystemProperties.get(PROP_WANT, "");
        final int wanted = UiRes.wanted(wantedRaw);
        final boolean restart = UiRes.needsRestart(target, active);
        final int prev = UiRes.previous(active, wanted);
        Log.i(TAG, "change to " + UiRes.label(target) + " (wanted " + UiRes.label(wanted) + ", active "
                + (active == 0 ? "?" : UiRes.label(active)) + ")" + (restart ? ": restart" : ": runs already, no restart"));
        if (sKeepNew != 0) {
            // a choice made in the chooser answers a "Keep this resolution?" still waiting for a free
            // screen: the new resolution works; the marker is rewritten / cleared below
            Log.i(TAG, "a new choice while the keep question waits: " + UiRes.label(sKeepNew) + " counts as kept");
            finishKeep();
        }
        if (restart) {
            try { OverlayHost.get(sApp).dismissAll(false); } catch (Throwable t) { Log.w(TAG, "dismiss: " + t); }
        }
        worker().post(() -> {
            forgetFallback();                                 // a new choice: the old note goes
            if (!restart) {
                boolean ok = setWanted(target);
                clearMarker();
                Ui.main().post(() -> {
                    if (ok) Notify.show(sApp, sApp.getString(R.string.uires_row), UiRes.label(target));
                    else failed(onFailed);
                });
                return;
            }
            String boot = Hal.bootId();
            if (!commitMarker(target, prev, boot == null ? "" : boot)) {
                Ui.main().post(() -> failed(onFailed));
                return;
            }
            if (!setWanted(target)) {
                clearMarker();
                Ui.main().post(() -> failed(onFailed));
                return;
            }
            if (!awaitLanePick(target)) {
                // the lane did not take it (it ignores 1440 from the app, its debug file pins a mode, or a
                // recorded fallback could not be cleared): the next start would run the same resolution and
                // the marker would turn into a false "did not start properly" note. Nothing changes.
                restoreWanted(wantedRaw);
                clearMarker();
                Ui.main().post(() -> failed(onFailed));
                return;
            }
            if (!reboot("change " + UiRes.label(prev) + " -> " + UiRes.label(target))) {
                restoreWanted(wantedRaw);                     // nothing changes later by surprise
                clearMarker();
                Ui.main().post(() -> failed(onFailed));
            }
        });
    }

    private static void failed(Runnable onFailed) {
        Notify.show(sApp, sApp.getString(R.string.uires_row), sApp.getString(R.string.uires_failed));
        if (onFailed != null) {
            try { onFailed.run(); } catch (Throwable t) { Log.w(TAG, "onFailed: " + t); }
        }
    }

    /** z9x-uires: persist.z9x.ui_res = h, read back. */
    private static boolean setWanted(int h) {
        String v = Integer.toString(h);
        try {
            SystemProperties.set(PROP_WANT, v);
            boolean ok = v.equals(SystemProperties.get(PROP_WANT, ""));
            Log.i(TAG, PROP_WANT + " = " + v + (ok ? "" : ": NOT written (read back '"
                    + SystemProperties.get(PROP_WANT, "") + "')"));
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "set " + PROP_WANT + ": " + t);
            return false;
        }
    }

    /**
     * z9x-uires, between {@link #setWanted} and the restart: init runs the uires lane's "pick" on every
     * write of persist.z9x.ui_res, which copies the value to /metadata (the only place its 'on fs' step
     * can read: persist properties load later) and then publishes sys.z9x.ui_res.want / .failed
     * ({@link UiRes#pickDone}). A restart before that starts in the old resolution (and a re-picked
     * fallback stays recorded): the projector would then switch one start later, without a question.
     * Waits at most {@value #PICK_WAIT_MS} ms (usually well under a second); no wait without the lane.
     * False = the lane is there and did not take {@code h} (the lane ignores a value it does not accept
     * from the app, e.g. 1440, and its debug file overrides the choice): a change then restarts nothing
     * ({@link #change}); the way back from "Keep this resolution?" restarts anyway ({@link #onRevert}).
     */
    private static boolean awaitLanePick(int h) {
        try {
            if (SystemProperties.get(PROP_LANE_WANT, "").isEmpty()) return true;   // image without the lane
            long t0 = SystemClock.uptimeMillis();
            while (true) {
                String want = SystemProperties.get(PROP_LANE_WANT, ""), fl = SystemProperties.get(PROP_LANE_FAILED, "");
                long ms = SystemClock.uptimeMillis() - t0;
                if (UiRes.pickDone(want, fl, h)) {
                    Log.i(TAG, "uires lane took " + UiRes.label(h) + " over in " + ms + " ms");
                    return true;
                }
                if (ms >= PICK_WAIT_MS) {
                    Log.w(TAG, "uires lane did not take " + UiRes.label(h) + " over in " + ms + " ms (want '" + want
                            + "', failed '" + fl + "')");
                    return false;
                }
                SystemClock.sleep(PICK_POLL_MS);
            }
        } catch (Throwable t) {
            Log.w(TAG, "uires lane wait: " + t);
            return true;                                      // unknown: as before, the restart goes ahead
        }
    }

    /** z9x-uires: persist.z9x.ui_res back to exactly what it was (also unset). */
    private static void restoreWanted(String raw) {
        try {
            SystemProperties.set(PROP_WANT, raw == null ? "" : raw);
            Log.i(TAG, PROP_WANT + " back to '" + raw + "'");
        } catch (Throwable t) {
            Log.w(TAG, "restore " + PROP_WANT + ": " + t);
        }
    }

    /** z9x-uires: orderly reboot "z9x-uires" (blocks when it works). False = refused / failed. */
    private static boolean reboot(String why) {
        try {
            StandbyController.noteOrderlyReboot("ui resolution");
            Log.w(TAG, "restart for the interface resolution (" + why + ")");
            PowerManager pm = sApp.getSystemService(PowerManager.class);
            pm.reboot(REBOOT_REASON);                         // REBOOT (platform signature); blocks
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "restart failed (" + why + ")", t);
            return false;
        }
    }

    // =================================================================== marker (z9x-uires)

    private static SharedPreferences prefs() {
        return sApp.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean commitMarker(int nw, int prev, String boot) {
        try {
            return prefs().edit().putInt(K_NEW, nw).putInt(K_PREV, prev).putString(K_BOOT, boot).commit();
        } catch (Throwable t) {
            Log.w(TAG, "marker: " + t);
            return false;
        }
    }

    /** z9x-uires: remember "asked for {@code from}, {@code to} runs" for the setting's summary. */
    private static void rememberFallback(int from, int to) {
        sFellFrom = from;
        sFellTo = to;
        try {
            prefs().edit().putInt(K_FELL_FROM, from).putInt(K_FELL_TO, to).commit();
        } catch (Throwable t) {
            Log.w(TAG, "fallback note: " + t);
        }
    }

    /** z9x-uires. */
    private static void forgetFallback() {
        if (sFellFrom == 0 && sFellTo == 0) return;
        sFellFrom = 0;
        sFellTo = 0;
        try {
            prefs().edit().remove(K_FELL_FROM).remove(K_FELL_TO).commit();
        } catch (Throwable t) {
            Log.w(TAG, "fallback note: " + t);
        }
    }

    private static void clearMarker() {
        try {
            prefs().edit().remove(K_NEW).remove(K_PREV).remove(K_BOOT).commit();
        } catch (Throwable t) {
            Log.w(TAG, "clear marker: " + t);
        }
    }

    /** z9x-uires, once per process: is this the first start after a user's change? */
    private static void checkMarker() {
        try {
            SharedPreferences p = prefs();
            sFellFrom = p.getInt(K_FELL_FROM, 0);
            sFellTo = p.getInt(K_FELL_TO, 0);
            int nw = p.getInt(K_NEW, 0), prev = p.getInt(K_PREV, 0);
            String bootReq = p.getString(K_BOOT, "");
            String bootNow = Hal.bootId();
            int active = activeNow(sApp);
            UiRes.Status st = status(sApp);
            switch (UiRes.keep(nw, bootReq, bootNow, active)) {
                case NONE:
                    Log.i(TAG, "interface resolution: " + st);
                    return;
                case SAME_BOOT:
                    Log.i(TAG, "change to " + UiRes.label(nw) + " asked in this boot: restart pending (" + st + ")");
                    return;
                case DROP:
                    Log.w(TAG, "change to " + UiRes.label(nw) + " asked, this boot runs "
                            + (active == 0 ? "?" : UiRes.label(active)) + " (" + st + "): no question, marker cleared");
                    clearMarker();
                    if (active != 0) rememberFallback(nw, active);  // the summary says it calmly
                    return;
                case ASK:
                default:
                    final int back = UiRes.isChoice(prev) && prev != nw ? prev : UiRes.P1080;
                    Log.i(TAG, "first start at " + UiRes.label(nw) + " after the user's change (" + st
                            + "): asking to keep it, else back to " + UiRes.label(back));
                    Ui.main().post(() -> armKeep(nw, back));
            }
        } catch (Throwable t) {
            Log.w(TAG, "marker check: " + t);
        }
    }

    // =================================================================== "Keep this resolution?" (main)

    private static void armKeep(int nw, int prev) {
        sKeepNew = nw;
        sKeepPrev = prev;
        sKeepLeftMs = UiRes.KEEP_SECONDS * 1000L;
        sKeepInterrupts = 0;
        sKeepWaitSince = 0;
        registerScreenOn();
        Ui.main().removeCallbacks(KEEP_CHECK);
        Ui.main().post(KEEP_CHECK);
    }

    private static void keepCheck() {
        Ui.main().removeCallbacks(KEEP_CHECK);
        if (sKeepNew == 0 || UiResPanel.isKeepShowing()) return;
        PowerManager pm = sApp.getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) {
            sKeepWaitSince = 0;                              // SCREEN_ON checks again
            return;
        }
        String busy = blocker();
        if (busy == null) {
            if (sKeepLeftMs <= 0) {                          // closed too often without an answer
                onRevert("closed " + sKeepInterrupts + " times without an answer");
                return;
            }
            if (UiResPanel.showKeep(sApp, sKeepNew, sKeepPrev, sKeepLeftMs)) {
                sKeepWaitSince = 0;
                sKeepLastBlocker = null;
                return;
            }
            busy = "window not shown";                       // retried, but not for ever
        }
        long now = SystemClock.uptimeMillis();
        if (sKeepWaitSince == 0) sKeepWaitSince = now;
        if (!busy.equals(sKeepLastBlocker)) Log.i(TAG, "keep question waits: " + busy);
        sKeepLastBlocker = busy;
        if (now - sKeepWaitSince > KEEP_GIVE_UP_MS) {
            Log.w(TAG, "keep question not shown for " + KEEP_GIVE_UP_MS / 60_000 + " min (" + busy + "): "
                    + UiRes.label(sKeepNew) + " stays");
            finishKeep();
            worker().post(UiResolution::clearMarker);
            return;
        }
        Ui.main().postDelayed(KEEP_CHECK, KEEP_POLL_MS);
    }

    /** Why the question cannot be shown now, or null. Main thread. */
    private static String blocker() {
        if (!"1".equals(SystemProperties.get("sys.boot_completed", ""))) return "boot not completed";
        if (!KeyReceiver.isSetupComplete(sApp)) return "setup running";
        if (StandbyController.isActive() || StandbyController.shutdownInFlight()) return "standby";
        if (WakeCurtain.isShowing()) return "curtain";
        if (AkOverlay.isActive()) return "auto keystone";
        if (org.z9x.projector.eye.EyeGuard.isActive()) return "eye protection";
        if (OverlayHost.get(sApp).current() != null) return "another panel";
        try {
            DreamManager dm = sApp.getSystemService(DreamManager.class);
            if (dm != null && dm.isDreaming()) return "screensaver";
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void registerScreenOn() {
        if (sScreenOn != null) return;
        try {
            sScreenOn = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (sKeepNew == 0) return;
                    sKeepWaitSince = 0;
                    Ui.main().removeCallbacks(KEEP_CHECK);
                    Ui.main().postDelayed(KEEP_CHECK, KEEP_POLL_MS);
                }
            };
            sApp.registerReceiver(sScreenOn, new IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "screen receiver: " + t);
        }
    }

    private static void finishKeep() {
        sKeepNew = 0;
        Ui.main().removeCallbacks(KEEP_CHECK);
        if (sScreenOn != null) {
            try { sApp.unregisterReceiver(sScreenOn); } catch (Throwable ignored) { }
            sScreenOn = null;
        }
    }

    /** Main (UiResPanel): Keep. */
    static void onKeep() {
        if (sKeepNew == 0) return;
        Log.i(TAG, UiRes.label(sKeepNew) + " kept by the user");
        finishKeep();
        worker().post(UiResolution::clearMarker);
    }

    /** Main (UiResPanel): BACK, "Back to ..." or the end of the countdown. */
    static void onRevert(String how) {
        if (sKeepNew == 0) return;
        final int nw = sKeepNew, prev = sKeepPrev;
        finishKeep();
        Log.w(TAG, UiRes.label(nw) + " not kept (" + how + "): back to " + UiRes.label(prev));
        worker().post(() -> {
            clearMarker();                                    // no question after the way back
            if (!setWanted(prev)) {
                Ui.main().post(() -> failed(null));
                return;
            }
            // restarts even when the lane did not take prev over: this is the way out of a mode the user
            // did not confirm, and a 4K start restarted before the lane's check passed falls back to 1080p
            if (!awaitLanePick(prev)) Log.w(TAG, "restarting anyway (the way back)");
            if (!reboot("back " + UiRes.label(nw) + " -> " + UiRes.label(prev))) Ui.main().post(() -> failed(null));
        });
    }

    /**
     * Main (UiResPanel): closed by HOME, screen off, focus loss or an AK run; asked again later with the
     * time left (at least {@value #KEEP_MIN_LEFT_MS} ms). Closed more than
     * {@value UiRes#KEEP_MAX_INTERRUPTS} times: no answer, back at the next free screen (keepCheck; never
     * while the screen goes off), so nothing that keeps closing it can hold the countdown off for ever.
     */
    static void onKeepInterrupted(long leftMs) {
        if (sKeepNew == 0) return;
        sKeepInterrupts++;
        sKeepLeftMs = sKeepInterrupts > UiRes.KEEP_MAX_INTERRUPTS ? 0 : Math.max(KEEP_MIN_LEFT_MS, leftMs);
        Log.i(TAG, "keep question closed without an answer (" + sKeepInterrupts + "): "
                + (sKeepLeftMs > 0 ? "asked again (" + sKeepLeftMs / 1000 + " s)" : "no answer, going back"));
        Ui.main().removeCallbacks(KEEP_CHECK);
        Ui.main().postDelayed(KEEP_CHECK, KEEP_POLL_MS);
    }

    // =================================================================== quick panel row

    /** "Interface resolution  4K >" on the quick panel's Picture page: opens the chooser. Main thread. */
    private static final class PanelRow implements QuickPanel.Extension {
        private Context app;
        private NavRow row;

        @Override
        public void addRows(Context c, Page page) {
            app = c.getApplicationContext();
            row = page.add(new NavRow(app, app.getString(R.string.uires_row), () -> {
                try {
                    if (!KeyReceiver.isSetupComplete(app)) {
                        Ui.toast(app, R.string.toast_setup_running);
                        return;
                    }
                    UiResPanel.showChooser(app, true);
                } catch (Throwable t) {
                    Log.w(TAG, "panel row: " + t);
                }
            }));
            onPageShown();
        }

        @Override
        public void onPageShown() {
            if (row == null) return;
            try {
                row.setValue(UiRes.label(status(app).wanted));
            } catch (Throwable t) {
                Log.w(TAG, "panel row value: " + t);
            }
        }
    }
}
