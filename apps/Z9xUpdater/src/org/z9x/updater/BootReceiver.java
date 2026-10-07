// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * After every boot: tell the user how a pending update ended (new build running = success; old
 * build + z9x_ota.sh "rolledback" = the update could not start and the previous version was
 * restored), clean up, and (re)schedule the checks.
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
        Store.State s = st.state();
        if (!target.isEmpty() && s != Store.State.READY && s != Store.State.INSTALLING) {
            st.clearPending();
        } else if (!target.isEmpty() && target.equals(now)) {
            String v = st.pendingVersion();
            Log.i(Ota.TAG, "update to " + v + " is running (slot " + Ota.slot() + ", gate " + ota + ")");
            st.clearPending();
            UpdateService.deleteFiles(c);
            st.clearOffer();
            st.setState(Store.State.IDLE);
            st.setLastResult("ok:" + v);
            UpdateService.notifyResult(c, c.getString(R.string.done_ok_title, v), "");
        } else if (!target.isEmpty() && (s == Store.State.READY || "rolledback".equals(ota))
                && !Engine.isNeedReboot()) {
            String v = st.pendingFromVersion();
            Log.w(Ota.TAG, "update to " + target + " did not start; running " + now + " (gate " + ota + ")");
            st.clearPending();
            UpdateService.deleteFiles(c);
            st.clearOffer();
            st.setState(Store.State.IDLE);
            st.setLastResult("rollback:" + v);
            UpdateService.notifyResult(c, c.getString(R.string.done_rollback_title),
                    c.getString(R.string.done_rollback_body, v));
        } else if (s == Store.State.INSTALLING) {
            // the install was interrupted by a restart: update_engine starts over next time
            st.clearPending();
            st.setState(Store.State.DOWNLOADED);
        } else if (s == Store.State.DOWNLOADING || s == Store.State.CHECKING) {
            st.setState(s == Store.State.DOWNLOADING ? Store.State.PAUSED : Store.State.IDLE);
        }
        Ota.setBusy(c, false, st.state().name().toLowerCase());
    }
}
