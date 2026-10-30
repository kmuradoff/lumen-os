package org.lumen.projector.fit;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.lumen.projector.hal.GmpfClient;
import org.lumen.projector.hal.Gmpf2Client;
import org.lumen.projector.hal.KstPoint;

import java.util.Locale;

/**
 * Fit to my screen size, the Z9X (G0082) adapter (docs/fit_screen/DESIGN.md 9.2). Z9X only, never shared.
 *
 * SUPPORTED since Lumen OS 1.0.8 (owner 2026-10-30: O1, the zoom fix F1-F4 of docs/fit_screen/Z9X_ZOOM_AUDIT.md, and
 * O2, the reads 279 / 584). The quick panel row shows; a fit still refuses until a calibration exists
 * (FitRunner: fit_e_no_calib; ThrowModel.DEFAULTS is empty), so the first run is "Calibrate the zoom" as designed.
 *
 * Every lens call goes through the guarded Z9X clients, exactly as the Z9X zoom panel (OpticalZoomPanel, stock
 * ZoomStateView):
 *   session   IGmpf2 18(true, 2) + 121, then 117 (optical 0..12) and 153(12, 10) (limit positions, 1 expected);
 *             120 + 18(false, 2)
 *   position  116 then IGmpf2 13 (both fresh for the client guards): progress = 1 + 13 at optical 0, else 116 + 1
 *   smaller   progress 0 -> IGmpf2 14(13 + 1) (the limit position, guarded EXCEPTION), else 119(116 + 1)
 *   home      153(5, 10) inside a session
 * Nothing else moves the lens: no bigger move, no multi-step call, no 14 / 119 that is not the neighbour of a fresh
 * read (the clients refuse it anyway). A session that does not open as the verified model (117 0..12, 153(12,10) = 1)
 * refuses every step. The zoom panel's marker (prefs z9x_zoom / session_open) is set around a session, so
 * OpticalZoomPanel.install closes a session a dead process left open.
 * Reads: 173 (put mode), 279 ToF (GmpfClient budget: 1 s apart, 6 a minute), 584 accelerometer (80 ms, 20 a
 * minute), 186 corners. Axis order of 584 for the pitch UNVERIFIED (DESIGN M5, TEST_PLAN).
 */
final class Z9xFitDevice implements FitDevice {
    static final String TAG = "Z9xFit";
    /** O1 + O2 approved by the owner 2026-10-30; the HAL methods exist (GmpfClient / Gmpf2Client, Lumen OS 1.0.8). */
    static final boolean SUPPORTED = true;
    /** The verified model: 117 optical 0..12 and 153(12,10) = 1 (zoom audit F8 checks the 1 on the device). */
    private static final int OPTICAL_MAX = 12, LIMIT_TOTAL = 1;
    /**
     * Accelerometer axes of 584 (int16 x, y, z): z is about 4092 with the owner's Z9X lying flat (LumenSensors device
     * test 2026-10-27), so "up" = z; "forward along the lens" = x is UNVERIFIED (DESIGN M5).
     */
    static final int AXIS_FORWARD = 0, AXIS_UP = 2;
    /** 1 g is about 4096 counts; plausible range. */
    private static final int G_MIN = 2500, G_MAX = 6000;
    private static final int PITCH_SAMPLES = 5;
    private static final long PITCH_GAP_MS = 100;

    private final Context app;
    private int mount = -1;
    private boolean session;
    /** The session did not open as verified: every step refuses until it is closed. */
    private boolean sessionBroken;

    Z9xFitDevice(Context app) { this.app = app.getApplicationContext(); }

    @Override public String modelId() { return "G0082"; }

    @Override public boolean supported() { return SUPPORTED; }

    @Override public int mountMode() {
        try {
            GmpfClient.PutMode m = FitHal.hal((g, g2) -> g.getPutMode());
            mount = m != null ? m.wire : -1;
        } catch (Exception e) {
            Log.w(TAG, "173: " + e);
            mount = -1;
        }
        Log.i(TAG, "mount mode " + mount);
        return mount;
    }

    @Override public int readDistanceMm() {
        try {
            int d = FitHal.hal((g, g2) -> g.getTofDistance());
            Log.i(TAG, "279 getTofDistance -> " + d + " mm");
            return d;
        } catch (Exception e) {
            Log.w(TAG, "279 getTofDistance: " + e);
            return -1;
        }
    }

    /**
     * 5 accelerometer samples 100 ms apart, averaged. Desk: theta = atan2(a_forward, a_up) (nose up = positive, the
     * picture goes up); ceiling (body upside down): theta = atan2(-a_forward, -a_up) (nose down at a lower screen =
     * positive). NaN when a sample fails or the vector is not about 1 g with "up" dominant. Same formula as the HB.
     */
    @Override public double readPitchDeg() {
        long[] sum = new long[3];
        int n = 0;
        try {
            for (int i = 0; i < PITCH_SAMPLES; i++) {
                if (i > 0) FitHal.sleep(PITCH_GAP_MS);
                int[] v = FitHal.hal((g, g2) -> g.getMotionDetection());
                Log.i(TAG, "584 -> acc " + v[0] + " " + v[1] + " " + v[2] + ", gyro " + v[3] + " " + v[4] + " "
                        + v[5] + ", type " + v[6]);
                for (int k = 0; k < 3; k++) sum[k] += v[k];
                n++;
            }
        } catch (Exception e) {
            Log.w(TAG, "584: " + e);
            return Double.NaN;
        }
        double[] a = {sum[0] / (double) n, sum[1] / (double) n, sum[2] / (double) n};
        return pitch(a, mount == 1 || mount == 3);
    }

    /** Package-private for reading the formula next to the test plan; plain math. */
    static double pitch(double[] a, boolean ceiling) {
        double g = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        if (g < G_MIN || g > G_MAX || Math.abs(a[AXIS_UP]) < 0.7 * g) {
            Log.w(TAG, String.format(Locale.ROOT, "accelerometer not plausible: %.0f %.0f %.0f", a[0], a[1], a[2]));
            return Double.NaN;
        }
        double f = a[AXIS_FORWARD], u = a[AXIS_UP];
        double deg = ceiling ? Math.toDegrees(Math.atan2(-f, -u)) : Math.toDegrees(Math.atan2(f, u));
        if (deg > 90) deg -= 180;                        // the up axis read the other way round: same tilt
        else if (deg < -90) deg += 180;
        Log.i(TAG, String.format(Locale.ROOT, "pitch %.2f deg (acc %.0f %.0f %.0f, %s)", deg, a[0], a[1], a[2],
                ceiling ? "ceiling" : "desk"));
        return deg;
    }

    @Override public boolean runAutoKeystone() {
        try {
            boolean ok = FitHal.autoKeystone(app);
            Log.i(TAG, "auto keystone -> " + ok);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "auto keystone: " + e);
            return false;
        }
    }

    @Override public int[] readKeystoneCorners() {
        try {
            KstPoint p = FitHal.hal((g, g2) -> g.getKeystonePoints());
            int[] c = new int[8];
            for (int i = 0; i < 4; i++) {
                c[2 * i] = p.x(i);
                c[2 * i + 1] = p.y(i);
            }
            Log.i(TAG, "186 corners " + java.util.Arrays.toString(c));
            return c;
        } catch (Exception e) {
            Log.w(TAG, "186: " + e);
            return null;
        }
    }

    @Override public int positionCount() { return LIMIT_TOTAL + OPTICAL_MAX + 1; }

    /** 116 then IGmpf2 13, as the zoom panel's readProgress. HAL thread. */
    private static int progress(GmpfClient g, Gmpf2Client g2) throws Exception {
        int cur = g.getZoomStep()[1];
        int lim = g2.getZoomLimitStep()[1];
        if (cur < 0 || cur > OPTICAL_MAX || lim > 0 || lim < -LIMIT_TOTAL) {
            throw new IllegalStateException("116 optical " + cur + ", 13 limit " + lim);
        }
        if (cur != 0 && lim != 0) throw new IllegalStateException("limit " + lim + " away from index 0 (" + cur + ")");
        return cur == 0 ? LIMIT_TOTAL + lim : cur + LIMIT_TOTAL;
    }

    @Override public int readProgress() {
        try {
            int p = FitHal.hal(Z9xFitDevice::progress);
            Log.i(TAG, "position " + p);
            return p;
        } catch (Exception e) {
            Log.w(TAG, "position: " + e);
            return -1;
        }
    }

    @Override public void homeZoom() {
        boolean own = !session;
        try {
            if (own) beginZoomSession();
            long t0 = SystemClock.uptimeMillis();
            int r = FitHal.hal((g, g2) -> g.zoomHome());
            Log.i(TAG, "153(5,10) home -> " + r + " in " + (SystemClock.uptimeMillis() - t0) + " ms");
        } catch (Exception e) {
            Log.w(TAG, "home: " + e);
        } finally {
            if (own) endZoomSession();
        }
    }

    @Override public void beginZoomSession() {
        if (session) return;
        setMarker(true);
        session = true;
        try {
            FitHal.hal((g, g2) -> {
                Log.i(TAG, "18(true,2) -> " + g2.setKstPrepareForZoom(true) + ", 121(0) -> " + g.zoomSessionStart());
                int[] range = g.getZoomStepRange();
                int lim = g.getZoomLimitTotal();
                Log.i(TAG, "117 -> optical " + range[1] + ".." + range[2] + ", 153(12,10) -> " + lim);
                if (range[1] != 0 || range[2] != OPTICAL_MAX || lim != LIMIT_TOTAL) {
                    throw new IllegalStateException("zoom model is not 1 + 0.." + OPTICAL_MAX);
                }
                return null;
            });
        } catch (Exception e) {
            Log.w(TAG, "session: " + e);
            sessionBroken = true;
        }
    }

    @Override public void endZoomSession() {
        if (!session) return;
        session = false;
        sessionBroken = false;
        boolean ok;
        try {
            ok = FitHal.hal((g, g2) -> {
                boolean fine = true;
                try { Log.i(TAG, "120(0) -> " + g.zoomSessionFinish()); } catch (Throwable t) { fine = false; Log.w(TAG, "120: " + t); }
                try { Log.i(TAG, "18(false,2) -> " + g2.setKstPrepareForZoom(false)); } catch (Throwable t) { fine = false; Log.w(TAG, "18(false,2): " + t); }
                return fine;
            });
        } catch (Exception e) {
            ok = false;
            Log.w(TAG, "close: " + e);
        }
        if (ok) setMarker(false);                         // else the zoom panel's connect hook closes it
    }

    @Override public int stepSmaller() {
        if (!session || sessionBroken) { Log.w(TAG, "step refused: no verified session"); return -1; }
        try {
            return FitHal.hal((g, g2) -> {
                int now = progress(g, g2);                // fresh 116 + 13 for the client guards
                if (now >= LIMIT_TOTAL + OPTICAL_MAX) throw new IllegalStateException("already the smallest");
                long t0 = SystemClock.uptimeMillis();
                int r;
                String what;
                if (now < LIMIT_TOTAL) {
                    int lim = now - LIMIT_TOTAL;
                    r = g2.setOpticalZoomLimitStep(lim + 1);
                    what = "IGmpf2 14(" + (lim + 1) + ")";
                } else {
                    int cur = now - LIMIT_TOTAL;
                    r = g.setOpticalZoomStep(cur + 1);
                    what = "119(" + (cur + 1) + ")";
                }
                int after = progress(g, g2);
                Log.i(TAG, what + " -> " + r + " in " + (SystemClock.uptimeMillis() - t0) + " ms, position " + now + " -> " + after);
                return r == 0 && after == now + 1 ? after : -1;
            });
        } catch (Exception e) {
            Log.w(TAG, "step: " + e);
            return -1;
        }
    }

    @Override public void autofocus() {
        try {
            FitHal.autofocus(app);
        } catch (Exception e) {
            Log.w(TAG, "autofocus: " + e);
        }
    }

    /** The zoom panel's marker (OpticalZoomPanel PREFS / K_OPEN): synchronous, on disk before 121 is sent. */
    private void setMarker(boolean on) {
        try {
            SharedPreferences p = app.createDeviceProtectedStorageContext().getSharedPreferences("z9x_zoom", Context.MODE_PRIVATE);
            p.edit().putBoolean("session_open", on).commit();
        } catch (Throwable t) {
            Log.w(TAG, "marker: " + t);
        }
    }
}
