package org.lumen.projector;

import android.content.Context;
import android.util.Log;

import org.lumen.projector.hal.GmpfClient;
import org.lumen.projector.panel.PanelRules;

/**
 * Lumen OS 1.0.8 Z9X sweep (2026-11-04): the AI picture default scene the way stock XRMService keeps it (AIPQ.java:133,
 * 401-408; XRMService.java:164). After every HAL (re)connect and at every screen on: when IGmpf 666 reads an AI scene
 * (10..17), 667(0, 16) "film" is written again (G0082: support_aipq_mode=true, support_multipq_type 2, so stock uses
 * PIC_DEFAULT 16). Without it the vendor stayed at scene 10 ("tv") for minutes on 1.0.8 (diag boot #44). Z9X App only;
 * HAL work on "z9x-hal".
 */
public final class AiScenePolicy {
    private static final String TAG = "Z9xAiScene";
    private static boolean sInstalled;

    private AiScenePolicy() {}

    /** Z9X App.onCreate, main thread, once. */
    public static synchronized void install(Context ctx) {
        if (sInstalled) return;
        sInstalled = true;
        Hal.addConnectedListener(() -> reassert("HAL connected"));
    }

    /** Any thread: queues the check on the HAL thread. */
    public static void reassert(String why) {
        Hal.run((g, g2) -> {
            int raw = g.getPictureModeRaw();
            if (!PanelRules.aiSceneReassert(raw)) return;
            boolean ok = g.setAiScene(GmpfClient.AiScene.FILM);
            Log.i(TAG, why + ": 666 read " + raw + ", 667(0,16) -> " + ok);
        });
    }
}
