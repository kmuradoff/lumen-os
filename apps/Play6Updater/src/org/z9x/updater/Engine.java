// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import android.content.Context;
import android.os.RecoverySystem;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Play 6 (fork of the Z9X Engine, tools/play6/forks.tsv): no update_engine and no second slot. The whole
 * package is installed by XGIMI's recovery, which trusts the Lumen Play 6 OTA certificate after the one-time
 * recovery step (tools/play6/recovery_trust.py): {@link #apply} checks the whole-file signature against
 * /system/etc/security/otacerts.zip (the same Lumen certificate), writes the BCB
 * "--update_package=/cache/lumen_ota/..." through RecoverySystem and restarts into the recovery. The package
 * lives on /cache (not encrypted, mounted by the recovery), so no uncrypt block map is needed.
 * The update_engine-shaped methods the shared UI calls answer "idle / nothing pending".
 */
public final class Engine {
    public interface Listener {
        void onStatus(int status, float percent);
        void onComplete(int errorCode);
    }

    private static Engine sInstance;

    private Engine() {}

    public static synchronized Engine get() {
        if (sInstance == null) sInstance = new Engine();
        return sInstance;
    }

    public void addListener(Listener l) {}
    public void removeListener(Listener l) {}

    public boolean bind() { return true; }

    /** No update_engine: always idle (the install ends in the recovery, the result is read after the boot). */
    public int status() { return Ota.UE_IDLE; }

    public float percent() { return 0f; }

    public static boolean isNeedReboot() { return false; }

    /** Payload metadata does not exist for recovery packages: the whole file is checked in {@link #apply}. */
    public boolean verifyMetadata(File meta) { return true; }

    /** Space is checked before the download (Preflight.downloadSpace on /cache); the recovery needs no more. */
    public long allocateSpace(File meta, UpdateManifest.Pkg p) { return 0; }

    /** Whole-file signature check of the package against the system OTA certificates. */
    public static boolean verifyPackage(File zip) {
        try {
            RecoverySystem.verifyPackage(zip, null, null);
            return true;
        } catch (GeneralSecurityException | IOException e) {
            Log.w(Ota.TAG, "verifyPackage " + zip + ": " + e);
            return false;
        }
    }

    /**
     * BCB + restart into the recovery. Returns only on failure (RecoverySystem throws). The caller checked the
     * package with {@link #verifyPackage} right before, while the state still said "downloaded".
     */
    public void apply(Context c, File zip, UpdateManifest.Pkg p) throws IOException {
        // a new install starts from scratch: the updater's resume markers of an earlier install
        // (last_command, *.UPDATED) would turn its steps into no-ops
        File[] old = new File("/cache/recovery").listFiles();
        if (old != null) {
            for (File f : old) {
                if (f.getName().endsWith(".UPDATED") || f.getName().equals("last_command")) f.delete();
            }
        }
        Log.i(Ota.TAG, "installPackage " + zip + ": restarting into the recovery");
        RecoverySystem.installPackage(c, zip);
        throw new IOException("installPackage returned");
    }

    public void cancel() {}

    public void revertReady() {}

    public int cleanupAppliedPayload() { return 0; }
}
