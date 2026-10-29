// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;

import java.io.File;

/**
 * Read-only checks before an update is offered or installed (ota/SPEC.md 9.3). Every check reads a
 * property or a sticky broadcast; nothing is written. Play 6 fork (tools/play6/forks.tsv).
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
        if (m.schema != 1 || !Ota.DEVICE.equals(m.device)) return new Block("err_signature", "");
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

    /**
     * Everything that must hold right before the restart into the recovery (Play 6): the recovery trusts the
     * Lumen certificate (lumen_recovery.sh publishes sys.lumen.recovery_cert at boot: "ok" = a Lumen
     * recovery, "stock" = XGIMI's own, which would refuse the package), mains power or a well charged
     * battery (the install takes a few minutes and must not run out of power), not too hot.
     */
    public static Block install(Context c) {
        String rc = Ota.prop("sys.lumen.recovery_cert");
        if (!"ok".equals(rc)) return new Block("blk_recovery", rc.isEmpty() ? "?" : rc);
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null && b.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
            boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = level < 0 || scale <= 0 ? -1 : level * 100 / scale;
            if (!plugged && pct < 50) return new Block("blk_power", "");
        }
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm != null && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
            return new Block("blk_thermal", "");
        }
        return null;
    }
}
