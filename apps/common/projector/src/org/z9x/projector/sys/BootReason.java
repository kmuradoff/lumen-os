package org.z9x.projector.sys;

import java.util.Locale;

/**
 * Lumen OS 1.0.1: did this boot follow a restart that nobody started at the remote? Plain Java (the
 * reason strings are read by the caller), host test test/uires (sh test/uires/run.sh).
 *
 * Unattended restarts: the updater and its boot gate (init "reboot,z9x-ota", "reboot,z9x-ota-rollback",
 * "reboot,z9x-ota-stuck"), the interface resolution change and its boot fallback ("reboot,z9x-uires*",
 * display.UiResolution), the boot rescue and the installer (z9x_rescue.sh "reboot,fastboot", fastbootd's
 * own "reboot,from_fastboot", "reboot,bootloader") and Android's RescueParty. After those the remote is
 * simply asleep (it reconnects at its first key), so RemoteAutoPair does not ask for it.
 *
 * Where Android 14 keeps the reason (bootstat): sys.boot.reason (this boot, e.g. "reboot,z9x-ota"),
 * sys.boot.reason.last (the reason init persisted at the last orderly reboot), persist.sys.boot.reason
 * (that persisted value, cleared by bootstat early in the boot) and ro.boot.bootreason (the bootloader's).
 */
public final class BootReason {
    private BootReason() {}

    /** Substrings (lower case) of the reasons above; "z9x-ota" also covers -rollback and -stuck. */
    private static final String[] UNATTENDED = {"z9x-ota", "z9x-uires", "fastboot", "bootloader", "rescue"};

    /** The first of {@code reasons} that names an unattended restart (trimmed, as given), or null. */
    public static String unattended(String... reasons) {
        if (reasons == null) return null;
        for (String r : reasons) {
            if (r == null) continue;
            String s = r.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            for (String u : UNATTENDED) {
                if (s.contains(u)) return r.trim();
            }
        }
        return null;
    }
}
