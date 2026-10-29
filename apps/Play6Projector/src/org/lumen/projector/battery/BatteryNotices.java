package org.lumen.projector.battery;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

import org.lumen.projector.Hal;
import org.lumen.projector.R;
import org.lumen.projector.Ui;
import org.lumen.projector.power.PowerKey;
import org.lumen.projector.ui.DialogPanel;
import org.lumen.projector.ui.Notify;
import org.lumen.projector.ui.OverlayHost;
import org.lumen.projector.ui.TextRow;
import org.lumen.projector.ui.Theme;

/**
 * Battery cards and low-battery warnings of the XGIMI Play 6 (docs/play6/SETTINGS_PLAN.md 3.3;
 * docs/play6/research/battery_sound_features.md 1.4 and 6.1 B5). Cards and notices only: Lumen never
 * turns the projector off by itself (the setting of plan 3.3, persist.lumen.p6.batt_shutdown, is owner
 * decision O5 and is not built). Main thread only.
 *
 * Power source cards, as stock SystemUI (BatteryHelper), from the vendor power event type 3 (Battery
 * passes every event on; no card on type 0, no 3-minute rule: the vendor sends no source event at boot).
 * The card waits 1.5 s, so the power saving change the vendor makes about 0.5 s after the source change
 * (type 5) is known; then, only while the screen is on:
 *  - INTERNAL_BATTERY: "Powered by the internal battery" (+ "Power saving is on" while 327 says on);
 *  - STANDARD_ADAPTER: "Power adapter connected" (+ "Power saving is off" when power saving was on at the
 *    event and is off now);
 *  - LOW_POWER: "Power bank or third-party adapter connected" (+ "Power saving is on" while on);
 *  - STANDARD_BATTERY: "Battery stand connected" (+ "Power saving is on" while on);
 *  - ACCESS_ADAPTER (stand pairing, stock only with its pairing button) and unknown values: no card.
 * Type 5 false_by_level (the user raised the brightness on battery): "Power saving is off", not while the
 * starry sky lens is in (IGmpf2 84, as stock). Without a registered power listener (kill switch, failed
 * registration) the same source cards come from the watchdog's agreed state (a change of IGmpf2 52, or
 * of 238 while the IGmpf2 guard is closed), after the first 3 minutes only.
 *
 * Low battery, from the agreed state (Battery), only after the first 3 minutes after boot, only on the
 * internal battery (adapter out, IGmpf2 52 == 3):
 *  - level <= 30, once per discharge: card "Battery low: N % left";
 *  - level <= 10, once per discharge: card "Battery very low: N % left. Connect the power adapter.";
 *  - level <= 5: the notice "Battery almost empty" with "Turn off" (the user's own Power off, the power
 *    menu's PowerManager.shutdown) and "Keep watching" (focused first). It stays until the adapter is in;
 *    closed by the user, it comes back at the next lower level. It waits while another panel is open.
 * A discharge ends when the agreed state says adapter in: the 30 / 10 cards can come again.
 *
 * Low battery from the vendor event type 1 (strings of stock GmPowerManager / BatteryHelper, inventory B
 * 2.4, 2.7; also through the test hook BatteryTestReceiver):
 *  - LumensPowerSaving / LowPowerAlarm: the 30 / 10 cards, unless that card was shown in this discharge;
 *  - LowPowerPoweroff: the full-width countdown, shown at once on this thread without a HAL read:
 *    "Battery empty. The projector turns off in 10 s. Connect the power adapter to keep watching." It
 *    reads the battery every 2 s meanwhile; at 0 it ends in the 5 % notice (no shutdown, O5);
 *  - type 3 STANDARD_ADAPTER or LOW_POWER: cancel at once; STANDARD_BATTERY: cancel when IGmpf2 51 reads
 *    above 5 %.
 * Cancel of the countdown and the notice (stock set, Battery.State.cancelsWarning): one read with 238 true,
 * or IGmpf2 52 = standard adapter / low power source / stand above 5 %.
 */
public final class BatteryNotices {
    private static final String TAG = Battery.TAG;
    private static final int COUNTDOWN_S = 10;
    private static final long CHECK_EVERY_S = 2;

    /** The source card waits this long for the vendor's power saving change (type 5, about 0.5 s later). */
    private static final long CARD_DELAY_MS = 1_500;

    // Vendor power event strings (stock GmPowerManager, IPowerCallback.powerEvent(type, value); research 1.2).
    static final String V_LOW = "LumensPowerSaving", V_VERY_LOW = "LowPowerAlarm", V_POWER_OFF = "LowPowerPoweroff";
    static final String V_ECO_OFF_BY_LEVEL = "false_by_level";
    private static final String COPOWER = "ENUM_COPOWER_DEVICE_";
    /** Source cards (type 3 values, or IGmpf2 52 without the listener). */
    private static final int CARD_INTERNAL = 0, CARD_ADAPTER = 1, CARD_LOW_POWER = 2, CARD_STAND = 3;

    // main thread state
    /** The source card waiting for CARD_DELAY_MS, or null. */
    private static Runnable sCard;
    /** Power saving at the first source event of the waiting card (stock reads 327 at the event). */
    private static Boolean sCardEcoBefore;
    private static boolean sWarnedLow, sWarnedVeryLow;
    private static DialogPanel sNotice;
    /** The notice is due without a level (the countdown ended) until a cancel or the user closes it. */
    private static boolean sForceNotice;
    /** The level at which the user closed the notice; it comes back below it. null = not closed. */
    private static Integer sClosedAt;
    private static Countdown sCountdown;
    /** "notice waits" was logged for the current wait (one line per wait, not per read). */
    private static boolean sWaitLogged;
    /** A warning is up or due: Battery polls every 10 s. Any thread. */
    private static volatile boolean sUp;

    private BatteryNotices() {}

    /** The 5 % notice or the countdown is up or due (Battery polls faster). Any thread. */
    static boolean isUp() {
        return sUp;
    }

    private static void updateUp() {
        sUp = sNotice != null || sCountdown != null || sForceNotice;
    }

    /** One line for Projector settings > Diagnostics. Main thread. */
    public static String describe() {
        return "cards 30 % " + (sWarnedLow ? "shown" : "armed") + ", 10 % " + (sWarnedVeryLow ? "shown" : "armed")
                + "; source card " + (sCard != null ? "waiting" : "none")
                + "; notice " + (sNotice != null ? "up" : sForceNotice ? "due" : sClosedAt != null
                ? "closed at " + sClosedAt + " %" : "off") + "; countdown " + (sCountdown != null ? "running" : "off")
                + "; automatic shutdown: not built (O5)";
    }

    // ================================================================== from Battery (main thread)
    /** Every read: the cancel rule (one read is enough), then the threshold logic on the agreed state. */
    static void onRead(Context app, Battery.State last) {
        if (app == null || last == null) return;
        boolean cancel = last.cancelsWarning();
        if (cancel && (sNotice != null || sCountdown != null || sForceNotice)) {
            cancelWarnings(app, "read " + last.describe());
        }
        evaluate(app, cancel);
    }

    /**
     * The agreed state changed: a discharge ends with the adapter in; without a registered power listener
     * a source change of the agreed state shows the source card (after the first 3 minutes only).
     */
    static void onStableChanged(Context app, Battery.State old, Battery.State now) {
        if (app == null || now == null) return;
        if (Boolean.TRUE.equals(now.adapter)) {                  // a discharge ended
            sWarnedLow = sWarnedVeryLow = false;
            sClosedAt = null;
        }
        if (old == null || Hal.isPowerListenerRegistered()) return;   // the cards come from the vendor events
        int card = -1;
        if (now.source != null && old.source != null) {
            if (!now.source.equals(old.source)) card = cardOfSource(now.source);
        } else if (now.source == null && old.adapter != null && now.adapter != null && !old.adapter.equals(now.adapter)) {
            card = now.adapter ? CARD_ADAPTER : CARD_INTERNAL;  // IGmpf2 guard closed: 238 only
        }
        if (card < 0) return;
        if (!Battery.settled()) {
            Log.i(TAG, "source change in the first 3 minutes: no card");
            return;
        }
        showSourceCard(app, card, old.eco, "watchdog " + now.describe());
    }

    /** IGmpf2 52 -> card (4, the stand with adapter, has no stock card of its own: none). */
    private static int cardOfSource(int src) {
        switch (src) {
            case Battery.SRC_ADAPTER: return CARD_ADAPTER;
            case Battery.SRC_LOW_POWER: return CARD_LOW_POWER;
            case Battery.SRC_INTERNAL: return CARD_INTERNAL;
            case Battery.SRC_STAND: return CARD_STAND;
            default: return -1;
        }
    }

    /** A type 3 value -> card; ACCESS_ADAPTER (stand pairing) and unknown values: none. */
    private static int cardOfEvent(String v) {
        if (v == null || !v.startsWith(COPOWER)) return -1;
        switch (v.substring(COPOWER.length())) {
            case "INTERNAL_BATTERY": return CARD_INTERNAL;
            case "STANDARD_ADAPTER": return CARD_ADAPTER;
            case "LOW_POWER": return CARD_LOW_POWER;
            case "STANDARD_BATTERY": return CARD_STAND;
            default: return -1;
        }
    }

    /** A type 3 event: the source card after CARD_DELAY_MS; a newer event replaces a waiting card. */
    private static void queueSourceCard(Context app, int card, String why) {
        if (sCard != null) Ui.main().removeCallbacks(sCard);
        else sCardEcoBefore = Battery.eco();                     // power saving before this burst
        final Runnable[] self = new Runnable[1];
        self[0] = () -> {
            if (sCard != self[0]) return;
            sCard = null;
            showSourceCard(app, card, sCardEcoBefore, why);
        };
        sCard = self[0];
        Ui.main().postDelayed(self[0], CARD_DELAY_MS);
    }

    /**
     * The source card (stock BatteryHelper texts). {@code ecoBefore}: power saving at the source event; the
     * value now is Battery's (the vendor's type 5 was read meanwhile). Only while the screen is on.
     */
    private static void showSourceCard(Context app, int card, Boolean ecoBefore, String why) {
        Boolean ecoNow = Battery.eco();
        if (!interactive(app)) {
            Log.i(TAG, "source card " + card + " not shown: screen off (" + why + ")");
            return;
        }
        int title;
        int desc = 0;
        switch (card) {
            case CARD_INTERNAL:
                title = R.string.p6_card_internal;
                if (Boolean.TRUE.equals(ecoNow)) desc = R.string.p6_card_eco_on;
                break;
            case CARD_ADAPTER:
                title = R.string.p6_card_adapter;
                if (Boolean.TRUE.equals(ecoBefore) && Boolean.FALSE.equals(ecoNow)) desc = R.string.p6_card_eco_off;
                break;
            case CARD_LOW_POWER:
                title = R.string.p6_card_low_power;
                if (Boolean.TRUE.equals(ecoNow)) desc = R.string.p6_card_eco_on;
                break;
            case CARD_STAND:
                title = R.string.p6_card_stand;
                if (Boolean.TRUE.equals(ecoNow)) desc = R.string.p6_card_eco_on;
                break;
            default:
                return;
        }
        Log.i(TAG, "card: source " + card + " (" + why + "), power saving " + ecoBefore + " -> " + ecoNow);
        Notify.show(app, app.getString(title), desc != 0 ? app.getString(desc) : null);
    }

    /** Type 5 false_by_level: "Power saving is off", not while the starry sky lens is in (IGmpf2 84, stock). */
    private static void ecoOffCard(Context app) {
        if (!interactive(app)) {
            Log.i(TAG, "power saving off card not shown: screen off");
            return;
        }
        Hal.query((g, g2) -> g2.isIdentityOpen() ? g2.isConvexLensIn() : null, lens -> {    // IGmpf2 84
            if (Boolean.TRUE.equals(lens)) {
                Log.i(TAG, "power saving off card not shown: starry sky lens in");
                return;
            }
            Log.i(TAG, "card: power saving off (brightness raised)");
            Notify.show(app, app.getString(R.string.p6_card_eco_off));
        });
    }

    /** SCREEN_OFF: the countdown goes (the user put the projector to sleep); OverlayHost closes the notice. */
    static void onScreenOff(Context app) {
        if (sCard != null) {
            Ui.main().removeCallbacks(sCard);
            sCard = null;
        }
        if (sCountdown != null) {
            Log.i(TAG, "countdown closed: screen off");
            sCountdown.stop();
            sCountdown = null;
        }
        updateUp();
    }

    /**
     * The threshold logic of plan 3.3 on the agreed state. {@code cancel}: the last read says the
     * adapter (or another cancelling source) is in, so no warning now.
     */
    private static void evaluate(Context app, boolean cancel) {
        if (cancel) return;
        if (!interactive(app)) return;                           // an event read while asleep: at SCREEN_ON
        Battery.State s = Battery.stable();
        boolean due = sForceNotice;                              // the countdown ended: no level rule
        if (s != null && Battery.settled() && s.discharging() && s.level != null) {
            int l = s.level;
            if (l <= Battery.EMPTY) {
                sWarnedLow = sWarnedVeryLow = true;
                due |= sClosedAt == null || l < sClosedAt;
            } else if (l <= Battery.VERY_LOW) {
                if (!sWarnedVeryLow) cardVeryLow(app, l);
                sWarnedLow = sWarnedVeryLow = true;
            } else if (l <= Battery.LOW) {
                if (!sWarnedLow) cardLow(app, l);
                sWarnedLow = true;
            }
        }
        if (due) showNotice(app, s != null ? s.level : null);
        updateUp();
    }

    private static void cardLow(Context app, int level) {
        Log.i(TAG, "card: battery low (" + level + " %)");
        Notify.show(app, app.getString(R.string.p6_batt_low, level));
    }

    private static void cardVeryLow(Context app, int level) {
        Log.i(TAG, "card: battery very low (" + level + " %)");
        Notify.show(app, app.getString(R.string.p6_batt_very_low, level), app.getString(R.string.p6_batt_connect));
    }

    // ================================================================== the 5 % notice
    /** Shows the notice unless it is up, the screen is off, or another panel is in use (then the next read). */
    private static void showNotice(Context app, Integer level) {
        if (sNotice != null && sNotice.isShowing()) return;
        if (!interactive(app)) return;
        OverlayHost host = OverlayHost.get(app);
        if (host.current() != null) {
            if (!sWaitLogged) Log.i(TAG, "notice waits: another panel is open");
            sWaitLogged = true;
            return;
        }
        sWaitLogged = false;
        final DialogPanel[] self = new DialogPanel[1];
        self[0] = new DialogPanel(app.getString(R.string.p6_batt_empty_title),
                app.getString(R.string.p6_batt_empty_msg), app.getString(R.string.p6_batt_turn_off),
                app.getString(R.string.p6_batt_keep_watching),
                ok -> onNoticeResult(app, self[0], ok));
        self[0].setAutoHideMs(0);                                // stays until the adapter is in or the user acts
        if (!host.show(self[0])) {
            Log.w(TAG, "notice could not be shown");
            return;
        }
        sNotice = self[0];
        Log.i(TAG, "notice: battery almost empty (" + (level == null ? "level unknown" : level + " %") + ")");
        updateUp();
    }

    /**
     * DialogPanel result (main thread, once). A notice closed by a cancel is no longer sNotice: ignored.
     * Closed by the user: it comes back below the level of this moment.
     */
    private static void onNoticeResult(Context app, DialogPanel p, boolean ok) {
        if (p != sNotice) return;
        sNotice = null;
        if (ok) {
            sForceNotice = false;
            Log.i(TAG, "notice: Turn off (user)");
            PowerKey.PowerActions.powerOff(app);                 // the power menu's Power off
        } else if (interactive(app)) {
            sForceNotice = false;
            Battery.State s = Battery.stable();
            sClosedAt = s != null && s.level != null ? Math.min(s.level, Battery.EMPTY) : Battery.EMPTY;
            Log.i(TAG, "notice closed by the user (comes back below " + sClosedAt + " %)");
        }                                                        // screen off: due again after the wake
        updateUp();
    }

    /** Closes the countdown and the notice: the adapter (or another cancelling source) is in. */
    private static void cancelWarnings(Context app, String why) {
        Log.i(TAG, "warning closed: " + why);
        sForceNotice = false;
        sClosedAt = null;
        sWaitLogged = false;
        if (sCountdown != null) {
            sCountdown.stop();
            sCountdown = null;
        }
        DialogPanel n = sNotice;
        sNotice = null;                                          // its result is ignored now
        if (n != null) n.dismiss();
        updateUp();
    }

    private static boolean interactive(Context app) {
        try {
            PowerManager pm = app.getSystemService(PowerManager.class);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    // ================================================================== vendor power events
    /**
     * A vendor power event, after Battery logged it and queued its read (the registered listener, or
     * BatteryTestReceiver on test builds: {@code test}, the same path). Main thread.
     */
    static void onPowerEvent(Context app, int type, String value, boolean test) {
        switch (type) {
            case Battery.EV_LEVEL:
                if ((V_LOW.equals(value) || V_VERY_LOW.equals(value)) && !interactive(app)) {
                    Log.i(TAG, value + ": no card while the screen is off (the level rule shows it later)");
                } else if (V_LOW.equals(value)) {
                    if (test || !sWarnedLow) cardLow(app, Battery.LOW);
                    sWarnedLow = true;
                } else if (V_VERY_LOW.equals(value)) {
                    if (test || !sWarnedVeryLow) cardVeryLow(app, Battery.VERY_LOW);
                    sWarnedLow = sWarnedVeryLow = true;
                } else if (V_POWER_OFF.equals(value)) {
                    startCountdown(app);
                }                                                // a plain level: the read decides
                break;
            case Battery.EV_SOURCE_TYPE:
                if ((COPOWER + "STANDARD_ADAPTER").equals(value) || (COPOWER + "LOW_POWER").equals(value)) {
                    cancelWarnings(app, "type 3 " + value);
                } else if ((COPOWER + "STANDARD_BATTERY").equals(value)) {
                    Hal.query((g, g2) -> g2.isIdentityOpen() ? g2.getStandBatteryLevel() : null, v -> {   // IGmpf2 51
                        Log.i(TAG, "stand battery " + (v == null ? "not read" : v + " %"));
                        if (v != null && v > Battery.EMPTY) {
                            cancelWarnings(app, "type 3 " + value + ", stand " + v + " %");
                        }
                    });
                }
                int card = cardOfEvent(value);
                if (card >= 0) queueSourceCard(app, card, "type 3 " + value);
                break;
            case Battery.EV_ECO:
                if (V_ECO_OFF_BY_LEVEL.equals(value)) ecoOffCard(app);
                break;
            default:                                             // 0: the read decides (238); 4, 6, 7: no card
                break;
        }
        updateUp();
    }

    // ================================================================== the countdown
    /** LowPowerPoweroff: the countdown at once (no HAL read first); the cancel check reads every 2 s. */
    private static void startCountdown(Context app) {
        if (sCountdown != null) {
            Log.i(TAG, "countdown already running");
            return;
        }
        if (!interactive(app)) {
            Log.i(TAG, "countdown not shown: screen off");
            return;
        }
        Countdown c = new Countdown(app);
        if (!c.start()) {
            Log.w(TAG, "countdown window could not be added: the notice instead");
            sForceNotice = true;
            showNotice(app, null);
            return;
        }
        sCountdown = c;
        Log.i(TAG, "countdown: " + COUNTDOWN_S + " s");
        updateUp();
        Battery.readNow();
    }

    /** Countdown at 0: no shutdown (not built, O5); the 5 % notice instead. */
    private static void onCountdownEnd(Context app, Countdown c) {
        if (c != sCountdown) return;
        sCountdown = null;
        Log.i(TAG, "countdown ended: no automatic shutdown (O5), the notice instead");
        sForceNotice = true;
        sClosedAt = null;
        showNotice(app, Battery.stable() != null ? Battery.stable().level : null);
        updateUp();
    }

    /**
     * The full-width countdown band across the middle of the screen: a static, non-focusable window
     * (keys keep going to the app below), one tick per second on the main thread, at most 10 s.
     */
    private static final class Countdown {
        private final Context app;
        private final LinearLayout band;
        private final TextRow msg;
        private int left = COUNTDOWN_S;
        private boolean stopped;
        private final Runnable tick = this::tick;

        Countdown(Context app) {
            this.app = app;
            band = new LinearLayout(app);
            band.setOrientation(LinearLayout.VERTICAL);
            band.setBackgroundColor(Theme.NOTIFY_BG);
            int padH = Theme.px(app, 120), padV = Theme.px(app, 40);
            band.setPadding(padH, padV, padH, padV);
            LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            band.addView(new TextRow(app, app.getString(R.string.p6_batt_countdown_title), Theme.PANEL_TITLE_SIZE,
                    Theme.TEXT), wrap);
            msg = new TextRow(app, text(), Theme.ROW_TITLE_SIZE, Theme.TEXT_DIM);
            band.addView(msg, wrap);
        }

        private String text() {
            return app.getString(R.string.p6_batt_countdown_msg, left);
        }

        boolean start() {
            WindowManager.LayoutParams lp = OverlayHost.params(false, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            lp.setTitle("P6BatteryCountdown");
            if (!OverlayHost.get(app).addStatic(band, lp)) return false;
            Ui.main().postDelayed(tick, 1_000);
            return true;
        }

        private void tick() {
            if (stopped) return;
            left--;
            if (left <= 0) {
                stop();
                onCountdownEnd(app, this);
                return;
            }
            msg.setText(text());
            if ((COUNTDOWN_S - left) % CHECK_EVERY_S == 0) Battery.readNow();   // the cancel rule
            Ui.main().postDelayed(tick, 1_000);
        }

        void stop() {
            if (stopped) return;
            stopped = true;
            Ui.main().removeCallbacks(tick);
            OverlayHost.get(app).removeStatic(band);
        }
    }
}
