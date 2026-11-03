package org.lumen.projector.fit;

import android.content.Context;

/**
 * Fit to my screen size: the adapter of this app's device. HB fork of the Z9X file (forks.tsv): returns HbFitDevice;
 * the screens call only this.
 */
public final class FitDevices {
    private FitDevices() {}

    public static FitDevice get(Context ctx) { return new HbFitDevice(ctx); }

    /** Cheap, main-thread safe: whether the quick panel shows the fit row (false: hidden 2026-10-31, HbFitDevice.SUPPORTED). */
    public static boolean supported(Context ctx) { return HbFitDevice.isSupported(); }
}
