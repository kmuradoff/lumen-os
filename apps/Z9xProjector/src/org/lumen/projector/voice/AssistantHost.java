package org.lumen.projector.voice;

import android.content.Context;

import org.lumen.projector.Hal;
import org.lumen.projector.panel.QuickPanel;

/** The Z9X side of the voice assistant (voice/Assistant, shared): focus, keystone, quick panel pages. */
public final class AssistantHost implements Assistant.Host {
    @Override public void autofocus(Context c) { Hal.requestAutofocus(c); }
    @Override public void keystone(Context c) { Hal.requestKeystone(c, false); }
    @Override public void openPanel(Context c, String section) {
        if (section == null) QuickPanel.toggle(c); else QuickPanel.show(c, section);
    }
    @Override public boolean lowPower() { return false; }
}
