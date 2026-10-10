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
import android.provider.Settings;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.Ui;
import org.z9x.projector.ak.AkOverlay;
import org.z9x.projector.home.LauncherSwitcher;
import org.z9x.projector.panel.QuickPanel;
import org.z9x.projector.remote.RemoteAutoPair;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lumen OS 1.0 SetupBridge (setup/bridge_api.md, PLAN C1): Lumen Setup (org.z9x.setup) and Lumen Home
 * ask the persistent projector app to do hardware work. This app stays the only one that talks to the
 * HAL and to Bluetooth bonding; every existing gate and whitelist stays where it is (HalController's
 * kstInFlight / AF busy / AkOverlay / motor stop, RemoteAutoPair, the GmpfClient whitelist).
 *
 * <b>Transport</b>: authority {@value #AUTHORITY}, exported, permission {@value #PERMISSION}
 * (signature, defined here); only {@link #call} is used and it checks the permission itself (the
 * framework enforces a provider's permission only for query / insert / update / delete). The reply always
 * has {@code ok}; on failure {@code err} = not_ready | busy | feature_off | unsupported | denied.
 * Work on the HAL is posted to the existing hal / kst threads; user actions run on the main thread (as
 * from a key) and the binder thread waits briefly for them. Log: one line per call, tag Z9xBridge,
 * {@code <caller> <method> -> <ok|err> <ms>} (the 1-2 Hz state polls only when they fail).
 *
 * <b>Methods</b>: ping, remote_state, hal_state, af_run, kst_auto, kst_fit, kst_manual, focus_manual,
 * toggles_get, toggle_set, set_device_name, set_launcher, feature. Deliberately NOT offered: any generic
 * IGmpf code / transact, calibration, factory or gyro (583) calls, manualFocus motor runs from another
 * process (manual focus stays inside ManualFocusActivity with its watchdog), Bluetooth bond / unbond.
 */
public final class SetupBridgeProvider extends ContentProvider {
    private static final String TAG = "Z9xBridge";
    public static final String AUTHORITY = "org.z9x.projector.setupbridge";
    public static final String PERMISSION = "org.z9x.projector.permission.SETUP_BRIDGE";
    public static final int API_VERSION = 1;

    private static final long UI_WAIT_MS = 400;
    private static final long TOGGLE_WAIT_MS = 1_200;
    private static final long LAUNCHER_WAIT_MS = 5_500;
    private static final long CURTAIN_CACHE_MS = 1_000;
    private static final long CURTAIN_FIRST_WAIT_MS = 150;

    /** Features the first-run setup may ask about; the ALICE track flips "alice" later. */
    private static final String[][] FEATURES = {{"alice", "false"}};

    /** hal_state's curtainFit, read through the hal thread, cached at most CURTAIN_CACHE_MS. */
    private static volatile Boolean sCurtainFit;
    private static volatile long sCurtainAt;
    private static volatile boolean sCurtainReading;

    @Override
    public boolean onCreate() {
        return true;
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
                return b;
            }
            case "remote_state": {
                Bundle b = remoteBundle(app);
                b.putBoolean("ok", true);
                return b;
            }
            case "hal_state": {
                Bundle b = halBundle(app, true);
                b.putBoolean("ok", true);
                return b;
            }
            case "af_run":
                return request(onMain(() -> Hal.requestAutofocus(app)));
            case "kst_auto":
                return request(onMain(() -> Hal.requestKeystone(app, false)));
            case "kst_fit": {
                if (Boolean.FALSE.equals(curtainFit(app, true))) return err("unsupported");
                return request(onMain(() -> Hal.requestKeystone(app, true)));
            }
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
                return toggles(app, null, false);
            case "toggle_set": {
                String name = x.getString("name");
                if (name == null || !x.containsKey("on")) return err("denied");
                return toggles(app, name, x.getBoolean("on"));
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
                boolean on = false;
                for (String[] f : FEATURES) if (f[0].equals(arg)) on = Boolean.parseBoolean(f[1]);
                b.putBoolean("enabled", on);
                return b;
            }
            default:
                return err("unsupported");
        }
    }

    // ------------------------------------------------------------------ state bundles (also SetupEvents)

    /**
     * remote_state fields: bonded, connected, name, scanning, mode; Lumen OS 1.0 adds pairing and found.
     * connected = a bonded XGIMI remote whose link AND HID input device are up (its keys work). Any thread.
     */
    static Bundle remoteBundle(Context app) {
        Bundle b = new Bundle();
        RemoteAutoPair.State st = RemoteAutoPair.state(app);
        b.putInt("bonded", st.bonded);
        b.putBoolean("connected", st.connected);
        b.putString("name", st.name);
        b.putBoolean("scanning", st.scanning);
        b.putString("mode", st.mode);
        b.putBoolean("pairing", st.pairing);
        b.putString("found", st.found);
        return b;
    }

    /** hal_state fields: ready, kstRunning, afBusy, akOverlay, curtainFit (cached, may be absent). */
    static Bundle halBundle(Context app, boolean waitFirst) {
        Bundle b = new Bundle();
        b.putBoolean("ready", Hal.isReady() && Hal.isHandshakeDone());
        b.putBoolean("kstRunning", Hal.isKeystoneRunning());
        b.putBoolean("afBusy", Hal.isAfBusy());
        b.putBoolean("akOverlay", AkOverlay.isActive());
        Boolean cf = curtainFit(app, waitFirst);
        if (cf != null) b.putBoolean("curtainFit", cf);
        return b;
    }

    /** Cached 290 read (through readToggles on the hal thread), refreshed when older than 1 s. */
    private static Boolean curtainFit(Context app, boolean waitFirst) {
        long now = SystemClock.uptimeMillis();
        if (now - sCurtainAt > CURTAIN_CACHE_MS && !sCurtainReading && Hal.isReady()) {
            sCurtainReading = true;
            final CountDownLatch l = new CountDownLatch(1);
            Ui.main().post(() -> {
                try {
                    Hal.readToggles(app, m -> {
                        Boolean v = m.get("CURTAIN_FIT");
                        if (v != null) {
                            sCurtainFit = v;
                            sCurtainAt = SystemClock.uptimeMillis();
                        }
                        sCurtainReading = false;
                        l.countDown();
                    });
                } catch (Throwable t) {
                    sCurtainReading = false;
                    l.countDown();
                }
            });
            if (waitFirst && sCurtainFit == null) {
                try { l.await(CURTAIN_FIRST_WAIT_MS, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
            }
        }
        return sCurtainFit;
    }

    // ------------------------------------------------------------------ actions

    /** Runs a REQ_* request on the main thread (as from a key) and waits briefly. */
    private interface Req { int run(); }

    private static int onMain(Req r) {
        final AtomicReference<Integer> out = new AtomicReference<>();
        final CountDownLatch l = new CountDownLatch(1);
        Ui.main().post(() -> {
            try {
                out.set(r.run());
            } catch (Throwable t) {
                Log.w(TAG, "request: " + t);
                out.set(Hal.REQ_NOT_READY);
            } finally {
                l.countDown();
            }
        });
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

    /** toggles_get (name null) or toggle_set; replies with every toggle read back. */
    private static Bundle toggles(Context app, String setName, boolean on) {
        if (!Hal.isReady()) return err("not_ready");
        final AtomicReference<Map<String, Boolean>> out = new AtomicReference<>();
        final CountDownLatch l = new CountDownLatch(1);
        final boolean[] known = {true};
        Ui.main().post(() -> {
            try {
                if (setName == null) {
                    Hal.readToggles(app, m -> { out.set(m); l.countDown(); });
                } else if (!Hal.setToggle(app, setName, on, m -> { out.set(m); l.countDown(); })) {
                    known[0] = false;
                    l.countDown();
                }
            } catch (Throwable t) {
                Log.w(TAG, "toggles: " + t);
                l.countDown();
            }
        });
        try {
            if (!l.await(TOGGLE_WAIT_MS, TimeUnit.MILLISECONDS)) return err("busy");
        } catch (InterruptedException e) {
            return err("busy");
        }
        if (!known[0]) return err("denied");
        Map<String, Boolean> m = out.get();
        if (m == null) return err("not_ready");
        Bundle b = ok();
        for (Map.Entry<String, Boolean> e : m.entrySet()) {
            if (e.getValue() != null) b.putBoolean(e.getKey(), e.getValue());
        }
        Boolean cf = m.get("CURTAIN_FIT");
        if (cf != null) {
            sCurtainFit = cf;
            sCurtainAt = SystemClock.uptimeMillis();
        }
        if (setName != null && m.get(setName) == null) return err("not_ready");
        return b;
    }

    /**
     * Settings.Global device_name (read by Chromecast built-in and the framework; Z9xAirPlay 1.0 follows it
     * through a ContentObserver unless the user saved an AirPlay-only name) and the Bluetooth adapter name
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
