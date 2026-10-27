package org.lumen.sensors.core;

/**
 * Unit and value words of the current language, resolved once from the app resources by the UI
 * (Hub) and handed to the sampler thread; immutable. Units carry their own leading space (a
 * no-break space where the language wants one: "58 °C", "23%" in English, "23 %" in Russian).
 * Plain Java (host tests build one by hand).
 */
public final class Words {
    public static final int C = 0, PCT = 1, MHZ = 2, MB = 3, GB = 4, MBITS = 5, DBM = 6, MM = 7, V = 8,
            H = 9, HZ = 10, MIN = 11, DEG = 12, GHZ = 13, ON = 14, OFF = 15, YES = 16, NO = 17,
            MIN_W = 18, MAX_W = 19, G = 20;
    public static final int COUNT = 21;

    private final String[] w;
    /** Decimal separator of the language ('.' English, ',' Russian). */
    public final char dec;

    public Words(String[] words, char decimalSeparator) {
        if (words == null || words.length != COUNT) throw new IllegalArgumentException("need " + COUNT + " words");
        w = words.clone();
        dec = decimalSeparator;
    }

    public String get(int i) { return w[i]; }

    /** English words (tests, and the fallback before resources are read). */
    public static Words english() {
        return new Words(new String[] {
                " °C", "%", " MHz", " MB", " GB", " Mbit/s", " dBm", " mm",
                " V", " h", " Hz", " min", "°", " GHz", "On", "Off", "Yes", "No",
                "min", "max", " g"}, '.');
    }
}
