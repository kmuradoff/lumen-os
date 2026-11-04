// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

import android.content.Context;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Checks before an update is offered or installed. Every check reads a property, a file
 * of the image or asks update_engine. The one write: install() asks the boot gate for a fresh read
 * (sys.z9x.ota.request=probe) and waits for it, so it must run on a worker thread (UpdaterActivity.bg and the
 * UpdateService worker are its only callers).
 *
 * Device-neutral fork of apps/Z9xUpdater Preflight.java:
 * the manifest's device must equal this image's wire id (Ota.device(); an empty one refuses every manifest),
 * version codes and names follow a project decision, and an install also needs the boot gate's word that a rollback
 * works on this boot (properties published by tools/devices/ota/image/lumen_ota.sh, decide service):
 * <ul>
 * <li>sys.z9x.ota.hal: "ok", else "bad:&lt;why&gt;" (empty: the gate never ran) -> blk_hal;</li>
 * <li>sys.z9x.ota.marked: "1" when the running slot is marked successful, else blk_not_marked;</li>
 * <li>sys.z9x.ota.merge: "none" when no snapshot merge is pending or running; another value -> blk_merge;
 *     empty (unknown) -> blk_hal;</li>
 * <li>sys.z9x.ota.active: the slot the boot HAL starts next must be the running one (ro.boot.slot_suffix), else
 *     blk_active.</li>
 * </ul>
 * These are read only after a fresh probe of this install (probe(): blk_probe when the gate does not answer).
 * The build facts update_engine relies on (storage(): blk_vab, blk_partitions) come first: Virtual A/B on
 * without compression, and the static partition list update_engine copies is this build's own list, without
 * vbmeta or mboot. The Z9X vbmeta, pending, thermal and engine checks are kept.
 */
public final class Preflight {
    private Preflight() {}

    /** A blocking problem: string resource name + argument. */
    public static final class Block {
        public final String res, arg;
        Block(String res, String arg) { this.res = res; this.arg = arg; }
    }

    /** Is this manifest an update for this projector at all? null = yes. "uptodate" = nothing newer. */
    public static Block applicable(UpdateManifest m) {
        return applicable(m.schema, m.device, m.channel, m.vendorIncremental, m.versionCode, m.minVersionCode);
    }

    static Block applicable(int schema, String device, String channel, List<String> vendorIncremental,
                            int versionCode, int minVersionCode) {
        String mine = Ota.device();
        if (schema != 1 || mine.isEmpty() || !mine.equals(device)) return new Block("err_signature", "");
        // the manifest's channel must be this image's own file (docs/ota.md "Variants"): a USB stick or a
        // mirror may carry another edition's signed manifest, which must never be installed here
        if (!("update-" + channel + ".json").equals(Ota.channelFile())) return new Block("blk_channel", channel);
        String vendor = Ota.prop("ro.vendor.build.version.incremental");
        if (!vendorIncremental.isEmpty() && !vendorIncremental.contains(vendor)) {
            return new Block("blk_vendor", vendor.isEmpty() ? "?" : vendor);
        }
        int cur = Ota.currentVersionCode();
        if (versionCode <= cur) return new Block("uptodate", "");
        if (cur < minVersionCode) return new Block("blk_min_version", Ota.versionName(minVersionCode));
        return null;
    }

    /** Protected-video blob set check: warning only. */
    public static boolean blobsMissing(UpdateManifest m) {
        if (m.blobsSets.isEmpty()) return false;
        return !"ok".equals(Ota.prop("sys.z9x.blobs")) || !m.blobsSets.contains(Ota.prop("sys.z9x.blobs.set"));
    }

    /** Space for the download in our directory (zip + 256 MB margin). */
    public static Block downloadSpace(Context c, UpdateManifest.Pkg p, long have) {
        File d = Ota.dir(c);
        long need = p.size - have + (256L << 20);
        if (d.getUsableSpace() < need) {
            return new Block("blk_space", android.text.format.Formatter.formatShortFileSize(c, need));
        }
        return null;
    }

    /** Everything that must hold right before update_engine is asked to write the other slot. */
    public static Block install(Context c) {
        String other = Ota.otherSlot();
        int flags = Ota.otherVbmetaFlags();
        if (other.isEmpty() || flags < 0) return new Block("blk_vbmeta_unknown", "");
        if ((flags & 2) == 0) return new Block("blk_vbmeta_title", Ota.slotLetter(other));
        Block g = storage();
        if (g == null) g = probe();
        if (g == null) g = slotState();
        if (g != null) return g;
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm != null && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
            return new Block("blk_thermal", "");
        }
        int s = Engine.get().status();
        if (s < 0) return new Block("blk_engine", "");
        if (s == Ota.UE_CLEANUP) return new Block("blk_merge", "");
        return null;
    }

    /**
     * The boot gate's state of the running slot: not pending, rollback possible (HAL ok), the next boot is this
     * slot, slot marked successful, no merge. null = an install may start.
     */
    static Block slotState() {
        if ("pending".equals(Ota.prop("sys.z9x.ota"))) return new Block("blk_pending", "");
        String hal = Ota.prop("sys.z9x.ota.hal").trim();
        if (!"ok".equals(hal)) {
            String why = hal.startsWith("bad:") ? hal.substring(4).trim() : hal;
            return new Block("blk_hal", why.isEmpty() ? "?" : why);
        }
        // the next boot must be this slot: else an update or a rollback is already staged
        String run = Ota.prop("ro.boot.slot_suffix").trim(), act = Ota.prop("sys.z9x.ota.active").trim();
        if (run.isEmpty() || !run.equals(act)) return new Block("blk_active", Ota.slotLetter(act));
        if (!"1".equals(Ota.prop("sys.z9x.ota.marked").trim())) return new Block("blk_not_marked", "");
        String merge = Ota.prop("sys.z9x.ota.merge").trim();
        if (merge.isEmpty()) return new Block("blk_hal", "merge");
        if (!"none".equals(merge)) return new Block("blk_merge", "");
        return null;
    }

    /** The init request property of the gate (lumen_ota.rc: request=probe starts z9x_ota_decide). */
    static final String REQUEST = "sys.z9x.ota.request";
    /** The gate's answer property: "probing" while decide runs, then "ok" or "bad:<why>". */
    static final String HAL = "sys.z9x.ota.hal";
    /** init's state of the probe service ("running" while it runs; may not be readable under enforcing). */
    static final String SVC = "init.svc.z9x_ota_decide";
    /** How long probe() waits for the gate in all, how often it looks, and when it asks once more. */
    static final long PROBE_MS = 20000, STEP_MS = 25, RESEND_MS = 1000;

    /** The wait of probe(); the host test steps a fake gate here. */
    interface Pause { void ms(long ms) throws InterruptedException; }
    static Pause pause = Thread::sleep;

    /**
     * Asks the gate to read the boot HAL again and waits for the answer (worker thread only). The props it
     * publishes are otherwise as old as the boot (or the last probe), so a slot marked since would still read
     * as unmarked. A fresh answer is one seen after this request: hal cleared by us and then set again, or
     * hal "probing" or the service running, then a final hal value. null = fresh; the caller then reads
     * slotState().
     * One install probes twice (UpdaterActivity, then UpdateService), and init ignores a start while the
     * service still runs: so a running service is waited for first, and an unanswered request is sent once more.
     */
    static Block probe() {
        if ("pending".equals(Ota.prop("sys.z9x.ota"))) return new Block("blk_pending", "");
        long waited = 0;
        try {
            for (; "running".equals(Ota.prop(SVC).trim()); waited += STEP_MS) {
                if (waited >= PROBE_MS) return new Block("blk_probe", "timeout");
                pause.ms(STEP_MS);
            }
            // an init trigger needs the value to change: clear it first (it is still "probe" from the last time)
            if (!Ota.setProp(REQUEST, "")) return new Block("blk_probe", "setprop refused");
            // best effort: with hal cleared, a gate faster than one look still reads as fresh. decide then takes
            // this run for the first of the boot, which acts only while sys.z9x.ota is pending: refused above,
            // and count sets pending only at post-fs-data, so it never comes back later in a boot
            boolean cleared = Ota.setProp(HAL, "");
            if (!Ota.setProp(REQUEST, "probe")) return new Block("blk_probe", "setprop refused");
            boolean started = false, resent = false;
            for (long asked = 0; ; waited += STEP_MS, asked += STEP_MS) {
                String hal = Ota.prop(HAL).trim();
                if ("running".equals(Ota.prop(SVC).trim()) || "probing".equals(hal)) started = true;
                else if ((started || cleared) && !hal.isEmpty()) return null;
                if (waited >= PROBE_MS) return new Block("blk_probe", started ? "timeout" : "no answer");
                if (!started && !resent && asked >= RESEND_MS) {
                    resent = true;
                    if (!Ota.setProp(REQUEST, "") || !Ota.setProp(REQUEST, "probe")) {
                        return new Block("blk_probe", "setprop refused");
                    }
                }
                pause.ms(STEP_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Block("blk_probe", "interrupted");
        }
    }

    /** This build's own copy of the partition list (the image writes it there, hb_ota_props.txt). */
    static final String BUILD_PROP = "/system/product/etc/build.prop";

    /** Build facts update_engine relies on: Virtual A/B without compression, and the partition list. */
    static Block storage() {
        if (!"true".equals(Ota.prop("ro.virtual_ab.enabled").trim())) return new Block("blk_vab", "virtual A/B off");
        // compressed snapshots (VABC) are not what the gate and the storage twin were proven with
        if ("true".equals(Ota.prop("ro.virtual_ab.compression.enabled").trim())) {
            return new Block("blk_vab", "compression on");
        }
        return partitions(Ota.prop("ro.vendor.build.ab_ota_partitions"), Ota.prop("ro.product.ab_ota_partitions"),
                buildList(new File(BUILD_PROP)));
    }

    /**
     * The static partitions update_engine copies to the other slot: it reads the vendor list first, then the
     * product one. The vendor list must be empty, the product one must be this build's own (a stock product
     * partition lists vbmeta and mboot), and no entry may be vbmeta or mboot. vbmeta_system is allowed.
     */
    static Block partitions(String vendor, String product, String own) {
        if (!vendor.trim().isEmpty()) return new Block("blk_partitions", "vendor list");
        String list = product.trim();
        if (own == null) return new Block("blk_partitions", "no build list");
        if (list.isEmpty() || !list.equals(own.trim())) return new Block("blk_partitions", "not the build's list");
        for (String p : list.split(",", -1)) {
            String n = p.trim();
            if (!n.matches("[a-z0-9_]+")) return new Block("blk_partitions", "bad entry");
            if (n.endsWith("_a") || n.endsWith("_b")) n = n.substring(0, n.length() - 2);
            if ("vbmeta".equals(n)) return new Block("blk_partitions", "vbmeta");
            if (n.startsWith("mboot")) return new Block("blk_partitions", "mboot");
        }
        return null;
    }

    /** The last ro.product.ab_ota_partitions line of a build.prop (init keeps the last one); null = none. */
    static String buildList(File f) {
        String key = "ro.product.ab_ota_partitions=", v = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f),
                StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) {
                l = l.trim();
                if (l.startsWith(key)) v = l.substring(key.length());
            }
        } catch (IOException | SecurityException e) {
            return null;
        }
        return v;
    }
}
