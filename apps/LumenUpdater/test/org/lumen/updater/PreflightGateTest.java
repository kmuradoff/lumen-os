// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

import android.os.SystemProperties;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Host test of the install checks around the boot gate (sh test/ota/run.sh, next to PreflightHbTest):
 * <ul>
 * <li>probe(): the request sequence ("", hal cleared, then "probe"), a fresh answer only after the request,
 *     a run still going (init ignores the start: waited for, or asked once more), the 20 s limit, a refused
 *     setprop, pending, an interrupt; then slotState() on what the fake gate published;</li>
 * <li>storage() and partitions(): Virtual A/B on without compression, the vendor list empty, the product list
 *     the build's own, no vbmeta or mboot; buildList() of a build.prop.</li>
 * </ul>
 * The fake gate runs in Preflight.pause: it sees the request in the stand-in's set log and answers after a few
 * looks, as z9x_ota_decide does (lumen_ota.sh decide: hal "probing", then the props, then hal ok or bad:).
 */
public final class PreflightGateTest {
    private static int failed, passed;
    private static final String HB_LIST = "armfw,boot,bootdata,dtbo,optee,tvconfig,tvservice,vbmeta_system,xgimiconfig";
    private static final String STOCK_LIST =
            "armfw,boot,bootdata,dtbo,mboot,optee,tvconfig,tvservice,vbmeta,vbmeta_system,xgimiconfig";

    private static void eq(String what, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static String res(Preflight.Block b) { return b == null ? "ok" : b.res; }

    private static String arg(Preflight.Block b) { return b == null ? "" : b.arg; }

    private static void put(String k, String v) {
        if (v == null) SystemProperties.VALUES.remove(k);
        else SystemProperties.VALUES.put(k, v);
    }

    /** A booted HB on slot a whose props are as old as the boot: the slot was marked since (marked still 0). */
    private static void staleBoot() {
        SystemProperties.VALUES.clear();
        SystemProperties.SETS.clear();
        SystemProperties.DENY.clear();
        put("ro.boot.slot_suffix", "_a");
        put("sys.z9x.ota", "none");
        put("sys.z9x.ota.hal", "ok");
        put("sys.z9x.ota.marked", "0");
        put("sys.z9x.ota.merge", "none");
        put("sys.z9x.ota.active", "_a");
        put("init.svc.z9x_ota_decide", "stopped");
    }

    /** z9x_ota_decide as init starts it on request=probe; it writes the map directly (not the set log). */
    static final class Gate implements Preflight.Pause {
        int delay = 2, run = 4, looks, seen, start = -1, busy;   // busy: looks an earlier run still takes
        boolean answers = true, finishes = true, viaService, svcHidden;
        String hal = "ok", marked = "1", merge = "none", active = "_a";

        @Override
        public void ms(long ms) {
            boolean earlier = looks < busy;   // init ignores a start while the earlier run goes on
            looks++;
            for (; seen < SystemProperties.SETS.size(); seen++) {
                if ((Preflight.REQUEST + "=probe").equals(SystemProperties.SETS.get(seen)) && answers && !earlier) {
                    start = looks + delay;
                }
            }
            if (busy > 0 && looks == busy && !svcHidden) put("init.svc.z9x_ota_decide", "stopped");
            if (looks == start) {
                if (viaService) put("init.svc.z9x_ota_decide", "running");
                else put("sys.z9x.ota.hal", "probing");
            }
            if (start >= 0 && looks == start + run && finishes) {
                put("sys.z9x.ota.marked", marked);
                put("sys.z9x.ota.merge", merge);
                put("sys.z9x.ota.active", active);
                put("sys.z9x.ota.hal", hal);
                put("init.svc.z9x_ota_decide", "stopped");
            }
        }
    }

    private static int count(String set) {
        int n = 0;
        for (String x : SystemProperties.SETS) if (x.equals(set)) n++;
        return n;
    }

    private static Gate gate() {
        Gate g = new Gate();
        Preflight.pause = g;
        return g;
    }

    private static String write(File f, String... lines) throws IOException {
        Files.write(f.toPath(), Arrays.asList(lines), StandardCharsets.UTF_8);
        return f.getPath();
    }

    public static void main(String[] a) throws IOException {
        // the request sequence and a fresh answer: the stale props say "not marked", the probe reads "marked"
        staleBoot();
        Gate g = gate();
        eq("stale props before the probe", "blk_not_marked", res(Preflight.slotState()));
        eq("fresh probe", "ok", res(Preflight.probe()));
        eq("request sequence", Arrays.asList("sys.z9x.ota.request=", "sys.z9x.ota.hal=",
                "sys.z9x.ota.request=probe"), SystemProperties.SETS);
        eq("fresh probe waits for the run", true, g.looks >= g.delay + g.run);
        eq("after the probe", "ok", res(Preflight.slotState()));
        // a second install in the same boot: the request is still "probe", so it is cleared again first
        eq("request left as probe", "probe", SystemProperties.get(Preflight.REQUEST, ""));
        SystemProperties.SETS.clear();
        g = gate();
        eq("second probe", "ok", res(Preflight.probe()));
        eq("second request sequence", Arrays.asList("sys.z9x.ota.request=", "sys.z9x.ota.hal=",
                "sys.z9x.ota.request=probe"), SystemProperties.SETS);

        // the second probe of one install while the first run still logs: init would ignore the start
        staleBoot();
        put("init.svc.z9x_ota_decide", "running");
        g = gate();
        g.busy = 3;
        eq("earlier run, service seen", "ok", res(Preflight.probe()));
        eq("earlier run waited for", 1, count(Preflight.REQUEST + "=probe"));
        eq("earlier run: published", "ok", res(Preflight.slotState()));
        staleBoot();
        put("init.svc.z9x_ota_decide", null);
        g = gate();
        g.busy = 3;
        g.svcHidden = true;
        eq("earlier run, service hidden", "ok", res(Preflight.probe()));
        eq("hidden: asked once more", 2, count(Preflight.REQUEST + "=probe"));
        eq("hidden: after about 1 s", true, g.looks >= Preflight.RESEND_MS / Preflight.STEP_MS);
        staleBoot();
        put("init.svc.z9x_ota_decide", "running");
        g = gate();
        g.busy = 100000;
        eq("service never stops", "timeout", arg(Preflight.probe()));
        eq("service never stops: nothing set", 0, SystemProperties.SETS.size());

        // the gate never ran this boot (hal empty) and answers now
        staleBoot();
        put("sys.z9x.ota.hal", null);
        gate();
        eq("first read of this boot", "ok", res(Preflight.probe()));
        eq("first read published", "ok", res(Preflight.slotState()));

        // only the service state shows the run (hal goes from the stale ok straight to ok)
        staleBoot();
        g = gate();
        g.viaService = true;
        eq("run seen by init.svc", "ok", res(Preflight.probe()));

        // what the gate publishes decides: bad HAL, another slot next, a merge
        staleBoot();
        g = gate();
        g.hal = "bad:bootable unknown";
        eq("probe answers bad", "ok", res(Preflight.probe()));
        eq("bad HAL after the probe", "blk_hal", res(Preflight.slotState()));
        eq("bad HAL reason", "bootable unknown", arg(Preflight.slotState()));
        staleBoot();
        g = gate();
        g.active = "_b";
        Preflight.probe();
        eq("other slot next after the probe", "blk_active", res(Preflight.slotState()));
        staleBoot();
        g = gate();
        g.merge = "merging";
        Preflight.probe();
        eq("merge after the probe", "blk_merge", res(Preflight.slotState()));

        // no answer within 20 s: refused, and the wait is the 20 s budget (800 looks of 25 ms)
        staleBoot();
        g = gate();
        g.answers = false;
        Preflight.Block b = Preflight.probe();
        eq("no answer", "blk_probe", res(b));
        eq("no answer arg", "no answer", arg(b));
        eq("no answer budget", (int) (Preflight.PROBE_MS / Preflight.STEP_MS), g.looks);
        staleBoot();
        put("sys.z9x.ota.hal", null);
        g = gate();
        g.answers = false;
        eq("hal empty, no answer", "no answer", arg(Preflight.probe()));
        staleBoot();
        g = gate();
        g.finishes = false;
        b = Preflight.probe();
        eq("stuck probing", "blk_probe", res(b));
        eq("stuck probing arg", "timeout", arg(b));
        // a gate faster than one look: fresh, since hal was cleared first
        staleBoot();
        g = gate();
        g.delay = 0;
        g.run = 0;
        eq("gate faster than one look", "ok", res(Preflight.probe()));
        // without the hal clear (refused) it is not seen as fresh: refused (fail closed; T1 measures the run time)
        staleBoot();
        SystemProperties.DENY.add("sys.z9x.ota.hal");
        g = gate();
        g.delay = 0;
        g.run = 0;
        eq("fast gate, hal not clearable", "no answer", arg(Preflight.probe()));
        staleBoot();
        SystemProperties.DENY.add("sys.z9x.ota.hal");
        gate();
        eq("hal not clearable, normal gate", "ok", res(Preflight.probe()));

        // a refused setprop (SELinux) is a clear refusal, not a crash
        staleBoot();
        SystemProperties.DENY.add(Preflight.REQUEST);
        g = gate();
        b = Preflight.probe();
        eq("setprop refused", "blk_probe", res(b));
        eq("setprop refused arg", "setprop refused", arg(b));
        eq("setprop refused: no wait", 0, g.looks);
        eq("Ota.setProp refused", false, Ota.setProp(Preflight.REQUEST, "probe"));

        // pending: no request at all
        staleBoot();
        put("sys.z9x.ota", "pending");
        gate();
        eq("pending", "blk_pending", res(Preflight.probe()));
        eq("pending: nothing set", 0, SystemProperties.SETS.size());

        // an interrupt (UpdateService stop) ends the wait
        staleBoot();
        Preflight.pause = ms -> { throw new InterruptedException(); };
        b = Preflight.probe();
        eq("interrupted", "interrupted", arg(b));
        eq("interrupt flag kept", true, Thread.interrupted());

        // storage(): Virtual A/B on, compression off
        staleBoot();
        put("ro.virtual_ab.enabled", "true");
        put("ro.virtual_ab.compression.enabled", "false");
        put("ro.product.ab_ota_partitions", HB_LIST);
        eq("no build.prop on the host", "no build list", arg(Preflight.storage()));
        put("ro.virtual_ab.enabled", null);
        eq("no Virtual A/B", "blk_vab", res(Preflight.storage()));
        eq("no Virtual A/B arg", "virtual A/B off", arg(Preflight.storage()));
        put("ro.virtual_ab.enabled", "false");
        eq("Virtual A/B false", "blk_vab", res(Preflight.storage()));
        put("ro.virtual_ab.enabled", "true");
        put("ro.virtual_ab.compression.enabled", "true");
        eq("compression on", "blk_vab", res(Preflight.storage()));
        eq("compression on arg", "compression on", arg(Preflight.storage()));
        put("ro.virtual_ab.compression.enabled", null);
        eq("compression unset", "no build list", arg(Preflight.storage()));

        // partitions(): what update_engine copies to the other slot
        eq("HB list", "ok", res(Preflight.partitions("", HB_LIST, HB_LIST)));
        eq("vendor list set", "vendor list", arg(Preflight.partitions(HB_LIST, HB_LIST, HB_LIST)));
        eq("vendor list blank", "ok", res(Preflight.partitions(" ", HB_LIST, HB_LIST)));
        eq("no build list", "no build list", arg(Preflight.partitions("", HB_LIST, null)));
        eq("stock product list wins", "not the build's list", arg(Preflight.partitions("", STOCK_LIST, HB_LIST)));
        eq("blk_partitions", "blk_partitions", res(Preflight.partitions("", STOCK_LIST, HB_LIST)));
        eq("no list at all", "not the build's list", arg(Preflight.partitions("", "", "")));
        eq("vbmeta in the build", "vbmeta", arg(Preflight.partitions("", "boot,vbmeta", "boot,vbmeta")));
        eq("vbmeta_b", "vbmeta", arg(Preflight.partitions("", "boot,vbmeta_b", "boot,vbmeta_b")));
        eq("mboot in the build", "mboot", arg(Preflight.partitions("", "mboot,boot", "mboot,boot")));
        eq("stock list as the build's", "mboot", arg(Preflight.partitions("", STOCK_LIST, STOCK_LIST)));
        eq("vbmeta_system only", "ok", res(Preflight.partitions("", "vbmeta_system", "vbmeta_system")));
        eq("empty entry", "bad entry", arg(Preflight.partitions("", "boot,,dtbo", "boot,,dtbo")));
        eq("space list", "bad entry", arg(Preflight.partitions("", "boot dtbo", "boot dtbo")));

        // buildList(): the last line of the key, comments and other keys ignored
        File dir = Files.createTempDirectory("lumen_preflight").toFile();
        File bp = new File(dir, "build.prop");
        write(bp, "# ro.product.ab_ota_partitions=vbmeta", "ro.lumen.version=1.0.8-beta1",
                "ro.product.ab_ota_partitions=" + STOCK_LIST, "ro.product.ab_ota_partitions=" + HB_LIST);
        eq("build list", HB_LIST, Preflight.buildList(bp));
        write(bp, "ro.lumen.version=1.0.8-beta1");
        eq("build list missing", null, Preflight.buildList(bp));
        eq("build.prop missing", null, Preflight.buildList(new File(dir, "none")));
        bp.delete();
        dir.delete();

        System.out.println("preflight gate tests passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
