package org.lumen.sensors.x50;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import org.lumen.sensors.R;
import org.lumen.sensors.core.Fmt;
import org.lumen.sensors.core.Group;
import org.lumen.sensors.core.Levels;
import org.lumen.sensors.core.Registry;
import org.lumen.sensors.core.Sample;
import org.lumen.sensors.core.Sampler;
import org.lumen.sensors.core.Source;
import org.lumen.sensors.hal.X50Gmpf;
import org.lumen.sensors.hal.X50Gmpf.Call;
import org.lumen.sensors.hal.X50Structs;

import java.util.ArrayList;

/**
 * X50 ONLY: the projector values that exist only inside XGIMI's gmpf HAL of the X50 (temperature sensors, fans, light,
 * input signal, motion), read through {@link X50Gmpf} (the allow list is its Call enum) on the plan of
 * {@link X50Schedule}. The pump, the light gate and the failure rules are those of the Z9X source
 * (z9x/Z9xHalSource), copied rather than shared so the Z9X code never changes for another device:
 *  - one call at a time on the sampler thread, at least 200 ms apart while probing and 500 ms apart after that
 *    (never more than 2 per second, no bursts), the wanted task that was due first;
 *  - DISPLAY 28 getScreenOnOff gates every other read: nothing else goes out unless a reading of this round says the
 *    light is on; light off = only DISPLAY 28, once per round; 3 failures of it = nothing until the next start;
 *  - the input (TV 4) picks the signal read: TV 38 HDMI info on HDMI, TV 122 video info on the media input;
 *  - probing once per start (sensors 1..8, 10..12; fans 0 and 1; every value's first read), also with only the
 *    settings screen open; periodic reads only while the HUD draws that value in its mode;
 *  - a failed call hides its row, 3 in a row stop it; an answer slower than 2 s hides the row until the next good
 *    read, 5 slow ones in a row stop it; no call in the first 60 s after boot.
 * X50 specifics: three lasers (sensors 5 red, 4 green, 11 second red; 8 blue has no sensor), sensor 6 "BP" and 12 the
 * focus module (names of xgimiconfig/G0000 tempctrl_pid.json); the accelerometer comes as int arrays and its z axis
 * is turned so lying flat reads +1 g as on the Z9X; a fan mode or light mode without a known meaning shows its raw
 * code with a note; DISPLAY 176 gives one hours row per laser (hidden while the vendor answers 0).
 */
public final class X50HalSource implements Source {
    private static final String TAG = "LumenSensors";

    private static final long GAP_MS = X50Schedule.GAP_MS;
    private static final long SLOW_CALL_MS = 2_000;
    private static final long RETRY_MS = 3_000;
    private static final int MAX_FAILS = 3;
    private static final int MAX_SLOW = 5;
    private static final long CONNECT_RETRY_MS = 10_000;
    private static final int CONNECT_TRIES = 6;
    private static final long BOOT_SETTLE_MS = 60_000;
    private static final int USAGE_LOG_CHARS = 240;

    private static final int M_TEMP = Sample.MODE_DETAILED | Sample.MODE_COMPACT | Sample.MODE_GRAPH;
    private static final int M_FAN = Sample.MODE_DETAILED | Sample.MODE_COMPACT;
    private static final int M_ROW = Sample.MODE_DETAILED;
    private static final int M_ROW_GRAPH = Sample.MODE_DETAILED | Sample.MODE_GRAPH;

    /** Sensor indexes probed: 9 (the CPU, type 255 on the X50) never; 3, 7, 8, 10 answer 0 there and drop out. */
    static final int[] SENSORS = {1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12};
    /** Fan ids probed (tempctrl: two fans; both answered at the survey). */
    static final int[] FANS = {0, 1};

    private final Context app;
    private final X50Gmpf hal = new X50Gmpf();
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
    private long screenCheckedAt;
    private int inputSource = X50Schedule.INPUT_UNKNOWN;
    private Task screenTask;
    private OneTask hdmiTask, videoTask;

    // texts, resolved at start
    private final String[] fanModes = new String[12];
    private String ctD65, ct1, srcAndroid, srcHdmi1, srcHdmi2;
    private Resources res;

    public X50HalSource(Context app) {
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
        inputSource = X50Schedule.INPUT_UNKNOWN;
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
        screenTask = null;
        hdmiTask = videoTask = null;
        hal.disconnect();
        connected = false;
    }

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
                Log.w(TAG, "X50 HAL pump (logged once): " + e);
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
                Log.i(TAG, "X50 gmpf HAL connected");
            } else {
                if (++connectTries < CONNECT_TRIES) {
                    h.postAtTime(pump, now + CONNECT_RETRY_MS);
                } else {
                    Log.w(TAG, "X50 gmpf HAL not reachable from this app, no projector values until the next start");
                }
                return;
            }
        }
        Task best = null;
        for (int i = 0; i < tasks.size(); i++) {
            Task t = tasks.get(i);
            if (t != screenTask && wanted(t) && (best == null || t.due < best.due)) best = t;
        }
        if (best == null) return;                         // idle until the view changes (kick)
        long at = Math.max(best.due, lastCall + X50Schedule.gapMs(!best.probed));
        if (at > now) {
            h.postAtTime(pump, at);
            return;
        }
        if (lampOff || now - screenCheckedAt > periodOf(X50Schedule.P_ROUND) || now >= screenTask.due) {
            if (screenTask.dead) return;                  // lampOff is set: no HAL reads until the next start
            if (screenTask.due > now) {
                h.postAtTime(pump, screenTask.due);
                return;
            }
            best = screenTask;
        }
        execute(best);
        h.postAtTime(pump, lastCall + GAP_MS);
    }

    private boolean wanted(Task t) {
        if (t.dead || !t.needed()) return false;
        if (!t.probed) return true;
        return t.shownNow();
    }

    private void execute(Task t) {
        long t0 = SystemClock.uptimeMillis();
        boolean plausible = false, failed = false;
        try {
            plausible = t.run();
        } catch (IllegalArgumentException e) {
            Log.e(TAG, t.name + ": " + e.getMessage());        // argument refused before sending: a bug
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
            if (t == screenTask) lampOff = true;
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
            Log.w(TAG, t.name + " took " + (end - t0) + " ms");
            if (++t.slow >= MAX_SLOW) {
                t.dead = true;
                hide(t);
                if (t == screenTask) lampOff = true;
                Log.w(TAG, t.name + ": " + MAX_SLOW + " slow calls in a row, not read again until the next start");
                return;
            }
            if (t != screenTask) {
                hide(t);
                t.again = false;
                t.due = end + Math.max(RETRY_MS, t.period());
                return;
            }
        } else {
            t.slow = 0;
        }
        if (t == screenTask) screenCheckedAt = end;
        if (t.again) {
            t.again = false;
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
            t.dead = true;
            return;
        }
        t.due = X50Schedule.nextDue(t.due, end, t.period());
    }

    private void hide(Task t) {
        synchronized (reg.lock) {
            t.hide();
        }
    }

    private long periodOf(int kind) {
        return X50Schedule.periodMs(kind, sampler.refreshMs());
    }

    // ================================================================== tasks
    private abstract class Task {
        final String name;
        final int group, modes;
        final int periodKind;
        long due;
        int fails, slow;
        boolean dead, probed, again, deadIfAbsent, once;

        /** The period comes from the plan of the call's service and code (X50Schedule.kindOf). */
        Task(String name, Call call, int group, int modes) {
            this.name = name;
            this.group = group;
            this.modes = modes;
            int kind = X50Schedule.kindOf(call.service(), call.code);
            this.periodKind = kind >= 0 ? kind : X50Schedule.P_SLOW;
            this.due = SystemClock.uptimeMillis();
        }

        long period() { return periodOf(periodKind); }

        boolean needed() { return true; }

        boolean shownNow() { return sampler.shown(group, modes); }

        abstract boolean run() throws Exception;

        abstract void hide();
    }

    private abstract class OneTask extends Task {
        Sample sample;

        OneTask(String name, Call call, int group, int modes) {
            super(name, call, group, modes);
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
        screenTask = new Task("DISPLAY 28 screen", Call.GET_SCREEN_ON_OFF, -1, 0) {
            @Override boolean run() throws Exception {
                boolean on = hal.screenOn();
                if (on == lampOff) {
                    lampOff = !on;
                    Log.i(TAG, on ? "light on: X50 HAL reads resume" : "light off: X50 HAL reads pause");
                }
                return true;
            }

            @Override void hide() {}
        };
        tasks.add(screenTask);

        tasks.add(new OneTask("TEMPERATURE 6 system", Call.GET_SYSTEM_TEMPERATURE, Group.TEMPS, M_ROW_GRAPH) {
            { deadIfAbsent = true; }

            @Override Sample create() {
                return new Sample("x50.sys", Group.TEMPS, Fmt.TEMP).label(R.string.s_system).kind(Levels.K_BOARD)
                        .order(20).graph();
            }

            @Override boolean run() throws Exception {
                int v = hal.systemTemperature();
                if (v < 1 || v > 127) return false;
                set(v);
                return true;
            }
        });

        tasks.add(new OneTask("TEMPERATURE 3 fan mode", Call.GET_FAN_MODE, Group.COOLING, M_ROW) {
            { deadIfAbsent = true; }

            @Override Sample create() {
                return new Sample("x50.fanmode", Group.COOLING, Fmt.TEXT).label(R.string.s_fan_mode).order(10);
            }

            @Override boolean run() throws Exception {
                int v = hal.fanMode();
                if (v < 0 || v > 255) return false;
                setText(X50Structs.fanModeKnown(v) ? fanModes[v] : rawCode(v));
                return true;
            }
        });

        tasks.add(new OneTask("DISPLAY 7 brightness level", Call.GET_DLP_LUMENS_LEVEL, Group.LIGHT, M_ROW) {
            { deadIfAbsent = true; }

            @Override Sample create() {
                return new Sample("x50.lamp", Group.LIGHT, Fmt.INT).label(R.string.s_lamp).order(10);
            }

            @Override boolean run() throws Exception {
                int v = hal.lampLevel();
                if (v < 1 || v > 10) return false;
                set(v);
                return true;
            }
        });

        tasks.add(new OneTask("TV 4 input source", Call.GET_CURRENT_INPUT_SOURCE, Group.PICTURE, M_ROW) {
            { deadIfAbsent = true; }

            @Override Sample create() {
                return new Sample("x50.input", Group.PICTURE, Fmt.TEXT).label(R.string.s_source).order(10);
            }

            @Override boolean run() throws Exception {
                int v = X50Schedule.input(hal.inputSource());
                setInput(v);
                String t = v == X50Schedule.INPUT_MEDIA ? srcAndroid
                        : (v == X50Schedule.INPUT_HDMI1 ? srcHdmi1 : (v == X50Schedule.INPUT_HDMI2 ? srcHdmi2 : null));
                if (t == null) return false;
                setText(t);
                return true;
            }

            @Override void hide() {
                super.hide();
                inputSource = X50Schedule.INPUT_UNKNOWN;
                if (hdmiTask != null) hdmiTask.hide();
                if (videoTask != null) videoTask.hide();
            }
        });

        tasks.add(new MotionTask());

        tasks.add(new OneTask("FOCUS 59 focus motor", Call.GET_FOCUS_MOTOR_CURRENT_STEP, Group.MOTION, M_ROW) {
            { deadIfAbsent = true; }

            @Override Sample create() {
                return new Sample("x50.focus", Group.MOTION, Fmt.INT).label(R.string.s_focus).order(40);
            }

            @Override boolean run() throws Exception {
                int v = hal.focusMotorStep();
                if (v < -100_000 || v > 100_000) return false;
                set(v);
                return true;
            }
        });

        tasks.add(new SensorProbe());
        tasks.add(new FanProbe());

        tasks.add(new OneTask("TEMPERATURE 12 over-temperature", Call.IS_TEMP_ULTRA_HIGH, Group.TEMPS, M_ROW) {
            @Override Sample create() {
                return new Sample("x50.overheat", Group.TEMPS, Fmt.YES_NO).label(R.string.s_overheat)
                        .kind(Levels.K_FLAG).order(200);
            }

            @Override boolean run() throws Exception {
                set(hal.overTemperature() ? 1 : 0);
                return true;
            }
        });

        tasks.add(new OneTask("DISPLAY 152 Boost", Call.GET_LENS_DISP_MODE, Group.LIGHT, M_ROW) {
            @Override Sample create() {
                return new Sample("x50.boost", Group.LIGHT, Fmt.ON_OFF).label(R.string.s_boost).order(20);
            }

            @Override boolean run() throws Exception {
                int v = hal.lensDispMode();
                if (v != 0 && v != 1) return false;
                set(v);
                return true;
            }
        });

        tasks.add(new OneTask("DISPLAY 5 light mode", Call.GET_DLP_LUMENS_MODE, Group.LIGHT, M_ROW) {
            @Override Sample create() {
                return new Sample("x50.lightmode", Group.LIGHT, Fmt.TEXT).label(R.string.s_light_mode).order(30);
            }

            @Override boolean run() throws Exception {
                int v = hal.lumensMode();
                if (v < 0 || v > 255) return false;
                setText(X50Structs.lightModeKnown(v) ? (v == 16 ? ctD65 : ct1) : rawCode(v));
                return true;
            }
        });

        tasks.add(new OneTask("DISPLAY 178 light power", Call.GET_DISP_LUMENS_LEVEL, Group.LIGHT, M_ROW) {
            { deadIfAbsent = true; }

            @Override Sample create() {
                return new Sample("x50.power", Group.LIGHT, Fmt.PERCENT).label(R.string.s_laser_power).order(40);
            }

            @Override boolean run() throws Exception {
                int v = hal.lightPower();
                if (v < 0 || v > 1000) return false;
                set(v / 10.0);
                return true;
            }
        });

        tasks.add(new LightUsageTask());

        tasks.add(new OneTask("TV 78 HDR type", Call.GET_HDR_TYPE, Group.PICTURE, M_ROW) {
            @Override Sample create() {
                return new Sample("x50.hdr", Group.PICTURE, Fmt.TEXT).label(R.string.s_hdr).order(20);
            }

            @Override boolean run() throws Exception {
                String t = X50Structs.hdrName(hal.hdrType());
                if (t == null) return false;
                setText(t);
                return true;
            }
        });

        videoTask = new OneTask("TV 122 video info", Call.GET_VIDEO_INFO, Group.PICTURE, M_ROW) {
            @Override boolean needed() { return X50Schedule.readsVideoInfo(inputSource); }

            @Override Sample create() {
                return new Sample("x50.video", Group.PICTURE, Fmt.SIGNAL).label(R.string.s_video).order(30);
            }

            @Override boolean run() throws Exception {
                if (!hal.videoInfo(st) || !X50Structs.videoPlausible(st)) return false;
                double fps = st[2] / 100.0;
                set(st[0], st[1], fps > 0 && fps < 500 ? fps : 0, 0);
                return true;
            }
        };
        tasks.add(videoTask);

        tasks.add(new OneTask("DISPLAY 179 output frame rate", Call.GET_DISP_OUTPUT_FPS, Group.PICTURE, M_ROW) {
            @Override Sample create() {
                return new Sample("x50.outfps", Group.PICTURE, Fmt.HZ).label(R.string.s_out_fps).order(50);
            }

            @Override boolean run() throws Exception {
                int v = hal.outputFps();
                if (v < 1 || v > 240) return false;
                set(v);
                return true;
            }
        });

        tasks.add(new OneTask("DISPLAY 57 output timing", Call.GET_OUTPUT_TIMING_INFO, Group.PICTURE, M_ROW) {
            @Override Sample create() {
                return new Sample("x50.output", Group.PICTURE, Fmt.SIGNAL).label(R.string.s_output).order(35);
            }

            @Override boolean run() throws Exception {
                if (!hal.outputTiming(st) || !X50Structs.outputPlausible(st)) return false;
                set(st[4], st[5], st[6], 0);                // what the DLP chip gets, not the UI plane
                return true;
            }
        });

        hdmiTask = new OneTask("TV 38 HDMI info", Call.GET_HDMI_INFO, Group.PICTURE, M_ROW) {
            @Override boolean needed() { return X50Schedule.readsHdmiInfo(inputSource); }

            @Override Sample create() {
                return new Sample("x50.hdmi", Group.PICTURE, Fmt.SIGNAL).label(R.string.s_hdmi).order(40);
            }

            @Override boolean run() throws Exception {
                if (!hal.hdmiInfo(st) || !X50Structs.hdmiPlausible(st)) return false;
                set(st[0], st[1], st[4] / 100.0, st[2]);
                return true;
            }
        };
        tasks.add(hdmiTask);
    }

    private void setInput(int v) {
        inputSource = v;
        synchronized (reg.lock) {
            if (hdmiTask != null && !X50Schedule.readsHdmiInfo(v)) hdmiTask.hide();
            if (videoTask != null && !X50Schedule.readsVideoInfo(v)) videoTask.hide();
        }
    }

    // ------------------------------------------------------------------ MOTION 2
    private final class MotionTask extends Task {
        Sample tilt, accel, ang;

        MotionTask() {
            super("MOTION 2 motion", Call.GET_MOTION_DETECTION, Group.MOTION, M_ROW);
            deadIfAbsent = true;
        }

        @Override boolean run() throws Exception {
            if (!hal.motion(st)) return false;
            double deg = X50Structs.tiltDegrees(st[0], st[1], st[2]);
            if (accel == null) {
                tilt = reg.add(new Sample("x50.tilt", Group.MOTION, Fmt.DEG).label(R.string.s_tilt).order(10));
                accel = reg.add(new Sample("x50.accel", Group.MOTION, Fmt.XYZ_G).label(R.string.s_accel).order(20));
                // angData: raw values, meaning (angles or gyroscope) and scale unknown
                ang = reg.add(new Sample("x50.ang", Group.MOTION, Fmt.XYZ).label(R.string.s_ang_raw).order(21));
            }
            synchronized (reg.lock) {
                if (Double.isNaN(deg)) tilt.hide(); else tilt.set(deg);
                accel.set(X50Structs.accelG(st[0]), X50Structs.accelG(st[1]), X50Structs.accelZUpG(st[2]));
                ang.set(st[3], st[4], st[5]);
            }
            return true;
        }

        @Override boolean shownNow() {
            if (accel == null) return super.shownNow();
            return sampler.shows(tilt) || sampler.shows(accel) || sampler.shows(ang);
        }

        @Override void hide() {
            if (tilt != null) tilt.hide();
            if (accel != null) accel.hide();
            if (ang != null) ang.hide();
        }
    }

    // ------------------------------------------------------------------ TEMPERATURE 7 sensor probe
    /** One TEMPERATURE 7 call per step over {@link #SENSORS}; then one task per sensor that answered. */
    private final class SensorProbe extends Task {
        private final int[] temp = new int[16];
        private int k;

        SensorProbe() {
            super("TEMPERATURE 7 sensor probe", Call.GET_TEMPERATURE_FROM_SENSOR, Group.TEMPS, M_TEMP);
        }

        @Override boolean run() throws Exception {
            int idx = SENSORS[k];
            int v;
            try {
                v = hal.sensorTemperature(idx);
            } catch (android.os.RemoteException e) {
                v = 0;                                      // this index is skipped, the probe goes on
            }
            if (v >= 1 && v <= 127) temp[idx] = v;
            k++;
            if (k < SENSORS.length) {
                again = true;
                return true;
            }
            resolve();
            dead = true;
            return true;
        }

        @Override void hide() {}

        private void resolve() {
            long now = SystemClock.uptimeMillis();
            for (int idx : SENSORS) {
                if (temp[idx] <= 0) continue;
                Sample s = new Sample("x50.t" + idx, Group.TEMPS, Fmt.TEMP).graph();
                switch (idx) {
                    case 1: s.label(R.string.s_air).kind(Levels.K_AIR).order(30).compact(4, R.string.s_air_short); break;
                    case 2: s.label(R.string.s_dmd).kind(Levels.K_DMD).order(40).compact(2, R.string.s_dmd_short); break;
                    case 5: s.label(R.string.s_laser_r).kind(Levels.K_LED).order(50)
                            .compact(3, R.string.s_laser_short); break;
                    case 4: s.label(R.string.s_laser_g).kind(Levels.K_LED).order(51); break;
                    case 11: s.label(R.string.s_laser_r2).kind(Levels.K_LED).order(52); break;
                    case 6: s.label(R.string.s_bp).kind(Levels.K_BOARD).order(60); break;
                    case 12: s.label(R.string.s_focus_module).kind(Levels.K_BOARD).order(70); break;
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
            super("TEMPERATURE 7 sensor " + idx, Call.GET_TEMPERATURE_FROM_SENSOR, Group.TEMPS, sample.modes);
            this.idx = idx;
            this.sample = sample;
            probed = true;
        }

        @Override boolean shownNow() { return sampler.shows(sample); }

        @Override boolean run() throws Exception {
            int v = hal.sensorTemperature(idx);
            if (v < 1 || v > 127) return false;
            synchronized (reg.lock) {
                sample.set(v);
            }
            return true;
        }

        @Override void hide() { sample.hide(); }
    }

    // ------------------------------------------------------------------ TEMPERATURE 5 fan probe
    private final class FanProbe extends Task {
        private final int[] speed = new int[FANS.length];
        private int k;

        FanProbe() {
            super("TEMPERATURE 5 fan probe", Call.GET_FAN_ROTATION_RATE, Group.COOLING, M_FAN);
        }

        @Override boolean run() throws Exception {
            try {
                speed[k] = hal.fanSpeed(FANS[k]);
            } catch (android.os.RemoteException e) {
                speed[k] = 0;
            }
            k++;
            if (k < FANS.length) {
                again = true;
                return true;
            }
            int found = 0;
            for (int v : speed) if (v > 0) found++;
            long now = SystemClock.uptimeMillis();
            int n = 0;
            for (int i = 0; i < FANS.length; i++) {
                if (speed[i] <= 0) continue;
                n++;
                Sample s = new Sample("x50.fan" + FANS[i], Group.COOLING, Fmt.INT).order(20 + i);
                if (found == 1) s.label(R.string.s_fan); else s.label(R.string.s_fan_n, n);
                if (n == 1) s.compact(5, R.string.s_fan_short);
                FanTask t = new FanTask(FANS[i], reg.add(s));
                synchronized (reg.lock) {
                    t.sample.set(speed[i]);
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
            super("TEMPERATURE 5 fan " + fan, Call.GET_FAN_ROTATION_RATE, Group.COOLING, sample.modes);
            this.fan = fan;
            this.sample = sample;
            probed = true;
        }

        @Override boolean shownNow() { return sampler.shows(sample); }

        @Override boolean run() throws Exception {
            int v = hal.fanSpeed(fan);
            if (v < 0 || v > 65535) return false;
            synchronized (reg.lock) {
                sample.set(v);
            }
            return true;
        }

        @Override void hide() { sample.hide(); }
    }

    // ------------------------------------------------------------------ DISPLAY 176 laser hours
    /** Once per start: one row per light source of light_list with hours above 0 ("Laser N time"). */
    private final class LightUsageTask extends Task {
        private final int[] ids = new int[X50Structs.LIGHTS_MAX];
        private final long[] times = new long[X50Structs.LIGHTS_MAX];
        private Sample[] rows;

        LightUsageTask() {
            super("DISPLAY 176 light usage", Call.GET_LIGHT_USAGE_INFO, Group.LIGHT, M_ROW);
            deadIfAbsent = true;
            once = true;
        }

        @Override boolean run() throws Exception {
            String json = hal.lightUsageJson();
            if (!usageLogged) {
                usageLogged = true;
                Log.i(TAG, "DISPLAY 176 light usage (usage_time read as hours): " + brief(json));
            }
            int n = X50Structs.lightUsage(json, ids, times);
            if (n <= 0) return false;
            boolean any = false;
            for (int i = 0; i < n; i++) if (!Double.isNaN(X50Structs.lightHours(times[i]))) any = true;
            if (!any) return false;                       // the vendor keeps no hours: no rows
            if (rows == null) {
                rows = new Sample[n];
                for (int i = 0; i < n; i++) {
                    if (Double.isNaN(X50Structs.lightHours(times[i]))) continue;
                    rows[i] = reg.add(new Sample("x50.hours" + i, Group.LIGHT, Fmt.HOURS)
                            .label(R.string.s_laser_hours, n == 1 ? 1 : i + 1).order(60 + i));
                }
            }
            synchronized (reg.lock) {
                for (int i = 0; i < rows.length && i < n; i++) {
                    if (rows[i] != null) rows[i].set(X50Structs.lightHours(times[i]));
                }
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
        res = app.getResources();
        for (int i = 0; i < 10; i++) fanModes[i] = res.getString(R.string.fan_manual, i + 1);
        fanModes[10] = res.getString(R.string.fan_auto);
        fanModes[11] = res.getString(R.string.fan_test);
        ctD65 = res.getString(R.string.ct_d65);
        ct1 = res.getString(R.string.ct_1);
        srcAndroid = res.getString(R.string.src_android);
        srcHdmi1 = res.getString(R.string.src_hdmi, 1);
        srcHdmi2 = res.getString(R.string.src_hdmi, 2);
    }

    /** A vendor code without a known meaning: "code 14 (unknown)". */
    private String rawCode(int v) {
        return res.getString(R.string.w_raw_code, v);
    }

    private static String brief(String text) {
        if (text == null) return "null";
        String t = text.replace('\n', ' ').replace('\r', ' ');
        return t.length() <= USAGE_LOG_CHARS ? t : t.substring(0, USAGE_LOG_CHARS) + "...";
    }
}
