package org.lumen.projector.fit;

/**
 * Fit to my screen size: the zoom positions (docs/fit_screen/DESIGN.md section 5). Plain Java.
 *
 * Positions as the stock zoom dial: progress 0 = the biggest picture (the vendor's limit position, stock
 * ZoomStateView limitStep), 1..13 = optical index 0..12; N = limitTotal + opticalMax + 1 = 14 on the Z9X family
 * (zoom.ini zoomLimitStepEn = 1, zoomMotorLimitStep = 129; HB device test 2026-10-29, SURVEY_hb-t5b Z1). The
 * adapters report progress and N; the core never sees a vendor index.
 *
 * Motor coordinate (interpolation only): m(0) = 0, m(p) = 129 + 160 * (p - 1) for p >= 1 (one optical position =
 * zoomUnitStep 16 x 10 = 160 motor steps), m(13) = 2049.
 */
public final class ZoomModel {
    public static final int POSITIONS = 14;
    public static final int LIMIT_MOTOR_STEPS = 129, OPTICAL_MOTOR_STEPS = 160;

    private ZoomModel() {}

    public static int motor(int p) {
        if (p < 0 || p >= POSITIONS) throw new IllegalArgumentException("position " + p);
        return p == 0 ? 0 : LIMIT_MOTOR_STEPS + OPTICAL_MOTOR_STEPS * (p - 1);
    }

    /** "Size N of 14" as the zoom panels count it (14 = the biggest picture). */
    public static int displayNumber(int p) { return POSITIONS - p; }
}
