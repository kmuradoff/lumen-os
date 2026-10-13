package org.z9x.home.ui;

import org.z9x.home.Props;

/**
 * Device-neutral graphics switches for weak GPUs (a GPU that also composes every frame and cannot fill
 * a calm Home frame at the sky's rate), set by the image of a device that needs them, read once per
 * process. With no property set every value is the 1.0.2 behaviour, and Home issues exactly the 1.0.2
 * drawing commands.
 * <ul>
 *   <li>{@code ro.lumen.home.sky_fps}: the living sky on Home. Unset or negative: as the scene needs
 *   (8..30 fps). 0: still (no motion, the minute render and the fades only). N: at most N frames per
 *   second.</li>
 *   <li>{@code ro.lumen.home.sky_scale}: 0 &lt; s &lt;= 1 = the sky drawn in parts: the static ones (sky,
 *   sun, moon, land, scrims) in one cached GPU layer, the soft moving ones (clouds, fog) in a layer at s of
 *   the real pixels drawn enlarged (bilinear), the small crisp ones (stars, birds, lights, glints,
 *   fireflies, rain, snow) at full resolution. Unset: one draw, as 1.0.2.</li>
 *   <li>{@code ro.lumen.home.sky_yield}: 1 = the sky yields to the UI: no sky frames while keys come in,
 *   during page and tab transitions and while another window has the focus; the motion eases back in 1.5 s
 *   after the last of them.</li>
 *   <li>{@code ro.lumen.home.lite}: 1 = no starting-window background behind the sky and the search
 *   backdrop, the capped sky and the hero stage cached in GPU layers, layers for full-screen fades.</li>
 * </ul>
 * Any of the three sky switches also moves the sky's particles on its own run clock (it advances only
 * while sky frames run, so stopping and resuming never makes them jump).
 * {@code debug.lumen.home.<name>} wins when set ({@link Props}), for A/B tests on a test build:
 * {@code adb shell setprop debug.lumen.home.sky_fps 4}, then {@code adb shell am force-stop org.z9x.home}.
 * The screensaver ignores all of them (its moving sky is the point of it).
 */
public final class Gfx {
    private Gfx() {}

    /** Sky frames per second on Home: negative = as the scene needs (1.0.2), 0 = still, N = at most N. */
    public static final int SKY_FPS = Props.num("sky_fps", -1);
    /** Weak-GPU drawing: no window background under the sky, GPU layers for the sky, the stage and fades. */
    public static final boolean LITE = Props.num("lite", 0) > 0;
    /** The sky drawn in parts, its soft moving layer at this scale (0 &lt; s &lt;= 1); 0 = one draw (1.0.2). */
    public static final float SKY_SCALE = scale(Props.fnum("sky_scale", 0f));
    /** The sky yields to the UI (keys, transitions, another window focused). */
    public static final boolean SKY_YIELD = Props.num("sky_yield", 0) > 0;

    /** A sky scale in (0, 1], else 0 (off). */
    public static float scale(float s) {
        return s > 0f && s <= 1f ? s : 0f;
    }
}
