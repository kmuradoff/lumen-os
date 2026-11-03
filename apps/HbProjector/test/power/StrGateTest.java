package org.lumen.projector.power;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.os.SystemProperties;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * hb-t7 host check of the HB sleep decision (power/StrGate is a byte-identical Z9X copy; the HB behaviour comes from
 * the image props): with devices/goat_elegant/image/hb_lumen_prop.txt the gate allows STR (sleep = STR as stock),
 * the kill switch 0 still blocks it, and the blockers keep their order (allow_str, update, slot on probation, USB
 * host). Also checks that the image props set persist.z9x.allow_str=1 and persist.z9x.bootdark=0 exactly once.
 * Runs on the build host, where /sys/class/udc does not exist (no UDC state): the USB host is the sticky USB_STATE.
 *   java ... StrGateTest PATH/hb_lumen_prop.txt
 */
public final class StrGateTest {
    private static int pass, fail;

    private static void check(boolean ok, String what) {
        if (ok) pass++;
        else { fail++; System.out.println("FAIL: " + what); }
    }

    private static Map<String, String> props(String path) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        int allow = 0, dark = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            for (String l; (l = r.readLine()) != null; ) {
                l = l.trim();
                if (l.isEmpty() || l.startsWith("#") || !l.contains("=")) continue;
                String k = l.substring(0, l.indexOf('=')), v = l.substring(l.indexOf('=') + 1);
                if (k.equals(StrGate.PROP_ALLOW_STR)) allow++;
                if (k.equals("persist.z9x.bootdark")) dark++;
                m.put(k, v);
            }
        }
        check(allow == 1, "hb_lumen_prop.txt sets " + StrGate.PROP_ALLOW_STR + " once (" + allow + ")");
        check(dark == 1, "hb_lumen_prop.txt sets persist.z9x.bootdark once (" + dark + ")");
        return m;
    }

    private static void reset(Map<String, String> image) {
        SystemProperties.P.clear();
        SystemProperties.P.putAll(image);
        SystemClock.now = 60_000;
        StrGate.setOtaBusy(false);
    }

    public static void main(String[] a) throws Exception {
        Map<String, String> image = props(a[0]);
        check("1".equals(image.get(StrGate.PROP_ALLOW_STR)), "image: persist.z9x.allow_str=1 (sleep = STR)");
        check("0".equals(image.get("persist.z9x.bootdark")), "image: persist.z9x.bootdark=0 (no boot-dark power-off)");
        Context ctx = new Context();

        reset(image);
        check(StrGate.allowStr(), "image props: STR allowed");
        check(StrGate.blocker(ctx) == null, "image props, no USB host: no blocker (" + StrGate.blocker(ctx) + ")");

        reset(image);
        SystemProperties.P.remove(StrGate.PROP_ALLOW_STR);
        check(StrGate.allowStr(), "unset: STR allowed (StrGate default)");

        reset(image);
        SystemProperties.P.put(StrGate.PROP_ALLOW_STR, "0");
        check(!StrGate.allowStr(), "0: kill switch");
        check(StrGate.B_ALLOW_STR.equals(StrGate.blocker(ctx)), "0: blocker allow_str=0 first");

        reset(image);
        ctx.sticky = new Intent().putExtra("connected", true).putExtra("configured", true);
        String b = StrGate.blocker(ctx);
        check(StrGate.isUsb(b), "USB host configured blocks STR (" + b + ")");
        ctx.sticky = new Intent().putExtra("connected", true);
        SystemProperties.P.put("persist.sys.usb.config", "adb");
        check(StrGate.isUsb(StrGate.blocker(ctx)), "cable in with adb blocks STR");
        SystemProperties.P.put("persist.sys.usb.config", "mtp");
        check(StrGate.blocker(ctx) == null, "cable in without adb: STR");
        ctx.sticky = null;

        reset(image);
        StrGate.setOtaBusy(true);
        ctx.sticky = new Intent().putExtra("configured", true);
        check(StrGate.B_OTA_BUSY.equals(StrGate.blocker(ctx)), "update installing comes before USB");
        StrGate.setOtaBusy(false);
        ctx.sticky = null;

        reset(image);
        SystemProperties.P.put("sys.z9x.ota", "pending");
        check(StrGate.B_OTA_UNMARKED.equals(StrGate.blocker(ctx)), "slot on probation blocks STR early after boot");
        SystemClock.now = StrGate.OTA_UNMARKED_MAX_MS + 1;
        check(StrGate.blocker(ctx) == null, "slot on probation no longer blocks after 15 min");

        System.out.println((fail == 0 ? "OK: " : "FAILED: ") + pass + " passed, " + fail + " failed (power StrGate, hb-t7 props)");
        if (fail != 0) System.exit(1);
    }
}
