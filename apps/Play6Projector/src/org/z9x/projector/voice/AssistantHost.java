package org.z9x.projector.voice;

import android.content.Context;

import org.z9x.projector.Hal;
import org.z9x.projector.panel.QuickPanel;

/** The Play 6 side of the voice assistant (voice/Assistant, shared): focus, keystone, quick panel pages; weak GPU. */
public final class AssistantHost implements Assistant.Host {
    @Override public void autofocus(Context c) { Hal.requestAutofocus(c); }
    @Override public void keystone(Context c) { Hal.requestKeystone(c); }
    @Override public void openPanel(Context c, String section) {
        if (section == null) QuickPanel.toggle(c); else QuickPanel.show(c, section);
    }
    @Override public boolean lowPower() { return true; }
}
