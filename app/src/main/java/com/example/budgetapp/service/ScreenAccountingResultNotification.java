package com.example.budgetapp.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;

import com.example.budgetapp.MainActivity;
import com.example.budgetapp.R;
import com.example.budgetapp.database.Transaction;
import com.example.budgetapp.util.TransactionDisplay;
import com.example.budgetapp.util.TransactionRemarks;

import java.util.UUID;

import static com.example.budgetapp.util.TransactionDisplay.DETAIL_SEPARATOR;

public final class ScreenAccountingResultNotification {
    public static final String CHANNEL_ID = "screen_accounting_results";

    private ScreenAccountingResultNotification() { }

    public static boolean isAllowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static void post(Context context, Transaction transaction) {
        if (!isAllowed(context)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String category = TransactionDisplay.category(transaction);
        if (!TextUtils.isEmpty(transaction.subCategory)) category += " / " + transaction.subCategory;
        String remark = TransactionRemarks.display(transaction);
        String summary = TransactionDisplay.amount(transaction, true) + DETAIL_SEPARATOR + category
                + (remark.isEmpty() ? "" : DETAIL_SEPARATOR + remark);
        PendingIntent openApp = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        try {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.screen_accounting_result_channel), NotificationManager.IMPORTANCE_LOW));
            manager.notify(UUID.randomUUID().toString(), 0, new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_ledger_book)
                    .setContentTitle(context.getString(R.string.screen_accounting_result_success))
                    .setContentText(summary)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(summary))
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setContentIntent(openApp)
                    .setAutoCancel(true)
                    .build());
        } catch (SecurityException ignored) {
            // Notification permission can be revoked while a transaction is being saved.
        }
    }
}
