package android.util;

/** Host stub of android.util.Log for test/features: prints nothing, counts warnings. */
public final class Log {
    public static int warnings;
    private Log() {}
    public static int i(String tag, String msg) { return 0; }
    public static int w(String tag, String msg) { warnings++; return 0; }
    public static int e(String tag, String msg) { return 0; }
}
