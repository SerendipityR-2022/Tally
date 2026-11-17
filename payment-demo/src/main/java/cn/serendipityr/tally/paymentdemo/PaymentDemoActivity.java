package cn.serendipityr.tally.paymentdemo;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Standalone receipt fixture: two layouts, no permissions and no payment integration. */
public final class PaymentDemoActivity extends Activity {
    private static final int READY = 0, COMBINED = 1, SPLIT = 2;
    private int page;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page = state == null ? READY : state.getInt("page");
        showPage(page);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", page);
        super.onSaveInstanceState(state);
    }

    private void showPage(int next) {
        page = next;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        int side = dp(24);
        panel.setPadding(side, dp(64), side, dp(48));
        scroll.addView(panel);
        text(panel, R.string.demo_caption, 15, 0);
        if (page == READY) {
            text(panel, R.string.demo_title, 28, 0);
            button(panel, R.string.demo_pay, R.id.pay_combined, () -> showPage(COMBINED));
            button(panel, R.string.demo_pay_split, R.id.pay_split, () -> showPage(SPLIT));
        } else {
            text(panel, page == COMBINED ? R.string.demo_success_combined : R.string.demo_success,
                    28, R.id.payment_status);
            if (page == SPLIT) text(panel, R.string.demo_paid, 23, R.id.payment_amount);
            text(panel, R.string.demo_method, 15, 0);
            text(panel, R.string.demo_account, 18, 0);
            button(panel, R.string.demo_return, R.id.return_button, this::finish);
        }
        setContentView(scroll);
    }

    private void text(LinearLayout panel, int label, int sp, int id) {
        TextView text = new TextView(this);
        if (id != 0) text.setId(id);
        text.setText(label);
        text.setTextSize(sp);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        panel.addView(text, lp);
    }

    private void button(LinearLayout panel, int label, int id, Runnable action) {
        Button button = new Button(this);
        button.setId(id);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        panel.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }

    private int dp(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
}
