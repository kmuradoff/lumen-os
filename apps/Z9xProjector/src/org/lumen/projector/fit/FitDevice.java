package org.lumen.projector.fit;

/**
 * Fit to my screen size: what the core needs from a projector (docs/fit_screen/DESIGN.md 9.1). One adapter per
 * device app (Z9xFitDevice, HbFitDevice), never shared; the core knows no HAL code, so it cannot send a banned
 * value. The only lens moves are {@link #stepSmaller} (one stock single-position call) and {@link #homeZoom}
 * (the stock home walk). Every method may block (HAL thread round trip); never called on the main thread.
 */
public interface FitDevice {
    /** "G0082", "G0130": the key of the stored screen and calibration. */
    String modelId();

    /** The verified 14-position zoom model and every HAL call of the run are allowed on this build and device. */
    boolean supported();

    /** 0 desk front, 1 ceiling front, 2 desk rear, 3 ceiling rear; -1 unknown. */
    int mountMode();

    /** One ToF read in mm (an active measurement, about 0.6 s); -1 on error. The adapter keeps its own budget. */
    int readDistanceMm();

    /** Tilt in degrees in the sign convention of {@link FitGeometry} (mount mode applied); NaN when unknown. */
    double readPitchDeg();

    /** The stock auto keystone; blocks until it ended. false when it could not run. */
    boolean runAutoKeystone();

    /** The corrected corners TL, TR, BR, BL (x, y) in 3840 x 2160 physical panel pixels, or null. */
    int[] readKeystoneCorners();

    /** N, the zoom positions of the verified model (14). */
    int positionCount();

    /** Fresh read: 0 (the biggest picture) .. N-1; -1 on error. */
    int readProgress();

    /** The stock home walk (the biggest picture); the caller reads the position back. */
    void homeZoom();

    /** The zoom session of the stock dial (prepare + start); always closed by {@link #endZoomSession}. */
    void beginZoomSession();

    void endZoomSession();

    /** One position smaller, exactly one stock single-position call, returns the read-back progress (-1 error). */
    int stepSmaller();

    /** The autofocus the zoom panels run after the lens moved; blocks until it ended (bounded). */
    void autofocus();
}
