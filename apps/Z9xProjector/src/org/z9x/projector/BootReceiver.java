package org.z9x.projector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.z9x.projector.audio.AudioPathReporter;
import org.z9x.projector.audio.Z9xAudioFixer;
import org.z9x.projector.sys.SystemFixes;

/**
 * BOOT_COMPLETED (and LOCKED_BOOT_COMPLETED, harmless): kick the HAL handshake, the audio fixer, the
 * v6.3.1 audio path report, the v6.4 equalizer boot re-apply and the v6.1 runtime system fixes (module "sys", BOOT_COMPLETED only),
 * and the v6.5 boot-dark check (StandbyController.onBootCompleted).
 * Lumen OS 1.0: Recents boot-clean, first-boot disables and the ASSISTANT role (MemoryGuard; the
 * runtime USER_UNLOCKED receiver in App triggers the same), and the launcher repair
 * (LauncherSwitcher.ensure, a no-op until the first-run setup chose a launcher).
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)) return;
        Log.i("Z9xProjector", "boot broadcast " + a);
        try {
            HalController.get(ctx).onBootCompleted();
        } catch (Throwable t) {
            Log.e("Z9xProjector", "BootReceiver hal", t);
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)) return;
        try {
            // v6.5 boot-dark: an unattended reset (0xF1, no wake source) powers off again
            org.z9x.projector.power.StandbyController.onBootCompleted(ctx);
        } catch (Throwable t) {
            Log.e("Z9xProjector", "BootReceiver boot-dark", t);
        }
        try {
            org.z9x.projector.mem.MemoryGuard.onBoot(ctx, "boot completed");
        } catch (Throwable t) {
            Log.e("Z9xProjector", "BootReceiver memory guard", t);
        }
        try {
            org.z9x.projector.home.LauncherSwitcher.ensure(ctx);
        } catch (Throwable t) {
            Log.e("Z9xProjector", "BootReceiver launcher", t);
        }
        try {
            Z9xAudioFixer.onBootCompleted(ctx);
        } catch (Throwable t) {
            Log.e("Z9xProjector", "BootReceiver audio", t);
        }
        try {
            AudioPathReporter.onBootCompleted(ctx);
        } catch (Throwable t) {
            Log.e("Z9xProjector", "BootReceiver audio path", t);
        }
        try {
            org.z9x.projector.audio.SoundEq.onBootCompleted(ctx);    // v6.4 equalizer after the vendor restore
        } catch (Throwable t) {
            Log.e("Z9xProjector", "BootReceiver equalizer", t);
        }
        try {
            org.z9x.projector.audio.AiSoundEngine.onBootCompleted(ctx);   // v6.4 AI sub-mode after the vendor restore
        } catch (Throwable t) {
            Log.e("Z9xProjector", "BootReceiver AI sound", t);
        }
        try {
            SystemFixes.onBoot(ctx.getApplicationContext());
        } catch (Throwable t) {
            Log.e("Z9xProjector", "SystemFixes.onBoot", t);
        }
    }
}
