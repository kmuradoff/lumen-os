package org.z9x.home.sky;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Lumen type for the screensaver: display "Prata" (big clock) and UI "Onest" from the app's assets when
 * they are bundled, else the system serif / sans-serif.
 */
final class SkyFonts {
    private SkyFonts() {}

    private static Typeface sDisplay, sUi;

    static synchronized Typeface display(Context c) {
        if (sDisplay == null) sDisplay = load(c, "fonts/Prata-Regular.ttf", Typeface.SERIF);
        return sDisplay;
    }

    static synchronized Typeface ui(Context c) {
        if (sUi == null) sUi = load(c, "fonts/Onest-Variable.ttf", Typeface.SANS_SERIF);
        return sUi;
    }

    private static Typeface load(Context c, String asset, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), asset);
        } catch (Throwable t) {
            return fallback;
        }
    }
}
