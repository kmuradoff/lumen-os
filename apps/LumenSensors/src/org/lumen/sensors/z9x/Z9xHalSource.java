package org.lumen.sensors.z9x;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lumen.sensors.R;
import org.lumen.sensors.core.Fmt;
import org.lumen.sensors.core.Group;
import org.lumen.sensors.core.Levels;
import org.lumen.sensors.core.Registry;
import org.lumen.sensors.core.Sample;
import org.lumen.sensors.core.Sampler;
import org.lumen.sensors.core.Source;
import org.lumen.sensors.hal.GmpfAllowlist;
import org.lumen.sensors.hal.GmpfStructs;
import org.lumen.sensors.hal.Z9xGmpfReader;

import java.util.ArrayList;

/**
 * Z9X ONLY: the projector values that exist only inside XGIMI's gmpf HAL (temperature sensors, fans,
 * light, input signal, motion), read through {@link Z9xGmpfReader} (allowlist of read-only codes) on
 * the plan of {@link Z9xSchedule} (periods per code, gap, input and ToF rules; plain Java, host-tested).
 *
 * Scheduling (one task per value, all on the sampler thread, never on the main thread):
 *  - at least {@link Z9xSchedule#GAP_MS} (200 ms) before a first read (probe step) of a start and
 *    {@link Z9xSchedule#STEADY_GAP_MS} (500 ms) before every later read, ToF included (a 196 goes at
 *    the pace of the read it comes before): after the probes never more than 2 calls in a second. The
 *    pump runs the wanted task that was due first;
 *  - temperatures, fans and the screen check every max(4 s, refresh) ("round"); light, picture and
 *    signal, motion, focus motor and over-temperature every 10 s; supply voltage every 30 s; ToF every
 *    60 s at most; light usage once per start. The vendor serves every gmpf call on one thread and logs
 *    each one: 0.1 kept it at about 4.8 calls per second (device test). Compact (1.25 per second) and
 *    graphs (1.5) keep these periods; detailed mode asks for about 3.2 and gets 2 at most, so its reads
 *    come late and are then due a period after their call ({@link Z9xSchedule#nextDue}): every read
 *    waits the same few seconds more (round reads about every 8 s; the sums are in Z9xSchedule);
 *  - 196 getScreenOnOff gates every other read: no read goes out unless a 196 reading from this round
 *    (younger than the round period) says the light is on. When the light is off, or 196 failed, only
 *    196 is read (once per round); when 196 is dead (3 failures), nothing is read until the next start.
 *    196 itself is read only right before another read is due (never on its own while nothing else is);
 *  - the input (696) picks the signal read: 662 HDMI info on HDMI, IGmpf2 319 video info on the media
 *    input (on HDMI both showed the same signal); an unknown input reads and shows neither;
 *  - ToF 279 is an active vendor measurement (about 0.6 s on the one gmpf thread, see
 *    {@link Z9xSchedule#TOF_MS}): read only while its row is drawn, at most once per 60 s (also across
 *    starts), never before every other first read of a start is done, 5 s
 *    ({@link Z9xSchedule#TOF_AFTER_MS}) after that and after a view change, then in its turn behind
 *    every read that was due before it;
 *  - probing once per start: sensor indexes, fan ids and every value's first read. A value that is
 *    absent there is never shown. Probing also runs with only the settings screen open (so it can
 *    list the groups) and runs to its end whatever the mode or the groups; periodic reads run only
 *    while a HUD is on screen and draws that very sample in its mode ({@link Sampler#shows}: the
 *    compact strip draws only ranked samples, so a detailed-only sensor is not read in compact mode);
 *  - a failed call hides that row; 3 failures in a row stop that task until the next start. A call
 *    that returns but took more than 2 s (contention on the vendor's global mutex: AK, zoom) hides
 *    the row until the next good read and does not count as a failure; 5 slow calls in a row stop
 *    the task (a value that is always slow would stall every source on the sampler thread);
 *  - no call in the first 60 s after boot (the vendor's own boot work, PWM watchdog).
 * Sensor index 0 is not probed (438 returns the same slot). Index 9 (the CPU) is probed only when no
 * thermal zone gives the SoC temperature: each 439(9) call adds one sample to the vendor's 10-sample
 * CPU average, a small side effect. 339 is applied only when it marks at least one sensor valid (its
 * meaning is unverified; all false is read as "not trustworthy"). LED sensors 4 and 8 that read the
 * same as 5 collapse into one "LED" row (the Z9X probably has one LED-board sensor).
 */
public final class Z9xHalSource implements Source {
    private static final String TAG = "LumenSensors";

    private static final long GAP_MS = Z9xSchedule.GAP_MS;
    private static final long SLOW_CALL_MS = 2_000;
    private static final long RETRY_MS = 3_000;
    private static final int MAX_FAILS = 3;
    private static final int MAX_SLOW = 5;
    private static final long CONNECT_RETRY_MS = 10_000;
    private static final int CONNECT_TRIES = 6;
    private static final long BOOT_SETTLE_MS = 60_000;

    private static final int IGMPF = GmpfAllowlist.IGMPF, IGMPF2 = GmpfAllowlist.IGMPF2;
    /** The raw IGmpf2 27 JSON in the log: at most this many characters. */
    private static final int USAGE_LOG_CHARS = 240;

    /** Modes of the probes (a probe runs to its end anyway; see wanted()). */
    private static final int M_TEMP = Sample.MODE_DETAILED | Sample.MODE_COMPACT | Sample.MODE_GRAPH;
    private static final int M_FAN = Sample.MODE_DETAILED | Sample.MODE_COMPACT;
    private static final int M_ROW = Sample.MODE_DETAILED;
    private static final int M_ROW_GRAPH = Sample.MODE_DETAILED | Sample.MODE_GRAPH;

    private static final String[] CURRENT_NAMES = {"R", "G", "B", "C", "R2", "G2", "B2", "C2"};

    private final Context app;
    private final Z9xGmpfReader hal = new Z9xGmpfReader();
    private final ArrayList<Task> tasks = new ArrayList<>();
    private final int[] st = new int[8];
    private final Runnable pump = this::pump;
    private final Runnable onView = this::kick;

    private Sampler sampler;
    private Registry reg;
    private Handler h;
    private boolean running, connected, lampOff, pumpLogged, usageLogged;
    private int connectTries;
    private long lastCall;
    /** Uptime of the last good 196 reading (0 = none since the start). */
    private long screenCheckedAt;
    /** Uptime when every first read but ToF was done (0 = not yet this start). */
    private long probesDoneAt;
    /** Uptime of the last ToF call; kept across starts, so a quick restart does not measure again. */
    private long tofCalledAt;
    private int inputSource = Z9xSchedule.INPUT_UNKNOWN;
    private Task screenTask, tofTask;
    private OneTask hdmiTask, videoTask;

    // texts, resolved at start
    private final String[] fanModes = new String[12];
    private String ctD65, ct1, srcAndroid, srcHdmi1, srcHdmi2;

    public Z9xHalSource(Context app) {
        this.app = app;
    }

    // ================================================================== Source
    @Override
    public void start(Sampler s) {
        sampler = s;
        reg = s.registry();
        h = s.handler();
        running = true;
        connected = false;
        connectTries = 0;
        lampOff = false;
        pumpLogged = false;
        usageLogged = false;
        lastCall = 0;
        screenCheckedAt = 0;
        probesDoneAt = 0;
        inputSource = Z9xSchedule.INPUT_UNKNOWN;
        loadTexts();
        tasks.clear();
        buildTasks();
        s.addViewListener(onView);
        h.post(pump);
    }

    @Override
    public void poll(Sampler s, long now) {
        // the HAL work runs on its own pump (one call per gap at most), not on the refresh tick
    }

    @Override
    public void stop(Sampler s) {
        running = false;
        if (h != null) h.removeCallbacks(pump);
        s.removeViewListener(onView);
        tasks.clear();
        screenTask = tofTask = null;
        hdmiTask = videoTask = null;
        hal.disconnect();
        connected = false;
    }

    /**
     * The view changed (mode, groups, HUD on screen): look for work at once. ToF waits a moment, so it
     * does not join the reads that the change makes due. Sampler thread.
     */
    private void kick() {
        if (!running || h == null) return;
        delayTof(SystemClock.uptimeMillis());
        h.removeCallbacks(pump);
        h.post(pump);
    }

    /** ToF not before TOF_AFTER_MS from now, and not within TOF_MS of the last ToF call. */
    private void delayTof(long now) {
        if (tofTask == null) return;
        long at = now + Z9xSchedule.TOF_AFTER_MS;
        if (tofCalledAt != 0) at = Math.max(at, tofCalledAt + Z9xSchedule.TOF_MS);
        if (tofTask.due < at) tofTask.due = at;
    }

    /** Has every task but ToF had its first read (or will it not have one now)? */
    private boolean probesDone() {
        for (int i = 0; i < tasks.size(); i++) {
            Task t = tasks.get(i);
            if (t == screenTask || t == tofTask || t.dead || t.probed || t.fails > 0 || t.slow > 0) continue;
            if (t.needed()) return false;
        }
        return true;
    }

    // ================================================================== pump
    private void pump() {
        if (!running) return;
        try {
            pumpOnce();
        } catch (Throwable e) {                             // never kill the sampler thread's looper
            if (!pumpLogged) {
                pumpLogged = true;
                Log.w(TAG, "HAL pump (logged once): " + e);
            }
            h.removeCallbacks(pump);
            h.postAtTime(pump, SystemClock.uptimeMillis() + RETRY_MS);
        }
    }

    private void pumpOnce() {
        long now = SystemClock.uptimeMillis();
        long sinceBoot = SystemClock.elapsedRealtime();
        if (sinceBoot < BOOT_SETTLE_MS) {
            h.postAtTime(pump, now + (BOOT_SETTLE_MS - sinceBoot));
            return;
        }
        if (!connected) {
            if (hal.connect()) {
                connected = true;
                Log.i(TAG, "XGIMI HAL connected");
            } else {
                if (++connectTries < CONNECT_TRIES) {
                    h.postAtTime(pump, now + CONNECT_RETRY_MS);
                } else {
                    Log.w(TAG, "XGIMI HAL not available, no projector values until the next start");
                }
                return;
            }
        }
        if (probesDoneAt == 0 && probesDone()) {
            probesDoneAt = now;                           // ToF may come now, a little later
            delayTof(now);
        }
        Task best = null;                                 // ToF too: its due already keeps it after the probes
        for (int i = 0; i < tasks.size(); i++) {
            Task t = tasks.get(i);
            if (t != screenTask && wanted(t) && (best == null || t.due < best.due)) best = t;
        }
        if (best == null) return;                         // idle until the view changes (kick); no 196 either
        // first reads (and a 196 before one) at the start pace; every later read, ToF too, 2 per second at most
        long at = Math.max(best.due, lastCall + Z9xSchedule.gapMs(!best.probed && best != tofTask));
        if (at > now) {                                   // nothing due yet (196 neither: it goes right before a read)
            h.postAtTime(pump, at);
            return;
        }
        if (lampOff || now - screenCheckedAt > periodOf(Z9xSchedule.P_ROUND) || now >= screenTask.due) {
            // the light check comes first: no other read without a fresh 196 that says the light is on
            if (screenTask.dead) return;                  // lampOff is set: no HAL reads until the next start
            if (screenTask.due > now) {                   // 196 failed a moment ago: its retry comes first
                h.postAtTime(pump, screenTask.due);
                return;
            }
            best = screenTask;
        }
        execute(best);
        h.postAtTime(pump, lastCall + GAP_MS);
    }

    /**
     * Would this task (not 196) read now if the light were on? A probe (first read, multi-step probe)
     * runs whatever is on screen, so the group list and the rows are complete; a periodic read only
     * while the HUD draws its value.
     */
    private boolean wanted(Task t) {
        if (t.dead || !t.needed()) return false;
        if (t == tofTask && probesDoneAt == 0) return false;   // after every other first read
        if (!t.probed) return !t.probeOnlyWhenShown || sampler.shown(t.group, t.modes);
        return t.shownNow();
    }

    private void execute(Task t) {
        long t0 = SystemClock.uptimeMillis();
        boolean plausible = false, failed = false;
        try {
            plausible = t.run();
        } catch (SecurityException e) {
            Log.e(TAG, t.name + ": " + e.getMessage());        // allowlist refusal: a bug, never retried
            t.dead = true;
            if (t == screenTask) lampOff = true;
            lastCall = SystemClock.uptimeMillis();
            return;
        } catch (Throwable e) {
            failed = true;
            if (t.fails == 0) Log.w(TAG, t.name + " failed: " + e);
        }
        long end = SystemClock.uptimeMillis();
        lastCall = end;
        if (failed) {
            t.fails++;
            hide(t);
            if (t == screenTask) lampOff = true;           // no light check: no other read until 196 answers
            if (t.fails >= MAX_FAILS) {
                t.dead = true;
                Log.w(TAG, t.name + ": " + MAX_FAILS + " failures, not read again until the next start");
            } else {
                t.due = end + Math.max(RETRY_MS, t.period());
            }
            return;
        }
        t.fails = 0;
        if (end - t0 > SLOW_CALL_MS) {
            // it answered, but late (the vendor mutex was busy): not a failure, the row waits for a good read
            Log.w(TAG, t.name + " took " + (end - t0) + " ms");
            if (++t.slow >= MAX_SLOW) {
                t.dead = true;
                hide(t);
                if (t == screenTask) lampOff = true;
                Log.w(TAG, t.name + ": " + MAX_SLOW + " slow calls in a row, not read again until the next start");
                return;
            }
            if (t != screenTask) {                         // a slow 196 still tells the light state
                hide(t);
                t.again = false;                           // a probe goes on with its next step later
                t.due = end + Math.max(RETRY_MS, t.period());
                return;
            }
        } else {
            t.slow = 0;
        }
        if (t == screenTask) screenCheckedAt = end;
        if (t.again) {                                     // a multi-step probe: next step as soon as allowed;
            t.again = false;                               // it counts as probed only when it resolves
            t.due = end;
            return;
        }
        if (!t.probed) {
            t.probed = true;
            if (!plausible && t.deadIfAbsent) {
                t.dead = true;
                hide(t);
                return;
            }
        }
        if (!plausible) hide(t);
        if (t.once && plausible) {
            t.dead = true;                                 // static value: keep the row, never read again
            return;
        }
        t.due = Z9xSchedule.nextDue(t.due, end, t.period());   // late (detailed mode): a period after the call
        if (t == tofTask) delayTof(end);                   // a full TOF_MS after this call, whatever the phase
    }

    private void hide(Task t) {
        synchronized (reg.lock) {
            t.hide();
        }
    }

    private long periodOf(int kind) {
        return Z9xSchedule.periodMs(kind, sampler.refreshMs());
    }

    // ================================================================== tasks
    private abstract class Task {
        final String name;
        final int group, modes;
        /** Z9xSchedule.P_* of the task's HAL code. */
        final int periodKind;
        long due;
        int fails, slow;
        boolean dead, probed, again;
        /** An implausible first reading means the value does not exist here: never read again. */
        boolean deadIfAbsent;
        /** A static value: read once per start. */
        boolean once;
        /** Not even probed unless its group is on screen (ToF). */
        boolean probeOnlyWhenShown;

        /** The period comes from the plan of {@code code} on {@code iface} (Z9xSchedule.kindOf). */
        Task(String name, int iface, int code, int group, int modes) {
            this.name = name;
            this.group = group;
            this.modes = modes;
            int kind = Z9xSchedule.kindOf(iface, code);
            this.periodKind = kind >= 0 ? kind : Z9xSchedule.P_SLOW;
            this.due = SystemClock.uptimeMillis();
        }

        long period() { return periodOf(periodKind); }

        boolean needed() { return true; }

        /** Does the HUD draw this task's value now? Tasks with Samples ask about them. */
        boolean shownNow() { return sampler.shown(group, modes); }

        /** One HAL call. Sets the sample(s) and returns true when the value is plausible. */
        abstract boolean run() throws Exception;

        /** Hide the task's rows. Called with the registry lock held. */
        abstract void hide();
    }

    /** A task with one sample, registered at its first plausible value. */
    private abstract class OneTask extends Task {
        Sample sample;

        OneTask(String name, int iface, int code, int group, int modes) {
            super(name, iface, code, group, modes);
        }

        abstract Sample create();

        @Override boolean shownNow() { return sample != null ? sampler.shows(sample) : super.shownNow(); }

        void set(double v) {
            if (sample == null) sample = reg.add(create());
            synchronized (reg.lock) {
                sample.set(v);
            }
        }

        void set(double v, double v2, double v3, int flags) {
            if (sample == null) sample = reg.add(create());
            synchronized (reg.lock) {
                sample.set(v, v2, v3);
                sample.flags = flags;
            }
        }

        void setText(String text) {
            if (sample == null) sample = reg.add(create());
            synchronized (reg.lock) {
                sample.setText(text);
            }
        }

        @Override
        void hide() {
            if (sample != null) sample.hide();
        }
    }

    private void buildTasks() {
        // probe priority = list order (ties of "due" go to the earlier task): one value per group first
        // the gate: pump() runs it, never wanted()
        screenTask = new Task("196 screen", IGMPF, GmpfAllowlist.SCREEN_ON, -1, 0) {
            @Override boolean run() throws Exception {
                boolean on = hal.screenOn();
                if (on == lampOff) {
                    lampOff = !on;
                    Log.i(TAG, on ? "light on: HAL reads resume" : "light off: HAL reads pause");
                }
                return true;
            }

            @Override void hide() {}
        };
        tasks.add(screenTask);

        tasks.add(new OneTask("438 system temperature", IGMPF, GmpfAllowlist.SYSTEM_TEMPERATURE, Group.TEMPS,
                M_ROW_GRAPH) {   // no compact rank
            { deadIfAbsent = true; }

            @Override Sample create() {
                return new Sample("hal.sys", Group.TEMPS, Fmt.TEMP).label(R.string.s_system).kind(Levels.K_BOARD)
                        .order(20).graph();
            }

            @Override boolean run() throws Exception {
                int v = hal.systemTemperature();
                if (v < 1 || v > 127) return false;
                set(v);
                return true;
            }
        });

        tasks.add(new OneTask("435 fan mode", IGMPF, GmpfAllowlist.FAN_MODE, Group.COOLING, M_ROW) {
            { deadIfAbsent = true; }

            @Override Sample create() {
                return new Sample("hal.fanmode", Group.COOLING, Fmt.TEXT).label(R.string.s_fan_mode).order(10);
            }

            @Override boolean run() throws Exception {
                int v = hal.fanMode();
                if (v < 0 || v >= fanModes.length) return false;
                setText(fanModes[v]);
                return true;
            }
        });

        tasks.add(new OneTask("177 brightness level", IGMPF, GmpfAllowlist.LAMP_LEVEL, Group.LIGHT, M_ROW) {
            { deadIfAbsent = true; }

            @Override Sample create() {
                return new Sample("hal.lamp", Group.LIGHT, Fmt.INT).label(R.string.s_lamp).order(10);
            }

            @Override boolean run() throws Exception {
                int v = hal.lampLevel();
                if (v < 1 || v > 10) return false;
                set(v);
                return true;
            }
        });

        tasks.add(new OneTask("696 input source", IGMPF, GmpfAllowlist.INPUT_SOURCE, Group.PICTURE, M_ROW) {
            { deadIfAbsent = true; }

            @Override Sample create() {
                return new Sample("hal.input", Group.PICTURE, Fmt.TEXT).label(R.string.s_source).order(10);
            }

            @Override boolean run() throws Exception {
                int v = Z9xSchedule.input(hal.inputSource());
                setInput(v);
                String t = v == Z9xSchedule.INPUT_MEDIA ? srcAndroid
                        : (v == Z9xSchedule.INPUT_HDMI1 ? srcHdmi1 : (v == Z9xSchedule.INPUT_HDMI2 ? srcHdmi2 : null));
                if (t == null) return false;
                setText(t);
                return true;
            }

            /** Failed, dead or implausible: the input is unknown, so neither signal row is read or shown. */
            @Override void hide() {
                super.hide();
                inputSource = Z9xSchedule.INPUT_UNKNOWN;
                if (hdmiTask != null) hdmiTask.hide();
                if (videoTask != null) videoTask.hide();
            }
        });

        tasks.add(new MotionTask());

        tasks.add(new OneTask("266 focus motor", IGMPF, GmpfAllowlist.FOCUS_STEP, Group.MOTION, M_ROW) {
            { deadIfAbsent = true; }

            @Override Sample create() {
                return new Sample("hal.focus", Group.MOTION, Fmt.INT).label(R.string.s_focus).order(40);
            }

            @Override boolean run() throws Exception {
                int v = hal.focusMotorStep();
                if (v < -100_000 || v > 100_000) return false;
                set(v);
                return true;
            }
        });

        tasks.add(new SensorProbe(socFromThermalZone()));
        tasks.add(new FanProbe());

        tasks.add(new OneTask("IGmpf2 241 over-temperature", IGMPF2, GmpfAllowlist.OVER_TEMPERATURE, Group.TEMPS,
                M_ROW) {
            @Override Sample create() {
                return new Sample("hal.overheat", Group.TEMPS, Fmt.YES_NO).label(R.string.s_overheat)
                        .kind(Levels.K_FLAG).order(200);
            }

            @Override boolean run() throws Exception {
                set(hal.overTemperature() ? 1 : 0);
                return true;
            }
        });

        tasks.add(new OneTask("IGmpf2 51 Boost", IGMPF2, GmpfAllowlist.LENS_DISP_MODE, Group.LIGHT, M_ROW) {
            @Override Sample create() {
                return new Sample("hal.boost", Group.LIGHT, Fmt.ON_OFF).label(R.string.s_boost).order(20);
            }

            @Override boolean run() throws Exception {
                int v = hal.lensDispMode();
                if (v != 0 && v != 1) return false;
                set(v);
                return true;
            }
        });

        tasks.add(new OneTask("175 color temperature", IGMPF, GmpfAllowlist.LUMENS_MODE, Group.LIGHT, M_ROW) {
            @Override Sample create() {
                return new Sample("hal.ct", Group.LIGHT, Fmt.TEXT).label(R.string.s_color_temp).order(30);
            }

            @Override boolean run() throws Exception {
                int v = hal.lumensMode();
                String t = v == 16 ? ctD65 : (v == 17 ? ct1 : null);
                if (t == null) return false;
                setText(t);
                return true;
            }
        });

        tasks.add(new OneTask("IGmpf2 25 LED level", IGMPF2, GmpfAllowlist.LED_LEVEL, Group.LIGHT, M_ROW) {
            { deadIfAbsent = true; }

            @Override Sample create() {
                return new Sample("hal.ledlevel", Group.LIGHT, Fmt.PERCENT).label(R.string.s_led_power).order(40);
            }

            @Override boolean run() throws Exception {
                int v = hal.ledLevel();
                if (v < 0 || v > 1000) return false;
                set(v / 10.0);
                return true;
            }
        });

        tasks.add(new LedCurrentsTask());

        tasks.add(new OneTask("IGmpf2 68 supply voltage", IGMPF2, GmpfAllowlist.SUPPLY_VOLTAGE, Group.LIGHT, M_ROW) {
            { deadIfAbsent = true; }   // 0 on the Z9X's mains supply: then never shown

            @Override Sample create() {
                return new Sample("hal.voltage", Group.LIGHT, Fmt.VOLT).label(R.string.s_voltage).order(70);
            }

            @Override boolean run() throws Exception {
                float v = hal.supplyVoltage();
                if (!(v > 1f && v < 60f)) return false;
                set(v);
                return true;
            }
        });

        tasks.add(new OneTask("IGmpf2 27 light usage", IGMPF2, GmpfAllowlist.LIGHT_USAGE, Group.LIGHT, M_ROW) {
            { deadIfAbsent = true; once = true; }

            @Override Sample create() {
                return new Sample("hal.hours", Group.LIGHT, Fmt.HOURS).label(R.string.s_light_hours).order(60);
            }

            @Override boolean run() throws Exception {
                String json = hal.lightUsageJson();
                if (!usageLogged) {                         // harmless device data: the device test checks the unit
                    usageLogged = true;
                    Log.i(TAG, "IGmpf2 27 light usage (usage_time read as hours): " + brief(json));
                }
                double hours = GmpfStructs.lightHours(lightUsageTime(json));
                if (Double.isNaN(hours)) return false;
                set(hours);
                return true;
            }
        });

        tasks.add(new OneTask("620 HDR type", IGMPF, GmpfAllowlist.HDR_TYPE, Group.PICTURE, M_ROW) {
            @Override Sample create() {
                return new Sample("hal.hdr", Group.PICTURE, Fmt.TEXT).label(R.string.s_hdr).order(20);
            }

            @Override boolean run() throws Exception {
                String t = hdrName(hal.hdrType());
                if (t == null) return false;
                setText(t);
                return true;
            }
        });

        videoTask = new OneTask("IGmpf2 319 video info", IGMPF2, GmpfAllowlist.VIDEO_INFO, Group.PICTURE, M_ROW) {
            @Override boolean needed() { return Z9xSchedule.readsVideoInfo(inputSource); }   // media input only

            @Override Sample create() {
                return new Sample("hal.video", Group.PICTURE, Fmt.SIGNAL).label(R.string.s_video).order(30);
            }

            @Override boolean run() throws Exception {
                if (!hal.videoInfo(st) || !GmpfStructs.videoPlausible(st)) return false;
                double fps = st[2] / 100.0;
                set(st[0], st[1], fps > 0 && fps < 500 ? fps : 0, 0);
                return true;
            }
        };
        tasks.add(videoTask);

        tasks.add(new OneTask("IGmpf2 22 output frame rate", IGMPF2, GmpfAllowlist.OUTPUT_FPS, Group.PICTURE, M_ROW) {
            @Override Sample create() {
                return new Sample("hal.outfps", Group.PICTURE, Fmt.HZ).label(R.string.s_out_fps).order(50);
            }

            @Override boolean run() throws Exception {
                int v = hal.outputFps();
                if (v < 1 || v > 240) return false;
                set(v);
                return true;
            }
        });

        hdmiTask = new OneTask("662 HDMI info", IGMPF, GmpfAllowlist.HDMI_INFO, Group.PICTURE, M_ROW) {
            @Override boolean needed() { return Z9xSchedule.readsHdmiInfo(inputSource); }    // HDMI input only

            @Override Sample create() {
                return new Sample("hal.hdmi", Group.PICTURE, Fmt.SIGNAL).label(R.string.s_hdmi).order(40);
            }

            @Override boolean run() throws Exception {
                if (!hal.hdmiInfo(st) || !GmpfStructs.hdmiPlausible(st)) return false;   // no signal: no row
                set(st[0], st[1], st[4] / 100.0, st[2]);
                return true;
            }
        };
        tasks.add(hdmiTask);

        tofTask = new OneTask("279 ToF distance", IGMPF, GmpfAllowlist.TOF_DISTANCE, Group.MOTION, M_ROW) {
            { probeOnlyWhenShown = true; }

            @Override Sample create() {
                return new Sample("hal.tof", Group.MOTION, Fmt.MM).label(R.string.s_tof).order(30);
            }

            @Override boolean run() throws Exception {
                tofCalledAt = SystemClock.uptimeMillis();
                int v = hal.tofDistance();                  // the vendor measures now (about 0.6 s)
                if (v < 1 || v > 10_000) return false;
                set(v);
                return true;
            }
        };
        tasks.add(tofTask);
        delayTof(SystemClock.uptimeMillis());
    }

    /** A new 696 reading: the signal row of the other input is hidden (and not read: needed() says no). */
    private void setInput(int v) {
        inputSource = v;
        synchronized (reg.lock) {
            if (hdmiTask != null && !Z9xSchedule.readsHdmiInfo(v)) hdmiTask.hide();
            if (videoTask != null && !Z9xSchedule.readsVideoInfo(v)) videoTask.hide();
        }
    }

    /** Is the SoC temperature already shown from a thermal zone (ThermalSource started before us)? */
    private boolean socFromThermalZone() {
        synchronized (reg.lock) {
            ArrayList<Sample> all = reg.samplesLocked();
            for (int i = 0; i < all.size(); i++) {
                Sample s = all.get(i);
                if (s.group == Group.TEMPS && s.kind == Levels.K_SOC && s.available && s.id.startsWith("tz")) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 584 motion
    private final class MotionTask extends Task {
        Sample tilt, accel, gyro;

        MotionTask() {
            super("584 motion", IGMPF, GmpfAllowlist.MOTION, Group.MOTION, M_ROW);
            deadIfAbsent = true;
        }

        @Override boolean run() throws Exception {
            if (!hal.motion(st)) return false;
            double deg = GmpfStructs.tiltDegrees(st[0], st[1], st[2]);
            if (accel == null) {
                tilt = reg.add(new Sample("hal.tilt", Group.MOTION, Fmt.DEG).label(R.string.s_tilt).order(10));
                accel = reg.add(new Sample("hal.accel", Group.MOTION, Fmt.XYZ_G).label(R.string.s_accel).order(20));
                // raw counts: the gyroscope scale is unknown
                gyro = reg.add(new Sample("hal.gyro", Group.MOTION, Fmt.XYZ).label(R.string.s_gyro).order(21));
            }
            synchronized (reg.lock) {
                if (Double.isNaN(deg)) tilt.hide(); else tilt.set(deg);
                accel.set(GmpfStructs.accelG(st[0]), GmpfStructs.accelG(st[1]), GmpfStructs.accelG(st[2]));
                gyro.set(st[3], st[4], st[5]);
            }
            return true;
        }

        @Override boolean shownNow() {
            if (accel == null) return super.shownNow();
            return sampler.shows(tilt) || sampler.shows(accel) || sampler.shows(gyro);
        }

        @Override void hide() {
            if (tilt != null) tilt.hide();
            if (accel != null) accel.hide();
            if (gyro != null) gyro.hide();
        }
    }

    // ------------------------------------------------------------------ 439 / 339 sensor probe
    /** One call per step: 439(index), then 339(index) for a plausible one; resolves when done. */
    private final class SensorProbe extends Task {
        private final int[] indexes;
        private final float[] temp = new float[16];
        private final int[] status = new int[16];         // -1 unknown, 0 invalid, 1 valid
        private int k;
        private boolean statusStep;

        SensorProbe(boolean socFromZone) {
            super("439 sensor probe", IGMPF, GmpfAllowlist.SENSOR_TEMPERATURE, Group.TEMPS, M_TEMP);
            indexes = socFromZone ? new int[] {1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12}
                    : new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
            java.util.Arrays.fill(status, -1);
        }

        @Override boolean run() throws Exception {
            int idx = indexes[k];
            if (!statusStep) {
                float v;
                try {
                    v = hal.sensorTemperature(idx);
                } catch (android.os.RemoteException e) {
                    v = 0;                                  // this index is skipped, the probe goes on
                }
                if (v >= 1f && v <= 127f) {
                    temp[idx] = v;
                    statusStep = true;
                } else {
                    k++;
                }
            } else {
                try {
                    status[idx] = hal.sensorStatus(idx) ? 1 : 0;
                } catch (android.os.RemoteException e) {
                    status[idx] = -1;
                }
                statusStep = false;
                k++;
            }
            if (k < indexes.length) {
                again = true;
                return true;
            }
            resolve();
            dead = true;
            return true;
        }

        @Override void hide() {}

        private void resolve() {
            boolean anyValid = false;
            for (int idx : indexes) if (temp[idx] > 0 && status[idx] == 1) anyValid = true;
            boolean[] keep = new boolean[16];
            for (int idx : indexes) {
                if (temp[idx] <= 0) continue;
                keep[idx] = !anyValid || status[idx] != 0;
                if (!keep[idx]) Log.i(TAG, "sensor " + idx + " dropped: 339 says invalid");
            }
            if (!anyValid) Log.i(TAG, "339 marks no sensor valid: not applied");
            boolean oneLed = keep[5] && (!keep[4] || temp[4] == temp[5]) && (!keep[8] || temp[8] == temp[5]);
            if (oneLed) keep[4] = keep[8] = false;
            long now = SystemClock.uptimeMillis();
            for (int idx : indexes) {
                if (!keep[idx]) continue;
                Sample s = new Sample("hal.t" + idx, Group.TEMPS, Fmt.TEMP).graph();
                switch (idx) {
                    case 1: s.label(R.string.s_air).kind(Levels.K_AIR).order(30).compact(4, R.string.s_air_short); break;
                    case 2: s.label(R.string.s_dmd).kind(Levels.K_DMD).order(40).compact(2, R.string.s_dmd_short); break;
                    case 5: s.label(oneLed ? R.string.s_led : R.string.s_led_r).kind(Levels.K_LED).order(50)
                            .compact(3, R.string.s_led_short); break;
                    case 4: s.label(R.string.s_led_g).kind(Levels.K_LED).order(51); break;
                    case 8: s.label(R.string.s_led_b).kind(Levels.K_LED).order(52); break;
                    case 9: s.label(R.string.s_cpu_vendor).kind(Levels.K_SOC).order(15).compact(1, R.string.s_soc_short); break;
                    default: s.label(R.string.s_sensor, idx).kind(Levels.K_BOARD).order(100 + idx); break;
                }
                SensorTask t = new SensorTask(idx, reg.add(s));
                synchronized (reg.lock) {
                    t.sample.set(temp[idx]);
                }
                t.due = now + t.period();
                tasks.add(t);
            }
        }
    }

    private final class SensorTask extends Task {
        final int idx;
        final Sample sample;

        SensorTask(int idx, Sample sample) {
            super("439 sensor " + idx, IGMPF, GmpfAllowlist.SENSOR_TEMPERATURE, Group.TEMPS, sample.modes);
            this.idx = idx;
            this.sample = sample;
            probed = true;
        }

        @Override boolean shownNow() { return sampler.shows(sample); }

        @Override boolean run() throws Exception {
            float v = hal.sensorTemperature(idx);
            if (v < 1f || v > 127f) return false;
            synchronized (reg.lock) {
                sample.set(v);
            }
            return true;
        }

        @Override void hide() { sample.hide(); }
    }

    // ------------------------------------------------------------------ 437 fan probe
    private final class FanProbe extends Task {
        private static final int FANS = 6;            // the vendor PID CSV has 6 fan slots
        private final int[] speed = new int[FANS];
        private int id;

        FanProbe() {
            super("437 fan probe", IGMPF, GmpfAllowlist.FAN_SPEED, Group.COOLING, M_FAN);
        }

        @Override boolean run() throws Exception {
            try {
                speed[id] = hal.fanSpeed(id);
            } catch (android.os.RemoteException e) {
                speed[id] = 0;
            }
            id++;
            if (id < FANS) {
                again = true;
                return true;
            }
            int found = 0;
            for (int v : speed) if (v > 0) found++;
            long now = SystemClock.uptimeMillis();
            int n = 0;
            for (int f = 0; f < FANS; f++) {
                if (speed[f] <= 0) continue;
                n++;
                Sample s = new Sample("hal.fan" + f, Group.COOLING, Fmt.INT).order(20 + f);
                if (found == 1) s.label(R.string.s_fan); else s.label(R.string.s_fan_n, n);
                if (n == 1) s.compact(5, R.string.s_fan_short);
                FanTask t = new FanTask(f, reg.add(s));
                synchronized (reg.lock) {
                    t.sample.set(speed[f]);
                }
                t.due = now + t.period();
                tasks.add(t);
            }
            dead = true;
            return true;
        }

        @Override void hide() {}
    }

    private final class FanTask extends Task {
        final int fan;
        final Sample sample;

        FanTask(int fan, Sample sample) {
            super("437 fan " + fan, IGMPF, GmpfAllowlist.FAN_SPEED, Group.COOLING, sample.modes);
            this.fan = fan;
            this.sample = sample;
            probed = true;
        }

        @Override boolean shownNow() { return sampler.shows(sample); }

        @Override boolean run() throws Exception {
            int v = hal.fanSpeed(fan);                 // 0 = stopped now (it answered at the probe)
            synchronized (reg.lock) {
                sample.set(v);
            }
            return true;
        }

        @Override void hide() { sample.hide(); }
    }

    // ------------------------------------------------------------------ IGmpf2 252 LED currents
    private final class LedCurrentsTask extends Task {
        private Sample[] rows;
        private final int[] cur = new int[8];

        LedCurrentsTask() {
            super("IGmpf2 252 LED currents", IGMPF2, GmpfAllowlist.LED_CURRENTS, Group.LIGHT, M_ROW);
            deadIfAbsent = true;
        }

        @Override boolean run() throws Exception {
            if (!hal.ledCurrents(cur)) return false;
            if (rows == null) {
                // the channels that are not 0 at the probe are this projector's; all 0 = nothing to show
                boolean any = false;
                for (int v : cur) if (v != 0) any = true;
                if (!any) return false;
                rows = new Sample[8];
                for (int i = 0; i < 8; i++) {
                    if (cur[i] == 0) continue;
                    rows[i] = reg.add(new Sample("hal.cur" + i, Group.LIGHT, Fmt.INT)
                            .label(R.string.s_led_current, CURRENT_NAMES[i]).order(50 + i));
                }
            }
            synchronized (reg.lock) {
                for (int i = 0; i < 8; i++) if (rows[i] != null) rows[i].set(cur[i]);
            }
            return true;
        }

        @Override boolean shownNow() {
            if (rows == null) return super.shownNow();
            for (Sample s : rows) if (s != null && sampler.shows(s)) return true;
            return false;
        }

        @Override void hide() {
            if (rows == null) return;
            for (Sample s : rows) if (s != null) s.hide();
        }
    }

    // ------------------------------------------------------------------ helpers
    private void loadTexts() {
        Resources r = app.getResources();
        for (int i = 0; i < 10; i++) fanModes[i] = r.getString(R.string.fan_manual, i + 1);
        fanModes[10] = r.getString(R.string.fan_auto);
        fanModes[11] = r.getString(R.string.fan_test);
        ctD65 = r.getString(R.string.ct_d65);
        ct1 = r.getString(R.string.ct_1);
        srcAndroid = r.getString(R.string.src_android);
        srcHdmi1 = r.getString(R.string.src_hdmi, 1);
        srcHdmi2 = r.getString(R.string.src_hdmi, 2);
    }

    /** getHdrType values (GmpfClient HDR_*): names are the formats' own, not translated. */
    static String hdrName(int v) {
        switch (v) {
            case 0: return "SDR";
            case 1: return "HDR10";
            case 2: return "Dolby Vision";
            case 4: return "HLG";
            case 5: return "HDR10+";
            case 6: return "HDR Vivid";
            default: return null;
        }
    }

    /**
     * usage_time of light source 0 in the IGmpf2 27 JSON (light_id 0, else the first entry, as stock
     * reads it), or -1 when the text is not that JSON. GmpfStructs.lightHours checks it (whole hours).
     */
    static long lightUsageTime(String json) {
        if (json == null || json.isEmpty()) return -1;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray list = o.optJSONArray("light_list");
            if (list == null || list.length() == 0) return -1;
            JSONObject pick = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject e = list.optJSONObject(i);
                if (e != null && e.optInt("light_id", -1) == 0) {
                    pick = e;
                    break;
                }
            }
            if (pick == null) pick = list.optJSONObject(0);
            if (pick == null || !pick.has("usage_time")) return -1;
            return pick.optLong("usage_time", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** A vendor text for the log: one line, at most USAGE_LOG_CHARS characters. */
    private static String brief(String text) {
        if (text == null) return "null";
        String t = text.replace('\n', ' ').replace('\r', ' ');
        return t.length() <= USAGE_LOG_CHARS ? t : t.substring(0, USAGE_LOG_CHARS) + "...";
    }
}
