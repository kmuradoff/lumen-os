// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * After every boot: tell the user how a pending update ended ({@link Outcome#afterBoot}: new build
 * running = success; the build it started from running again + z9x_ota.sh "rolledback" = the update
 * could not start and the previous version was restored), clean up, and (re)schedule the checks.
 * A success is not final yet: the gate's health check reports 90 s after boot (sys.z9x.ota=unhealthy +
 * sys.z9x.ota.why, the slot is already marked then, no rollback), so CheckJob JOB_GATE looks again
 * ({@link #gateVerdict}) and turns the result into "running, but a check found a problem". A slot the
 * gate rolls back after boot_completed (unmarked, health failed) is said as "restored" on the boot that
 * follows ({@link Outcome#rolledBackLater}).
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                evaluate(c);
                CheckJob.schedule(c);
            } catch (Throwable t) {
                Log.w(Ota.TAG, "boot", t);
            } finally {
                pr.finish();
            }
        }, "updater-boot").start();
    }

    static void evaluate(Context c) {
        Store st = Store.get(c);
        String target = st.pendingTarget();
        String now = Ota.buildId();
        String ota = Ota.prop("sys.z9x.ota");
        if (Outcome.resultStale(st.lastResult(), st.lastResultBuild(), now)) {
            Log.i(Ota.TAG, "result '" + st.lastResult() + "' of build '" + st.lastResultBuild() + "' dropped: running " + now);
            st.setLastResult("");
        }
        Store.State s = st.state();
        Outcome.Boot b = Outcome.afterBoot(target, st.pendingFrom(), st.pendingVersion(), now, Ota.currentVersion(),
                s == Store.State.READY, s == Store.State.INSTALLING, ota, Engine::isNeedReboot);
        switch (b) {
            case STALE:
                st.clearPending();
                break;
            case OK:
            case UNHEALTHY: {
                String v = st.pendingVersion();
                Log.i(Ota.TAG, "update to " + v + " is running (slot " + Ota.slot() + ", gate " + ota + ")");
                finish(c, st);
                if (b == Outcome.Boot.UNHEALTHY) {
                    problem(c, st, v, Ota.prop("sys.z9x.ota.why"));
                } else {
                    st.setLastResult("ok:" + v);
                    st.setGateWatch(v);
                    UpdateService.notifyResult(c, c.getString(R.string.done_ok_title, v), "");
                }
                break;
            }
            case ROLLED_BACK: {
                String v = st.pendingFromVersion();
                Log.w(Ota.TAG, "update to " + target + " did not start; running " + now + " (gate " + ota + ")");
                finish(c, st);
                st.setGateWatch("");
                st.setLastResult("rollback:" + v);
                UpdateService.notifyResult(c, c.getString(R.string.done_rollback_title),
                        c.getString(R.string.done_rollback_body, v));
                break;
            }
            case OTHER:
                // neither the update nor its starting build runs (written again from a computer): say nothing
                Log.w(Ota.TAG, "update to " + target + " from " + st.pendingFrom() + " ended unknown: running " + now
                        + " (gate " + ota + "), no result shown");
                finish(c, st);
                break;
            case INTERRUPTED:
                // the install was interrupted by a restart: update_engine starts over next time
                st.clearPending();
                st.setState(Store.State.DOWNLOADED);
                break;
            default:
                if (s == Store.State.DOWNLOADING || s == Store.State.CHECKING) {
                    st.setState(s == Store.State.DOWNLOADING ? Store.State.PAUSED : Store.State.IDLE);
                }
        }
        if (b == Outcome.Boot.NONE && Outcome.rolledBackLater(st.gateWatch(), ota, Ota.currentVersion())) {
            // the update ran, then the gate rolled it back after boot_completed: its "Updated to" was
            // dropped above (recorded on the other build), the restored version is said instead
            String v = Ota.currentVersion();
            Log.w(Ota.TAG, "update to " + st.gateWatch() + " was rolled back after it started; running " + now);
            st.setGateWatch("");
            st.setLastResult("rollback:" + v);
            UpdateService.notifyResult(c, c.getString(R.string.done_rollback_title),
                    c.getString(R.string.done_rollback_body, v));
        }
        // the update's first boot (or a restart before the verdict): ask the gate again in a few minutes
        if (!st.gateWatch().isEmpty()) CheckJob.scheduleGate(c, 1);
        Ota.setBusy(c, false, st.state().name().toLowerCase());
    }

    /** The pending update ended (either way): its files, offer and state go. */
    private static void finish(Context c, Store st) {
        st.clearPending();
        UpdateService.deleteFiles(c);
        st.clearOffer();
        st.setState(Store.State.IDLE);
    }

    /**
     * CheckJob JOB_GATE (a few minutes after the update's first boot): the boot gate's verdict.
     * @return true = the gate has not decided yet, look again later
     */
    static boolean gateVerdict(Context c, boolean lastTry) {
        Store st = Store.get(c);
        String v = st.gateWatch();
        if (v.isEmpty()) return false;
        String ota = Ota.prop("sys.z9x.ota"), why = Ota.prop("sys.z9x.ota.why");
        boolean health = "running".equals(Ota.prop("init.svc.z9x_ota_health"));   // "" if not readable
        switch (Outcome.afterGate(ota, why, health, lastTry)) {
            case WAIT:
                Log.i(Ota.TAG, "update to " + v + ": gate " + ota + (health ? ", health check running" : "")
                        + ", asking again later");
                return true;
            case PROBLEM:
                problem(c, st, v, why);
                break;
            default:
                Log.i(Ota.TAG, "update to " + v + ": gate " + ota + (why.isEmpty() ? "" : " (" + why + ")")
                        + ", nothing to report");
        }
        st.setGateWatch("");
        return false;
    }

    /** The update runs, but the gate's check after it failed: a calm result with the reason. */
    private static void problem(Context c, Store st, String v, String why) {
        Log.w(Ota.TAG, "update to " + v + " is running, but the boot gate reports a problem: " + why);
        st.setGateWatch("");
        st.setLastResult("unhealthy:" + v, why);
        UpdateService.notifyResult(c, c.getString(R.string.done_unhealthy_title, v),
                c.getString(R.string.done_unhealthy_body, problemText(c, why)));
    }

    /** sys.z9x.ota.why in plain words of the current language (unknown reasons as the gate wrote them). */
    static String problemText(Context c, String why) {
        switch (Outcome.why(why)) {
            case RESTARTED: return c.getString(R.string.why_restarted);
            case SERVICE: return c.getString(R.string.why_service);
            case LAMP: return c.getString(R.string.why_lamp);
            case CONFIRM: return c.getString(R.string.why_confirm);
            case NO_ROLLBACK: return c.getString(R.string.why_no_rollback);
            case OTHER: return why.trim();
            default: return c.getString(R.string.why_unknown);
        }
    }
}
