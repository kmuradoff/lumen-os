package org.z9x.projector.ambient;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.media.AudioManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.z9x.projector.Hal;
import org.z9x.projector.Ui;
import org.z9x.projector.hal.Play6Gmpf;
import org.z9x.projector.ui.OverlayHost;
import org.z9x.projector.ui.Panel;

/**
 * Play 6 ambient light (P3 item 21; research of the stock v1.7.116 path, docs/play6/TASKS.md "P3 stock features").
 *
 * What stock does on the G0058: the stand has NO separate atmosphere LED (system.ini has no atmosLightCfgPath, so
 * libxgimi creates no AtmosLightControl; lamplight.ini is the single MCU status LED). The "ambient speaker" light
 * of stock (SystemUI AmbientLightSettingActivity + com.xgimi.doubanfm AtmosphereLightService) is the projector
 * itself drawing a full-screen coloured animation: colours orange, blue, yellow, green, pink, purple, colourful
 * (cycles the six) and none (picture off, IGmpf 195(false)); effects standard (steady, pulses with music), always
 * (steady), rhythm (breathing 2.5 s in, 4 s hold, 2.5 s out; pulses with music). The choice lives in
 * Settings.Global ambient_light_data as {"color":..., "mode":...} (default orange / standard); stock UP / DOWN
 * cycle the colour, LEFT / RIGHT / OK send the media keys 88 / 87 / 85. No IGmpf2 atmosphere (95..112) call is
 * made by stock on the Play 6, and none is made here (the setters reach a NULL device on the G0058; status 4 is
 * a factory state).
 *
 * Lumen opens it from the quick panel (Ambient light page) instead of the closing of the device (that trigger is
 * the vendor music-player event, IGmpf 227, not reviewed: see TASKS.md). The same Global key and values are used,
 * so a choice survives the way stock keeps it. Differences: "none" is not offered here (the Bluetooth speaker
 * mode has its own "light off"); the music pulse follows a fixed beat while media plays (AudioManager.isMusicActive,
 * no RECORD_AUDIO for a Visualizer); the night light use: an optional lower projector brightness while the light
 * shows (IGmpf 176, only ever lower than the level read with 177 before, so the vendor's battery power saving is
 * never turned off by it), the level read back and restored at the end only while it is still ours (a marker
 * covers a process death).
 *
 * HAL: IGmpf 177 read, 176(1..10) write, both existing Play6Gmpf methods, on p6-hal. Log tag P6Ambient.
 */
public final class AmbientLight extends Panel {
    static final String TAG = "P6Ambient";

    /** Stock Settings.Global key and JSON (SystemUI SystemUtils.setAmbientLight / doubanfm AmbientBean). */
    public static final String GLOBAL_KEY = "ambient_light_data";
    public static final String[] COLORS = {"orange", "blue", "yellow", "green", "pink", "purple", "colorful", "none"};
    public static final String[] MODES = {"standard", "always", "rhythm"};
    /** Stock doubanfm colors.xml: main and mask (second) colour per colour name, in COLORS order (without colorful). */
    private static final int[] MAIN = {0xFFFC7900, 0xFF006CFF, 0xFFF4DD00, 0xFF20FF00, 0xFFF57EB8, 0xFF7C00FF};
    private static final int[] MASK = {0xFFEEED00, 0xFF20FF3A, 0xFFF07300, 0xFF0C79EB, 0xFFFF597B, 0xFF00DFFF};
    private static final int COLORFUL = 6;
    /** Stock "none": no light (the picture off, IGmpf 195(false) through power/Lamp, back on at the end). */
    public static final int NONE = 7;

    private static final String PREFS = "p6_ambient";
    /** Projector brightness while the light shows, 1..10; 0 = leave it. */
    private static final String K_LEVEL = "level";
    /** Marker: {saved level * 100 + our level}; 176(saved) owed while 177 still reads our level. */
    private static final String K_RESTORE = "restore";
    private static final long RECOVER_DELAY_MS = 7_000;

    private static final long BREATH_IN_MS = 2_500, BREATH_HOLD_MS = 4_000, BREATH_OUT_MS = 2_500;
    private static final long PULSE_MS = 250;
    private static final long CYCLE_MS = 6_000, CROSSFADE_MS = 1_500;
    private static final long MUSIC_POLL_MS = 1_000;
    private static final long COLOR_KEY_GAP_MS = 600;               // stock AmbientUtils debounce
    private static final float BREATH_LOW = 0.15f, PULSE_LOW = 0.6f;

    private static volatile AmbientLight sOpen;

    private final Context app;
    private int color;
    private int mode;

    private AmbientLight(Context app) {
        this.app = app;
    }

    // ================================================================== settings (any thread)
    /** The stored colour index into COLORS (stock default orange; unknown reads as orange). */
    public static int colorIndex(Context c) { return indexOf(COLORS, read(c, "color"), 0); }

    /** The stored effect index into MODES (stock default standard). */
    public static int modeIndex(Context c) { return indexOf(MODES, read(c, "mode"), 0); }

    public static void setColor(Context c, int i) {
        if (i >= 0 && i < COLORS.length) write(c, COLORS[i], MODES[modeIndex(c)]);
    }

    public static void setMode(Context c, int i) {
        if (i >= 0 && i < MODES.length) write(c, COLORS[colorIndex(c)], MODES[i]);
    }

    /** Brightness while the light shows (1..10), 0 = unchanged. */
    public static int level(Context c) {
        int v = prefs(c).getInt(K_LEVEL, 0);
        return v >= 1 && v <= 10 ? v : 0;
    }

    public static void setLevel(Context c, int v) {
        prefs(c).edit().putInt(K_LEVEL, v >= 1 && v <= 10 ? v : 0).apply();
    }

    private static String read(Context c, String field) {
        try {
            String s = Settings.Global.getString(c.getContentResolver(), GLOBAL_KEY);
            if (s == null || s.isEmpty()) return null;
            return new JSONObject(s).optString(field, null);
        } catch (Throwable t) {
            Log.w(TAG, GLOBAL_KEY + " unreadable: " + t);
            return null;
        }
    }

    private static void write(Context c, String color, String mode) {
        try {
            JSONObject o = new JSONObject();
            o.put("color", color);
            o.put("mode", mode);
            Settings.Global.putString(c.getContentResolver(), GLOBAL_KEY, o.toString());
            Log.i(TAG, GLOBAL_KEY + " = " + o);
        } catch (Throwable t) {
            Log.w(TAG, GLOBAL_KEY + " not written: " + t);
        }
    }

    private static int indexOf(String[] a, String v, int dflt) {
        if (v != null) for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return dflt;
    }

    private static SharedPreferences prefs(Context c) {
        try {
            return c.getApplicationContext().createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    // ================================================================== open / close (main thread)
    public static boolean isOpen() { return sOpen != null; }

    /** Shows the ambient light. Main thread. */
    public static void open(Context ctx) {
        open(ctx, false);
    }

    /**
     * The night light (owner 2026-10-21, quick panel tile "Night light"): the ambient light at once with the last
     * colour (a lit one: "None" counts as orange), the night light's own effect (default "Always on", a calm light)
     * and the brightness choice of the ambient light (never brighter). BACK turns it off. Main thread.
     */
    public static void openNight(Context ctx) {
        open(ctx, true);
    }

    /** The night light's effect index into MODES (default 1 = always on). */
    public static int nightModeIndex(Context c) {
        int v = prefs(c).getInt(K_NIGHT_MODE, 1);
        return v >= 0 && v < MODES.length ? v : 1;
    }

    public static void setNightMode(Context c, int i) {
        if (i >= 0 && i < MODES.length) prefs(c).edit().putInt(K_NIGHT_MODE, i).apply();
    }

    /** The colour of the night light: the last colour, "None" as orange. */
    public static int nightColorIndex(Context c) {
        int v = colorIndex(c);
        return v == NONE ? 0 : v;
    }

    private static final String K_NIGHT_MODE = "night_effect";

    private static void open(Context ctx, boolean night) {
        Context app = ctx.getApplicationContext();
        if (sOpen != null) return;
        AmbientLight p = new AmbientLight(app);
        p.color = night ? nightColorIndex(app) : colorIndex(app);
        p.mode = night ? nightModeIndex(app) : modeIndex(app);
        if (!OverlayHost.get(app).show(p)) {
            Log.w(TAG, "window could not be added");
            return;
        }
        sOpen = p;
        Log.i(TAG, (night ? "night light " : "") + "on: color " + COLORS[p.color] + ", effect " + MODES[p.mode] + ", level " + level(app));
        final int want = level(app);
        if (want > 0) Hal.run((g, g2) -> dim(app, g, want));
    }

    /** Closes the ambient light if it is shown (the speaker mode, a HOME press). Main thread. */
    public static void close() {
        AmbientLight p = sOpen;
        if (p != null) p.dismiss();
    }

    /**
     * Play6App at the start gate: a brightness that a dead process left lowered is restored after the next IGmpf
     * (re)connect, only while 177 still reads the level we set.
     */
    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Hal.addConnectedListener(() -> {
            if (prefs(app).getInt(K_RESTORE, 0) == 0) return;
            Hal.runDelayed((g, g2) -> { if (sOpen == null) restore(app, g, "connect"); }, RECOVER_DELAY_MS);
        });
    }

    /** p6-hal: 177, then 176(want) only when want is lower; marker first. */
    private static void dim(Context app, Play6Gmpf g, int want) {
        try {
            int cur = g.getLampLevel();                                  // 177
            if (cur < 1 || cur > 10 || want >= cur) {
                Log.i(TAG, "brightness: 177 reads " + cur + ", wanted " + want + ": left as it is");
                return;
            }
            prefs(app).edit().putInt(K_RESTORE, cur * 100 + want).commit();
            g.setLampLevel(want);                                        // 176
            Log.i(TAG, "brightness: 176(" + want + ") (was " + cur + "), 177 now " + g.getLampLevel());
            if (sOpen == null) restore(app, g, "closed meanwhile");      // closed before this task ran
        } catch (Throwable t) {
            Log.w(TAG, "brightness: " + t);
        }
    }

    /** p6-hal: 176(saved) when 177 still reads our level; the marker is cleared either way once read. */
    private static void restore(Context app, Play6Gmpf g, String who) {
        int m = prefs(app).getInt(K_RESTORE, 0);
        if (m == 0) return;
        int saved = m / 100, ours = m % 100;
        try {
            int cur = g.getLampLevel();                                  // 177
            if (cur == ours && saved >= 1 && saved <= 10) {
                g.setLampLevel(saved);                                   // 176
                Log.i(TAG, who + ": brightness back to " + saved + " (177 now " + g.getLampLevel() + ")");
            } else {
                Log.i(TAG, who + ": 177 reads " + cur + ", not our " + ours + ": left as it is");
            }
            prefs(app).edit().putInt(K_RESTORE, 0).commit();
        } catch (Throwable t) {
            Log.w(TAG, who + ": brightness restore: " + t + " (marker kept)");
        }
    }

    // ================================================================== window
    private AmbientView view;

    @Override
    protected WindowManager.LayoutParams onCreateLayoutParams(Context c) {
        return fullscreen(c);
    }

    /** Full-screen focusable overlay over the real display size, screen kept on (also the speaker mode's). */
    public static WindowManager.LayoutParams fullscreen(Context c) {
        WindowManager.LayoutParams lp = OverlayHost.fullscreenParams(true);
        try {
            android.graphics.Point real = new android.graphics.Point();
            c.getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(real);
            if (real.x > 0 && real.y > 0) { lp.width = real.x; lp.height = real.y; }
        } catch (Throwable t) {
            Log.w(TAG, "real display size: " + t);
        }
        lp.x = 0;
        lp.y = 0;
        lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        return lp;
    }

    @Override
    protected long autoHideMs() { return 0; }                            // stays until BACK / HOME

    @Override
    protected View onCreateView(Context c) {
        view = new AmbientView(c, color, mode);
        return view;
    }

    /** This window turned the lamp off (colour "None"): Lamp.on owed at the end. */
    private boolean lampOff;

    @Override
    protected void onShown(View root) {
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(600).start();
        view.start();
        if (color == NONE && !org.z9x.projector.power.StandbyController.isActive()) {
            lampOff = true;
            org.z9x.projector.power.Lamp.off("ambient none", on -> Log.i(TAG, "none: lamp off -> 196 reads " + on));
        }
    }

    @Override
    protected void animateOut(View root, Runnable end) {
        root.animate().cancel();
        root.animate().alpha(0f).setDuration(300).withEndAction(end).start();
    }

    @Override
    protected void onDismissed() {
        if (sOpen == this) sOpen = null;
        if (view != null) view.stop();
        Log.i(TAG, "off");
        if (lampOff && !org.z9x.projector.power.StandbyController.isActive()) {
            org.z9x.projector.power.Lamp.on("ambient end", on -> Log.i(TAG, "lamp on -> 196 reads " + on));
        }
        if (prefs(app).getInt(K_RESTORE, 0) != 0 && !Hal.run((g, g2) -> restore(app, g, "off"))) {
            Log.w(TAG, "brightness restore not queued: left to the connect hook");
        }
    }

    /** Stock keys: UP / DOWN colour, LEFT / RIGHT / OK media keys 88 / 87 / 85; BACK closes (Panel). */
    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        boolean used = view != null && view.onKey(ev);
        if (used && lampOff && view.color() != NONE) {                    // left "None" with UP / DOWN: light again
            lampOff = false;
            org.z9x.projector.power.Lamp.on("ambient color", on -> Log.i(TAG, "lamp on -> 196 reads " + on));
        }
        return used;
    }

    /**
     * RECORD_AUDIO for the Visualizer (stock doubanfm declares it for the same rhythm): granted to ourselves once
     * with GRANT_RUNTIME_PERMISSIONS (signature, the platform key), as RemoteAutoPair grants its Bluetooth ones.
     */
    static void grantRecordAudio(Context app) {
        String perm = android.Manifest.permission.RECORD_AUDIO;
        if (app.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        try {
            app.getPackageManager().grantRuntimePermission(app.getPackageName(), perm, android.os.Process.myUserHandle());
            Log.i(TAG, "RECORD_AUDIO granted (for the Visualizer)");
        } catch (Throwable t) {
            Log.w(TAG, "RECORD_AUDIO not granted: " + t);
        }
    }

    /** Media key to the active media session (the stock 88 / 87 / 85). */
    public static void sendMedia(Context app, int code) {
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
            Log.i(TAG, "media key " + code);
        } catch (Throwable e) {
            Log.w(TAG, "media key: " + e);
        }
    }

    // ================================================================== the light (main thread)
    /**
     * The coloured light itself: two full-screen glows (the back one takes a new colour, then they cross-fade) and the
     * effect, re-planned every MUSIC_POLL_MS: media playing with standard / rhythm = a pulse on a fixed beat; rhythm
     * without media = breath (stock MixBreathCell 2.5 s / 4 s / 2.5 s); otherwise steady. Colourful moves on every
     * CYCLE_MS. Only view alpha animates (GPU layers): cheap on the Mali-G52. Also used inside the speaker mode.
     */
    public static final class AmbientView extends FrameLayout {
        private final Context app;
        private int color, mode, cycle, phase;
        private long lastColorKey, phaseStart, nextCycle;
        private Glow front, back;
        private boolean running;
        private final Runnable tick = this::tick;

        public AmbientView(Context c, int color, int mode) {
            super(c);
            this.app = c.getApplicationContext();
            this.color = Math.max(0, Math.min(COLORS.length - 1, color));
            this.mode = Math.max(0, Math.min(MODES.length - 1, mode));
            setBackgroundColor(Color.BLACK);
            back = new Glow(c);
            front = new Glow(c);
            addView(back);
            addView(front);
            front.set(shownColor());
        }

        public void start() {
            if (running) return;
            running = true;
            Ui.main().post(tick);
        }

        public void stop() {
            running = false;
            Ui.main().removeCallbacks(tick);
            front.animate().cancel();
            back.animate().cancel();
            stopVisualizer();
        }

        // ---- the music rhythm, as stock doubanfm AtmosphereLightViewModel.startVisual: Visualizer on the output
        // mix (session 0, RECORD_AUDIO and MODIFY_AUDIO_SETTINGS as stock), FFT at 3/4 of the maximum rate, the
        // magnitudes of bins 2..4 summed (fftDataAnalyze), stock MixRotateLightCell: min(sum / 310, 1) ^ 1.2,
        // updates at most every 50 ms. Without the permission or a Visualizer the pulse falls back to a fixed beat.
        private android.media.audiofx.Visualizer visualizer;
        private volatile float level = -1f;                              // 0..1 from the FFT, -1 = no data
        private long lastVisual;

        private void startVisualizer() {
            if (visualizer != null) return;
            try {
                grantRecordAudio(app);
                android.media.audiofx.Visualizer v = new android.media.audiofx.Visualizer(0);
                v.setEnabled(false);
                int[] range = android.media.audiofx.Visualizer.getCaptureSizeRange();
                if (range != null && range.length > 1) v.setCaptureSize(range[1]);
                v.setDataCaptureListener(new android.media.audiofx.Visualizer.OnDataCaptureListener() {
                    @Override public void onWaveFormDataCapture(android.media.audiofx.Visualizer vv, byte[] b, int rate) { }
                    @Override public void onFftDataCapture(android.media.audiofx.Visualizer vv, byte[] fft, int rate) {
                        level = rhythm(fft);
                    }
                }, android.media.audiofx.Visualizer.getMaxCaptureRate() * 3 / 4, false, true);
                v.setEnabled(true);
                visualizer = v;
                Log.i(TAG, "visualizer on (capture " + v.getCaptureSize() + ")");
            } catch (Throwable t) {
                Log.w(TAG, "visualizer not available, fixed beat: " + t);
                visualizer = null;
            }
        }

        private void stopVisualizer() {
            if (visualizer == null) return;
            try { visualizer.setEnabled(false); visualizer.release(); } catch (Throwable ignored) { }
            visualizer = null;
            level = -1f;
            Log.i(TAG, "visualizer off");
        }

        /** Stock fftDataAnalyze + bins 2..4 + MixRotateLightCell scaling. */
        private static float rhythm(byte[] fft) {
            if (fft == null || fft.length < 12) return -1f;
            float sum = 0f;
            for (int bin = 2; bin <= 4; bin++) sum += (float) Math.hypot(fft[2 * bin], fft[2 * bin + 1]);
            float f = Math.min(1f, sum / 310f);
            return (float) Math.pow(f, 1.2d);
        }

        /** UP / DOWN: next / previous colour (stored as stock does); LEFT / RIGHT / OK: media keys. */
        public boolean onKey(KeyEvent ev) {
            int code = ev.getKeyCode();
            int media = code == KeyEvent.KEYCODE_DPAD_LEFT ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    : code == KeyEvent.KEYCODE_DPAD_RIGHT ? KeyEvent.KEYCODE_MEDIA_NEXT
                    : code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE : 0;
            if (media != 0) {
                if (ev.getAction() == KeyEvent.ACTION_UP) sendMedia(app, media);
                return true;
            }
            if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return true;
            long now = SystemClock.uptimeMillis();
            if (now - lastColorKey < COLOR_KEY_GAP_MS) return true;
            lastColorKey = now;
            int n = COLORS.length - 1;                                  // the lit colours; "None" is chosen in the page
            int from = color == NONE ? 0 : color;
            color = (from + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : n - 1)) % n;
            cycle = 0;
            setColor(app, color);
            Log.i(TAG, "color " + COLORS[color]);
            crossfade(shownColor());
            return true;
        }

        public int color() { return color; }

        private int shownColor() { return color == COLORFUL ? cycle : color == NONE ? -1 : color; }

        private boolean isMusic() {
            try { return app.getSystemService(AudioManager.class).isMusicActive(); } catch (Throwable t) { return false; }
        }

        private void tick() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            boolean music = isMusic();
            boolean pulse = music && mode != 1;
            boolean breath = !music && mode == 2;
            if (color == COLORFUL) {
                if (nextCycle == 0) nextCycle = now + CYCLE_MS;
                else if (now >= nextCycle) {
                    cycle = (cycle + 1) % MAIN.length;
                    crossfade(cycle);
                    nextCycle = now + CYCLE_MS;
                }
            }
            long next = MUSIC_POLL_MS;
            if (color == NONE) {                                         // no light: nothing animates
                front.setAlpha(0f);
                Ui.main().postDelayed(tick, MUSIC_POLL_MS);
                return;
            }
            if (pulse) startVisualizer(); else stopVisualizer();
            float lv = level;
            if (pulse && visualizer != null && lv >= 0f) {
                if (now - lastVisual >= 50) {
                    lastVisual = now;
                    front.animate().cancel();
                    front.setAlpha(PULSE_LOW + (1f - PULSE_LOW) * lv);
                }
                Ui.main().postDelayed(tick, 50);
                phase = 0;
                return;
            }
            if (pulse) {
                final Glow f = front;
                f.animate().cancel();
                f.animate().alpha(PULSE_LOW).setDuration(PULSE_MS).setInterpolator(new LinearInterpolator())
                        .withEndAction(() -> { if (running) f.animate().alpha(1f).setDuration(PULSE_MS * 3).start(); })
                        .start();
                next = PULSE_MS * 4;                                     // a fixed beat (about 120 per minute)
                phase = 0;
            } else if (breath) {
                if (now - phaseStart >= phaseLength()) {
                    phase = (phase + 1) % 3;
                    phaseStart = now;
                    float to = phase == 2 ? BREATH_LOW : 1f;
                    if (phase != 1) front.animate().alpha(to).setDuration(phaseLength()).start();
                }
                next = Math.min(MUSIC_POLL_MS, Math.max(50, phaseLength() - (now - phaseStart)));
            } else if (front.getAlpha() < 1f) {
                front.animate().alpha(1f).setDuration(400).start();
                phase = 0;
            }
            Ui.main().postDelayed(tick, next);
        }

        private long phaseLength() {
            return phase == 0 ? BREATH_IN_MS : phase == 1 ? BREATH_HOLD_MS : BREATH_OUT_MS;
        }

        /** The back layer takes the new colour and fades in on top, the old one fades out; then they swap. */
        private void crossfade(int idx) {
            final Glow oldFront = front;
            back.set(idx);
            back.setAlpha(0f);
            back.bringToFront();
            front = back;
            back = oldFront;
            front.animate().alpha(1f).setDuration(CROSSFADE_MS).start();
            oldFront.animate().alpha(0f).setDuration(CROSSFADE_MS).start();
        }
    }

    /** One full-screen glow: the main colour with a soft radial light of the mask colour (stock two colours). */
    private static final class Glow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int idx = -1;

        Glow(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_HARDWARE, null);                     // alpha animates on the GPU layer
        }

        void set(int i) {
            idx = i < 0 ? -1 : Math.min(MAIN.length - 1, i);
            shader();
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            shader();
        }

        private void shader() {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || idx < 0) return;
            paint.setShader(new RadialGradient(w * 0.5f, h * 0.55f, Math.max(w, h) * 0.6f,
                    new int[] {MASK[idx], MAIN[idx], darker(MAIN[idx])}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        }

        private static int darker(int c) {
            return Color.argb(255, Color.red(c) / 3, Color.green(c) / 3, Color.blue(c) / 3);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (idx < 0) return;
            c.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }
}
