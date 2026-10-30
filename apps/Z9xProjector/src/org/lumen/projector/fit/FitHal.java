package org.lumen.projector.fit;

import android.os.Looper;
import android.os.SystemClock;

import org.lumen.projector.Hal;
import org.lumen.projector.Ui;
import org.lumen.projector.ak.AkOverlay;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fit to my screen size: blocking hops from the fit worker thread to the HAL thread and to the main thread, for
 * the device adapters. Device-neutral Android code (Hal and Ui have the same API in every projector app). Never
 * call from the main thread or the HAL thread (it would wait for itself).
 */
public final class FitHal {
    public static final long HAL_TIMEOUT_MS = 15_000;
    private static final long KEYSTONE_TIMEOUT_MS = 45_000, AF_TIMEOUT_MS = 12_000;

    private FitHal() {}

    /** Runs {@code q} on the HAL thread and waits for it; its exception is thrown here. */
    public static <T> T hal(Hal.Query<T> q) throws Exception {
        checkThread();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        boolean queued = Hal.runDelayed((g, g2) -> {
            try { value.set(q.run(g, g2)); } catch (Throwable t) { error.set(t); } finally { done.countDown(); }
        }, 0, () -> {
            error.set(new IllegalStateException("HAL not connected"));
            done.countDown();
        });
        if (!queued) throw new IllegalStateException("HAL task not queued");
        if (!done.await(HAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw new TimeoutException("HAL task");
        Throwable t = error.get();
        if (t instanceof Exception) throw (Exception) t;
        if (t != null) throw new RuntimeException(t);
        return value.get();
    }

    /** Runs {@code c} on the main thread and waits for it. */
    public static <T> T main(Callable<T> c) throws Exception {
        checkThread();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        if (!Ui.main().post(() -> {
            try { value.set(c.call()); } catch (Throwable t) { error.set(t); } finally { done.countDown(); }
        })) throw new IllegalStateException("main task not queued");
        if (!done.await(HAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw new TimeoutException("main task");
        Throwable t = error.get();
        if (t instanceof Exception) throw (Exception) t;
        if (t != null) throw new RuntimeException(t);
        return value.get();
    }

    /**
     * The app's own "Auto keystone now" (Hal.requestKeystone: keystone thread, motor interlock, the AK overlay),
     * then waits until it and its overlay ended. false when it was refused or did not end in 45 s.
     */
    public static boolean autoKeystone(android.content.Context app) throws Exception {
        int r = main(() -> Hal.requestKeystone(app, false));
        if (r != Hal.REQ_OK) return false;
        long t0 = SystemClock.uptimeMillis();
        sleep(500);
        while (Hal.isKeystoneRunning() || AkOverlay.isActive()) {
            if (SystemClock.uptimeMillis() - t0 > KEYSTONE_TIMEOUT_MS) return false;
            sleep(200);
        }
        sleep(500);                                       // the vendor finishes its warp after the last event
        return true;
    }

    /** The zoom panels' autofocus after a lens move, then waits (12 s at most) until it ended. */
    public static void autofocus(android.content.Context app) throws Exception {
        int r = main(() -> Hal.requestAutofocus(app));
        if (r != Hal.REQ_OK) return;
        long t0 = SystemClock.uptimeMillis();
        sleep(800);
        while (Hal.isAfBusy() && SystemClock.uptimeMillis() - t0 < AF_TIMEOUT_MS) sleep(200);
    }

    public static void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }

    private static void checkThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("FitHal on the main thread");
    }
}
