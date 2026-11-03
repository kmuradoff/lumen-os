package android.os;

/** Host stub for test/power: a settable clock. */
public class SystemClock {
    public static long now = 0;

    public static long elapsedRealtime() { return now; }
    public static long uptimeMillis() { return now; }
}
