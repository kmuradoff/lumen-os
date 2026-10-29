package org.lumen.home.data;

import org.lumen.home.Props;
import org.lumen.home.ui.Gfx;

/**
 * The weak-GPU switches (ui/Gfx) read their properties as documented there, and with none set (the Z9X)
 * every switch is off: sky_fps -1 (as the scene needs) and lite false, the 1.0.2 drawing.
 */
final class GfxTest {
    static void run() {
        // on the JVM SystemProperties is a stub: the switches take their defaults, as on a device without them
        T.eq(Gfx.SKY_FPS, -1, "sky_fps unset: -1 (no cap)");
        T.eq(Gfx.LITE, false, "lite unset: off");
        T.eq(Gfx.SKY_SCALE, 0f, "sky_scale unset: one draw");
        T.eq(Gfx.SKY_YIELD, false, "sky_yield unset: off");
        T.eq(Props.parseFloat("0.5", 0f), 0.5f, "0.5");
        T.eq(Props.parseFloat(" 1 ", 0f), 1f, "trimmed float");
        T.eq(Props.parseFloat("", 0f), 0f, "empty float: default");
        T.eq(Props.parseFloat("NaN", 0f), 0f, "NaN: default");
        T.eq(Props.parseFloat("half", 0f), 0f, "not a number: default");
        T.eq(Gfx.scale(0.5f), 0.5f, "scale 0.5 kept");
        T.eq(Gfx.scale(1f), 1f, "scale 1 kept (parts at full size)");
        T.eq(Gfx.scale(0f), 0f, "scale 0: off");
        T.eq(Gfx.scale(2f), 0f, "scale above 1: off");
        T.eq(Gfx.scale(-0.5f), 0f, "negative scale: off");
        T.eq(Props.parse("", -1), -1, "empty: default");
        T.eq(Props.parse(null, 0), 0, "null: default");
        T.eq(Props.parse("0", -1), 0, "0: still sky");
        T.eq(Props.parse(" 4 ", -1), 4, "trimmed number");
        T.eq(Props.parse("-1", 0), -1, "negative kept");
        T.eq(Props.parse("true", 0), 1, "true: 1");
        T.eq(Props.parse("false", 1), 0, "false: 0");
        T.eq(Props.parse("fast", 7), 7, "not a number: default");
    }
}
