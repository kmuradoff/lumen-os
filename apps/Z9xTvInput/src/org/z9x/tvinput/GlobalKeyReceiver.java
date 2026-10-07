/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Receives ACTION_GLOBAL_BUTTON from PhoneWindowManager / GlobalKeyManager for the keys mapped to
 * org.z9x.tvinput/.GlobalKeyReceiver in the framework res/xml/global_keys.xml (static RRO on
 * "android"): KEYCODE_TV_INPUT_HDMI_1 (243) and KEYCODE_TV_INPUT_HDMI_2 (244), the direct HDMI keys.
 *
 * <p>v6.1: the Source key (KEYCODE_TV_INPUT, 178) belongs to org.z9x.projector (its SourceOverlay
 * input chooser over the video; gsi/apps/V61_KEYS_PLAN.md), and the v6.1 RRO routes it there, so it
 * no longer reaches this receiver. If it still arrives here (an image whose framework RRO was not
 * updated) it is ignored while org.z9x.projector's KeyReceiver exists, so the two apps never both
 * act on Source; only when that receiver is absent or disabled does the v6 behaviour (inputs panel)
 * run as a last-resort fallback.
 *
 * <p>Manifest: android:exported="false", no intent-filter. system_server (uid 1000) may deliver
 * to non-exported receivers; GLOBAL_BUTTON is a protected broadcast. The action is checked here.
 * GlobalKeyManager sends every DOWN, repeated DOWN and the UP as separate broadcasts; only a
 * non-canceled UP acts. Runs on the main thread: only cheap binder calls and startActivity.
 *
 * <p>Setup gate: while the first-run setup is not complete (HdmiInputs.isSetupComplete), the keys
 * do nothing but a short hint, so no inputs panel / viewer opens over SetupWraith (the framework
 * itself refuses key-started activities and HOME during setup).
 */
public class GlobalKeyReceiver extends BroadcastReceiver {
    private static final String TAG = "Z9xHdmiKeys";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            handle(context, intent);
        } catch (Throwable t) {
            Log.e(TAG, "global key handling failed", t);
        }
    }

    private static void handle(Context context, Intent intent) {
        if (context == null || intent == null
                || !Intent.ACTION_GLOBAL_BUTTON.equals(intent.getAction())) {
            return;
        }
        KeyEvent event;
        try {
            event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
        } catch (Throwable t) {
            event = null;
        }
        if (event == null || event.getAction() != KeyEvent.ACTION_UP || event.isCanceled()) return;
        final Context c = context.getApplicationContext();
        int keyCode = event.getKeyCode();
        Log.i(TAG, "global key " + KeyEvent.keyCodeToString(keyCode));
        if (!HdmiInputs.isSetupComplete(c)) {
            Log.i(TAG, "setup not complete: key ignored");
            HdmiInputs.notify(c, c.getString(R.string.toast_setup_running));
            return;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_TV_INPUT:
                // v6.1: owned by org.z9x.projector (see class doc). Fallback only without it.
                if (projectorOwnsSourceKey(c)) {
                    Log.w(TAG, "KEYCODE_TV_INPUT reached tvinput although org.z9x.projector owns it"
                            + " (framework global_keys RRO not v6.1?): ignored");
                    break;
                }
                // Google TV "Inputs" panel (com.google.android.tvlauncher), else our own list.
                HdmiInputs.openInputsPanel(c);
                break;
            case KeyEvent.KEYCODE_TV_INPUT_HDMI_1:
                openPort(c, 1);
                break;
            case KeyEvent.KEYCODE_TV_INPUT_HDMI_2:
                openPort(c, 2);
                break;
            default:
                break;
        }
    }

    /** org.z9x.projector's global-key receiver (v6.1 owner of KEYCODE_TV_INPUT). */
    static final ComponentName PROJECTOR_KEY_RECEIVER =
            new ComponentName("org.z9x.projector", "org.z9x.projector.KeyReceiver");

    /**
     * True when org.z9x.projector/.KeyReceiver is installed and enabled. Needs the
     * &lt;queries&gt; entry for org.z9x.projector in the manifest (package visibility).
     */
    static boolean projectorOwnsSourceKey(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            // Throws NameNotFoundException when the package or the receiver is missing (or not
            // visible); without MATCH_DISABLED_COMPONENTS a disabled receiver/app is not found.
            pm.getReceiverInfo(PROJECTOR_KEY_RECEIVER, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "projector receiver check: " + t);
            return true;            // unknown: do not double-handle
        }
    }

    private static void openPort(Context c, int port) {
        String id = HdmiInputs.findInputIdForPort(c, port);
        if (id == null) {
            try {
                HdmiInputs.notify(c, c.getString(R.string.toast_no_input, c.getString(R.string.input_label, port)));
            } catch (Throwable ignored) {
            }
            return;
        }
        HdmiInputs.openViewer(c, id, "key");
    }
}
