package org.z9x.projector;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.util.Log;

import org.z9x.projector.ak.AkOverlay;
import org.z9x.projector.audio.AudioPathReporter;
import org.z9x.projector.audio.Z9xAudioFixer;
import org.z9x.projector.eye.EyeGuard;
import org.z9x.projector.panel.QuickPanel;
import org.z9x.projector.power.PowerPolicy;
import org.z9x.projector.remote.RemoteAutoPair;
import org.z9x.projector.ui.OverlayHost;

/**
 * Persistent process entry. Everything is wrapped: a crash here would restart the persistent
 * process in a tight loop. No HAL call happens here: HalController only starts its worker
 * threads, and the handshake itself waits for sys.boot_completed=1 and the lamp.
 *
 * v6.1 wiring (V61_ARCH.md): OverlayHost (overlay windows), module installs (AkOverlay,
 * PowerPolicy, RemoteAutoPair, EyeGuard), one SCREEN_ON/OFF receiver that fans out to the focus motor,
 * OverlayHost and PowerPolicy. Each install is isolated: one failing module never stops the others.
 */
public final class App extends Application {
    private static final String TAG = "Z9xProjector";

    /** Locales at the last onCreate / configuration change (main thread). */
    private LocaleList mLocales;

    @Override
    public void onCreate() {
        super.onCreate();
        try { mLocales = getResources().getConfiguration().getLocales(); } catch (Throwable ignored) { }
        try {
            Ui.main();
            Z9xAudioFixer.install(this);
        } catch (Throwable t) {
            Log.e(TAG, "audio fixer install", t);
        }
        HalController hal = null;
        try {
            hal = HalController.get(this);
            hal.start();
        } catch (Throwable t) {
            Log.e(TAG, "HalController start", t);
        }
        // ===== v6.3.1: media output path report to the vendor (IGmpf 59, stock audioserver behaviour) =====
        try { AudioPathReporter.install(this); } catch (Throwable t) { Log.e(TAG, "AudioPathReporter.install", t); }
        // ===== v6.4: equalizer re-apply triggers (SoC PEQ, IGmpf 50/51) and AI sound (61(3) + 63 per app) =====
        try { org.z9x.projector.audio.SoundEq.install(this); } catch (Throwable t) { Log.e(TAG, "SoundEq.install", t); }
        try { org.z9x.projector.audio.AiSoundEngine.install(this); } catch (Throwable t) { Log.e(TAG, "AiSoundEngine.install", t); }
        // vendor sound-restore detector (re-apply EQ / AI sub-mode when the vendor actually wrote)
        try { org.z9x.projector.audio.VendorSoundWatch.install(this); } catch (Throwable t) { Log.e(TAG, "VendorSoundWatch.install", t); }
        try {
            OverlayHost.get(this);
        } catch (Throwable t) {
            Log.e(TAG, "OverlayHost", t);
        }
        try { AkOverlay.install(this); } catch (Throwable t) { Log.e(TAG, "AkOverlay.install", t); }
        try { PowerPolicy.install(this); } catch (Throwable t) { Log.e(TAG, "PowerPolicy.install", t); }
        // ===== Lumen OS 1.0 module "cec": HDMI-CEC power (wake guards, PM51 cec0 arm, standby to devices) =====
        try { org.z9x.projector.cec.CecPolicy.install(this); } catch (Throwable t) { Log.e(TAG, "CecPolicy.install", t); }
        try { RemoteAutoPair.install(this); } catch (Throwable t) { Log.e(TAG, "RemoteAutoPair.install", t); }
        try { EyeGuard.install(this); } catch (Throwable t) { Log.e(TAG, "EyeGuard.install", t); }
        // ===== v6.2 module "screensaver": lamp dim/restore guard (marker recovery, SCREEN_OFF restore) =====
        try { org.z9x.projector.dream.DreamLamp.install(this); } catch (Throwable t) { Log.e(TAG, "DreamLamp.install", t); }
        // ===== v6.5: the idle timeout is ours (dream, never an Android sleep; screensaver turn-off -> standby) =====
        try { org.z9x.projector.dream.IdleOwner.install(this); } catch (Throwable t) { Log.e(TAG, "IdleOwner.install", t); }
        // ===== v6.2 module "game": HDMI console recognition + auto game profile (no HAL call here) =====
        try { org.z9x.projector.game.GameProfile.install(this); } catch (Throwable t) { Log.e(TAG, "GameProfile.install", t); }
        // ===== v6.3 manual keystone: resume real-time keystone if a session could not (paused marker) =====
        try { org.z9x.projector.panel.ManualKeystonePanel.install(this); } catch (Throwable t) { Log.e(TAG, "ManualKeystonePanel.install", t); }
        // ===== Lumen OS 1.0: SetupBridge events, Recents boot-clean at user unlock =====
        try { org.z9x.projector.setup.SetupEvents.install(this); } catch (Throwable t) { Log.e(TAG, "SetupEvents.install", t); }
        try { registerUnlockReceiver(); } catch (Throwable t) { Log.e(TAG, "unlock receiver", t); }
        try {
            registerScreenReceiver(hal);
        } catch (Throwable t) {
            Log.e(TAG, "screen receiver", t);
        }
    }

    /**
     * ACTION_USER_UNLOCKED is a registered-only broadcast: RecentTasks loads the persisted tasks at unlock,
     * so the Recents boot-clean (MemoryGuard P3) also runs then (once per boot).
     */
    private void registerUnlockReceiver() {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    if (i != null && Intent.ACTION_USER_UNLOCKED.equals(i.getAction())) {
                        org.z9x.projector.mem.MemoryGuard.onBoot(c, "user unlocked");
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
     * vendor limit checks remain the backstop, README_ru.md), close panels at once, then
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
                    try { org.z9x.projector.cec.CecPolicy.onScreenOff(c.getApplicationContext()); } catch (Throwable t) { Log.w(TAG, "CecPolicy.onScreenOff: " + t); }
                    try { org.z9x.projector.audio.AiSoundEngine.onScreenOff(); } catch (Throwable t) { Log.w(TAG, "AiSoundEngine.onScreenOff: " + t); }
                    try { org.z9x.projector.audio.VendorSoundWatch.onScreenOff(); } catch (Throwable t) { Log.w(TAG, "VendorSoundWatch.onScreenOff: " + t); }
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    try { PowerPolicy.onScreenOn(c.getApplicationContext()); } catch (Throwable t) { Log.w(TAG, "PowerPolicy.onScreenOn: " + t); }
                    try { org.z9x.projector.cec.CecPolicy.onScreenOn(c.getApplicationContext()); } catch (Throwable t) { Log.w(TAG, "CecPolicy.onScreenOn: " + t); }
                    try { AudioPathReporter.onScreenOn(); } catch (Throwable t) { Log.w(TAG, "AudioPathReporter.onScreenOn: " + t); }
                    try { org.z9x.projector.audio.SoundEq.onScreenOn(); } catch (Throwable t) { Log.w(TAG, "SoundEq.onScreenOn: " + t); }
                    try { org.z9x.projector.audio.AiSoundEngine.onScreenOn(); } catch (Throwable t) { Log.w(TAG, "AiSoundEngine.onScreenOn: " + t); }
                    try { org.z9x.projector.audio.VendorSoundWatch.onScreenOn(); } catch (Throwable t) { Log.w(TAG, "VendorSoundWatch.onScreenOn: " + t); }
                }
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        registerReceiver(screen, f, Context.RECEIVER_EXPORTED);
    }
}
