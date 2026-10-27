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
import org.lumen.sensors.hal.GmpfStructs;
import org.lumen.sensors.hal.Z9xGmpfReader;

import java.util.ArrayList;

/**
 * Z9X ONLY: the projector values that exist only inside XGIMI's gmpf HAL (temperature sensors, fans,
 * light, input signal, motion), read through {@link Z9xGmpfReader} (allowlist of read-only codes).
 *
 * Scheduling (one task per value, all on the sampler thread, never on the main thread):
 *  - at most ONE HAL call per {@link #GAP_MS} (200 ms = 5 calls per second in total); the pump always
 *    runs the most overdue task that is wanted, so the calls spread over each period;
 *  - temperatures, fans and the screen check every max(2 s, refresh) ("round"); light, voltage and
 *    LED values every 10 s; picture and signal every 5 s; motion only while the Motion group is on
 *    screen (584 every round, ToF 279 and the focus motor every 5 s); light usage once per start;
 *  - 196 getScreenOnOff gates every other read: no read goes out unless a 196 reading from this round
 *    (younger than the round period) says the light is on. When the light is off, or 196 failed, only
 *    196 is read (once per round); when 196 is dead (3 failures), nothing is read until the next start.
 *    196 itself is read only right before another read is due (never on its own while nothing else is);
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

    static final long GAP_MS = 200;
    private static final long ROUND_MIN_MS = 2_000;
    private static final long LIGHT_MS = 10_000;
    private static final long PICTURE_MS = 5_000;
    private static final long MOTION_SLOW_MS = 5_000;
    private static final long SLOW_CALL_MS = 2_000;
    private static final long RETRY_MS = 3_000;
    private static final int MAX_FAILS = 3;
    private static final int MAX_SLOW = 5;
    private static final long CONNECT_RETRY_MS = 10_000;
    private static final int CONNECT_TRIES = 6;
    private static final long BOOT_SETTLE_MS = 60_000;

    /** Period kinds. */
    private static final int P_ROUND = 0, P_LIGHT = 1, P_PICTURE = 2, P_MOTION_SLOW = 3;

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
    private boolean running, connected, lampOff, pumpLogged;
    private int connectTries;
    private long lastCall;
    /** Uptime of the last good 196 reading (0 = none since the start). */
    private long screenCheckedAt;
    private int inputSource = -1;
    private Task screenTask;
    private Sample hdmiSample, signalSample;

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
        lastCall = 0;
        screenCheckedAt = 0;
        inputSource = -1;
        hdmiSample = signalSample = null;
        loadTexts();
        tasks.clear();
        buildTasks();
        s.addViewListener(onView);
        h.post(pump);
    }

    @Override
    public void poll(Sampler s, long now) {
        // the HAL work runs on its own pump (one call per GAP_MS), not on the refresh tick
    }

    @Override
    public void stop(Sampler s) {
        running = false;
        if (h != null) h.removeCallbacks(pump);
        s.removeViewListener(onView);
        tasks.clear();
        screenTask = null;
        hal.disconnect();
        connected = false;
    }

    /** The view changed (mode, groups, HUD on screen): look for work at once. Sampler thread. */
    private void kick() {
        if (!running || h == null) return;
        h.removeCallbacks(pump);
        h.post(pump);
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
        Task best = null;
        for (int i = 0; i < tasks.size(); i++) {
            Task t = tasks.get(i);
            if (t != screenTask && wanted(t) && (best == null || t.due < best.due)) best = t;
        }
        if (best == null) return;                         // idle until the view changes (kick); no 196 either
        long at = Math.max(best.due, lastCall + GAP_MS);
        if (at > now) {                                   // nothing due yet (196 neither: it goes right before a read)
            h.postAtTime(pump, at);
            return;
        }
        if (lampOff || now - screenCheckedAt > periodOf(P_ROUND) || now >= screenTask.due) {
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
        long next = t.due + t.period();
        t.due = next >= end ? next : end + t.period();
    }

    private void hide(Task t) {
        synchronized (reg.lock) {
            t.hide();
        }
    }

    private long periodOf(int kind) {
        switch (kind) {
            case P_ROUND: return Math.max(ROUND_MIN_MS, sampler.refreshMs());
            case P_LIGHT: return LIGHT_MS;
            case P_PICTURE: return PICTURE_MS;
            default: return MOTION_SLOW_MS;
        }
    }

    // ================================================================== tasks
    private abstract class Task {
        final String name;
        final int group, modes, periodKind;
        long due;
        int fails, slow;
        boolean dead, probed, again;
        /** An implausible first reading means the value does not exist here: never read again. */
        boolean deadIfAbsent;
        /** A static value: read once per start. */
        boolean once;
        /** Not even probed unless its group is on screen (ToF). */
        boolean probeOnlyWhenShown;

        Task(String name, int group, int modes, int periodKind) {
            this.name = name;
            this.group = group;
            this.modes = modes;
            this.periodKind = periodKind;
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

        OneTask(String name, int group, int modes, int periodKind) {
            super(name, group, modes, periodKind);
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
        screenTask = new Task("196 screen", -1, 0, P_ROUND) {   // the gate: pump() runs it, never wanted()
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

        tasks.add(new OneTask("438 system temperature", Group.TEMPS, M_ROW_GRAPH, P_ROUND) {   // no compact rank
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

        tasks.add(new OneTask("435 fan mode", Group.COOLING, M_ROW, P_LIGHT) {
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

        tasks.add(new OneTask("177 brightness level", Group.LIGHT, M_ROW, P_LIGHT) {
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

        tasks.add(new OneTask("696 input source", Group.PICTURE, M_ROW, P_PICTURE) {
            { deadIfAbsent = true; }

            @Override Sample create() {
                return new Sample("hal.input", Group.PICTURE, Fmt.TEXT).label(R.string.s_source).order(10);
            }

            @Override boolean run() throws Exception {
                int v = hal.inputSource();
                String t = v == 0 ? srcAndroid : (v == 1 ? srcHdmi1 : (v == 2 ? srcHdmi2 : null));
                inputSource = t == null ? -1 : v;
                if (!isHdmi()) {
                    synchronized (reg.lock) {                // the HDMI rows belong to an HDMI input only
                        if (hdmiSample != null) hdmiSample.hide();
                        if (signalSample != null) signalSample.hide();
                    }
                }
                if (t == null) return false;
                setText(t);
                return true;
            }

            /** Failed, dead or implausible: the input is unknown, so the HDMI-only reads stop too. */
            @Override void hide() {
                super.hide();
                inputSource = -1;
                if (hdmiSample != null) hdmiSample.hide();
                if (signalSample != null) signalSample.hide();
            }
        });

        tasks.add(new MotionTask());

        tasks.add(new OneTask("266 focus motor", Group.MOTION, M_ROW, P_MOTION_SLOW) {
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

        tasks.add(new OneTask("IGmpf2 241 over-temperature", Group.TEMPS, M_ROW, P_LIGHT) {
            @Override Sample create() {
                return new Sample("hal.overheat", Group.TEMPS, Fmt.YES_NO).label(R.string.s_overheat)
                        .kind(Levels.K_FLAG).order(200);
            }

            @Override boolean run() throws Exception {
                set(hal.overTemperature() ? 1 : 0);
                return true;
            }
        });

        tasks.add(new OneTask("IGmpf2 51 Boost", Group.LIGHT, M_ROW, P_LIGHT) {
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

        tasks.add(new OneTask("175 color temperature", Group.LIGHT, M_ROW, P_LIGHT) {
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

        tasks.add(new OneTask("IGmpf2 25 LED level", Group.LIGHT, M_ROW, P_LIGHT) {
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

        tasks.add(new OneTask("IGmpf2 68 supply voltage", Group.LIGHT, M_ROW, P_LIGHT) {
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

        tasks.add(new OneTask("IGmpf2 27 light usage", Group.LIGHT, M_ROW, P_LIGHT) {
            { deadIfAbsent = true; once = true; }

            @Override Sample create() {
                return new Sample("hal.hours", Group.LIGHT, Fmt.HOURS).label(R.string.s_light_hours).order(60);
            }

            @Override boolean run() throws Exception {
                double hours = lightHours(hal.lightUsageJson());
                if (Double.isNaN(hours)) return false;
                set(hours);
                return true;
            }
        });

        tasks.add(new OneTask("620 HDR type", Group.PICTURE, M_ROW, P_PICTURE) {
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

        tasks.add(new OneTask("IGmpf2 319 video info", Group.PICTURE, M_ROW, P_PICTURE) {
            @Override Sample create() {
                return new Sample("hal.video", Group.PICTURE, Fmt.SIGNAL).label(R.string.s_video).order(30);
            }

            @Override boolean run() throws Exception {
                if (!hal.videoInfo(st) || !GmpfStructs.videoPlausible(st)) return false;
                double fps = st[2] / 100.0;
                set(st[0], st[1], fps > 0 && fps < 500 ? fps : 0, 0);
                return true;
            }
        });

        tasks.add(new OneTask("IGmpf2 22 output frame rate", Group.PICTURE, M_ROW, P_PICTURE) {
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

        tasks.add(new OneTask("662 HDMI info", Group.PICTURE, M_ROW, P_PICTURE) {
            @Override boolean needed() { return isHdmi(); }

            @Override Sample create() {
                return new Sample("hal.hdmi", Group.PICTURE, Fmt.SIGNAL).label(R.string.s_hdmi).order(40);
            }

            @Override boolean run() throws Exception {
                if (!hal.hdmiInfo(st) || !GmpfStructs.hdmiPlausible(st)) return false;   // no signal: no row
                set(st[0], st[1], st[4] / 100.0, st[2]);
                hdmiSample = sample;
                return true;
            }
        });

        tasks.add(new OneTask("592 signal status", Group.PICTURE, M_ROW, P_PICTURE) {
            @Override boolean needed() { return isHdmi(); }

            @Override Sample create() {
                return new Sample("hal.signal", Group.PICTURE, Fmt.INT).label(R.string.s_signal).order(45);
            }

            @Override boolean run() throws Exception {
                int v = hal.signalStatus();
                if (v < 0 || v > 1000) return false;
                set(v);
                signalSample = sample;
                return true;
            }
        });

        tasks.add(new OneTask("279 ToF distance", Group.MOTION, M_ROW, P_MOTION_SLOW) {
            { probeOnlyWhenShown = true; }

            @Override Sample create() {
                return new Sample("hal.tof", Group.MOTION, Fmt.MM).label(R.string.s_tof).order(30);
            }

            @Override boolean run() throws Exception {
                int v = hal.tofDistance();
                if (v < 1 || v > 10_000) return false;
                set(v);
                return true;
            }
        });
    }

    private boolean isHdmi() {
        return inputSource == 1 || inputSource == 2;
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
            super("584 motion", Group.MOTION, M_ROW, P_ROUND);
            deadIfAbsent = true;
        }

        @Override boolean run() throws Exception {
            if (!hal.motion(st)) return false;
            double deg = GmpfStructs.tiltDegrees(st[0], st[1], st[2]);
            if (accel == null) {
                tilt = reg.add(new Sample("hal.tilt", Group.MOTION, Fmt.DEG).label(R.string.s_tilt).order(10));
                accel = reg.add(new Sample("hal.accel", Group.MOTION, Fmt.XYZ).label(R.string.s_accel).order(20));
                gyro = reg.add(new Sample("hal.gyro", Group.MOTION, Fmt.XYZ).label(R.string.s_gyro).order(21));
            }
            synchronized (reg.lock) {
                if (Double.isNaN(deg)) tilt.hide(); else tilt.set(deg);
                accel.set(st[0], st[1], st[2]);
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
            super("439 sensor probe", Group.TEMPS, M_TEMP, P_ROUND);
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
            super("439 sensor " + idx, Group.TEMPS, sample.modes, P_ROUND);
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
            super("437 fan probe", Group.COOLING, M_FAN, P_ROUND);
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
            super("437 fan " + fan, Group.COOLING, sample.modes, P_ROUND);
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
            super("IGmpf2 252 LED currents", Group.LIGHT, M_ROW, P_LIGHT);
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
     * Hours of light source 0 from the IGmpf2 27 JSON (stock reads light_id 0, usage_time in seconds),
     * or NaN when the text is not that JSON.
     */
    static double lightHours(String json) {
        if (json == null || json.isEmpty()) return Double.NaN;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray list = o.optJSONArray("light_list");
            if (list == null || list.length() == 0) return Double.NaN;
            JSONObject pick = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject e = list.optJSONObject(i);
                if (e != null && e.optInt("light_id", -1) == 0) {
                    pick = e;
                    break;
                }
            }
            if (pick == null) pick = list.optJSONObject(0);
            if (pick == null || !pick.has("usage_time")) return Double.NaN;
            long sec = pick.optLong("usage_time", -1);
            if (sec < 0) return Double.NaN;
            double hours = sec / 3600.0;
            return hours < 200_000 ? hours : Double.NaN;
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
