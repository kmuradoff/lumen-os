package org.lumen.sensors.core;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;

/**
 * Runs every source on ONE background HandlerThread ("lumen-sensors") and publishes the values.
 *
 * Lifecycle (any thread calls start / stop; the work itself is ordered on the sampler thread):
 *   start(words) -> thread created if needed -> every source start() -> tick
 *   tick: every source poll() -> publish: level + formatted text of each available sample, graph
 *         point, availability check (layout version) -> Publisher (Hub posts to the main thread)
 *         -> next tick after refreshMs
 *   stop() -> sources stop(), registry cleared; the thread quits LINGER_MS later unless started
 *         again (a quick screen off / on keeps the thread instead of making a new one).
 * While stopped nothing runs and no thread exists (after the linger).
 *
 * View state (mode, enabled groups, refresh, whether a HUD is on screen) comes from the UI through
 * {@link #setView}; sources ask {@link #shows} (a registered sample) or {@link #shown} (a value not
 * registered yet) to skip work nobody looks at (the XGIMI HAL source polls only what the current
 * mode draws).
 * A source that throws never ends the loop: each poll is guarded (logged once per source and start),
 * and the next tick is posted in a finally block.
 */
public final class Sampler {
    private static final String TAG = "LumenSensors";
    private static final long LINGER_MS = 5_000;

    public static final int VIEW_COMPACT = 0, VIEW_DETAILED = 1, VIEW_GRAPHS = 2;

    /** Called on the sampler thread after each publish. */
    public interface Publisher { void onPublished(); }

    private final Registry reg;
    private final Source[] sources;
    private final Publisher publisher;
    private final ArrayList<Runnable> viewListeners = new ArrayList<>();

    private HandlerThread thread;
    private Handler h;
    private boolean running;
    private int generation;

    private volatile Words words = Words.english();
    private volatile long refreshMs = 2000;
    private volatile int view = VIEW_COMPACT;
    private volatile int groups = Group.ALL;
    private volatile boolean hud;
    private volatile int seenGroups;

    private final StringBuilder sb = new StringBuilder(64);
    /** A poll failure is logged once per source and start (sampler thread). */
    private final boolean[] pollLogged;
    private boolean publishLogged;

    public Sampler(Registry reg, Source[] sources, Publisher publisher) {
        this.reg = reg;
        this.sources = sources;
        this.publisher = publisher;
        this.pollLogged = new boolean[sources.length];
    }

    // ================================================================== lifecycle
    public synchronized void start(Words w) {
        if (w != null) words = w;
        if (running) return;
        running = true;
        generation++;
        if (thread == null) {
            thread = new HandlerThread("lumen-sensors", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            h = new Handler(thread.getLooper());
        }
        h.post(() -> {
            seenGroups = 0;
            java.util.Arrays.fill(pollLogged, false);
            publishLogged = false;
            for (Source s : sources) {
                try {
                    s.start(this);
                } catch (Throwable t) {
                    Log.w(TAG, s.getClass().getSimpleName() + " start: " + t);
                }
            }
            h.removeCallbacks(tick);
            tick.run();
        });
        Log.i(TAG, "sampler started");
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        final int gen = ++generation;
        final Handler hh = h;
        hh.removeCallbacks(tick);
        hh.post(() -> {
            hh.removeCallbacks(tick);
            for (Source s : sources) {
                try {
                    s.stop(this);
                } catch (Throwable t) {
                    Log.w(TAG, s.getClass().getSimpleName() + " stop: " + t);
                }
            }
            reg.clear();
            publisher.onPublished();
        });
        hh.postDelayed(() -> {
            synchronized (Sampler.this) {
                if (running || generation != gen || thread == null) return;
                thread.quitSafely();
                thread = null;
                h = null;
            }
        }, LINGER_MS);
        Log.i(TAG, "sampler stopped");
    }

    /** True between start and stop (sources check it before scheduling more work). */
    public synchronized boolean isRunning() {
        return running;
    }

    // ================================================================== view state
    /**
     * Mode (VIEW_*), enabled groups, refresh period, and whether a HUD is on screen (false: only
     * the settings screen is open, so sources do their probing but skip periodic HAL polling).
     */
    public void setView(int view, int groups, long refreshMs, boolean hud) {
        boolean refreshChanged = refreshMs != this.refreshMs;
        this.view = view;
        this.groups = groups;
        this.refreshMs = Math.max(500, refreshMs);
        this.hud = hud;
        Handler hh;
        synchronized (this) {
            hh = running ? h : null;
        }
        if (hh == null) return;
        hh.post(() -> {
            if (refreshChanged) {
                synchronized (reg.lock) {
                    ArrayList<Sample> all = reg.samplesLocked();
                    for (int i = 0; i < all.size(); i++) all.get(i).clearHistory();
                }
                hh.removeCallbacks(tick);
                hh.post(tick);
            }
            for (int i = 0; i < viewListeners.size(); i++) viewListeners.get(i).run();
        });
    }

    /** A source wants to hear about view changes (runs on the sampler thread). Call from start(). */
    public void addViewListener(Runnable r) {
        if (!viewListeners.contains(r)) viewListeners.add(r);
    }

    public void removeViewListener(Runnable r) {
        viewListeners.remove(r);
    }

    /**
     * True when a HUD is on screen and its mode could show a value of {@code group} with one of
     * {@code sampleModes}. For a value that has no Sample yet; a registered one asks {@link #shows}.
     */
    public boolean shown(int group, int sampleModes) {
        if (!hud || !Group.in(groups, group)) return false;
        int bit = view == VIEW_DETAILED ? Sample.MODE_DETAILED
                : (view == VIEW_GRAPHS ? Sample.MODE_GRAPH : Sample.MODE_COMPACT);
        return (sampleModes & bit) != 0;
    }

    /**
     * True when the HUD on screen draws this sample in its current mode (the same choice as HudView:
     * the compact strip takes only samples with a compact rank). Availability does not count: a hidden
     * row is still read, so it can come back.
     */
    public boolean shows(Sample s) {
        if (!hud || !Group.in(groups, s.group)) return false;
        int v = view;
        if (v == VIEW_DETAILED) return (s.modes & Sample.MODE_DETAILED) != 0;
        if (v == VIEW_GRAPHS) return (s.modes & Sample.MODE_GRAPH) != 0;
        return s.compactRank > 0 && (s.modes & Sample.MODE_COMPACT) != 0;
    }

    /** The language changed: units and words for the next publish. */
    public void setWords(Words w) {
        if (w != null) words = w;
    }

    public long refreshMs() { return refreshMs; }

    public Registry registry() { return reg; }

    public Words words() { return words; }

    /** The sampler thread's handler (sources schedule their own work on it). Sampler thread only. */
    public Handler handler() { return h; }

    /** Groups that had an available sample at some publish since the last start. */
    public int seenGroups() { return seenGroups; }

    // ================================================================== tick / publish
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Handler hh = h;
            if (hh == null) return;
            long now = SystemClock.uptimeMillis();
            try {
                for (int i = 0; i < sources.length; i++) {
                    try {
                        sources[i].poll(Sampler.this, now);
                    } catch (Throwable t) {
                        if (!pollLogged[i]) {
                            pollLogged[i] = true;
                            Log.w(TAG, sources[i].getClass().getSimpleName() + " poll (logged once): " + t);
                        }
                    }
                }
                publish();
            } catch (Throwable t) {
                if (!publishLogged) {
                    publishLogged = true;
                    Log.w(TAG, "publish (logged once): " + t);
                }
            } finally {
                hh.postAtTime(this, now + refreshMs);       // the loop never ends on a throw
            }
        }
    };

    private void publish() {
        Words w = words;
        int seen = 0;
        synchronized (reg.lock) {
            ArrayList<Sample> all = reg.samplesLocked();
            boolean changed = false;
            for (int i = 0; i < all.size(); i++) {
                Sample s = all.get(i);
                if (s.available != s.shownAvailable) {
                    s.shownAvailable = s.available;
                    changed = true;
                }
                if (!s.available) continue;
                seen |= 1 << s.group;
                s.level = Levels.level(s.kind, s.value);
                sb.setLength(0);
                Fmt.format(sb, s, w);
                int n = Math.min(sb.length(), s.chars.length);
                sb.getChars(0, n, s.chars, 0);
                s.len = n;
                if (s.hist != null && !Double.isNaN(s.value)) s.push((float) s.value);
            }
            if (changed) reg.bumpLayoutLocked();
        }
        seenGroups |= seen;
        publisher.onPublished();
    }
}
