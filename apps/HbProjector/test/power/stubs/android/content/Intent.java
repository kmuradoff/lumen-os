package android.content;

import java.util.HashMap;
import java.util.Map;

/** Host stub for test/power: boolean extras only. */
public class Intent {
    private final Map<String, Boolean> extras = new HashMap<>();

    public Intent putExtra(String k, boolean v) { extras.put(k, v); return this; }

    public boolean getBooleanExtra(String k, boolean def) { Boolean v = extras.get(k); return v == null ? def : v; }
}
