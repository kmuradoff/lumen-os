package org.lumen.projector;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.os.UserManager;
import android.util.Log;

import org.lumen.projector.ak.AkOverlay;
import org.lumen.projector.audio.AudioPathReporter;
import org.lumen.projector.audio.Z9xAudioFixer;
import org.lumen.projector.eye.EyeGuard;
import org.lumen.projector.hal.HbFeatures;
import org.lumen.projector.panel.QuickPanel;
import org.lumen.projector.power.PowerPolicy;
import org.lumen.projector.remote.RemoteAutoPair;
import org.lumen.projector.ui.OverlayHost;

/**
 * Persistent process entry. Everything is wrapped: a crash here would restart the persistent
 * process in a tight loop. No HAL call happens here: HalController only starts its worker
 * threads.
 *
 * Z9X HB fork: the process is direct boot aware and starts before
 * the user unlocks. onCreate starts only the feature table and HalController (the HbBoot handshake, device
 * protected storage); the rest of the Z9X onCreate body (startModules) runs once the user is unlocked
 * (isUserUnlocked, or ACTION_USER_UNLOCKED). No import of pre-1.0.8 org.z9x data (none on an HB), no 3D module
 * (support_3D=0).
 *
 * v6.1 wiring (v6.1 design): OverlayHost (overlay windows), module installs (AkOverlay,
 * PowerPolicy, RemoteAutoPair, EyeGuard), one SCREEN_ON/OFF receiver that fans out to the focus motor,
 * OverlayHost and PowerPolicy. Each install is isolated: one failing module never stops the others.
 */
public final class App extends Application {

    private static final String TAG = "Z9xProjector";

    /** startModules ran (main thread). */
    private boolean modulesStarted;

    /** Locales at the last onCreate / configuration change (main thread). */
    private LocaleList mLocales;

    @Override
    public void onCreate() {
        super.onCreate();
        try { mLocales = getResources().getConfiguration().getLocales(); } catch (Throwable ignored) { }
        try { Ui.main(); } catch (Throwable t) { Log.e(TAG, "Ui.main", t); }
        try { HbFeatures.init(); } catch (Throwable t) { Log.e(TAG, "HbFeatures.init", t); }
        HalController hal = null;
        try {
            hal = HalController.get(this);
            hal.start();
        } catch (Throwable t) {
            Log.e(TAG, "HalController start", t);
        }
        boolean unlocked = false;
        try {
            UserManager um = getSystemService(UserManager.class);
            unlocked = um == null || um.isUserUnlocked();
        } catch (Throwable t) {
            Log.w(TAG, "isUserUnlocked: " + t);
        }
        if (unlocked) {
            startModules(hal);
        } else {
            Log.i(TAG, "user locked: handshake only, the modules start at unlock");
            try { registerUnlockReceiver(hal); } catch (Throwable t) { Log.e(TAG, "unlock receiver", t); }
        }
    }

    /** The Z9X onCreate body after HalController.start (main thread, once, after the user unlocked). */
    private void startModules(HalController hal) {
        if (modulesStarted) return;
        modulesStarted = true;
        try {
            Z9xAudioFixer.install(this);
        } catch (Throwable t) {
            Log.e(TAG, "audio fixer install", t);
        }
        // ===== v6.3.1: media output path report to the vendor (IGmpf 59, stock audioserver behaviour) =====
        try { AudioPathReporter.install(this); } catch (Throwable t) { Log.e(TAG, "AudioPathReporter.install", t); }
        // ===== 1.0.4: manual sound output (stock Sound > Sound output), preferred device for the media strategy =====
        try { org.lumen.projector.audio.AudioOutputChoice.install(this); } catch (Throwable t) { Log.e(TAG, "AudioOutputChoice.install", t); }
        // ===== v6.4: equalizer re-apply triggers (SoC PEQ, IGmpf 50/51) and AI sound (61(3) + 63 per app) =====
        try { org.lumen.projector.audio.SoundEq.install(this); } catch (Throwable t) { Log.e(TAG, "SoundEq.install", t); }
        try { org.lumen.projector.audio.AiSoundEngine.install(this); } catch (Throwable t) { Log.e(TAG, "AiSoundEngine.install", t); }
        // vendor sound-restore detector (re-apply EQ / AI sub-mode when the vendor actually wrote)
        try { org.lumen.projector.audio.VendorSoundWatch.install(this); } catch (Throwable t) { Log.e(TAG, "VendorSoundWatch.install", t); }
        try {
            OverlayHost.get(this);
        } catch (Throwable t) {
            Log.e(TAG, "OverlayHost", t);
        }
        try { AkOverlay.install(this); } catch (Throwable t) { Log.e(TAG, "AkOverlay.install", t); }
        try { PowerPolicy.install(this); } catch (Throwable t) { Log.e(TAG, "PowerPolicy.install", t); }
        // ===== Lumen OS 1.0 module "cec": HDMI-CEC power (wake guards, PM51 cec0 arm, standby to devices) =====
        try { org.lumen.projector.cec.CecPolicy.install(this); } catch (Throwable t) { Log.e(TAG, "CecPolicy.install", t); }
        // Z9X HB: CEC volume control / System Audio follow the sound output choice at boot, wake and ARC (cec/HbSystemAudio)
        try { org.lumen.projector.cec.HbSystemAudio.install(this); } catch (Throwable t) { Log.e(TAG, "HbSystemAudio.install", t); }
        try { RemoteAutoPair.install(this); } catch (Throwable t) { Log.e(TAG, "RemoteAutoPair.install", t); }
        try { EyeGuard.install(this); } catch (Throwable t) { Log.e(TAG, "EyeGuard.install", t); }
        try { org.lumen.projector.voice.Assistant.install(this, new org.lumen.projector.voice.AssistantHost()); } catch (Throwable t) { Log.e(TAG, "Assistant.install", t); }
        // ===== Lumen OS 1.0.2: optical zoom, close a session a dead process left open (connect hook) =====
        try { org.lumen.projector.panel.OpticalZoomPanel.install(this); } catch (Throwable t) { Log.e(TAG, "OpticalZoomPanel.install", t); }
        // Lumen OS 1.0.4: zoom / shift and rotation screens close a session a dead process left open
        try { org.lumen.projector.panel.ZoomShiftPanel.install(this); } catch (Throwable t) { Log.e(TAG, "ZoomShiftPanel.install", t); }
        try { org.lumen.projector.panel.RotatePanel.install(this); } catch (Throwable t) { Log.e(TAG, "RotatePanel.install", t); }
        // ===== v6.2 module "screensaver": lamp dim/restore guard (marker recovery, SCREEN_OFF restore) =====
        try { org.lumen.projector.dream.DreamLamp.install(this); } catch (Throwable t) { Log.e(TAG, "DreamLamp.install", t); }
        // ===== v6.5: the idle timeout is ours (dream, never an Android sleep; screensaver turn-off -> standby) =====
        try { org.lumen.projector.dream.IdleOwner.install(this); } catch (Throwable t) { Log.e(TAG, "IdleOwner.install", t); }
        // ===== v6.2 module "game": HDMI console recognition + auto game profile (no HAL call here) =====
        // Z9X HB fork: the automatic console profile writes game settings by itself, so it starts only once
        // HbFeatures.F.GAME_MODE is ON (PENDING until the HB game values are checked)
        if (HbFeatures.on(HbFeatures.F.GAME_MODE)) {
            try { org.lumen.projector.game.GameProfile.install(this); } catch (Throwable t) { Log.e(TAG, "GameProfile.install", t); }
        }
        // ===== Lumen OS 1.0.3: the HDMI picture, IGmpf 698 follows tvinput's HDMI sessions (connect hook) =====
        try { org.lumen.projector.source.HdmiSource.install(this); } catch (Throwable t) { Log.e(TAG, "HdmiSource.install", t); }
        // ===== v6.3 manual keystone: resume real-time keystone if a session could not (paused marker) =====
        try { org.lumen.projector.panel.ManualKeystonePanel.install(this); } catch (Throwable t) { Log.e(TAG, "ManualKeystonePanel.install", t); }
        // ===== Lumen OS 1.0: SetupBridge events, Recents boot-clean at user unlock =====
        try { org.lumen.projector.setup.SetupEvents.install(this); } catch (Throwable t) { Log.e(TAG, "SetupEvents.install", t); }
        // ===== Lumen OS 1.0.1: interface resolution (quick panel row, "Keep this resolution?" after a change) =====
        // Z9X HB fork: the interface resolution row (quick panel extension) only with F.UI_RESOLUTION ON (PENDING), as
        // in SettingsActivity; the property gate inside UiResolution stays as well
        if (HbFeatures.on(HbFeatures.F.UI_RESOLUTION)) {
            try { org.lumen.projector.display.UiResolution.install(this); } catch (Throwable t) { Log.e(TAG, "UiResolution.install", t); }
        }
        // ===== Lumen OS 1.0.5: Google's Cast receiver off once (no Cast certificate for uncertified devices) =====
        try { org.lumen.projector.sys.CastOff.install(this); } catch (Throwable t) { Log.e(TAG, "CastOff.install", t); }
        try {
            registerScreenReceiver(hal);
        } catch (Throwable t) {
            Log.e(TAG, "screen receiver", t);
        }
        // Z9X HB fork (hb-t7): the HAL is usually connected before the modules start (direct boot split), so the
        // connect hooks they just added (DreamLamp / TimerLamp marker recovery, zoom / keystone session clean-up)
        // run now, once
        try { if (hal != null) hal.runLateConnectHooks(); } catch (Throwable t) { Log.e(TAG, "late connect hooks", t); }
    }

    /**
     * ACTION_USER_UNLOCKED is a registered-only broadcast: the modules start then (direct boot), and, as on the
     * Z9X, RecentTasks loads the persisted tasks at unlock, so the Recents boot-clean (MemoryGuard P3) also runs
     * then (once per boot). Registered only while the user is locked at process start.
     */
    private void registerUnlockReceiver(final HalController hal) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    if (i != null && Intent.ACTION_USER_UNLOCKED.equals(i.getAction())) {
                        startModules(hal);
                        org.lumen.projector.mem.MemoryGuard.onBoot(c, "user unlocked");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "unlock: " + t);
                }
            }
        };
        registerReceiver(r, new IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_EXPORTED);
    }

    /**
     * The persistent process is not restarted on a language change, so the overlays that build
     * their labels once (quick panel rows, AK captions) are rebuilt here: the panel is closed and
     * rebuilt with the new language on the next gear press. Main thread.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            LocaleList now = newConfig.getLocales();
            if (now == null || now.equals(mLocales)) return;
            Log.i(TAG, "locale changed " + mLocales + " -> " + now + ": rebuilding overlays");
            mLocales = now;
            try { QuickPanel.onLocaleChanged(this); } catch (Throwable t) { Log.w(TAG, "QuickPanel locale: " + t); }
            try { AkOverlay.onLocaleChanged(); } catch (Throwable t) { Log.w(TAG, "AkOverlay locale: " + t); }
        } catch (Throwable t) {
            Log.e(TAG, "onConfigurationChanged", t);
        }
    }

    /**
     * SCREEN_OFF: stop a running focus move (best effort; KNOWN LIMITATION: usually too late, the
     * vendor ignores manualFocus(2) once the display is off, and POWER never reaches the app; the
     * vendor limit checks remain the backstop), close panels at once, then
     * PowerPolicy.onScreenOff. SCREEN_ON: PowerPolicy.onScreenOn. Main thread.
     */
    private void registerScreenReceiver(final HalController hal) {
        BroadcastReceiver screen = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i == null ? null : i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    try { if (hal != null) hal.motor().stop(); } catch (Throwable t) { Log.w(TAG, "screen off motor: " + t); }
                    try { OverlayHost.get(c).onScreenOff(); } catch (Throwable t) { Log.w(TAG, "screen off overlays: " + t); }
                    try { PowerPolicy.onScreenOff(c.getApplicationContext()); } catch (Throwable t) { Log.w(TAG, "PowerPolicy.onScreenOff: " + t); }
                    try { org.lumen.projector.cec.CecPolicy.onScreenOff(c.getApplicationContext()); } catch (Throwable t) { Log.w(TAG, "CecPolicy.onScreenOff: " + t); }
                    try { org.lumen.projector.audio.AiSoundEngine.onScreenOff(); } catch (Throwable t) { Log.w(TAG, "AiSoundEngine.onScreenOff: " + t); }
                    try { org.lumen.projector.audio.VendorSoundWatch.onScreenOff(); } catch (Throwable t) { Log.w(TAG, "VendorSoundWatch.onScreenOff: " + t); }
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    try { PowerPolicy.onScreenOn(c.getApplicationContext()); } catch (Throwable t) { Log.w(TAG, "PowerPolicy.onScreenOn: " + t); }
                    try { org.lumen.projector.cec.CecPolicy.onScreenOn(c.getApplicationContext()); } catch (Throwable t) { Log.w(TAG, "CecPolicy.onScreenOn: " + t); }
                    try { org.lumen.projector.cec.HbSystemAudio.onScreenOn(); } catch (Throwable t) { Log.w(TAG, "HbSystemAudio.onScreenOn: " + t); }
                    try { AudioPathReporter.onScreenOn(); } catch (Throwable t) { Log.w(TAG, "AudioPathReporter.onScreenOn: " + t); }
                    try { org.lumen.projector.audio.SoundEq.onScreenOn(); } catch (Throwable t) { Log.w(TAG, "SoundEq.onScreenOn: " + t); }
                    try { org.lumen.projector.audio.AiSoundEngine.onScreenOn(); } catch (Throwable t) { Log.w(TAG, "AiSoundEngine.onScreenOn: " + t); }
                    try { org.lumen.projector.audio.VendorSoundWatch.onScreenOn(); } catch (Throwable t) { Log.w(TAG, "VendorSoundWatch.onScreenOn: " + t); }
                    try { org.lumen.projector.source.HdmiSource.onScreenOn(); } catch (Throwable t) { Log.w(TAG, "HdmiSource.onScreenOn: " + t); }
                    try { org.lumen.projector.source.BootSource.onPowerOn(c, "screen on"); } catch (Throwable t) { Log.w(TAG, "BootSource: " + t); }
                }
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        registerReceiver(screen, f, Context.RECEIVER_EXPORTED);
    }
}
