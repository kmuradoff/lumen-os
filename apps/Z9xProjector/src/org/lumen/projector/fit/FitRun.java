package org.lumen.projector.fit;

/**
 * Fit to my screen size: the run (docs/fit_screen/DESIGN.md section 7), the only caller of {@link FitDevice}.
 * Plain Java; runs on a worker thread of the app, never on the main thread or the HAL thread (the adapter posts
 * each call to the HAL thread and the auto keystone runs on the keystone thread).
 *
 *  1. supported(), N == 14                      nothing else is called otherwise
 *  2. mount mode 0..3
 *  3. 3 ToF reads 1 s apart (the whole budget), median; spread above 30 mm = someone in front of the sensor
 *  4. pitch (adapter: accelerometer average)
 *  5. the stock auto keystone; then the lens must be at progress 0 (the Z9X AK homes it); else one home walk
 *  6. corners (null or invalid: the inscribed-rectangle estimator)
 *  7. plan (FitPlanner)
 *  8. walk: inside ONE zoom session, target times stepSmaller(), each read back; a read-back that is not the next
 *     position stops at once; cancel stops after the running move. Never a bigger, multi-step or home move here.
 *  9. autofocus when the lens moved
 * 10. corners and progress read again, the size at the final position for the result card.
 * {@link FitCalibration} uses steps 1-6, {@link #walkSmallerTo} and {@link #focus} the same way.
 */
public final class FitRun {
    public static final int MAX_DISTANCE_READS = 3;
    public static final int DISTANCE_MIN_MM = 500, DISTANCE_MAX_MM = 4800, DISTANCE_SPREAD_MM = 30;
    public static final long DISTANCE_GAP_MS = 1000;
    public static final double PITCH_MAX_DEG = 30;

    public enum Phase { CHECK, DISTANCE, KEYSTONE, ZOOM, FOCUS, DONE }

    public enum Status {
        DONE, UNSUPPORTED, NO_SCREEN, NO_CALIBRATION, MOUNT_UNKNOWN, DISTANCE_RANGE, DISTANCE_UNSTABLE,
        PITCH_UNKNOWN, PITCH_RANGE, KEYSTONE_FAILED, NOT_HOME, LENS_UNEXPECTED, CANCELLED, ERROR
    }

    public interface Listener {
        /** Progress; for ZOOM a = moves done, b = moves planned. Called on the run's thread. */
        void onPhase(Phase phase, int a, int b);
    }

    public interface Sleeper { void sleep(long ms) throws InterruptedException; }

    /** What steps 1-6 found. */
    public static final class Prepared {
        public int mount = -1, progress = -1;
        public double distanceMm = Double.NaN, pitchDeg = Double.NaN;
        public int[] distances = new int[0];
        public int[] corners;
        public FitGeometry.Estimator estimator;
        public FitGeometry geometry;
    }

    public static final class Result {
        public Status status;
        public Prepared prepared;
        public FitPlanner.Plan plan;
        /** Position at the end (-1 unknown) and the size there. */
        public int finalProgress = -1;
        public FitGeometry.Size finalSize;
        public String detail = "";

        Result(Status s) { status = s; }

        @Override public String toString() {
            return status + (detail.isEmpty() ? "" : " (" + detail + ")") + ", final position " + finalProgress
                    + (finalSize != null ? ", size " + finalSize : "");
        }
    }

    private final FitDevice dev;
    private final Sleeper sleeper;
    private final Listener listener;
    private volatile boolean cancelled;
    private int distanceReads;
    private Prepared prep;

    public FitRun(FitDevice dev, Sleeper sleeper, Listener listener) {
        this.dev = dev;
        this.sleeper = sleeper != null ? sleeper : ms -> Thread.sleep(ms);
        this.listener = listener != null ? listener : (p, a, b) -> { };
    }

    /** Stop after the running step (any thread). */
    public void cancel() { cancelled = true; }

    public boolean isCancelled() { return cancelled; }

    public Prepared prepared() { return prep; }

    public int distanceReads() { return distanceReads; }

    /** One ToF read inside the run's budget (3); the 4th is refused without calling the device. */
    int readDistance() {
        if (distanceReads >= MAX_DISTANCE_READS) throw new IllegalStateException("distance budget used up");
        distanceReads++;
        return dev.readDistanceMm();
    }

    /** Steps 1-6. Returns null when everything is ready (see {@link #prepared()}), else the stopping result. */
    public Result prepare() {
        listener.onPhase(Phase.CHECK, 0, 0);
        if (!dev.supported()) return new Result(Status.UNSUPPORTED);
        if (dev.positionCount() != ZoomModel.POSITIONS) {
            Result r = new Result(Status.UNSUPPORTED);
            r.detail = "positions " + dev.positionCount();
            return r;
        }
        Prepared p = new Prepared();
        prep = p;
        p.mount = dev.mountMode();
        if (p.mount < 0 || p.mount > 3) return stop(Status.MOUNT_UNKNOWN, "mount " + p.mount);
        listener.onPhase(Phase.DISTANCE, 0, 0);
        int[] d = new int[MAX_DISTANCE_READS];
        for (int i = 0; i < d.length; i++) {
            if (cancelled) return stop(Status.CANCELLED, "");
            if (i > 0 && !pause(DISTANCE_GAP_MS)) return stop(Status.CANCELLED, "");
            d[i] = readDistance();
            if (d[i] < DISTANCE_MIN_MM || d[i] > DISTANCE_MAX_MM) {
                p.distances = java.util.Arrays.copyOf(d, i + 1);
                return stop(Status.DISTANCE_RANGE, "read " + d[i] + " mm");
            }
        }
        p.distances = d.clone();
        int[] s = d.clone();
        java.util.Arrays.sort(s);
        if (s[s.length - 1] - s[0] > DISTANCE_SPREAD_MM) return stop(Status.DISTANCE_UNSTABLE, "spread " + (s[s.length - 1] - s[0]) + " mm");
        p.distanceMm = s[s.length / 2];
        p.pitchDeg = dev.readPitchDeg();
        if (Double.isNaN(p.pitchDeg)) return stop(Status.PITCH_UNKNOWN, "");
        if (Math.abs(p.pitchDeg) > PITCH_MAX_DEG) return stop(Status.PITCH_RANGE, String.format(java.util.Locale.ROOT, "%.1f deg", p.pitchDeg));
        if (cancelled) return stop(Status.CANCELLED, "");
        listener.onPhase(Phase.KEYSTONE, 0, 0);
        if (!dev.runAutoKeystone()) return stop(Status.KEYSTONE_FAILED, "");
        p.progress = dev.readProgress();
        if (p.progress != 0) {
            dev.homeZoom();
            p.progress = dev.readProgress();
            if (p.progress != 0) return stop(Status.NOT_HOME, "position " + p.progress);
        }
        int[] c = dev.readKeystoneCorners();
        p.estimator = FitGeometry.estimatorFor(c);
        p.corners = p.estimator == FitGeometry.Estimator.CORNERS ? c.clone() : null;
        p.geometry = new FitGeometry(p.distanceMm, p.pitchDeg);
        return null;
    }

    /**
     * Step 8: from the current position down to {@code target} with single "smaller" moves in one session.
     * Returns null when the lens reached the target, else the stopping result (LENS_UNEXPECTED, CANCELLED).
     */
    public Result walkSmallerTo(int target) {
        int n = ZoomModel.POSITIONS;
        if (target < prep.progress || target > n - 1) return stop(Status.ERROR, "walk " + prep.progress + " -> " + target);
        if (target == prep.progress) return null;
        int from = prep.progress, total = target - from;
        dev.beginZoomSession();
        try {
            for (int k = from + 1; k <= target; k++) {
                if (cancelled) return stop(Status.CANCELLED, "stopped at " + prep.progress);
                if (prep.progress >= n - 1) return stop(Status.ERROR, "already the smallest");
                int got = dev.stepSmaller();
                listener.onPhase(Phase.ZOOM, k - from, total);
                if (got != k) {
                    prep.progress = got;
                    return stop(Status.LENS_UNEXPECTED, "expected " + k + ", read " + got);
                }
                prep.progress = got;
            }
        } finally {
            dev.endZoomSession();
        }
        return null;
    }

    /** Step 9. */
    public void focus() {
        listener.onPhase(Phase.FOCUS, 0, 0);
        dev.autofocus();
    }

    /** The whole fit (steps 1-10). */
    public Result run(ScreenSize screen, ThrowModel model, FitPlanner.Mode mode) {
        if (screen == null) return new Result(Status.NO_SCREEN);
        if (model == null) return new Result(Status.NO_CALIBRATION);
        Result stop = prepare();
        if (stop != null) return stop;
        Result r = new Result(Status.DONE);
        r.prepared = prep;
        r.plan = FitPlanner.plan(prep.geometry, prep.estimator, prep.corners, model, screen, mode);
        int start = prep.progress;
        Result walk = walkSmallerTo(r.plan.target);
        if (walk != null) {
            r.status = walk.status;
            r.detail = walk.detail;
        }
        boolean moved = prep.progress != start;
        if (moved && r.status != Status.LENS_UNEXPECTED) focus();
        int fin = dev.readProgress();
        r.finalProgress = fin;
        if (fin >= 0 && fin < model.positions()) {
            int[] c = dev.readKeystoneCorners();
            FitGeometry.Estimator e = FitGeometry.estimatorFor(c);
            int[] use = e == FitGeometry.Estimator.CORNERS ? c : null;
            if (!FitGeometry.validCorners(c)) {                 // no new read: the corners of step 6
                e = prep.estimator;
                use = prep.corners;
            }
            r.finalSize = prep.geometry.size(e, use, model.tr(fin));
        }
        listener.onPhase(Phase.DONE, 0, 0);
        return r;
    }

    private Result stop(Status s, String detail) {
        Result r = new Result(s);
        r.prepared = prep;
        r.detail = detail;
        r.finalProgress = prep != null ? prep.progress : -1;
        return r;
    }

    private boolean pause(long ms) {
        try {
            sleeper.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled = true;
            return false;
        }
    }
}
