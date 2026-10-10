// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import java.util.function.BooleanSupplier;

/** Host test of {@link Outcome} (sh test/run.sh): the result after a restart and the gate's verdict. */
public final class OutcomeTest {
    private static int failed, passed;

    private static void eq(String what, Object want, Object got) {
        if (want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static final String OLD = "lumen-1.0.0-101", NEW = "lumen-1.0.1-102", OTHER = "lumen-1.0.1-dev";
    private static final BooleanSupplier NO_REBOOT = () -> false, NEEDS_REBOOT = () -> true;
    private static final BooleanSupplier NOT_ASKED = () -> { throw new AssertionError("update_engine asked"); };

    private static Outcome.Boot boot(String target, String from, String now, String nowVersion,
            boolean ready, boolean installing, String ota, BooleanSupplier reboot) {
        return Outcome.afterBoot(target, from, "1.0.1", now, nowVersion, ready, installing, ota, reboot);
    }

    public static void main(String[] a) {
        // ---- update running
        eq("ok", Outcome.Boot.OK, boot(NEW, OLD, NEW, "1.0.1", true, false, "pending", NOT_ASKED));
        eq("ok, gate merging", Outcome.Boot.OK, boot(NEW, OLD, NEW, "1.0.1", true, false, "merging", NOT_ASKED));
        eq("ok after an install cut short of onComplete", Outcome.Boot.OK,
                boot(NEW, OLD, NEW, "1.0.1", false, true, "marked", NOT_ASKED));
        eq("unhealthy already at boot", Outcome.Boot.UNHEALTHY,
                boot(NEW, OLD, NEW, "1.0.1", true, false, "unhealthy", NOT_ASKED));
        // ---- 1.0.0's mistake: 'rolledback' published on the NEW slot is never "restored"
        eq("rolledback on the new slot", Outcome.Boot.OK, boot(NEW, OLD, NEW, "1.0.1", true, false, "rolledback", NOT_ASKED));
        eq("manifest build id differs, version runs", Outcome.Boot.OK,
                boot(NEW, OLD, OTHER, "1.0.1", true, false, "rolledback", NOT_ASKED));
        eq("neither build runs", Outcome.Boot.OTHER, boot(NEW, OLD, OTHER, "1.0.2", true, false, "rolledback", NO_REBOOT));
        eq("neither build runs, READY", Outcome.Boot.OTHER, boot(NEW, OLD, OTHER, "1.0.2", true, false, "none", NO_REBOOT));
        // ---- really rolled back: the starting build runs again
        eq("gate rollback", Outcome.Boot.ROLLED_BACK, boot(NEW, OLD, OLD, "1.0.0", true, false, "rolledback", NO_REBOOT));
        eq("switch undone, READY", Outcome.Boot.ROLLED_BACK, boot(NEW, OLD, OLD, "1.0.0", true, false, "none", NO_REBOOT));
        eq("from unknown (old prefs)", Outcome.Boot.ROLLED_BACK, boot(NEW, "", OLD, "1.0.0", true, false, "rolledback", NO_REBOOT));
        eq("still waiting for the restart", Outcome.Boot.NONE, boot(NEW, OLD, OLD, "1.0.0", true, false, "none", NEEDS_REBOOT));
        // ---- install states
        eq("stale pending", Outcome.Boot.STALE, boot(NEW, OLD, OLD, "1.0.0", false, false, "none", NOT_ASKED));
        eq("install cut by a restart", Outcome.Boot.INTERRUPTED, boot(NEW, OLD, OLD, "1.0.0", false, true, "none", NEEDS_REBOOT));
        eq("installing, nothing pending", Outcome.Boot.INTERRUPTED, boot("", "", OLD, "1.0.0", false, true, "none", NOT_ASKED));
        eq("nothing pending", Outcome.Boot.NONE, boot("", "", NEW, "1.0.1", false, false, "rolledback", NOT_ASKED));
        eq("nothing pending, unhealthy", Outcome.Boot.NONE, boot("", "", NEW, "1.0.1", false, false, "unhealthy", NOT_ASKED));

        // ---- the gate's verdict
        eq("gate unhealthy", Outcome.Gate.PROBLEM, Outcome.afterGate("unhealthy", "zygote not running", false, false));
        eq("gate merging", Outcome.Gate.FINE, Outcome.afterGate("merging", "", false, false));
        eq("gate marked", Outcome.Gate.FINE, Outcome.afterGate("marked", "", false, true));
        eq("gate off", Outcome.Gate.FINE, Outcome.afterGate("none", "", false, false));
        eq("gate marked, health still running", Outcome.Gate.WAIT, Outcome.afterGate("marked", "", true, false));
        eq("gate pending", Outcome.Gate.WAIT, Outcome.afterGate("pending", "", false, false));
        eq("gate rolling back", Outcome.Gate.WAIT, Outcome.afterGate("rollback", "", false, false));
        eq("gate pending at the end", Outcome.Gate.FINE, Outcome.afterGate("pending", "", false, true));
        eq("refused rollback at the end", Outcome.Gate.PROBLEM,
                Outcome.afterGate("pending", "no rollback: the snapshot merge has started", false, true));

        // ---- the gate rolled the update back after it had started (no pending record any more)
        eq("rolled back after the start", true, Outcome.rolledBackLater("1.0.1", "rolledback", "1.0.0"));
        eq("no first boot watched", false, Outcome.rolledBackLater("", "rolledback", "1.0.0"));
        eq("'rolledback' on the update's own version (1.0.0's mistake)", false,
                Outcome.rolledBackLater("1.0.1", "rolledback", "1.0.1"));
        eq("watched, no rollback", false, Outcome.rolledBackLater("1.0.1", "none", "1.0.0"));
        eq("watched, marked", false, Outcome.rolledBackLater("1.0.1", "marked", "1.0.1"));

        // ---- a recorded result belongs to the build it was recorded on
        eq("no result", false, Outcome.resultStale("", "", NEW));
        eq("ok on its build", false, Outcome.resultStale("ok:1.0.1", NEW, NEW));
        eq("unhealthy on its build", false, Outcome.resultStale("unhealthy:1.0.1", NEW, NEW));
        eq("restored on the restored build", false, Outcome.resultStale("rollback:1.0.0", OLD, OLD));
        eq("1.0.0 updater's 'restored' (no build) on the new slot", true, Outcome.resultStale("rollback:1.0.0", "", NEW));
        eq("restored, then the update runs", true, Outcome.resultStale("rollback:1.0.0", OLD, NEW));
        eq("ok, then rolled back after the result", true, Outcome.resultStale("ok:1.0.1", NEW, OLD));

        // ---- reasons (z9x_ota.sh health_ok / mark / rollback)
        eq("why empty", Outcome.Why.NONE, Outcome.why(""));
        eq("why null", Outcome.Why.NONE, Outcome.why(null));
        eq("why system_server", Outcome.Why.RESTARTED, Outcome.why("system_server restarted (3)"));
        eq("why zygote", Outcome.Why.SERVICE, Outcome.why("zygote not running"));
        eq("why surfaceflinger", Outcome.Why.SERVICE, Outcome.why("surfaceflinger not running"));
        eq("why gmpf", Outcome.Why.LAMP, Outcome.why("gmpf_main (lamp/fans) not running"));
        eq("why mark", Outcome.Why.CONFIRM, Outcome.why("healthy, but markBootSuccessful failed (boot HAL)"));
        eq("why no rollback", Outcome.Why.NO_ROLLBACK, Outcome.why("no rollback: slot _b is not bootable"));
        eq("why other", Outcome.Why.OTHER, Outcome.why("something new"));

        System.out.println((failed == 0 ? "OK" : "FAILED") + ": " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
