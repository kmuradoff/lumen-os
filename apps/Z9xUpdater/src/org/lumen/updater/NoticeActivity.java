// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The one-time statistics notice (Notice): a small card over the home screen with what is sent and two buttons.
 * "OK" has the focus and is also BACK (statistics stay on); "Turn off" turns the shared switch off. Shown once:
 * the choice counts as seen as soon as the card is up, whatever happens next. When it closes, the first send goes
 * right away (Stats.maybeSend checks the switch, so nothing goes after Turn off).
 */
public final class NoticeActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store st = Store.get(this);
        st.setStatsSeen(true);
        st.setNoticeDue(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 32);
        card.setPadding(p, Ui.dp(this, 28), p, Ui.dp(this, 28));
        card.setBackground(Ui.round(Ui.SURFACE, Ui.dp(this, 20)));

        TextView brand = Ui.text(this, "Lumen OS", 13, Ui.ACCENT, 500);   // the brand mark stays mixed case
        brand.setLetterSpacing(0.32f);
        card.addView(brand, Ui.margins(Ui.wrap(), 0, 0, 0, Ui.dp(this, 10)));
        card.addView(Ui.text(this, getString(R.string.notice_title), 26, Ui.TEXT, 400),
                Ui.margins(Ui.match(), 0, 0, 0, Ui.dp(this, 12)));
        card.addView(Ui.text(this, getString(R.string.notice_body), 17, Ui.TEXT, 400),
                Ui.margins(Ui.match(), 0, 0, 0, Ui.dp(this, 10)));
        card.addView(Ui.text(this, getString(R.string.notice_where, getString(R.string.app_name)), 14, Ui.DIM, 400),
                Ui.margins(Ui.match(), 0, 0, 0, Ui.dp(this, 22)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        TextView ok = Ui.button(this, getString(R.string.notice_ok), true, v -> finish());
        TextView off = Ui.button(this, getString(R.string.notice_off), false, v -> {
            st.setStatsOn(false);
            finish();
        });
        buttons.addView(ok, Ui.margins(Ui.wrap(), 0, 0, Ui.dp(this, 14), 0));
        buttons.addView(off, Ui.wrap());
        card.addView(buttons, Ui.wrap());
        card.setClipChildren(false);
        card.setClipToPadding(false);

        int w = Math.min(Ui.dp(this, 620), getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 48));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(card, lp);
        setContentView(root);
        ok.setFocusableInTouchMode(true);   // focused from the start even in touch mode: OK on the remote = OK
        ok.post(ok::requestFocus);
    }

    /** BACK is OK: statistics stay on. */
    @Override
    public void onBackPressed() {
        finish();
    }

    /** The card is answered (OK, BACK, Turn off, or HOME): the first send goes now, not at the next daily check. */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Stats.maybeSend(getApplicationContext());
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish();   // HOME or another app in front: the notice does not come back
    }
}
