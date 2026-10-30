package org.lumen.projector.fit;

import android.content.Context;

/**
 * Fit to my screen size: the adapter of this app's device. One file per device app (the HB app has its own copy
 * returning HbFitDevice); the screens call only this.
 */
public final class FitDevices {
    private FitDevices() {}

    public static FitDevice get(Context ctx) { return new Z9xFitDevice(ctx); }

    /** Cheap, main-thread safe: whether the quick panel shows the fit row (Z9X: false, hidden by the owner 2026-10-31). */
    public static boolean supported(Context ctx) { return Z9xFitDevice.SUPPORTED; }
}
