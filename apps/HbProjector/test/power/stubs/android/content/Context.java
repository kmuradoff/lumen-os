package android.content;

/** Host stub for test/power: only what power/StrGate uses. */
public class Context {
    public static final int RECEIVER_EXPORTED = 2;
    public Intent sticky;

    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, int flags) { return sticky; }
}
