package org.lumen.projector.battery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.lumen.projector.Device;
import org.lumen.projector.Hal;
import org.lumen.projector.Ui;
import org.lumen.projector.device.DeviceStateProvider;
import org.lumen.projector.hal.Play6Gmpf;
import org.lumen.projector.hal.Play6Gmpf2;
import org.lumen.projector.panel.QuickPanel;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The internal battery and power saving of the XGIMI Play 6 (docs/play6/SETTINGS_PLAN.md section 3;
 * docs/play6/research/battery_sound_features.md 6.1 B4). Event driven since t13: the owner reversed
 * decision O2, so the Hal facade registers our IPowerCallback with the vendor power manager (IGmpf 235 /
 * 242, as stock) and every vendor power event reaches {@link #onPowerEvent} on the main thread. No
 * automatic shutdown. Nothing here writes: the reads are typed getters of the Play 6 clients (IGmpf 240
 * level, 238 adapter, 239 full, 327 power saving; IGmpf2 52 power source only while the identity guard is
 * open, IGmpf2 51 stand battery only for the cancel rule of BatteryNotices).
 *
 * Events (types of research 1.2), one P6Battery line each, then a read on the p6-hal thread with no 5 s
 * confirm (the vendor already filtered it): type 0 adapter, 3 source type and 7 source number read 240,
 * 238, 239, IGmpf2 52 and 327; type 1 (a level or a low-battery string) reads 240; type 5 (power saving)
 * reads 327 and 177 (the light level, logged: the vendor changes it together with power saving). The
 * events of one burst (the vendor sends 3, 5, 7, 6 and 0 within 0.6 s on a plug change) share one read,
 * 200 ms after the first of them. Types 3 and 5 also refresh an open quick panel at once (B7: the
 * brightness slider shows the vendor's new level), and every event goes on to BatteryNotices (cards).
 *
 * Watchdog, all on the p6-hal thread through the Hal facade, never an alarm or a wake lock:
 *  - the first read 5 s after every IGmpf connect (the first one comes right after the start gate);
 *  - then every 5 min while the screen is on and the power listener is registered, every 30 s while it
 *    is not (kill switch or a failed registration; plan 3.2), every 10 s while the 5 % notice or the
 *    countdown is up and no listener cancels it; none while the screen is off, one at once on SCREEN_ON;
 *  - a watchdog read that differs from the one before is confirmed 5 s later (one extra read per change),
 *    so it counts after two consecutive reads that agree ({@link #stable()}).
 * A chain is a token: SCREEN_OFF, SCREEN_ON, a reconnect or a test event start a new one and the old
 * pending read does nothing. A read that the facade skips (IGmpf not connected) ends the chain; the next
 * connect starts it again.
 *
 * Plausibility: a level of 0 (or above 100) is ignored, the field is then unknown; BatteryNotices runs no
 * threshold logic in the first 3 minutes after boot (the vendor's MCU thread fills the values). This app
 * shows no battery level itself any more (the Battery page, tile and info line moved to Lumen Home, B8):
 * the state is served to Home by device/DeviceStateProvider, which is told about every change. Logging:
 * one P6Battery line per event and one per change of the agreed state, never per watchdog read.
 */
public final class Battery {
    static final String TAG = "P6Battery";

    /**
     * Stock thresholds of G58/powerSupply/powerSupply.ini (lowPowerSaving = 30, lowPower = 10, powerOff = 5,
     * percent of the internal battery; plan 3.3): cards at the first two, the notice at the third.
     */
    public static final int LOW = 30, VERY_LOW = 10, EMPTY = 5;

    /** IGmpf2 52 getCopowerDeviceType (stock GmPowerManager constants, plan 3.1). */
    public static final int SRC_ADAPTER = 0, SRC_STAND = 1, SRC_LOW_POWER = 2, SRC_INTERNAL = 3,
            SRC_STAND_ADAPTER = 4;

    /**
     * Vendor power event types, IPowerCallback.powerEvent(type, value) (libxgimi.so POWER_XEVENT order,
     * research 1.2): adapter, level or low-battery string, source type string, stand level, power saving,
     * usage statistics, source number (IGmpf2 52).
     */
    public static final int EV_ADAPTER = 0, EV_LEVEL = 1, EV_SOURCE_TYPE = 3, EV_STAND_LEVEL = 4, EV_ECO = 5,
            EV_CONNECT_DATA = 6, EV_SOURCE = 7;

    static final long FIRST_READ_MS = 5_000;
    static final long POLL_MS = 30_000;
    /** Watchdog interval while the vendor power listener is registered (research 6.1 B4). */
    static final long LISTENING_POLL_MS = 300_000;
    static final long CONFIRM_MS = 5_000;
    static final long FAST_POLL_MS = 10_000;
    /** The events of one burst share one read this long after the first of them. */
    static final long EVENT_READ_MS = 200;
    /** A queued event read older than this was dropped by the facade (not connected): queue a new one. */
    static final long EVENT_READ_LOST_MS = 3_000;
    /** No threshold logic before this much uptime (plan 3.2). */
    static final long SETTLE_MS = 180_000;

    // what an event read reads (bit flags)
    private static final int READ_FULL = 1, READ_LEVEL = 1 << 1, READ_ECO = 1 << 2;

    /** One read (plan 3.1). Immutable. */
    public static final class State {
        /** IGmpf 240 percent 1..100; null = not readable, or 0 / above 100 (ignored). */
        public final Integer level;
        /** IGmpf 238 isAdapterPowered; null = not readable. */
        public final Boolean adapter;
        /** IGmpf 239 isBatteryChargedFull; null = not readable. */
        public final Boolean full;
        /** IGmpf2 52 power source; null while the IGmpf2 guard is closed or the read failed. */
        public final Integer source;
        /** IGmpf2 51 stand battery, read only for the cancel rule (source 1 while a warning is up); else null. */
        public final Integer standLevel;
        /** IGmpf 327 getEcoState, power saving; null = not readable. */
        public final Boolean eco;
        /** IGmpf 240 as read (-1 = the call failed), for the log when the level was ignored. */
        final int rawLevel;
        /** SystemClock.elapsedRealtime() of the read. */
        public final long at;

        State(Integer level, int rawLevel, Boolean adapter, Boolean full, Integer source, Integer standLevel,
              Boolean eco) {
            this.level = level;
            this.rawLevel = rawLevel;
            this.adapter = adapter;
            this.full = full;
            this.source = source;
            this.standLevel = standLevel;
            this.eco = eco;
            this.at = SystemClock.elapsedRealtime();
        }

        /** This state with a new level read (type 1). */
        State withLevel(int raw) {
            return new State(plausible(raw), raw, adapter, full, source, standLevel, eco);
        }

        /** This state with a new power saving read (type 5, the quick panel's Brightness page). */
        State withEco(Boolean e) {
            return new State(level, rawLevel, adapter, full, source, standLevel, e);
        }

        /** Level, adapter, full, source and eco are equal (the stand level is not part of the agreement). */
        boolean sameAs(State o) {
            return o != null && Objects.equals(level, o.level) && Objects.equals(adapter, o.adapter)
                    && Objects.equals(full, o.full) && Objects.equals(source, o.source) && Objects.equals(eco, o.eco);
        }

        /** Adapter in and not yet full: the bolt (stock: IGmpf 238). */
        public boolean charging() {
            return Boolean.TRUE.equals(adapter) && !Boolean.TRUE.equals(full);
        }

        /** Adapter in and full. */
        public boolean charged() {
            return Boolean.TRUE.equals(adapter) && Boolean.TRUE.equals(full);
        }

        /** On the internal battery: adapter out and IGmpf2 52 reports the internal battery (plan 3.3). */
        public boolean discharging() {
            return Boolean.FALSE.equals(adapter) && source != null && source == SRC_INTERNAL;
        }

        /**
         * The stock cancel set of the countdown and the 5 % notice (plan 3.3, BatteryHelper): 238 reads
         * true; or the power source is the standard adapter, a low power source (power bank, other
         * adapter) or the battery stand with more than 5 %. The stand with adapter and the internal
         * battery do not cancel.
         */
        public boolean cancelsWarning() {
            if (Boolean.TRUE.equals(adapter)) return true;
            if (source == null) return false;
            if (source == SRC_ADAPTER || source == SRC_LOW_POWER) return true;
            return source == SRC_STAND && standLevel != null && standLevel > EMPTY;
        }

        /** "level=87 adapter=true full=false source=0 eco=false" (the P6Battery line of device test C1). */
        public String describe() {
            String lv = level != null ? String.valueOf(level)
                    : rawLevel < 0 ? "- (240 failed)" : "- (240 read " + rawLevel + ", ignored)";
            return "level=" + lv + " adapter=" + str(adapter) + " full=" + str(full) + " source=" + str(source)
                    + " eco=" + str(eco) + (standLevel != null ? " stand=" + standLevel : "");
        }

        private static String str(Object o) {
            return o == null ? "-" : String.valueOf(o);
        }
    }

    private static final AtomicInteger TOKEN = new AtomicInteger();
    /** READ_* flags asked for by events and not yet read. */
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Context sApp;
    private static volatile boolean sScreenOn = true;
    private static volatile State sLast, sStable;
    private static volatile long sStableSince;
    /** Main thread: when the pending event read was queued. */
    private static long sPendingAt;
    // p6-hal thread state
    /** The last read was a confirmation read: the next one waits the normal interval. */
    private static boolean sConfirmed;

    private Battery() {}

    // ================================================================== start (Play6App)
    /**
     * Play6App at the start gate (p6-hal), before the Hal facade connects. Registers the connected hook
     * (first read 5 s after every IGmpf connect), the power event hook and the screen hooks; no HAL call
     * here. Only on a device with a battery (Device.hasBattery).
     */
    public static void install(Context ctx) {
        if (sApp != null || !Device.hasBattery()) return;
        final Context app = ctx.getApplicationContext();
        sApp = app;
        try {
            PowerManager pm = app.getSystemService(PowerManager.class);
            sScreenOn = pm == null || pm.isInteractive();
        } catch (Throwable t) {
            Log.w(TAG, "isInteractive: " + t);
        }
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    String a = i == null ? null : i.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(a)) onScreenOff(app);
                    else if (Intent.ACTION_SCREEN_ON.equals(a)) onScreenOn();
                } catch (Throwable t) {
                    Log.w(TAG, "screen hook: " + t);
                }
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        Hal.addConnectedListener(() -> {
            if (sScreenOn) schedule(FIRST_READ_MS);
        });
        Hal.addPowerEventListener((type, value) -> onPowerEvent(type, value, false));
        Log.i(TAG, "armed: vendor power events, first read " + FIRST_READ_MS / 1000 + " s after the IGmpf connect, "
                + "watchdog every " + LISTENING_POLL_MS / 1000 + " s (" + POLL_MS / 1000
                + " s without the listener) while the screen is on");
    }

    /** SCREEN_OFF, main thread: the watchdog stops (a pending read does nothing); warnings close. */
    private static void onScreenOff(Context app) {
        sScreenOn = false;
        TOKEN.incrementAndGet();
        BatteryNotices.onScreenOff(app);
    }

    /** SCREEN_ON, main thread: one read at once, then the normal chain. */
    private static void onScreenOn() {
        sScreenOn = true;
        schedule(0);
    }

    // ================================================================== state
    /** The agreed state (an event read, or two consecutive watchdog reads), or null before that. Any thread. */
    public static State stable() {
        return sStable;
    }

    /** The last read, or null. Any thread. */
    public static State last() {
        return sLast;
    }

    /** The state Lumen Home is told: the agreed state, else the last read, else null. Any thread. */
    public static State current() {
        State s = sStable;
        return s != null ? s : sLast;
    }

    /** True once the first 3 minutes after boot are over (plan 3.2). Any thread. */
    static boolean settled() {
        return SystemClock.elapsedRealtime() >= SETTLE_MS;
    }

    /** {@code r} runs on the MAIN thread after every change of the agreed state. Cheap only, no HAL. */
    public static void addListener(Runnable r) { LISTENERS.addIfAbsent(r); }

    public static void removeListener(Runnable r) { LISTENERS.remove(r); }

    /** One line for Projector settings > Diagnostics: the watchdog and its age, the agreed state. Any thread. */
    public static String describe() {
        if (sApp == null) return Device.hasBattery() ? "not started (start gate)" : "off (no battery)";
        long now = SystemClock.elapsedRealtime();
        StringBuilder sb = new StringBuilder();
        sb.append(!sScreenOn ? "watchdog paused (screen off)" : "watchdog every " + pollMs() / 1000 + " s");
        State l = sLast, s = sStable;
        sb.append("; last read ").append(l == null ? "none" : (now - l.at) / 1000 + " s ago: " + l.describe());
        if (s != null) sb.append("; agreed since ").append((now - sStableSince) / 1000).append(" s");
        if (!settled()) sb.append("; warnings start 3 min after boot");
        return sb.toString();
    }

    // ================================================================== vendor power events
    /**
     * A vendor power event (Hal's PowerEventListener; {@code test}: BatteryTestReceiver on test builds, the
     * same path). MAIN thread. One P6Battery line, a read of what the event changed, the quick panel
     * refresh on types 3 and 5, then the cards (BatteryNotices).
     */
    static void onPowerEvent(int type, String value, boolean test) {
        final Context app = sApp;
        if (app == null) return;
        Log.i(TAG, (test ? "test " : "") + "power event type=" + type + " value=" + value);
        switch (type) {
            case EV_ADAPTER: case EV_SOURCE_TYPE: case EV_SOURCE:
                requestEventRead(READ_FULL);
                break;
            case EV_LEVEL:
                requestEventRead(READ_LEVEL);                    // a level, or LumensPowerSaving / LowPower*
                break;
            case EV_ECO:
                requestEventRead(READ_ECO);
                break;
            default:                                            // 4 stand level (P2), 6 usage statistics
                break;
        }
        if (type == EV_SOURCE_TYPE || type == EV_ECO) QuickPanel.refreshIfShowing();   // B7: the new light level
        BatteryNotices.onPowerEvent(app, type, value, test);
    }

    /** Main thread: queues one event read for {@code what} (READ_*); events of one burst share it. */
    private static void requestEventRead(int what) {
        if (sApp == null || !Hal.isGateOpen()) return;          // never a "not ready" notice from here
        long now = SystemClock.elapsedRealtime();
        int before = PENDING.getAndUpdate(p -> p | what);
        if (before != 0 && now - sPendingAt < EVENT_READ_LOST_MS) return;   // the burst's read is queued
        sPendingAt = now;
        if (!Hal.runDelayed(Battery::eventRead, EVENT_READ_MS)) PENDING.set(0);
    }

    /** p6-hal: the read of the queued events, committed at once (no confirmation read). */
    private static void eventRead(Play6Gmpf g, Play6Gmpf2 g2) {
        int what = PENDING.getAndSet(0);
        if (what == 0) return;
        State cur = sLast;
        State s;
        if ((what & READ_FULL) != 0 || cur == null) {
            s = read(g, g2);
        } else {
            s = cur;
            if ((what & READ_LEVEL) != 0) s = s.withLevel(readLevel(g));
            if ((what & READ_ECO) != 0) s = s.withEco(readEco(g));
        }
        if ((what & READ_ECO) != 0) {
            Integer light = null;
            try { light = g.getLampLevel(); } catch (Throwable ignored) { }                  // 177
            Log.i(TAG, "power saving read: eco=" + s.eco + " light=" + (light == null ? "-" : light));
        }
        final State read = s;
        sLast = read;
        commit(read);
        final Context app = sApp;
        Ui.main().post(() -> BatteryNotices.onRead(app, read));
    }

    /**
     * p6-hal: a power saving value read elsewhere (the quick panel's Brightness page reads 327 with 177),
     * so Lumen Home sees it without another HAL call. A null (failed read) changes nothing.
     */
    public static void noteEco(Boolean eco) {
        if (eco == null || sApp == null) return;
        State last = sLast, st = sStable;
        if (last != null && !eco.equals(last.eco)) sLast = last.withEco(eco);
        if (st != null && !eco.equals(st.eco)) commit(st.withEco(eco));
    }

    /** Power saving as last read (IGmpf 327); null = unknown. Any thread. */
    public static Boolean eco() {
        State s = current();
        return s == null ? null : s.eco;
    }

    // ================================================================== watchdog (p6-hal)
    /** A read at once, then the normal chain (test events, the countdown's cancel check). Any thread. */
    static void readNow() {
        if (sScreenOn) schedule(0);
    }

    /** Starts a new chain: a read after {@code delayMs} on p6-hal. A pending read of an older chain does nothing. */
    private static void schedule(long delayMs) {
        if (sApp == null || !Hal.isGateOpen()) return;          // never a "not ready" notice from here
        final int t = TOKEN.incrementAndGet();
        Hal.runDelayed((g, g2) -> poll(t, g, g2), delayMs);
    }

    /** The watchdog interval right now. */
    private static long pollMs() {
        if (Hal.isPowerListenerRegistered()) return LISTENING_POLL_MS;
        return BatteryNotices.isUp() ? FAST_POLL_MS : POLL_MS;
    }

    /** p6-hal: one read, the agreement, the next read of this chain. */
    private static void poll(int t, Play6Gmpf g, Play6Gmpf2 g2) {
        if (t != TOKEN.get() || !sScreenOn) return;             // a newer chain runs, or the screen is off
        State prev = sLast;
        State s = read(g, g2);
        sLast = s;
        boolean agree = s.sameAs(prev);
        if (agree) commit(s);
        final Context app = sApp;
        Ui.main().post(() -> BatteryNotices.onRead(app, s));
        long next;
        if (!agree && !sConfirmed) {
            next = CONFIRM_MS;
            sConfirmed = true;
        } else {
            next = pollMs();
            sConfirmed = false;
        }
        if (sScreenOn) continueChain(t, next);
    }

    /**
     * p6-hal: the next read of chain {@code t}, but only if no newer chain started meanwhile. The check and the
     * new token are one compare-and-set: a SCREEN_ON (main thread) between them can no longer be replaced by
     * this chain's slower read, and a SCREEN_OFF after it still stops the read (poll checks sScreenOn).
     */
    private static void continueChain(int t, long delayMs) {
        if (sApp == null || !Hal.isGateOpen()) return;
        if (!TOKEN.compareAndSet(t, t + 1)) return;              // a newer chain runs
        final int nt = t + 1;
        Hal.runDelayed((g, g2) -> poll(nt, g, g2), delayMs);
    }

    /** p6-hal. Never throws; a failed getter leaves its field unknown. */
    private static State read(Play6Gmpf g, Play6Gmpf2 g2) {
        int raw = readLevel(g);                                  // 240
        Boolean adapter = null, full = null;
        try { adapter = g.isAdapterPowered(); } catch (Throwable ignored) { }          // 238
        try { full = g.isBatteryFull(); } catch (Throwable ignored) { }                // 239
        Integer source = null, stand = null;
        if (g2.isIdentityOpen()) {                               // all or nothing (plan 2.1)
            try { source = g2.getPowerSourceType(); } catch (Throwable ignored) { }    // IGmpf2 52
            if (source != null && source == SRC_STAND && BatteryNotices.isUp()) {
                try { stand = g2.getStandBatteryLevel(); } catch (Throwable ignored) { } // IGmpf2 51
            }
        }
        return new State(plausible(raw), raw, adapter, full, source, stand, readEco(g));
    }

    /** p6-hal: IGmpf 240, -1 when the call failed. */
    private static int readLevel(Play6Gmpf g) {
        try {
            return g.getBatteryLevel();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** p6-hal: IGmpf 327, null when the call failed. */
    private static Boolean readEco(Play6Gmpf g) {
        try {
            return g.getEcoState();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 1..100, else null: 0 is ignored (plan 3.2), so is anything above 100 or a failed read. */
    private static Integer plausible(int raw) {
        return raw >= 1 && raw <= 100 ? raw : null;
    }

    /**
     * p6-hal: {@code s} is agreed (an event read, or the second of two watchdog reads that agree). A change
     * becomes the agreed state, logged once; Lumen Home's provider is told.
     */
    private static void commit(State s) {
        State old = sStable;
        if (s.sameAs(old)) return;
        sStable = s;
        sStableSince = s.at;
        Log.i(TAG, "read " + s.describe() + (old == null ? " (first agreed read)" : ""));
        final Context app = sApp;
        DeviceStateProvider.notifyIfChanged(app);
        Ui.main().post(() -> {
            BatteryNotices.onStableChanged(app, old, s);
            for (Runnable r : LISTENERS) {
                try { r.run(); } catch (Throwable e) { Log.w(TAG, "listener: " + e); }
            }
        });
    }
}
