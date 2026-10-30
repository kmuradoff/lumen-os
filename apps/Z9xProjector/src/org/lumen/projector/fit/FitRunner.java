package org.lumen.projector.fit;

import android.content.Context;
import android.util.Log;

import org.lumen.projector.Hal;
import org.lumen.projector.R;
import org.lumen.projector.Ui;
import org.lumen.projector.ak.AkOverlay;
import org.lumen.projector.ui.DialogPanel;
import org.lumen.projector.ui.Notify;
import org.lumen.projector.ui.OverlayHost;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fit to my screen size: starts a fit or a calibration from the quick panel (docs/fit_screen/DESIGN.md 1.3, 8).
 * Main thread checks what it can without the HAL (supported, screen, calibration, no keystone running), asks for
 * confirmation, then runs the core on its own worker thread "z9x-fit" (the core blocks; the adapter hops to the
 * HAL thread per call). One run at a time. Progress: FitProgressPanel; result: a DialogPanel card. Logs: tag Z9xFit.
 */
public final class FitRunner {
    static final String TAG = "Z9xFit";
    private static volatile boolean sRunning;
    private static volatile FitRun sRun;
    private static FitProgressPanel sProgress;          // main thread

    private FitRunner() {}

    public static boolean isRunning() { return sRunning; }

    // =================================================================== fit
    public static void startFit(Context ctx) {
        Context app = ctx.getApplicationContext();
        FitDevice dev = FitDevices.get(app);
        if (!checkCommon(app)) return;
        String model = dev.modelId();
        FitStore.Screen sc = FitPrefs.screen(app, model);
        if (sc == null) { Notify.show(app, R.string.fit_e_no_screen); return; }
        ScreenSize size;
        try { size = sc.toSize(); } catch (IllegalArgumentException e) { Notify.show(app, R.string.fit_screen_bad); return; }
        ThrowModel model0 = FitPrefs.model(app, model);
        if (model0 == null) { Notify.show(app, R.string.fit_e_no_calib); return; }
        FitPlanner.Mode mode = sc.planMode();
        confirm(app, app.getString(R.string.fit_row), () -> worker(app, () -> {
            FitRun run = newRun(app, dev);
            Log.i(TAG, "fit start: model " + model + ", screen " + size + ", mode " + mode);
            FitRun.Result r = run.run(size, model0, mode);
            Log.i(TAG, "fit end: " + r + (r.plan != null ? ", target " + r.plan.target + " " + r.plan.kind
                    + String.format(Locale.ROOT, ", Dexact %.0f mm", r.plan.exactMm) : ""));
            if (r.prepared != null) {
                Log.i(TAG, String.format(Locale.ROOT, "fit inputs: mount %d, distance reads %s -> %.0f mm, pitch %.2f deg, estimator %s",
                        r.prepared.mount, java.util.Arrays.toString(r.prepared.distances), r.prepared.distanceMm,
                        r.prepared.pitchDeg, r.prepared.estimator));
            }
            if (r.status == FitRun.Status.DONE && r.finalProgress >= 0) {
                FitStore.LastFit f = new FitStore.LastFit();
                f.progress = r.finalProgress;
                f.distanceMm = r.prepared.distanceMm;
                f.pitchDeg = r.prepared.pitchDeg;
                f.date = System.currentTimeMillis();
                FitPrefs.setLastFit(app, model, f);
            }
            String title = app.getString(r.status == FitRun.Status.DONE ? R.string.fit_done : R.string.fit_row);
            String msg = resultText(app, r, size);
            ui(() -> show(app, new DialogPanel(title, msg, app.getString(R.string.ok), null, ok -> { }).setAutoHideMs(120_000)));
        }));
    }

    // =================================================================== calibration
    public static void startCalibration(Context ctx, FitCalibration.Kind kind) {
        Context app = ctx.getApplicationContext();
        FitDevice dev = FitDevices.get(app);
        if (!checkCommon(app)) return;
        String model = dev.modelId();
        confirm(app, app.getString(R.string.fit_cal_title), () -> worker(app, () -> {
            FitRun run = newRun(app, dev);
            FitCalibration cal = new FitCalibration(run, kind);
            Log.i(TAG, "calibration start: model " + model + ", kind " + kind);
            FitRun.Result stop = cal.prepare();
            if (stop != null) { finishCal(app, stop, null); return; }
            FitRun.Prepared p = cal.prepared();
            Log.i(TAG, String.format(Locale.ROOT, "calibration inputs: mount %d, distance %.0f mm, pitch %.2f deg, estimator %s, corners %s",
                    p.mount, p.distanceMm, p.pitchDeg, p.estimator, java.util.Arrays.toString(p.corners)));
            ThrowModel prior = FitPrefs.model(app, model);
            while (true) {
                double start = cal.predictedWidthMm(prior);
                String err = null;
                while (true) {
                    String step = app.getString(R.string.fit_cal_step, cal.step() + 1, cal.steps(),
                            ZoomModel.displayNumber(cal.position()));
                    double mm = askWidth(app, step, start, err);
                    if (Double.isNaN(mm)) {
                        finishCal(app, null, app.getString(R.string.fit_e_stopped,
                                ZoomModel.displayNumber(cal.position()), ZoomModel.POSITIONS));
                        return;
                    }
                    double tr = cal.record(mm);
                    Log.i(TAG, String.format(Locale.ROOT, "calibration position %d: %.1f mm -> TR %.4f", cal.position(), mm, tr));
                    if (!Double.isNaN(tr)) break;
                    err = app.getString(R.string.fit_cal_bad);
                    start = mm;
                }
                if (cal.step() >= cal.steps() - 1) break;
                FitRun.Result r = cal.next();
                if (r != null) { finishCal(app, r, null); return; }
            }
            ThrowModel.Built b = cal.finish();
            Log.i(TAG, "calibration result: " + b.problem + (b.checkPos >= 0 ? String.format(Locale.ROOT,
                    ", check position %d predicted %.4f measured %.4f", b.checkPos, b.predicted, b.measured) : ""));
            if (!b.ok()) {
                int why = b.problem == ThrowModel.Problem.FULL_TABLE_NEEDED ? R.string.fit_cal_r_full
                        : b.problem == ThrowModel.Problem.NOT_INCREASING ? R.string.fit_cal_r_order : R.string.fit_cal_r_range;
                finishCal(app, null, app.getString(R.string.fit_cal_refused) + ": " + app.getString(why));
                return;
            }
            FitStore.Calib c = new FitStore.Calib();
            c.points = b.model.points();
            c.distanceMm = p.distanceMm;
            c.pitchDeg = p.pitchDeg;
            c.corners = p.corners;
            c.date = System.currentTimeMillis();
            c.kind = kind == FitCalibration.Kind.FULL ? "full" : kind == FitCalibration.Kind.THREE ? "three" : "two";
            FitPrefs.setCalib(app, model, c);
            StringBuilder t = new StringBuilder();
            for (double[] q : c.points) t.append(String.format(Locale.ROOT, " p%d=%.4f", (int) q[0], q[1]));
            Log.i(TAG, "calibration saved:" + t);
            finishCal(app, null, app.getString(R.string.fit_cal_saved));
        }));
    }

    private static void finishCal(Context app, FitRun.Result stop, String text) {
        String msg = text != null ? text : resultText(app, stop, null);
        ui(() -> show(app, new DialogPanel(app.getString(R.string.fit_cal_title), msg, app.getString(R.string.ok), null,
                ok -> { }).setAutoHideMs(120_000)));
    }

    /** Shows one measurement screen and waits for the value (NaN = stopped). Worker thread. */
    private static double askWidth(Context app, String step, double startMm, String error) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Double> v = new AtomicReference<>(Double.NaN);
        ui(() -> {
            hideProgress();
            FitCalibPanel p = new FitCalibPanel(step, startMm, error, mm -> { v.set(mm); done.countDown(); });
            if (!OverlayHost.get(app).show(p)) done.countDown();
        });
        while (!done.await(500, TimeUnit.MILLISECONDS)) {
            if (sRun != null && sRun.isCancelled()) return Double.NaN;
        }
        return v.get();
    }

    // =================================================================== shared
    private static boolean checkCommon(Context app) {
        if (!FitDevices.supported(app)) { Notify.show(app, R.string.fit_e_unsupported); return false; }
        if (sRunning) return false;
        if (!Hal.isReady()) { Notify.show(app, R.string.zoom_unavailable); return false; }
        if (Hal.isKeystoneRunning() || AkOverlay.isActive()) { Notify.show(app, R.string.kst_reason_ak_busy); return false; }
        return true;
    }

    private static void confirm(Context app, String title, Runnable onOk) {
        show(app, new DialogPanel(title, app.getString(R.string.fit_confirm_msg), app.getString(R.string.fit_go),
                app.getString(R.string.cancel), ok -> { if (ok) onOk.run(); }));
    }

    private interface Work { void run() throws Exception; }

    private static void worker(Context app, Work w) {
        if (sRunning) return;
        sRunning = true;
        Thread t = new Thread(() -> {
            try {
                w.run();
            } catch (Throwable e) {
                Log.w(TAG, "run failed: " + e);
                ui(() -> Notify.show(app, R.string.fit_e_failed));
            } finally {
                sRun = null;
                sRunning = false;
                ui(FitRunner::hideProgress);
            }
        }, "z9x-fit");
        t.start();
    }

    private static FitRun newRun(Context app, FitDevice dev) {
        FitRun run = new FitRun(dev, null, (phase, a, b) -> ui(() -> onPhase(app, phase, a, b)));
        sRun = run;
        return run;
    }

    private static void onPhase(Context app, FitRun.Phase phase, int a, int b) {
        switch (phase) {
            case CHECK:
            case DISTANCE: progress(app, app.getString(R.string.fit_p_distance)); break;
            case KEYSTONE:                  // the camera sees the AK pattern alone; the app's AK notice shows instead
                hideProgress();
                break;
            case ZOOM: progress(app, app.getString(R.string.fit_p_zoom, a, b)); break;
            case FOCUS:                     // the vendor's camera AF needs its own test card alone (AfCardOverlay)
                hideProgress();
                break;
            default: hideProgress(); break;
        }
    }

    private static void progress(Context app, String text) {
        if (sProgress == null || !sProgress.isShowing()) {
            FitProgressPanel p = new FitProgressPanel(() -> {
                FitRun r = sRun;
                if (r != null) {
                    Log.i(TAG, "BACK: stop after the running step");
                    r.cancel();
                }
            });
            p.setText(text);
            if (OverlayHost.get(app).show(p)) sProgress = p;
        } else {
            sProgress.setText(text);
        }
    }

    private static void hideProgress() {
        if (sProgress != null) {
            sProgress.close();
            sProgress = null;
        }
    }

    private static void show(Context app, DialogPanel d) {
        hideProgress();
        try { OverlayHost.get(app).show(d); } catch (Throwable t) { Log.w(TAG, "card: " + t); }
    }

    private static void ui(Runnable r) { Ui.main().post(r); }

    // =================================================================== texts
    static String resultText(Context app, FitRun.Result r, ScreenSize s) {
        int n = ZoomModel.POSITIONS;
        switch (r.status) {
            case DONE: break;
            case UNSUPPORTED: return app.getString(R.string.fit_e_unsupported);
            case NO_SCREEN: return app.getString(R.string.fit_e_no_screen);
            case NO_CALIBRATION: return app.getString(R.string.fit_e_no_calib);
            case MOUNT_UNKNOWN: return app.getString(R.string.fit_e_mount);
            case DISTANCE_RANGE: return app.getString(R.string.fit_e_distance);
            case DISTANCE_UNSTABLE: return app.getString(R.string.fit_e_unstable);
            case PITCH_UNKNOWN: return app.getString(R.string.fit_e_pitch);
            case PITCH_RANGE: return app.getString(R.string.fit_e_pitch_range);
            case KEYSTONE_FAILED: return app.getString(R.string.fit_e_keystone);
            case NOT_HOME: return app.getString(R.string.fit_e_home);
            case LENS_UNEXPECTED: return app.getString(R.string.fit_e_lens);
            case CANCELLED:
                return r.finalProgress >= 0 ? app.getString(R.string.fit_e_stopped, ZoomModel.displayNumber(r.finalProgress), n)
                        : app.getString(R.string.fit_e_failed);
            default: return app.getString(R.string.fit_e_failed);
        }
        StringBuilder b = new StringBuilder();
        FitGeometry.Size z = r.finalSize != null ? r.finalSize : r.plan.size();
        if (s != null) {
            b.append(app.getString(R.string.fit_result_picture, cm(z.w), cm(z.h), cm(s.wMm), cm(s.hMm)));
            b.append('\n').append(app.getString(R.string.fit_result_gap, cm((s.wMm - z.w) / 2), cm((s.hMm - z.h) / 2)));
        }
        int fin = r.finalProgress >= 0 ? r.finalProgress : r.plan.target;
        b.append('\n').append(app.getString(R.string.fit_result_where,
                String.format(Locale.ROOT, "%.2f", r.prepared.distanceMm / 1000),
                String.format(Locale.ROOT, "%.1f", r.prepared.pitchDeg), ZoomModel.displayNumber(fin), n));
        FitPlanner.Plan p = r.plan;
        if (p.kind == FitPlanner.Kind.MOVE_CLOSER) {
            b.append('\n').append(app.getString(R.string.fit_small_screen, cm(-p.adviceMm)));
        } else if (p.kind == FitPlanner.Kind.MOVE_FURTHER) {
            b.append('\n').append(app.getString(R.string.fit_big_screen, cm(p.adviceMm)));
        } else if (p.showAdvice()) {
            b.append('\n').append(app.getString(p.adviceMm > 0 ? R.string.fit_advice_further : R.string.fit_advice_closer,
                    cm(Math.abs(p.adviceMm))));
        }
        return b.toString();
    }

    /** mm -> "12.5" cm. */
    private static String cm(double mm) { return FitScreenPage.num(mm / 10); }
}
