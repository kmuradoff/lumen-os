// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Process;
import android.util.Log;

import org.json.JSONException;

/**
 * Daily check (any network), plus one 10 minutes after boot. Never installs: in "Download
 * automatically" mode it only downloads and then asks (decision 2026-10-06 / ota Q6).
 */
public final class CheckJob extends JobService {
    private static final int JOB_DAILY = 7701, JOB_BOOT = 7702;

    public static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        ComponentName cn = new ComponentName(c, CheckJob.class);
        if (js.getPendingJob(JOB_DAILY) == null) {
            js.schedule(new JobInfo.Builder(JOB_DAILY, cn)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(24 * 3600 * 1000L, 6 * 3600 * 1000L)
                    .setPersisted(true).build());
        }
        js.schedule(new JobInfo.Builder(JOB_BOOT, cn)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(10 * 60 * 1000L)
                .setOverrideDeadline(30 * 60 * 1000L).build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Store st = Store.get(this);
        if (st.autoMode() == Store.AUTO_OFF) return false;
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                run(st);
            } catch (Throwable t) {
                Log.w(Ota.TAG, "check job", t);
            }
            jobFinished(params, false);
        }, "update-check").start();
        return true;
    }

    private void run(Store st) {
        Store.State s = st.state();
        if (s == Store.State.PAUSED && st.autoMode() == Store.AUTO_DOWNLOAD) {
            UpdateService.start(this, UpdateService.ACTION_DOWNLOAD, false);   // resume an interrupted download
            return;
        }
        Checker.Result r = Checker.check(this);
        if (r.outcome != Checker.Outcome.OFFER) return;
        String ver = r.manifest.version;
        if (st.autoMode() == Store.AUTO_DOWNLOAD && st.state() != Store.State.DOWNLOADED) {
            UpdateService.start(this, UpdateService.ACTION_DOWNLOAD, false);
        } else {
            UpdateService.notifyResult(this, getString(R.string.avail_title, ver), getString(R.string.n_available_body));
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

    static String versionOf(Store st) {
        try {
            return new UpdateManifest(st.manifestJson()).version;
        } catch (JSONException e) {
            return "";
        }
    }
}
