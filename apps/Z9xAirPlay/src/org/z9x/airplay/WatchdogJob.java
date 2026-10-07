/*
 * Restarts the AirPlay receiver when AirPlay is on but its service is not running, for the
 * cases START_STICKY does not cover: e.g. ActiveServices gave up restarting a service that
 * kept crashing at intervals longer than a minute, or the process was killed and its sticky
 * restart was dropped. The service is then started as from BootReceiver
 * (START_ACTIVITIES_FROM_BACKGROUND exempts it from the FGS start limits).
 *
 * What it can NOT do: bring back a process that AMS marked "bad" (a non-persistent app that
 * crashed twice within 60 s, AppErrors). As far as we know from AOSP (JobServiceContext binds
 * job services with FLAG_FROM_BACKGROUND, and ProcessList.startProcessLocked silently refuses
 * a background start of a bad process; not checked against the LineageOS 21 source), the job
 * cannot even run then, and broadcasts cannot start it either. Only a foreground start
 * (opening AirPlay settings) or a reboot clears that state. So after a native crash loop
 * open the settings once (also in /system/app: the app is deliberately not persistent, see
 * AndroidManifest.xml). Real isolation would need the native engine in its own
 * process (see BUILD_PLAN.md, open points).
 * If the service runs but its native start failed, it brings the next start retry forward.
 * Periodic (15 min, the minimum), persisted across reboots, cancelled when AirPlay is off.
 *
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.z9x.airplay;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

public final class WatchdogJob extends JobService {
    private static final int JOB_ID = 0x5a39_0001;
    private static final long PERIOD_MS = 15 * 60 * 1000L;

    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null || js.getPendingJob(JOB_ID) != null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, WatchdogJob.class))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build());
        } catch (Throwable t) {
            Log.w(AirPlayService.TAG, "watchdog job: " + t);
        }
    }

    static void cancel(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js != null) js.cancel(JOB_ID);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!Prefs.get(this).getBoolean(Prefs.ENABLED, Prefs.DEF_ENABLED)) return false;
        AirPlayService s = AirPlayService.instance();
        if (s == null) {
            Log.w(AirPlayService.TAG, "watchdog: the receiver was not running, starting it");
            AirPlayService.reload(this);
        } else {
            s.retryIfFailed("watchdog");   // running but its start failed: try again now
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
