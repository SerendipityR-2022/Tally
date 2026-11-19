package com.example.budgetapp.util;

import android.text.TextUtils;

import com.example.budgetapp.database.Transaction;

import java.util.Locale;

/** Shared formatting for the ledger and the saved transaction notification. */
public final class TransactionDisplay {
    public static final String DETAIL_SEPARATOR = "  ·  ";

    private TransactionDisplay() { }

    public static String category(Transaction transaction) {
        return TextUtils.isEmpty(transaction.category) ? "未分类"
                : CategoryManager.canonicalCategory(transaction.type, transaction.category);
    }

    public static String amount(Transaction transaction, boolean showCurrency) {
        String currency = TextUtils.isEmpty(transaction.currencySymbol) ? "¥" : transaction.currencySymbol;
        String sign = transaction.type == 1 ? "+" : transaction.type == 0 ? "−" : "";
        return sign + (showCurrency ? currency : "") + money(transaction.amount);
    }

    public static String money(double amount) {
        return String.format(Locale.CHINA, "%.2f", Math.abs(amount) < 0.005 ? 0.0 : amount);
    }
}
