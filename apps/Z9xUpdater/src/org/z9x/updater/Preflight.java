// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import android.content.Context;
import android.os.PowerManager;

import java.io.File;

/**
 * Read-only checks before an update is offered or installed (ota/SPEC.md 9.3). Every check reads a
 * property or asks update_engine; nothing is written.
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
        if (m.schema != 1 || !"z9x".equals(m.device)) return new Block("err_signature", "");
        // the manifest's channel must be this image's own file (docs/ota.md "Variants"): a USB stick or a
        // mirror may carry another edition's signed manifest, which must never be installed here
        if (!("update-" + m.channel + ".json").equals(Ota.channelFile())) return new Block("blk_channel", m.channel);
        String vendor = Ota.prop("ro.vendor.build.version.incremental");
        if (!m.vendorIncremental.isEmpty() && !m.vendorIncremental.contains(vendor)) {
            return new Block("blk_vendor", vendor.isEmpty() ? "?" : vendor);
        }
        int cur = Ota.currentVersionCode();
        if (m.versionCode <= cur) return new Block("uptodate", "");
        if (cur < m.minVersionCode) {
            int mv = m.minVersionCode;
            return new Block("blk_min_version", (mv / 10000) + "." + (mv / 100 % 100)
                    + (mv % 100 != 0 ? "." + (mv % 100) : ""));
        }
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
        String ota = Ota.prop("sys.z9x.ota");
        if ("pending".equals(ota)) return new Block("blk_pending", "");
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm != null && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
            return new Block("blk_thermal", "");
        }
        int s = Engine.get().status();
        if (s < 0) return new Block("blk_engine", "");
        if (s == Ota.UE_CLEANUP) return new Block("blk_merge", "");
        return null;
    }
}
