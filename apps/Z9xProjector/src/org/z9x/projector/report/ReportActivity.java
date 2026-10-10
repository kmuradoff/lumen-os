package org.z9x.projector.report;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.z9x.projector.R;

/**
 * "Send a problem report" (Projector settings > Diagnostics, only while ro.z9x.report.url is set).
 * Screens, one per ReportJob state:
 *   consent    what is included and what is removed; "Send report" / "Cancel" (focus starts on Cancel:
 *              nothing leaves the projector without a deliberate press)
 *   progress   collecting step i of n, then sending x of y; Cancel stops and deletes everything
 *   done       the report code (LR-XXXX) to give to the developer
 *   failed     why, "Try again" (the same zip, or a new collection) / "Close"
 * Framework widgets like SettingsActivity, D-pad only, every text 18sp or more. BACK: cancel a running
 * report, else close. The job lives in the process, so HOME during a report does not lose the code.
 */
public final class ReportActivity extends Activity implements ReportJob.Listener {
    private static final String TAG = ReportConfig.TAG;

    /** True when this image has a report server; Settings shows its row only then. */
    public static boolean isAvailable() {
        return ReportConfig.enabled();
    }

    /** Opens the report screen (any context; does nothing while the feature is off). */
    public static void open(Context ctx) {
        if (!isAvailable()) return;
        try {
            Intent i = new Intent(ctx, ReportActivity.class);
            if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "open: " + t);
        }
    }

    private ReportJob job;
    private ScrollView scroll;
    private TextView title;
    private LinearLayout body;
    private LinearLayout buttons;
    private ReportJob.State shown;
    private TextView status;
    private TextView hint;
    private ProgressBar bar;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!ReportConfig.enabled()) {
            finish();
            return;
        }
        job = ReportJob.get();
        buildFrame();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (job == null) return;
        job.addListener(this);
        shown = null;
        render();
    }

    @Override
    protected void onStop() {
        if (job != null) job.removeListener(this);
        super.onStop();
    }

    @Override
    public void onReportChanged() {
        if (!isFinishing() && !isDestroyed()) render();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (job != null) {
            if (job.busy()) job.cancel();
            else job.reset();
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ frame
    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildFrame() {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.bg));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(24), dp(48), dp(16));     // sized for 960x540 dp (1080p at xhdpi)
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        title = text("", 28, R.color.text, true);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(16);
        root.addView(buttons, blp);
        setContentView(scroll);
    }

    private TextView text(CharSequence s, int sp, int colorRes, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView add(LinearLayout parent, CharSequence s, int sp, int colorRes, boolean bold, int topDp) {
        TextView t = text(s, sp, colorRes, bold);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        parent.addView(t, lp);
        return t;
    }

    private TextView button(int res, Runnable action) {
        TextView b = text(getString(res), 20, R.color.text, false);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(200));
        b.setPadding(dp(28), dp(12), dp(28), dp(12));
        b.setBackgroundResource(R.drawable.row_bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(v -> {
            try {
                action.run();
            } catch (Throwable t) {
                Log.w(TAG, "click: " + t);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(16));
        buttons.addView(b, lp);
        return b;
    }

    // ------------------------------------------------------------------ states
    private void render() {
        ReportJob.State st = job.state();
        if (st == shown) {
            updateProgress();
            return;
        }
        shown = st;
        body.removeAllViews();
        buttons.removeAllViews();
        status = hint = null;
        bar = null;
        View focus;
        switch (st) {
            case COLLECTING:
            case UPLOADING:
                focus = buildProgress();
                break;
            case DONE:
                focus = buildDone();
                break;
            case FAILED:
                focus = buildFailed();
                break;
            default:
                focus = buildConsent();
                break;
        }
        scroll.scrollTo(0, 0);
        if (focus != null) focus.post(focus::requestFocus);
    }

    private View buildConsent() {
        title.setText(R.string.report_title);
        add(body, getString(R.string.report_intro), 18, R.color.text_dim, false, 0);

        final LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(12);
        body.addView(cols, clp);
        column(cols, R.string.report_included, R.string.report_inc_system, R.string.report_inc_logs,
                R.string.report_inc_crashes, R.string.report_inc_kernel, R.string.report_inc_state);
        View gap = new View(this);
        cols.addView(gap, new LinearLayout.LayoutParams(dp(32), 1));
        column(cols, R.string.report_excluded, R.string.report_exc_accounts, R.string.report_exc_network,
                R.string.report_exc_serial, R.string.report_exc_apps, R.string.report_exc_files);

        add(body, getString(R.string.report_footer), 18, R.color.text_dim, false, 12);
        String last = ReportStore.lastCode(this);
        long when = ReportStore.lastTime(this);
        if (last != null && when > 0) {
            String date = DateUtils.formatDateTime(this, when,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
            add(body, getString(R.string.report_last, last, date), 18, R.color.text_dim, false, 6);
        }

        button(R.string.report_send, () -> job.start(this));
        TextView cancel = button(R.string.cancel, this::finish);
        // Only when the text is taller than the screen (long languages): let D-pad UP reach it so the
        // ScrollView can show its top again. A block that fits never takes focus.
        cols.post(() -> {
            View content = scroll.getChildAt(0);
            if (content != null && content.getHeight() > scroll.getHeight()) {
                cols.setFocusable(true);
                cols.setBackgroundResource(R.drawable.row_bg);
            }
        });
        return cancel;
    }

    private void column(LinearLayout parent, int headerRes, int... items) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        parent.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        add(col, getString(headerRes), 20, R.color.accent, true, 0);
        for (int r : items) add(col, "•  " + getString(r), 18, R.color.text, false, 4);
    }

    private View buildProgress() {
        title.setText(R.string.report_title);
        status = add(body, "", 22, R.color.text, false, 8);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(false);
        bar.setMax(1000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        lp.topMargin = dp(16);
        body.addView(bar, lp);
        hint = add(body, getString(R.string.report_log_hint), 18, R.color.text_dim, false, 16);
        TextView cancel = button(R.string.cancel, () -> {
            job.cancel();
            finish();
        });
        updateProgress();
        return cancel;
    }

    private void updateProgress() {
        if (status == null || bar == null) return;
        if (job.state() == ReportJob.State.COLLECTING) {
            int n = Math.max(1, job.steps());
            int s = Math.max(1, Math.min(job.step(), n));
            status.setText(getString(R.string.report_collecting, s, n));
            bar.setProgress(1000 * (s - 1) / n);
            hint.setVisibility(View.VISIBLE);
        } else {
            long t = Math.max(1, job.total());
            status.setText(getString(R.string.report_sending, Formatter.formatShortFileSize(this, job.sent()),
                    Formatter.formatShortFileSize(this, job.total())));
            bar.setProgress((int) (1000 * Math.min(job.sent(), t) / t));
            hint.setVisibility(View.GONE);
        }
    }

    private View buildDone() {
        title.setText(R.string.report_done_title);
        TextView code = add(body, job.code() == null ? "" : job.code(), 56, R.color.accent, true, 12);
        code.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        add(body, getString(R.string.report_done_text), 20, R.color.text, false, 12);
        add(body, getString(R.string.report_footer), 18, R.color.text_dim, false, 12);
        return button(R.string.report_close, () -> {
            job.reset();
            finish();
        });
    }

    private View buildFailed() {
        title.setText(R.string.report_failed_title);
        add(body, getString(failureText(job.failure())), 20, R.color.text, false, 12);
        TextView retry = button(R.string.report_retry, () -> job.start(this));
        button(R.string.report_close, () -> {
            job.reset();
            finish();
        });
        return retry;
    }

    private static int failureText(ReportUploader.Failure f) {
        if (f == null) return R.string.report_err_server;
        switch (f) {
            case NO_NETWORK: return R.string.report_err_no_network;
            case NETWORK: return R.string.report_err_network;
            case RATE_LIMITED: return R.string.report_err_rate;
            case REJECTED: return R.string.report_err_rejected;
            case COLLECT: return R.string.report_err_collect;
            case SERVER:
            default: return R.string.report_err_server;
        }
    }
}
