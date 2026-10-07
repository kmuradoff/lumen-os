package org.z9x.setup.steps;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.z9x.setup.R;
import org.z9x.setup.SetupActivity;
import org.z9x.setup.ui.Pill;
import org.z9x.setup.ui.QrView;
import org.z9x.setup.ui.Ui;

/**
 * Alice link: a placeholder slot (SPEC 4.7). Hidden in Lumen OS 1.0 (user decision: Alice via Yaha
 * Cloud comes later). Shown only when Z9xProjector's bridge answers feature("alice") with
 * enabled=true; the ALICE track then supplies "url" and "code" in that reply.
 */
public class AliceStep extends Step {
    public static final String ID = "alice";

    public AliceStep(SetupActivity h) {
        super(h);
    }

    @Override
    public String id() { return ID; }

    @Override
    public boolean available() {
        Bundle b = host.aliceInfo;
        return b != null && b.getBoolean("enabled", false) && b.getString("url") != null;
    }

    @Override
    public CharSequence title() { return s(R.string.alice_title); }

    @Override
    public CharSequence subtitle() { return s(R.string.alice_subtitle); }

    @Override
    public View createContent() {
        Bundle b = host.aliceInfo;
        LinearLayout v = Ui.hbox(ctx());
        v.setGravity(Gravity.CENTER_VERTICAL);
        QrView qr = new QrView(ctx());
        qr.setText(b == null ? null : b.getString("url"));
        v.addView(qr, new LinearLayout.LayoutParams(Ui.px(300), Ui.px(300)));
        LinearLayout col = Ui.vbox(ctx());
        TextView scan = Ui.text(ctx(), 30, Ui.TEXT, Ui.regular());
        scan.setText(s(R.string.alice_scan));
        col.addView(scan);
        String code = b == null ? null : b.getString("code");
        if (code != null) {
            TextView c = Ui.text(ctx(), 28, Ui.TEXT_DIM, Ui.regular());
            c.setText(s(R.string.alice_code, code));
            col.addView(c, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
        }
        LinearLayout btns = Ui.hbox(ctx());
        Pill later = new Pill(ctx(), s(R.string.action_later), true);
        later.setOnClickListener(x -> host.next("skip"));
        btns.addView(later);
        col.addView(btns, Ui.lpTop(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 36));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cl.setMarginStart(Ui.px(40));
        v.addView(col, cl);
        return v;
    }
}
