package org.z9x.projector.setup;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.Ui;
import org.z9x.projector.ak.AkOverlay;
import org.z9x.projector.home.LauncherSwitcher;
import org.z9x.projector.panel.QuickPanel;
import org.z9x.projector.remote.RemoteAutoPair;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Lumen SetupBridge of the XGIMI Play 6 (docs/play6/impl/setup-design.md section 3): Lumen Setup
 * (org.z9x.setup) asks this persistent app for the hardware work of its remote and picture steps, its device
 * name and its launcher choice. The Play 6 class of the same name as the Z9X provider (tools/play6/forks.tsv):
 * the same transport, methods, replies and log line, built only on the Play 6 Hal facade (start gate, p6-hal /
 * p6-long, its own busy gates and toasts), the shared RemoteAutoPair and the shared LauncherSwitcher.
 *
 * <b>Transport</b>: authority {@value #AUTHORITY}, exported, permission {@value #PERMISSION} (signature,
 * defined by this app); only {@link #call} is used and it checks the permission itself (the framework enforces
 * a provider's permission only for query / insert / update / delete). The reply always has {@code ok}; on
 * failure {@code err} = not_ready | busy | feature_off | unsupported | denied. Log: one line per call, tag
 * P6Bridge, {@code <caller> <method> -> <ok|err> <ms>} (ping and the 1-2 Hz state polls only when they fail).
 *
 * <b>Methods</b> (API {@value #API_VERSION}):
 *  - ping: always answered, also before the start gate and on a device Device.isPlay6 rejects (the Setup
 *    decides from its first ping alone whether the bridge exists): version, projectorVersion(Code) and the
 *    optional {@code names}, this projector's own names for the Setup's name picker, from ro.product.model
 *    ("XGIMI Play 6", "Play 6"; that pair when the model is unset or names a Z9X);
 *  - remote_state: the shared RemoteAutoPair.state once Play6App installed it at the start gate
 *    ({@link #remoteStarted}); until then a "starting" snapshot (bonded 0, nothing connected), so a binder
 *    thread never installs the module early and off the gate order;
 *  - hal_state: ready (Hal.isReady: gate open and IGmpf connected), kstRunning, afBusy, akOverlay and
 *    curtainFit false (no fit-to-screen hardware). Flags only, no HAL call;
 *  - af_run, kst_auto: Hal.requestAutofocus / Hal.requestKeystone on the main thread, as from a key (307(44)
 *    and 326 keep their single senders); kst_fit: always unsupported, nothing sent;
 *  - kst_manual, focus_manual: the quick panel's manual keystone panel (it says itself why it refuses) and
 *    the manual focus overlay, after the not_ready / busy gates;
 *  - toggles_get, toggle_set: MOVE_AK only, the real-time keystone (IGmpf 585 set, 586 read back through the
 *    Hal facade, as the quick panel's row); any other name: denied. POWER_ON_AF is never offered, so the
 *    Setup never builds that row;
 *  - set_device_name: Settings.Global device_name and the Bluetooth adapter name;
 *  - set_launcher: LauncherSwitcher.applySync (classic answers unsupported: no tvlauncher on this image);
 *  - feature: enabled false for every name.
 * Deliberately NOT offered: any generic IGmpf code, calibration, factory or motor calls from another
 * process, Bluetooth bond / unbond.
 */
public final class SetupBridgeProvider extends ContentProvider {
    private static final String TAG = "P6Bridge";
    public static final String AUTHORITY = "org.z9x.projector.setupbridge";
    public static final String PERMISSION = "org.z9x.projector.permission.SETUP_BRIDGE";
    public static final int API_VERSION = 1;
    /** The only toggle of the Setup's picture step this projector has (Play6Gmpf 585 / 586). */
    public static final String T_MOVE_AK = "MOVE_AK";

    private static final long UI_WAIT_MS = 400;
    private static final long TOGGLE_WAIT_MS = 1_200;
    private static final long LAUNCHER_WAIT_MS = 5_500;
    /** This projector's names when ro.product.model gives none (the first one is the default name). */
    private static final String[] NAMES = {"XGIMI Play 6", "Play 6"};
    private static final String BRAND = "XGIMI ";

    /** Play6App installed RemoteAutoPair (main thread, start gate): remote_state may read it from now on. */
    private static volatile boolean sRemoteStarted;

    @Override
    public boolean onCreate() {
        return true;
    }

    /** Play6App, main thread, right after RemoteAutoPair.install at the start gate. */
    public static void remoteStarted() {
        sRemoteStarted = true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx == null) return null;
        if (ctx.checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("needs " + PERMISSION);
        }
        final long t0 = SystemClock.uptimeMillis();
        String caller;
        try {
            caller = getCallingPackage();
        } catch (Throwable t) {
            caller = "?";
        }
        Bundle r;
        try {
            r = dispatch(ctx.getApplicationContext(), method == null ? "" : method, arg, extras == null ? new Bundle() : extras);
        } catch (Throwable t) {
            Log.w(TAG, caller + " " + method + ": " + t);
            r = err("not_ready");
        }
        boolean ok = r.getBoolean("ok", false);
        boolean poll = "hal_state".equals(method) || "remote_state".equals(method) || "ping".equals(method);
        if (!poll || !ok) {
            Log.i(TAG, caller + " " + method + " -> " + (ok ? "ok" : r.getString("err", "?")) + " "
                    + (SystemClock.uptimeMillis() - t0));
        }
        return r;
    }

    private static Bundle dispatch(Context app, String method, String arg, Bundle x) {
        switch (method) {
            case "ping": {
                Bundle b = ok();
                b.putInt("version", API_VERSION);
                try {
                    PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                    b.putString("projectorVersion", pi.versionName);
                    b.putLong("projectorVersionCode", pi.getLongVersionCode());
                } catch (Throwable ignored) { }
                b.putStringArray("names", names());
                return b;
            }
            case "remote_state": {
                Bundle b = remoteBundle(app);
                b.putBoolean("ok", true);
                return b;
            }
            case "hal_state": {
                Bundle b = halBundle();
                b.putBoolean("ok", true);
                return b;
            }
            case "af_run":
                return request(onMain(() -> Hal.requestAutofocus(app)));
            case "kst_auto":
                return request(onMain(() -> Hal.requestKeystone(app)));
            case "kst_fit":
                return err("unsupported");                         // no fit-to-screen on this projector
            case "kst_manual": {
                if (!Hal.isReady()) return err("not_ready");
                if (Hal.isKeystoneRunning() || AkOverlay.isActive()) return err("busy");
                Ui.main().post(() -> QuickPanel.show(app, QuickPanel.SECTION_MANUAL_KEYSTONE));
                return ok();
            }
            case "focus_manual": {
                if (!Hal.isReady()) return err("not_ready");
                if (Hal.isKeystoneRunning() || AkOverlay.isActive()) return err("busy");
                Ui.main().post(() -> Hal.openManualFocus(app));
                return ok();
            }
            case "toggles_get":
                return moveAk(null);
            case "toggle_set": {
                String name = x.getString("name");
                if (!T_MOVE_AK.equals(name) || !x.containsKey("on")) return err("denied");
                return moveAk(x.getBoolean("on"));
            }
            case "set_device_name":
                return setDeviceName(app, x.getString("name"));
            case "set_launcher": {
                String target = x.getString("target");
                if (!LauncherSwitcher.MODE_LUMEN.equals(target) && !LauncherSwitcher.MODE_CLASSIC.equals(target)) {
                    return err("denied");
                }
                return LauncherSwitcher.applySync(app, target, "setup bridge", LAUNCHER_WAIT_MS);
            }
            case "feature": {
                Bundle b = ok();
                b.putBoolean("enabled", false);
                return b;
            }
            default:
                return err("unsupported");
        }
    }

    /**
     * ping's names: ro.product.model (the image's product_prop) when it is a clean name without "Z9X", and
     * the same without a leading "XGIMI "; {@link #NAMES} otherwise. Each is a clean name of 1..32 characters
     * (the Setup takes the list only then).
     */
    static String[] names() {
        String model = clean(SystemProperties.get("ro.product.model", ""));
        if (model == null || model.contains("Z9X")) return NAMES.clone();
        String shortName = model.startsWith(BRAND) ? clean(model.substring(BRAND.length())) : null;
        return shortName == null || shortName.equals(model) ? new String[] {model} : new String[] {model, shortName};
    }

    /** The Setup's Bridge.cleanName rule, null when the result is empty or longer than 32 characters. */
    private static String clean(String raw) {
        if (raw == null) return null;
        String n = raw.trim().replaceAll("\\s+", " ");
        return n.isEmpty() || n.length() > 32 ? null : n;
    }

    // ------------------------------------------------------------------ state bundles (also SetupEvents)

    /**
     * remote_state fields: bonded, connected, name, scanning, mode, pairing, found. connected = a bonded
     * XGIMI remote whose link AND HID input device are up (its keys work). Before the start gate installed
     * RemoteAutoPair: the "starting" snapshot (the Setup shows "Searching for the remote"). Any thread.
     */
    static Bundle remoteBundle(Context app) {
        if (!sRemoteStarted) {
            Bundle b = new Bundle();
            b.putInt("bonded", 0);
            b.putBoolean("connected", false);
            b.putString("name", "");
            b.putBoolean("scanning", false);
            b.putString("mode", "starting");
            b.putBoolean("pairing", false);
            b.putString("found", "");
            return b;
        }
        return remoteBundle(RemoteAutoPair.state(app));
    }

    /** The remote_state fields of a RemoteAutoPair snapshot (SetupEvents' "remote" event too). */
    static Bundle remoteBundle(RemoteAutoPair.State st) {
        Bundle b = new Bundle();
        b.putInt("bonded", st.bonded);
        b.putBoolean("connected", st.connected);
        b.putString("name", st.name);
        b.putBoolean("scanning", st.scanning);
        b.putString("mode", st.mode);
        b.putBoolean("pairing", st.pairing);
        b.putString("found", st.found);
        return b;
    }

    /** hal_state fields: ready, kstRunning, afBusy, akOverlay, curtainFit (always false here). Flags only. */
    static Bundle halBundle() {
        Bundle b = new Bundle();
        b.putBoolean("ready", Hal.isReady());
        b.putBoolean("kstRunning", Hal.isKeystoneRunning());
        b.putBoolean("afBusy", Hal.isAfBusy());
        b.putBoolean("akOverlay", AkOverlay.isActive());
        b.putBoolean("curtainFit", false);
        return b;
    }

    // ------------------------------------------------------------------ actions

    /** Runs a REQ_* request on the main thread (as from a key) and waits briefly. */
    private interface Req { int run(); }

    private static int onMain(Req r) {
        final AtomicReference<Integer> out = new AtomicReference<>();
        final CountDownLatch l = new CountDownLatch(1);
        boolean posted = Ui.main().post(() -> {
            try {
                out.set(r.run());
            } catch (Throwable t) {
                Log.w(TAG, "request: " + t);
                out.set(Hal.REQ_NOT_READY);
            } finally {
                l.countDown();
            }
        });
        if (!posted) return Hal.REQ_NOT_READY;
        try {
            if (!l.await(UI_WAIT_MS, TimeUnit.MILLISECONDS)) return Hal.REQ_BUSY;
        } catch (InterruptedException e) {
            return Hal.REQ_BUSY;
        }
        Integer v = out.get();
        return v == null ? Hal.REQ_NOT_READY : v;
    }

    private static Bundle request(int r) {
        switch (r) {
            case Hal.REQ_OK: return ok();
            case Hal.REQ_BUSY: return err("busy");
            case Hal.REQ_UNSUPPORTED: return err("feature_off");
            default: return err("not_ready");
        }
    }

    /**
     * toggles_get (on null) or toggle_set of MOVE_AK: 585 (set) then 586 (read back) in one task on p6-hal,
     * the quick panel's "Real-time keystone" pair; the reply carries MOVE_AK as read back. Not ready while
     * the start gate is closed or IGmpf is not connected (no refused task, no toast), busy after 1.2 s.
     */
    private static Bundle moveAk(Boolean on) {
        if (!Hal.isReady()) return err("not_ready");
        final AtomicReference<Boolean> out = new AtomicReference<>();
        final CountDownLatch l = new CountDownLatch(1);
        try {
            Hal.query((g, g2) -> {
                if (on != null) {
                    boolean r = g.setMoveAk(on);                   // 585
                    Log.i(TAG, "585 real-time keystone " + on + " -> " + r);
                }
                return g.getMoveAk();                              // 586
            }, v -> {
                out.set(v);
                l.countDown();
            });
        } catch (Throwable t) {
            Log.w(TAG, "move_ak: " + t);
            return err("not_ready");
        }
        try {
            if (!l.await(TOGGLE_WAIT_MS, TimeUnit.MILLISECONDS)) return err("busy");
        } catch (InterruptedException e) {
            return err("busy");
        }
        Boolean v = out.get();
        if (v == null) return err("not_ready");
        Bundle b = ok();
        b.putBoolean(T_MOVE_AK, v);
        return b;
    }

    /**
     * Settings.Global device_name (Cast and AirPlay follow it) and the Bluetooth adapter name
     * (BLUETOOTH_CONNECT, held). 1..32 printable characters after trimming.
     */
    private static Bundle setDeviceName(Context app, String raw) {
        if (raw == null) return err("denied");
        String n = raw.replaceAll("[\\p{Cntrl}]", "").trim().replaceAll("\\s+", " ");
        if (n.isEmpty() || n.length() > 32) return err("denied");
        try {
            Settings.Global.putString(app.getContentResolver(), Settings.Global.DEVICE_NAME, n);
        } catch (Throwable t) {
            Log.w(TAG, "device_name: " + t);
            return err("not_ready");
        }
        try {
            BluetoothManager bm = app.getSystemService(BluetoothManager.class);
            BluetoothAdapter a = bm == null ? null : bm.getAdapter();
            if (a != null && a.isEnabled()) a.setName(n);
        } catch (Throwable t) {
            Log.w(TAG, "bluetooth name: " + t);       // device_name is set; BT follows at its next start
        }
        Log.i(TAG, "device name set (" + n.length() + " chars)");
        return ok();
    }

    // ------------------------------------------------------------------ replies

    private static Bundle ok() {
        Bundle b = new Bundle();
        b.putBoolean("ok", true);
        return b;
    }

    private static Bundle err(String e) {
        Bundle b = new Bundle();
        b.putBoolean("ok", false);
        b.putString("err", e);
        return b;
    }

    // ------------------------------------------------------------------ unused ContentProvider API

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
