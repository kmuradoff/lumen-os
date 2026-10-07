package org.z9x.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.z9x.home.data.HomeRepository;
import org.z9x.home.proj.ProjectorBridge;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

/**
 * Emergency home after 3 crashes within 60 s (SPEC 12): a plain A-Z list of apps, Settings, "Switch to
 * the classic launcher" and "Try again". Only framework widgets and the Android focus system, no art,
 * no TvProvider, so it cannot share the bug that caused the crash loop.
 */
final class SafeModeView extends ScrollView {
    SafeModeView(Context c, Runnable retry) {
        super(c);
        setBackgroundColor(0xFF0F1115);
        setFillViewport(true);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 48);
        col.setPadding(pad, dp(c, 32), pad, dp(c, 32));
        addView(col);
        TextView t = new TextView(c);
        t.setText(R.string.safe_mode_title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTextColor(0xFFE8EAED);
        col.addView(t);
        TextView s = new TextView(c);
        s.setText(R.string.safe_mode_desc);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        s.setTextColor(0xFF9AA0A6);
        s.setPadding(0, dp(c, 8), 0, dp(c, 16));
        col.addView(s);
        Button first = button(c, col, c.getString(R.string.safe_mode_retry), v -> retry.run());
        button(c, col, c.getString(R.string.tile_android_settings), v -> ProjectorBridge.openAndroidSettings(c));
        if (HomeRepository.classicAvailable(c)) {
            button(c, col, c.getString(R.string.safe_mode_classic), v -> ProjectorBridge.setLauncher(c, "classic"));
        }
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> l = new ArrayList<>(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        Collator col2 = Collator.getInstance();
        l.sort((a, b) -> col2.compare(String.valueOf(a.loadLabel(pm)), String.valueOf(b.loadLabel(pm))));
        for (ResolveInfo ri : l) {
            if (ri.activityInfo == null || c.getPackageName().equals(ri.activityInfo.packageName)) continue;
            final Intent i = new Intent(Intent.ACTION_MAIN).setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            button(c, col, String.valueOf(ri.loadLabel(pm)), v -> Launch.start(c, i, "safe-mode app"));
        }
        first.requestFocus();
    }

    private static Button button(Context c, LinearLayout col, String text, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(c, 20), 0, dp(c, 20), 0);
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF0E0E0F, 0xFFE8EAED}));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, round(0xFFE8EAED, c));
        sl.addState(new int[]{}, round(0xFF1E232C, c));
        b.setBackground(sl);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 420), dp(c, 44));
        lp.topMargin = dp(c, 6);
        col.addView(b, lp);
        return b;
    }

    private static GradientDrawable round(int color, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, 22));
        return g;
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
