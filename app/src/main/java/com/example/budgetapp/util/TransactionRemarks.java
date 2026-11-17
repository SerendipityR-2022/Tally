package com.example.budgetapp.util;

import com.example.budgetapp.database.Transaction;

import java.text.DateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Presents previous label and remark data as one comment without rewriting the ledger. */
public final class TransactionRemarks {
    private static final Pattern GENERATED_TIME = Pattern.compile(
            "^(?:生效时间 )?\\p{Nd}{2}-\\p{Nd}{2}\\s+\\p{Nd}{2}:\\p{Nd}{2}(?:\\s+|$)");

    private TransactionRemarks() {}

    public static String display(Transaction transaction) {
        String remark = trimmed(transaction.remark);
        String legacy = trimmed(transaction.note);
        Matcher prefix = GENERATED_TIME.matcher(legacy);
        if (prefix.find()) {
            legacy = legacy.substring(prefix.end()).trim();
            if ("manual".equals(legacy) || "(后台)".equals(legacy)) legacy = "";
        }
        if ("PREVIEW_BILL".equals(remark)) return legacy;
        if (remark.isEmpty()) return legacy;
        if (legacy.isEmpty() || remark.equals(legacy)) return remark;
        return remark + " · " + legacy;
    }

    public static String withTime(Transaction transaction, DateFormat timeFormat) {
        String remark = display(transaction);
        if ("PREVIEW_BILL".equals(transaction.remark)) return remark;
        String time = timeFormat.format(new Date(displayTime(transaction)));
        return remark.isEmpty() ? time : time + " · " + remark;
    }

    public static long displayTime(Transaction transaction) {
        return transaction.recurringRuleId != 0 && transaction.scheduledExecuteTime > 0
                ? transaction.scheduledExecuteTime : transaction.date;
    }

    public static void applyEdit(Transaction original, Transaction updated, String remark) {
        if (display(original).equals(remark)) {
            // An edit to another field must not change data consumed by existing bookkeeping rules.
            updated.note = original.note;
            updated.remark = original.remark;
        } else if ("加班".equals(original.category) || "自动续费".equals(original.category)) {
            // The legacy column contains overtime hours or the renewal identity for these records.
            updated.note = original.note;
        }
    }

    private static String trimmed(String text) {
        return text == null ? "" : text.trim();
    }
}
