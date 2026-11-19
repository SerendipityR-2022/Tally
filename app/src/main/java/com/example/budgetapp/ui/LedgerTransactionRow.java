package com.example.budgetapp.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import com.example.budgetapp.R;
import com.example.budgetapp.database.Transaction;
import com.example.budgetapp.util.TransactionDisplay;
import com.example.budgetapp.util.TransactionRemarks;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static com.example.budgetapp.util.TransactionDisplay.DETAIL_SEPARATOR;

/** Displays the same transaction anatomy in the ledger and in the spending ranking. */
final class LedgerTransactionRow {
    private final View view;
    private final ImageView icon;
    private final TextView title;
    private final TextView meta;
    private final TextView amount;
    private final View photo;

    LedgerTransactionRow(View view) {
        this.view = view;
        icon = view.findViewById(R.id.ledger_tx_icon);
        title = view.findViewById(R.id.ledger_tx_title);
        meta = view.findViewById(R.id.ledger_tx_meta);
        amount = view.findViewById(R.id.ledger_tx_amount);
        photo = view.findViewById(R.id.ledger_tx_photo);
    }

    void bind(Transaction tx, String asset, boolean showCurrency, boolean showDate) {
        Context context = view.getContext();
        String category = TransactionDisplay.category(tx);
        LedgerCategory.bind(icon, LedgerCategory.icon(context, category, tx.type));
        title.setText(TextUtils.isEmpty(tx.subCategory) ? category : category + DETAIL_SEPARATOR + tx.subCategory);
        String detail = new SimpleDateFormat(showDate ? "M月d日 HH:mm" : "HH:mm", Locale.CHINA).format(new Date(TransactionRemarks.displayTime(tx)));
        if (!tx.isSettled) detail += DETAIL_SEPARATOR + "待生效";
        String remark = TransactionRemarks.display(tx);
        if (!remark.isEmpty()) detail += DETAIL_SEPARATOR + remark;
        if (!TextUtils.isEmpty(asset)) detail += DETAIL_SEPARATOR + asset;
        meta.setText(detail);
        amount.setText(TransactionDisplay.amount(tx, showCurrency));
        amount.setTextColor(context.getColor(tx.type == 1 ? R.color.ledger_income
                : tx.type == 2 ? R.color.ledger_chart_blue : R.color.ledger_text));
        photo.setVisibility(TextUtils.isEmpty(tx.photoPath) ? View.GONE : View.VISIBLE);
        view.setAlpha(tx.isSettled ? 1f : 0.65f);
    }

    static String money(double amount) {
        return TransactionDisplay.money(amount);
    }
}
