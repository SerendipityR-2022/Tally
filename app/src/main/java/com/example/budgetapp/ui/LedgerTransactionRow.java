package com.example.budgetapp.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import com.example.budgetapp.R;
import com.example.budgetapp.database.Transaction;
import com.example.budgetapp.util.TransactionRemarks;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
        String category = TextUtils.isEmpty(tx.category) ? "未分类" : tx.category;
        LedgerCategory.bind(icon, LedgerCategory.icon(context, category, tx.type));
        title.setText(TextUtils.isEmpty(tx.subCategory) ? category : category + "  ·  " + tx.subCategory);
        String detail = new SimpleDateFormat(showDate ? "M月d日 HH:mm" : "HH:mm", Locale.CHINA).format(new Date(TransactionRemarks.displayTime(tx)));
        if (!tx.isSettled) detail += "  ·  待生效";
        String remark = TransactionRemarks.display(tx);
        if (!remark.isEmpty()) detail += "  ·  " + remark;
        if (!TextUtils.isEmpty(asset)) detail += "  ·  " + asset;
        meta.setText(detail);
        String currency = TextUtils.isEmpty(tx.currencySymbol) ? "¥" : tx.currencySymbol;
        String sign = tx.type == 1 ? "+" : tx.type == 0 ? "−" : "";
        amount.setText(sign + (showCurrency ? currency : "") + money(tx.amount));
        amount.setTextColor(context.getColor(tx.type == 1 ? R.color.ledger_income
                : tx.type == 2 ? R.color.ledger_chart_blue : R.color.ledger_text));
        photo.setVisibility(TextUtils.isEmpty(tx.photoPath) ? View.GONE : View.VISIBLE);
        view.setAlpha(tx.isSettled ? 1f : 0.65f);
    }

    static String money(double amount) {
        return String.format(Locale.CHINA, "%.2f", Math.abs(amount) < 0.005 ? 0.0 : amount);
    }
}
