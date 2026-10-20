package org.z9x.projector;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import org.z9x.projector.battery.Battery;
import org.z9x.projector.diag.Probe;
import org.z9x.projector.home.LauncherSwitcher;
import org.z9x.projector.mem.MemoryGuard;
import org.z9x.projector.panel.ManualKeystonePanel;
import org.z9x.projector.power.PowerKey;
import org.z9x.projector.power.SleepTimer;
import org.z9x.projector.power.UiReady17;
import org.z9x.projector.remote.RemoteAutoPair;
import org.z9x.projector.setup.SetupBridgeProvider;
import org.z9x.projector.setup.SetupEvents;

import java.util.List;

/**
 * Start of the Lumen modules in the persistent process of the XGIMI Play 6 (docs/play6/SETTINGS_PLAN.md
 * 2.2). org.z9x.play6.App.onCreate calls {@link #onProcessStart} as its one added line, right after
 * BootHandshake.start (check K). That call only posts work: no HAL, no file I/O, no wait.
 *
 * The start gate, checked every 500 ms on p6-hal (no wake lock): sys.boot_completed=1; init.svc.bootanim
 * not "running"; the user unlocked; no live thread "play6-boot" in this process (BootHandshake runs there,
 * up to its trailing 394 read; read only, BootHandshake stays unchanged). Then 2 s more. There is no time
 * cap: if "play6-boot" never ends the gate never opens (fail closed), and a line is logged every 30 s.
 * Until then the Hal facade refuses every call.
 *
 * At the gate, in this order: the connected hooks of the probe (test builds), of the manual keystone
 * markers, of the battery (battery/Battery: its power event hook, first read 5 s after the connect) and of
 * the HDMI source (source/HdmiSource: IGmpf 698 against 696 after every connect); the SetupBridge events
 * (setup/SetupEvents: a connected hook and a focus listener, no HAL call);
 * the Hal facade (IGmpf connect with a death recipient, IGmpf2 identity, the focus listener 298 / 306, the
 * vendor power listener 235 / 242, a stale manual focus stop, then power/UiReady17 307(17) once per
 * boot_id, then the connected hooks);
 * MemoryGuard.onBoot; RemoteAutoPair.install (then the SetupBridge's remote_state reads it); the setup
 * hand-off check ({@link #resumeSetupIfNotHome}); the one-time device-name and speaker-volume fixes; the
 * sleep timer's volume restore; LauncherSwitcher.ensure (as the Z9X BootReceiver: a no-op until the
 * first-run setup is finished, then it repairs the recorded launcher choice). The focus listener comes before
 * 307(17) as in the Z9X HalController (17 last), so the events of the power-on AF / AK flow that 17 starts
 * reach the P6Focus log.
 *
 * First-run setup (docs/play6/impl/setup-design.md section 6): Lumen Setup (org.z9x.setup) is on the image
 * and sets the three setup flags itself at its end. {@link #provisionIfNoSetup} sets them only when no setup
 * app can do it (Lumen Setup missing or disabled, and no SETUP_WIZARD activity); at the gate
 * {@link #resumeSetupIfNotHome} starts the Setup when the flags are still 0 and HOME no longer resolves to it.
 *
 * Direct boot: only the application and org.z9x.play6.BootReceiver run before unlock. This class keeps
 * its one-shot flags in device-protected prefs ({@link #PREFS}); the shared code that uses
 * credential-protected prefs (RemoteAutoPair, MemoryGuard, AppSlots) starts only after the unlock check.
 */
public final class Play6App {
    private static final String TAG = "P6Hal";
    private static final long GATE_POLL_MS = 500;
    private static final long GATE_EXTRA_MS = 2_000;
    private static final long GATE_LOG_EVERY_MS = 30_000;
    private static final String HANDSHAKE_THREAD = "play6-boot";
    /** Device-protected prefs of the Play 6 one-shot flags (Play6App, power/UiReady17). */
    public static final String PREFS = "p6";
    private static final String K_NAME_DONE = "device_name_init";
    /** Stock parity 2026-10-21: the stock default device and Bluetooth names were replaced (fixStockNamesOnce). */
    private static final String K_NAMES_STOCK_DONE = "stock_names_fixed";
    private static final String K_SPEAKER_DONE = "spk_init";
    /** STREAM_MUSIC index the owner listened at on t10 (of 100; plan section 4, C 5.1). */
    private static final int SPEAKER_INIT_INDEX = 8;
    /** Lumen Setup's one activity: HOME priority 10, no SETUP_WIZARD category, disabled by itself at its end. */
    private static final ComponentName LUMEN_SETUP =
            new ComponentName("org.z9x.setup", "org.z9x.setup.SetupActivity");
    /** Disabled components too, and direct-boot aware or not, so a locked user's lookup still finds it. */
    private static final int MATCH_ANY = PackageManager.MATCH_DISABLED_COMPONENTS
            | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
            | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

    private static boolean sStarted;
    // p6-hal thread state
    private static long sWaitSince = -1;
    private static long sLastGateLog = -GATE_LOG_EVERY_MS;

    private Play6App() {}

    /** org.z9x.play6.App.onCreate, main thread, after BootHandshake.start. Posts only. */
    public static void onProcessStart(Context ctx) {
        try {
            synchronized (Play6App.class) {
                if (sStarted) return;
                sStarted = true;
            }
            final Context app = ctx.getApplicationContext();
            if (!Device.isPlay6()) {
                Log.w(TAG, "not a Play 6: the Lumen projector modules stay off");
                return;
            }
            Hal.init(app);
            // wiped data: the setup flags are Lumen Setup's; only without a setup app are they set here
            Hal.worker().post(() -> step("provisioning", () -> provisionIfNoSetup(app)));
            // the power key, the wake curtain and the screen hooks: receivers only, no HAL, no prefs
            Ui.main().post(() -> PowerKey.install(app));
            Hal.worker().post(() -> gateTick(app));
        } catch (Throwable t) {
            Log.e(TAG, "onProcessStart", t);
        }
    }

    /** p6-hal: one look at the gate conditions. */
    private static void gateTick(Context app) {
        long now = SystemClock.elapsedRealtime();
        if (sWaitSince < 0) sWaitSince = now;
        String why = gateBlocker(app);
        if (why != null) {
            if (now - sLastGateLog >= GATE_LOG_EVERY_MS) {
                sLastGateLog = now;
                Log.i(TAG, "gate closed: " + why + " (waiting " + (now - sWaitSince) / 1000 + " s)");
            }
            Hal.worker().postDelayed(() -> gateTick(app), GATE_POLL_MS);
            return;
        }
        Hal.worker().postDelayed(() -> openGate(app), GATE_EXTRA_MS);
    }

    /** The first gate condition that does not hold yet, or null. p6-hal. */
    private static String gateBlocker(Context app) {
        if (!"1".equals(SystemProperties.get("sys.boot_completed", ""))) return "sys.boot_completed";
        if ("running".equals(SystemProperties.get("init.svc.bootanim", ""))) return "boot animation";
        UserManager um = app.getSystemService(UserManager.class);
        if (um == null || !um.isUserUnlocked()) return "user locked";
        if (handshakeRunning()) return "boot handshake (" + HANDSHAKE_THREAD + ") running";
        return null;
    }

    /** A live thread "play6-boot" in this process (BootHandshake). Read only. */
    private static boolean handshakeRunning() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (HANDSHAKE_THREAD.equals(t.getName()) && t.isAlive()) return true;
        }
        return false;
    }

    /** p6-hal, once: the gate is open; start the modules in the order of the class comment. */
    private static void openGate(Context app) {
        String boot = Hal.bootId();
        String late = null;
        try {
            late = app.createDeviceProtectedStorageContext().getSharedPreferences("boot", Context.MODE_PRIVATE)
                    .getString("boot_id_late", null);
        } catch (Throwable t) {
            Log.w(TAG, "handshake prefs: " + t);
        }
        Log.i(TAG, "gate open " + SystemClock.elapsedRealtime() / 1000.0 + " s after boot (waited "
                + (SystemClock.elapsedRealtime() - sWaitSince) / 1000 + " s), handshake late stage "
                + (boot != null && boot.equals(late) ? "delivered this boot" : "not recorded for this boot"));
        // test builds only: registers a once-per-process connected hook, so it reads after 307(17)
        step("Probe", () -> Probe.install(app));
        // connected hook (no HAL call here): settles what a manual keystone session still owes the vendor
        step("ManualKeystonePanel.install", () -> ManualKeystonePanel.install(app));
        // connected hook (no HAL call here): settles what a zoom / rotation card session still owes (P3 item 21)
        step("Play6AdjustSession.install", () -> org.z9x.projector.panel.Play6AdjustSession.install(app));
        // connected hook (no HAL call here): a lowered ambient-light brightness left by a dead process (P3 item 21)
        step("AmbientLight.install", () -> org.z9x.projector.ambient.AmbientLight.install(app));
        // connected hook (no HAL call here) + a speaker Bluetooth role left set goes back to normal (P3 item 21)
        step("SpeakerMode.install", () -> org.z9x.projector.speaker.SpeakerMode.install(app));
        // stock parity: closing / opening the projector (IGmpf 227 event type 4) starts / ends the ambient speaker
        // owner 2026-10-21: the equalizer's re-apply triggers (no HAL call here)
        step("P6SoundEq.install", () -> org.z9x.projector.audio.P6SoundEq.install(app));
        step("SpeakerMode.installLidTrigger", () -> org.z9x.projector.speaker.SpeakerMode.installLidTrigger(app));
        // connected hook, power event hook, screen hooks (no HAL call here): the battery, plan 3.2
        step("Battery.install", () -> Battery.install(app));
        // Lumen 1.0.5: Bluetooth headphones get the media route and a non-zero vendor A2DP volume (no HAL call)
        step("BtAudio.install", () -> org.z9x.projector.audio.BtAudio.install(app));
        step("EyeGuard.install", () -> org.z9x.projector.eye.EyeGuard.install(app));
        // connected hook + SCREEN_ON hook (no HAL call here): IGmpf 698 follows tvinput's HDMI sessions (t14)
        step("HdmiSource.install", () -> org.z9x.projector.source.HdmiSource.install(app));
        // connected hook + focus listener (no HAL call here): the SetupBridge's "hal" / "af" events for Lumen Setup
        step("SetupEvents.install", () -> SetupEvents.install(app));
        step("Hal", () -> Hal.openGate(() -> UiReady17.sendOnce(app)));
        step("MemoryGuard.onBoot", () -> MemoryGuard.onBoot(app, "start gate"));
        step("BootSource.onPowerOn", () -> org.z9x.projector.source.BootSource.onPowerOn(app, "boot"));
        // the SetupBridge answers remote_state from RemoteAutoPair only once it is installed here, in gate order
        step("RemoteAutoPair.install", () -> Ui.main().post(() -> {
            RemoteAutoPair.install(app);
            SetupBridgeProvider.remoteStarted();
        }));
        // flags still 0, Lumen Setup enabled, HOME resolves elsewhere: an interrupted finish, the Setup goes on
        step("setup hand-off", () -> resumeSetupIfNotHome(app));
        step("device name", () -> fixDeviceNameOnce(app));
        step("stock names", () -> fixStockNamesOnce(app));
        step("speaker volume", () -> fixSpeakerVolumeOnce(app));
        step("SleepTimer.install", () -> SleepTimer.install(app));
        // the Setup's recorded launcher choice (z9x_launcher) when its set_launcher failed; no-op until setup ends
        step("LauncherSwitcher.ensure", () -> LauncherSwitcher.ensure(app));
    }

    private static void step(String what, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            Log.e(TAG, what, t);
        }
    }

    /** The device-protected prefs {@link #PREFS}. */
    public static SharedPreferences prefs(Context app) {
        return app.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The setup flags of wiped data (docs/play6/impl/setup-design.md section 6). Lumen Setup (org.z9x.setup)
     * is the first-run flow of this image; it has no SETUP_WIZARD category (a plain HOME activity, priority
     * 10) and sets the three flags itself at its end, then disables its activity. While its activity is
     * installed and enabled the flags are left to it. Otherwise (an image without it, or the activity
     * disabled by hand with the flags still 0) and with no SETUP_WIZARD activity either (the LineageOS
     * wizard and TvProvision are removed), nothing would mark the device and the user as set up, and the
     * framework keeps HOME and the remote's app keys blocked: then the three flags a finished setup sets
     * are set here. It never clears a flag.
     */
    private static void provisionIfNoSetup(Context app) {
        ContentResolver cr = app.getContentResolver();
        boolean device = Settings.Global.getInt(cr, Settings.Global.DEVICE_PROVISIONED, 0) == 1;
        boolean user = Settings.Secure.getInt(cr, "user_setup_complete", 0) == 1;
        boolean tv = Settings.Secure.getInt(cr, "tv_user_setup_complete", 0) == 1;
        if (device && user && tv) return;
        if (componentEnabled(app, LUMEN_SETUP)) {
            Log.i(TAG, "provisioning: Lumen Setup is installed and enabled, left to it (device " + device
                    + ", user " + user + ", tv " + tv + ")");
            return;
        }
        Intent setup = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.SETUP_WIZARD");
        // enabled ones only, direct-boot aware or not (the user may still be locked here)
        if (!app.getPackageManager().queryIntentActivities(setup,
                PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE).isEmpty()) {
            Log.i(TAG, "provisioning: a setup app is installed, left to it");
            return;
        }
        if (!device) Settings.Global.putInt(cr, Settings.Global.DEVICE_PROVISIONED, 1);
        if (!user) Settings.Secure.putInt(cr, "user_setup_complete", 1);
        if (!tv) Settings.Secure.putInt(cr, "tv_user_setup_complete", 1);
        Log.i(TAG, "provisioning: no setup app, marked set up (device " + device + ", user " + user + ", tv " + tv
                + " before)");
    }

    /**
     * The setup hand-off at the start gate (docs/play6/impl/setup-design.md 6.5), p6-hal, once per boot.
     * Lumen Setup's finish saves the HOME role (set_launcher, its step 2) before it writes the three flags
     * (step 3). A power cut or a crash between the two leaves the flags at 0 and the Setup enabled in state
     * "finishing", while HOME may resolve to Lumen Home: then nothing starts the Setup, its headless re-finish
     * never runs, and {@link #provisionIfNoSetup} leaves the flags to it, so Lumen Home would stay with the app
     * keys, Source, the mic and HOME blocked. So when the flags are not all 1, the Setup's activity is enabled
     * and the HOME intent does not resolve to it, the Setup is started here; its own re-entry rules decide
     * (state finishing: the finish again, headless; otherwise its UI at the stored step). Nothing happens on
     * a wiped first boot (HOME is the Setup) or once the flags are 1. The Z9X Setup and its Finisher are
     * unchanged; this is the Play 6's net only.
     */
    private static void resumeSetupIfNotHome(Context app) {
        ContentResolver cr = app.getContentResolver();
        boolean device = Settings.Global.getInt(cr, Settings.Global.DEVICE_PROVISIONED, 0) == 1;
        boolean user = Settings.Secure.getInt(cr, "user_setup_complete", 0) == 1;
        boolean tv = Settings.Secure.getInt(cr, "tv_user_setup_complete", 0) == 1;
        if (device && user && tv) return;
        if (!componentEnabled(app, LUMEN_SETUP)) return;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = app.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        ActivityInfo ai = ri == null ? null : ri.activityInfo;
        if (ai != null && LUMEN_SETUP.getPackageName().equals(ai.packageName)) return;   // HOME starts it itself
        String resolved = ai == null ? "nothing" : ai.packageName + "/" + ai.name;
        Log.w(TAG, "setup hand-off: setup flags not all set (device " + device + ", user " + user + ", tv " + tv
                + "), Lumen Setup enabled, HOME resolves to " + resolved + ": starting Lumen Setup");
        Ui.main().post(() -> {
            try {
                app.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(LUMEN_SETUP)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                Log.w(TAG, "setup hand-off: start Lumen Setup: " + t);    // disabled meanwhile: its finish ran
            }
        });
    }

    /**
     * Effective enabled state of an activity: an explicit setting wins, DEFAULT means the manifest value and
     * the application's; false when it is not installed. A Play 6 copy of the package-private
     * LauncherSwitcher.componentEnabled (shared file, not edited for one device), plus the two direct-boot
     * match flags: this runs at process start, possibly before the user is unlocked, and a locked user's
     * lookup would otherwise not find an activity that is not direct-boot aware (Lumen Setup's is not), which
     * would read as "not installed" and set the flags under the Setup.
     */
    private static boolean componentEnabled(Context app, ComponentName cn) {
        PackageManager pm = app.getPackageManager();
        try {
            int st = pm.getComponentEnabledSetting(cn);
            if (st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
            if (st != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) return false;
            ActivityInfo ai = pm.getActivityInfo(cn, MATCH_ANY);
            return ai.enabled && ai.applicationInfo != null && ai.applicationInfo.enabled;
        } catch (Throwable t) {
            return false;                                   // not installed
        }
    }

    /**
     * Once per data: Settings.Global device_name becomes ro.product.model, only while it is empty or names
     * a Z9X, so Lumen Home's "XGIMI Z9X" fallback is never reached and no Z9X name is advertised over
     * Cast, AirPlay or Bluetooth (plan 1.1). A name the owner chose is kept.
     */
    private static void fixDeviceNameOnce(Context app) {
        SharedPreferences p = prefs(app);
        if (p.getBoolean(K_NAME_DONE, false)) return;
        ContentResolver cr = app.getContentResolver();
        String model = SystemProperties.get("ro.product.model", "").trim();
        String cur = Settings.Global.getString(cr, Settings.Global.DEVICE_NAME);
        boolean replace = cur == null || cur.trim().isEmpty() || cur.contains("Z9X");
        if (replace && !model.isEmpty() && !model.contains("Z9X")) {
            Settings.Global.putString(cr, Settings.Global.DEVICE_NAME, model);
            Log.i(TAG, "device_name '" + cur + "' -> '" + model + "'");
        } else {
            Log.i(TAG, "device_name kept: '" + cur + "' (model '" + model + "')");
        }
        p.edit().putBoolean(K_NAME_DONE, true).apply();
    }

    /**
     * Stock parity 2026-10-21 (device test: device_name "XGIMI TV", Bluetooth name "我的Play 6", both left by the stock
     * firmware): once per data, a device name that is a stock or base default becomes ro.product.model (stock
     * system.ini DeviceShowName / BluetoothDeviceName "XGIMI Play 6"), and a Bluetooth name that is a stock default
     * becomes the device name. A name the owner typed is never touched (only the listed defaults). Nothing of
     * factory data is read or written: only Settings.Global device_name and BluetoothAdapter.setName. The flag is
     * set only when both are settled (Bluetooth off: tried again at the next start).
     */
    private static void fixStockNamesOnce(Context app) {
        SharedPreferences p = prefs(app);
        if (p.getBoolean(K_NAMES_STOCK_DONE, false)) return;
        ContentResolver cr = app.getContentResolver();
        String model = SystemProperties.get("ro.product.model", "").trim();
        if (model.isEmpty() || model.contains("Z9X")) return;
        String dev = Settings.Global.getString(cr, Settings.Global.DEVICE_NAME);
        if (dev == null || STOCK_DEVICE_NAMES.contains(dev.trim())) {
            Settings.Global.putString(cr, Settings.Global.DEVICE_NAME, model);
            Log.i(TAG, "device_name '" + dev + "' (stock default) -> '" + model + "'");
            dev = model;
        }
        try {
            android.bluetooth.BluetoothManager bm = app.getSystemService(android.bluetooth.BluetoothManager.class);
            android.bluetooth.BluetoothAdapter a = bm == null ? null : bm.getAdapter();
            if (a == null || a.getState() != android.bluetooth.BluetoothAdapter.STATE_ON) {
                Log.i(TAG, "bluetooth name: adapter not on, next start");
                return;
            }
            String bt = a.getName();
            if (bt != null && STOCK_BT_NAMES.contains(bt.trim())) {
                Log.i(TAG, "bluetooth name '" + bt + "' (stock default) -> '" + dev + "': " + a.setName(dev));
            } else {
                Log.i(TAG, "bluetooth name kept: '" + bt + "'");
            }
        } catch (Throwable t) {
            Log.w(TAG, "bluetooth name: " + t);
            return;
        }
        p.edit().putBoolean(K_NAMES_STOCK_DONE, true).apply();
    }

    /** Device names of the stock firmware and the base image (never a user's own name). */
    private static final java.util.Set<String> STOCK_DEVICE_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "", "XGIMI TV", "AOSP TV on ARM", "INUI_TV"));
    /** Bluetooth names of the stock firmware (its "my device" default 我的Play 6, INUI_TV, XGIMI TV). */
    private static final java.util.Set<String> STOCK_BT_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "我的Play 6", "我的Play6", "INUI_TV", "XGIMI TV", "AOSP TV on ARM"));

    /**
     * Once per data, after the audio-jack fix of Play6FrameworkOverlay (plan section 4, C 5.1): t10 routed
     * all media to a phantom wired headphone and left the speaker index at 0, so the first boot with the
     * fix would be silent. When media plays on the built-in speaker and its STREAM_MUSIC index is 0, set
     * {@link #SPEAKER_INIT_INDEX}. Decided only once the speaker is the active device (a boot that still
     * plays on the headphone checks again next boot).
     */
    private static void fixSpeakerVolumeOnce(Context app) {
        SharedPreferences p = prefs(app);
        if (p.getBoolean(K_SPEAKER_DONE, false)) return;
        AudioManager am = app.getSystemService(AudioManager.class);
        if (am == null) return;
        AudioAttributes media = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
        List<AudioDeviceAttributes> devs = am.getDevicesForAttributes(media);
        int type = devs == null || devs.isEmpty() ? -1 : devs.get(0).getType();
        if (type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            Log.i(TAG, "speaker volume: media plays on device type " + type + ", checked again next boot");
            return;
        }
        int idx = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (idx == 0) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, SPEAKER_INIT_INDEX, 0);
            Log.i(TAG, "speaker volume: index 0 -> " + SPEAKER_INIT_INDEX + " (once)");
        } else {
            Log.i(TAG, "speaker volume: index " + idx + " kept");
        }
        p.edit().putBoolean(K_SPEAKER_DONE, true).apply();
    }
}
