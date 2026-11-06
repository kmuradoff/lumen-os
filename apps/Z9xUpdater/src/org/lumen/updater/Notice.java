// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.PowerManager;
import android.util.Log;

import java.util.List;

/**
 * One-time statistics notice after an update (NoticeActivity). An install that updated never saw the first setup's
 * Done page, which shows the statistics switch: on the first boot after a version change the notice becomes due,
 * when the first setup is finished, this install never saw the choice (Store.statsSeen: the setup's Done page or
 * this notice) and the switch is on. A fresh install has its setup still running at that boot, so it gets the
 * choice there and never the notice. The due notice waits for the home screen itself (no HDMI input, no film, no
 * screensaver, nothing playing, screen on): CheckJob looks every minute after boot. The two decisions are in
 * NoticeRule (plain Java, NoticeTest).
 */
final class Notice {
    private Notice() {}

    /** First look 30 s after boot, then every minute for 8 h; still due afterwards: again after the next boot. */
    static final long FIRST_MS = 30 * 1000L, NEXT_MS = 60 * 1000L;
    static final int TRIES = 480;

    /**
     * The card is not shown (2026-11-05): statistics are on by default and the only switch is in the projector
     * settings, Diagnostics. A notice left due by an earlier build is cleared, so nothing waits for it.
     */
    static final boolean SHOWN = false;

    /** BOOT_COMPLETED / MY_PACKAGE_REPLACED: record the version, make the notice due, start looking. */
    static void onBoot(Context c) {
        Store st = Store.get(c);
        int vc = Ota.currentVersionCode();
        if (!SHOWN) {
            st.setNoticeVc(vc);
            if (st.noticeDue()) st.setNoticeDue(false);
            return;
        }
        if (NoticeRule.dueAtBoot(Stats.setupDone(c), st.statsSeen(), st.statsOn(), st.noticeVc(), vc)) st.setNoticeDue(true);
        st.setNoticeVc(vc);
        if (!st.noticeDue()) return;
        if (st.statsSeen() || !st.statsOn()) {
            st.setNoticeDue(false);
            return;
        }
        Log.i(Ota.TAG, "statistics notice due: waiting for the home screen");
        CheckJob.scheduleNotice(c, 1);
    }

    /** CheckJob: shows the notice when it may; true = look again later. */
    static boolean tryShow(Context c) {
        Store st = Store.get(c);
        if (!st.noticeDue() || st.statsSeen() || !st.statsOn()) {
            st.setNoticeDue(false);
            return false;
        }
        PowerManager pm = c.getSystemService(PowerManager.class);
        AudioManager am = c.getSystemService(AudioManager.class);
        boolean interactive = pm != null && pm.isInteractive();
        boolean playing = am != null && am.isMusicActive();
        ComponentName home = home(c), top = top(c);
        String h = home == null ? null : home.flattenToString(), t = top == null ? null : top.flattenToString();
        if (!NoticeRule.canShow(h, t, interactive, playing)) {
            Log.i(Ota.TAG, "statistics notice waits: home in front " + (h != null && h.equals(t)) + ", screen on "
                    + interactive + ", playing " + playing);
            return true;
        }
        Log.i(Ota.TAG, "statistics notice shown");
        c.startActivity(new Intent(c, NoticeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        return false;
    }

    /** The default home activity (Lumen Home or the classic launcher; visible through the manifest's HOME query). */
    static ComponentName home(Context c) {
        ResolveInfo ri = c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return null;
        // an activity-alias runs as its target: that is the class the task list reports
        String cls = ri.activityInfo.targetActivity != null ? ri.activityInfo.targetActivity : ri.activityInfo.name;
        return new ComponentName(ri.activityInfo.packageName, cls);
    }

    /** The activity in front (REAL_GET_TASKS, granted by the platform signature: every app's task, not only ours). */
    @SuppressWarnings("deprecation")
    static ComponentName top(Context c) {
        try {
            List<ActivityManager.RunningTaskInfo> t = c.getSystemService(ActivityManager.class).getRunningTasks(1);
            return t == null || t.isEmpty() ? null : t.get(0).topActivity;
        } catch (Throwable e) {
            return null;
        }
    }
}
