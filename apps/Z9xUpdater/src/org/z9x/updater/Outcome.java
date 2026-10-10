// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import java.util.function.BooleanSupplier;

/**
 * What the updater tells the user after a restart, from the pending update (Store) and the boot gate's
 * properties (tools/ota/image/z9x_ota.sh: sys.z9x.ota, sys.z9x.ota.why). Plain Java, no Android
 * calls: host test test/org/z9x/updater/OutcomeTest (sh test/run.sh).
 *
 * "Restored" is said only while the build the update started from runs again: the 1.0.0 gate
 * published 'rolledback' on the NEW slot after a needless reboot, and a manifest build id that does
 * not match the image must not turn a running update into a rollback either. A recorded result is
 * shown only on the build it was recorded on ({@link #resultStale}).
 */
final class Outcome {
    private Outcome() {}

    /** BootReceiver.evaluate, once per boot (and after an app update). */
    enum Boot {
        /** nothing was pending (or the update still waits for its restart) */
        NONE,
        /** a pending record from an install that never reached update_engine's end: dropped */
        STALE,
        /** the update runs; the gate's verdict comes later (z9x_ota.sh health, 90 s after boot) */
        OK,
        /** the update runs, and the gate already reports a problem */
        UNHEALTHY,
        /** the build the update started from runs again (gate rollback, bootloader fallback, undone switch) */
        ROLLED_BACK,
        /** neither the update nor the build it started from runs (reinstalled from a computer): no message */
        OTHER,
        /** the install was cut by a restart: update_engine starts over next time */
        INTERRUPTED
    }

    /** The gate's verdict on the update's first boot (CheckJob JOB_GATE). */
    enum Gate { WAIT, FINE, PROBLEM }

    /** sys.z9x.ota.why in plain words (UI strings why_*); OTHER = shown as the gate wrote it. */
    enum Why { NONE, RESTARTED, SERVICE, LAMP, CONFIRM, NO_ROLLBACK, OTHER }

    /**
     * @param target       build id the update installs (Store pend_target), "" = nothing pending
     * @param from         build id the update started from (pend_from), "" = unknown
     * @param targetVersion version the update installs (pend_version)
     * @param now          running build id (ro.z9x.build_id)
     * @param nowVersion   running version (ro.z9x.version)
     * @param needReboot   update_engine still holds a finished update for the next restart (binder call:
     *                     asked only when it decides)
     */
    static Boot afterBoot(String target, String from, String targetVersion, String now, String nowVersion,
            boolean ready, boolean installing, String ota, BooleanSupplier needReboot) {
        if (target.isEmpty()) return installing ? Boot.INTERRUPTED : Boot.NONE;
        if (!ready && !installing) return Boot.STALE;
        // the update runs: its build id, or (a manifest build id that does not match the image) its
        // version on a build that is not the one we came from
        boolean onTarget = target.equals(now) || (!now.isEmpty() && !now.equals(from)
                && !targetVersion.isEmpty() && targetVersion.equals(nowVersion));
        if (onTarget) return "unhealthy".equals(ota) ? Boot.UNHEALTHY : Boot.OK;
        if ((ready || "rolledback".equals(ota)) && !needReboot.getAsBoolean()) {
            return from.isEmpty() || from.equals(now) ? Boot.ROLLED_BACK : Boot.OTHER;
        }
        return installing ? Boot.INTERRUPTED : Boot.NONE;
    }

    /**
     * @param ota           sys.z9x.ota now
     * @param why           sys.z9x.ota.why now
     * @param healthRunning init.svc.z9x_ota_health reads "running" (empty when not readable)
     * @param lastTry       no later look: a gate still undecided ends here
     */
    static Gate afterGate(String ota, String why, boolean healthRunning, boolean lastTry) {
        if ("unhealthy".equals(ota)) return Gate.PROBLEM;
        // pending: the health check has not ended (or never ran); rollback: the gate is restarting right now
        if ("pending".equals(ota) || "rollback".equals(ota) || healthRunning) {
            if (!lastTry) return Gate.WAIT;
            return why.trim().isEmpty() ? Gate.FINE : Gate.PROBLEM;    // e.g. "no rollback: ..." still set
        }
        return Gate.FINE;   // marked / merging (vold or the gate marked the slot), none (gate off)
    }

    /**
     * The update ran (BootReceiver said "Updated to" and left {@code watch} for the gate's verdict), then
     * the gate rolled it back after boot_completed (health failed on a slot vold had not marked): this
     * boot runs the build it started from again. Only a 1.0.1+ gate publishes 'rolledback' off the
     * update's slot; the version check keeps 1.0.0's 'rolledback' on the new slot out as well.
     * @param watch      Store.gateWatch: the update's version, "" = no first boot waits for a verdict
     * @param nowVersion running version (ro.z9x.version)
     */
    static boolean rolledBackLater(String watch, String ota, String nowVersion) {
        return !watch.isEmpty() && "rolledback".equals(ota) && !watch.equals(nowVersion);
    }

    /**
     * A recorded result ("ok:", "unhealthy:", "rollback:<version>") speaks about the build it was recorded
     * on and is dropped on any other: after a rollback that happened only after the result was recorded,
     * after a reinstall from a computer, and for results of the 1.0 / 1.0.0 updater (no build recorded),
     * so a "restored" can never stand next to a running update.
     * @param resultBuild Store.lastResultBuild ("" = not recorded)
     * @param now         running build id (ro.z9x.build_id)
     */
    static boolean resultStale(String result, String resultBuild, String now) {
        return !result.isEmpty() && !resultBuild.equals(now);
    }

    /** The reasons z9x_ota.sh writes (health_ok, mark, rollback); anything else is shown as it is. */
    static Why why(String why) {
        String w = why == null ? "" : why.trim();
        if (w.isEmpty()) return Why.NONE;
        if (w.startsWith("no rollback:")) return Why.NO_ROLLBACK;
        if (w.startsWith("system_server restarted")) return Why.RESTARTED;
        if (w.startsWith("gmpf_main")) return Why.LAMP;
        if (w.startsWith("zygote not running") || w.startsWith("surfaceflinger not running")) return Why.SERVICE;
        if (w.contains("markBootSuccessful failed")) return Why.CONFIRM;
        return Why.OTHER;
    }
}
