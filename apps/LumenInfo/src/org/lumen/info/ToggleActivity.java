package org.lumen.info;

import android.app.Activity;
import android.os.Bundle;

/**
 * No screen: switches the overlay and closes. Reachable as `am start -n org.lumen.info/.ToggleActivity` or with the
 * action org.lumen.info.action.TOGGLE_OVERLAY, so a remote key or a launcher shortcut can be bound to it later.
 */
public final class ToggleActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        OverlayService.toggle(this);
        finish();
    }
}
