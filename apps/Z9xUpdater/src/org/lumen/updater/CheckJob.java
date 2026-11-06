// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.os.Process;
import android.util.Log;

import org.json.JSONException;

/**
 * Daily check (any network), plus one 10 minutes after boot. Never installs: in "Download
 * automatically" mode it only downloads and then asks (decision 2026-10-06).
 * JOB_GATE (no network, any update mode): the boot gate's verdict a few minutes after an update's
 * first boot (BootReceiver.gateVerdict), asked again while the gate has not decided.
 * JOB_NOTICE (no network): the one-time statistics notice waits for the home screen (Notice).
 * JOB_STATS (any network): statistics soon after boot, so an update is reported at once.
 */
public final class CheckJob extends JobService {
    private static final int JOB_DAILY = 7701, JOB_BOOT = 7702;
    /** Gate looks alternate between two ids: a running job never reschedules (and so stops) itself. */
    private static final int JOB_GATE = 7703, JOB_GATE_NEXT = 7704;
    /** First look 3 min after BOOT_COMPLETED (the gate checks 90 s after boot), then every 2 min, 5 looks. */
    private static final long GATE_FIRST_MS = 3 * 60 * 1000L, GATE_NEXT_MS = 2 * 60 * 1000L;
    private static final int GATE_TRIES = 5;
    /** The statistics notice's looks for the home screen (Notice), two alternating ids like the gate's. */
    private static final int JOB_NOTICE = 7705, JOB_NOTICE_NEXT = 7706;
    /** Statistics soon after boot (any network): an update is reported at once, not at the next check. */
    private static final int JOB_STATS = 7707;

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

    /** attempt 1..GATE_TRIES; attempt 1 (BootReceiver, never a running gate job) starts the series over. */
    static void scheduleGate(Context c, int attempt) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (attempt <= 1) js.cancel(JOB_GATE_NEXT);
        long wait = attempt <= 1 ? GATE_FIRST_MS : GATE_NEXT_MS;
        PersistableBundle x = new PersistableBundle();
        x.putInt("attempt", attempt);
        js.schedule(new JobInfo.Builder(attempt % 2 == 1 ? JOB_GATE : JOB_GATE_NEXT, new ComponentName(c, CheckJob.class))
                .setMinimumLatency(wait).setOverrideDeadline(wait + GATE_NEXT_MS)
                .setExtras(x).build());
    }

    /** attempt 1..Notice.TRIES; attempt 1 (Notice.onBoot) starts the series over. */
    static void scheduleNotice(Context c, int attempt) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || attempt > Notice.TRIES) return;
        if (attempt <= 1) js.cancel(JOB_NOTICE_NEXT);
        long wait = attempt <= 1 ? Notice.FIRST_MS : Notice.NEXT_MS;
        PersistableBundle x = new PersistableBundle();
        x.putInt("attempt", attempt);
        js.schedule(new JobInfo.Builder(attempt % 2 == 1 ? JOB_NOTICE : JOB_NOTICE_NEXT, new ComponentName(c, CheckJob.class))
                .setMinimumLatency(wait).setOverrideDeadline(wait + 60 * 1000L)
                .setExtras(x).build());
    }

    /** 30 s to 15 min after boot, once there is a network (Stats.maybeSend keeps its own once-a-day rule). */
    static void scheduleStats(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(JOB_STATS, new ComponentName(c, CheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(30 * 1000L).setOverrideDeadline(15 * 60 * 1000L).build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (params.getJobId() == JOB_STATS) {
            Stats.maybeSend(this);   // own thread
            return false;
        }
        if (params.getJobId() == JOB_GATE || params.getJobId() == JOB_GATE_NEXT) {
            int attempt = params.getExtras().getInt("attempt", 1);
            try {
                if (BootReceiver.gateVerdict(this, attempt >= GATE_TRIES)) scheduleGate(this, attempt + 1);
            } catch (Throwable t) {
                Log.w(Ota.TAG, "gate job", t);
            }
            return false;
        }
        if (params.getJobId() == JOB_NOTICE || params.getJobId() == JOB_NOTICE_NEXT) {
            int attempt = params.getExtras().getInt("attempt", 1);
            try {
                if (Notice.tryShow(this)) scheduleNotice(this, attempt + 1);
            } catch (Throwable t) {
                Log.w(Ota.TAG, "notice job", t);
            }
            return false;
        }
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
