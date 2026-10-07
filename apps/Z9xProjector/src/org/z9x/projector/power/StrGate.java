package org.z9x.projector.power;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * MODULE "power" (Lumen OS 1.0): may the SoC enter real STR (suspend to RAM) right now?
 *
 * Lumen OS 1.0 (VERIFIED on the device 2026-10-06): real STR works. A short POWER press, the sleep timer,
 * the power menu's Sleep and the idle timeout switch the lamp off at once and then put Android to sleep,
 * which suspends at once (no quick-wake window, no power-off after a window). The PM51 wake sources
 * are set at boot by init (overlay/v1/z9x_power.rc); a remote key wakes, the vendor power HAL injects
 * KEY_POWER, Android wakes and our SCREEN_ON path lights the lamp.
 *
 * <b>Blockers</b> ({@link #blocker}), in order. While one holds, the projector stays in the lamp-only
 * standby (display on, lamp off: StandbyController) or, after a foreign sleep, display off with a
 * partial wakelock; nothing resets:
 * <ol>
 * <li>{@value #PROP_ALLOW_STR}=0: the kill switch, v6.5 behaviour (lamp-only standby, then the real
 *     power-off after the standby window). Default (unset / 1): STR.</li>
 * <li>an update is being installed: {@value #PROP_OTA_BUSY}=1 or the updater's OTA_STATE broadcast
 *     (update_engine needs the CPU; PLAN C16).</li>
 * <li>the updated system slot is not confirmed yet (first {@value #OTA_UNMARKED_MAX_MS} ms after boot
 *     only): {@value #PROP_OTA} = "pending" (published by z9x_ota.sh while the booted slot is an update
 *     on probation; the health gate needs about 90 s of running system to mark it; PLAN C16). Every
 *     other value (none, marked, merging = marked slot with a snapshot merge still running, rollback,
 *     rolledback = back on the old, confirmed slot) is a confirmed slot.</li>
 * <li>a USB host is connected (ROOT CAUSE of every self-reboot on 2026-10-06: with the Mac's adb on
 *     the cable, the USB device controller keeps a clock on, PM51 logs "can not power down because clk
 *     is enable" and resets the chip 20 s later, boot_reason 0xF1): the UDC state is "configured", or
 *     the sticky USB_STATE says configured, or it says connected while the USB config contains adb.</li>
 * </ol>
 *
 * <b>USB drop</b> (behind {@value #PROP_USB_DROP}=1, default 0, for a later device test): instead of
 * staying in the lamp-only standby, the USB gadget is dropped right before the sleep (property
 * {@value #PROP_USB_DROP_REQ}=1, init sets sys.usb.config none) and restored at SCREEN_ON
 * ({@value #PROP_USB_DROP_REQ}=0, init restores persist.sys.usb.config). After a drop only the UDC state
 * and sys.usb.state count (the cable and the persisted adb config stay).
 *
 * Every read here is cheap (properties, one sticky intent, one tiny sysfs file) and runs on the
 * caller's thread. Never touches the HAL.
 */
public final class StrGate {
    private static final String TAG = "Z9xStr";

    static final String PROP_ALLOW_STR = "persist.z9x.allow_str";
    static final String PROP_USB_DROP = "persist.z9x.str_usb_drop";
    static final String PROP_USB_DROP_REQ = "sys.z9x.usb_drop";
    static final String PROP_OTA_BUSY = "sys.z9x.ota.busy";
    static final String PROP_OTA = "sys.z9x.ota";
    /** The unmarked-slot blocker only applies this long after boot (a stuck value must never block STR for good). */
    static final long OTA_UNMARKED_MAX_MS = 15 * 60_000L;
    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";
    private static final String UDC_DIR = "/sys/class/udc";

    static final String B_ALLOW_STR = "allow_str=0";
    static final String B_OTA_BUSY = "update installing";
    static final String B_OTA_UNMARKED = "updated system not confirmed yet";
    static final String B_USB = "USB host connected";

    /** OTA_STATE broadcast (z9x-standby writes, any thread reads). */
    private static volatile boolean sOtaBusy;
    /** We asked init to drop the USB gadget and have not restored it yet. */
    private static volatile boolean sUsbDropped;

    private StrGate() {}

    /** Kill switch: only an explicit 0 disables STR (Lumen OS 1.0 default: allowed). */
    public static boolean allowStr() {
        return !"0".equals(SystemProperties.get(PROP_ALLOW_STR, ""));
    }

    /** The current reason that keeps the SoC out of STR, or null when it may suspend. Any thread. */
    public static String blocker(Context ctx) {
        if (!allowStr()) return B_ALLOW_STR;
        if (otaBusy()) return B_OTA_BUSY;
        if (otaSlotUnmarked()) return B_OTA_UNMARKED;
        String usb = usbHost(ctx);
        if (usb != null) return B_USB + " (" + usb + ")";
        return null;
    }

    static boolean isUsb(String blocker) {
        return blocker != null && blocker.startsWith(B_USB);
    }

    // ------------------------------------------------------------------ OTA (PLAN C16)

    static boolean otaBusy() {
        return sOtaBusy || "1".equals(SystemProperties.get(PROP_OTA_BUSY, ""));
    }

    static void setOtaBusy(boolean busy) {
        if (sOtaBusy != busy) Log.i(TAG, "update installing: " + busy + " (updater broadcast)");
        sOtaBusy = busy;
    }

    /** The running slot is an update that the health gate has not marked successful yet. */
    static boolean otaSlotUnmarked() {
        if (SystemClock.elapsedRealtime() > OTA_UNMARKED_MAX_MS) return false;
        return otaSlotUnmarkedRaw();
    }

    /**
     * Same without the time limit (boot-dark: never power off a slot that is still on probation). Only
     * z9x_ota.sh's "pending" counts: "merging" is a marked slot whose snapshot merge is still running,
     * "rollback" / "rolledback" mean the old, confirmed slot runs (or is about to), "none" / "marked"
     * are confirmed. The attempt counter (sys.z9x.ota.attempt) is only meaningful next to "pending".
     */
    static boolean otaSlotUnmarkedRaw() {
        return "pending".equals(SystemProperties.get(PROP_OTA, "").trim());
    }

    // ------------------------------------------------------------------ USB host

    /** Short description of a connected USB host, or null. Any thread. */
    static String usbHost(Context ctx) {
        String udc = udcState();
        if ("configured".equals(udc)) return "udc configured";
        if (sUsbDropped) {
            // after our drop the cable is still in and persist.sys.usb.config still says adb: only the
            // live gadget state counts
            String st = SystemProperties.get("sys.usb.state", "");
            return st.isEmpty() || "none".equals(st) ? null : "sys.usb.state=" + st;
        }
        boolean connected = false, configured = false;
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(ACTION_USB_STATE), Context.RECEIVER_EXPORTED);
            if (i != null) {
                connected = i.getBooleanExtra("connected", false);
                configured = i.getBooleanExtra("configured", false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "USB_STATE: " + t);
        }
        if (configured) return "configured";
        if (connected && adbConfigured()) return "cable in, adb on";
        return null;
    }

    private static boolean adbConfigured() {
        return SystemProperties.get("persist.sys.usb.config", "").contains("adb")
                || SystemProperties.get("sys.usb.config", "").contains("adb");
    }

    /** First /sys/class/udc/<name>/state ("configured", "not attached", ...), or "" if unreadable. */
    private static String udcState() {
        try {
            File[] udcs = new File(UDC_DIR).listFiles();
            if (udcs == null) return "";
            for (File u : udcs) {
                try (BufferedReader r = new BufferedReader(new FileReader(new File(u, "state")))) {
                    String s = r.readLine();
                    if (s != null && !s.trim().isEmpty()) return s.trim();
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return "";
    }

    static IntentFilter usbStateFilter() {
        return new IntentFilter(ACTION_USB_STATE);
    }

    // ------------------------------------------------------------------ USB drop (persist.z9x.str_usb_drop=1)

    static boolean usbDropEnabled() {
        return "1".equals(SystemProperties.get(PROP_USB_DROP, ""));
    }

    static boolean usbDropped() {
        return sUsbDropped;
    }

    /** Asks init (z9x_power.rc) to set sys.usb.config none. Main thread. */
    static void dropUsb(String why) {
        try {
            SystemProperties.set(PROP_USB_DROP_REQ, "1");
            sUsbDropped = true;
            Log.w(TAG, "USB gadget dropped before STR (" + why + ")");
        } catch (Throwable t) {
            Log.w(TAG, "USB drop: " + t);
        }
    }

    /** Restores the USB gadget after a drop (SCREEN_ON / standby exit). Any thread. */
    static void restoreUsb(String why) {
        if (!sUsbDropped && !"1".equals(SystemProperties.get(PROP_USB_DROP_REQ, ""))) return;
        sUsbDropped = false;
        try {
            SystemProperties.set(PROP_USB_DROP_REQ, "0");
            Log.i(TAG, "USB gadget restored (" + why + ")");
        } catch (Throwable t) {
            Log.w(TAG, "USB restore: " + t);
        }
    }
}
