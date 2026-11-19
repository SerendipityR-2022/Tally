package com.example.budgetapp.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.widget.RemoteViews;

import com.example.budgetapp.MainActivity;
import com.example.budgetapp.R;
import com.example.budgetapp.database.AppDatabase;
import com.example.budgetapp.database.TransactionMinimal;
import com.example.budgetapp.util.TransactionDisplay;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WeeklySummaryWidget extends AppWidgetProvider {
    private static final String TOGGLE = "com.example.budgetapp.widget.WEEKLY_TOGGLE_VALUES";
    private static final String PREFS = "weekly_widget";
    private static final ExecutorService UPDATES = Executors.newSingleThreadExecutor();
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.SIMPLIFIED_CHINESE);
    private static final int[] DAYS = {R.id.weekly_day_0, R.id.weekly_day_1, R.id.weekly_day_2,
            R.id.weekly_day_3, R.id.weekly_day_4, R.id.weekly_day_5, R.id.weekly_day_6};
    private static final int FLAGS = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    private static final int BAR_WIDTH = 560;
    private static final int BAR_HEIGHT = 80;

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (TOGGLE.equals(action)) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
            if (info == null || !new ComponentName(context, WeeklySummaryWidget.class).equals(info.provider)) return;
            update(context, manager, new int[]{id}, true);
        } else if (Intent.ACTION_DATE_CHANGED.equals(action) || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            onUpdate(context, manager, manager.getAppWidgetIds(new ComponentName(context, WeeklySummaryWidget.class)));
        } else {
            super.onReceive(context, intent);
        }
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        update(context, manager, ids, false);
    }

    private void update(Context context, AppWidgetManager manager, int[] ids, boolean toggleAmounts) {
        if (ids.length == 0) return;
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        UPDATES.execute(() -> {
            try {
                SharedPreferences prefs = preferences(app);
                if (toggleAmounts) {
                    String key = Integer.toString(ids[0]);
                    // Persist the choice before showing it in the launcher.
                    if (!prefs.edit().putBoolean(key, !prefs.getBoolean(key, false)).commit()) {
                        Log.w("WeeklySummaryWidget", "Could not save amount visibility");
                    }
                }
                ZoneId zone = ZoneId.systemDefault();
                WeeklyLedger ledger = new WeeklyLedger(LocalDate.now(zone), zone);
                for (TransactionMinimal transaction : AppDatabase.getDatabase(app).transactionDao()
                        .getMinimalTransactionsSync(ledger.start(), ledger.endExclusive() - 1)) {
                    ledger.add(transaction);
                }
                Bitmap bars = drawBars(app, ledger);
                for (int id : ids) {
                    boolean hidden = prefs.getBoolean(Integer.toString(id), false);
                    manager.updateAppWidget(id, views(app, id, ledger, hidden, bars));
                }
            } catch (RuntimeException error) {
                Log.e("WeeklySummaryWidget", "Could not refresh weekly totals", error);
            } finally {
                if (pending != null) pending.finish();
            }
        });
    }

    @Override public void onDeleted(Context context, int[] appWidgetIds) {
        SharedPreferences.Editor edit = preferences(context).edit();
        for (int id : appWidgetIds) edit.remove(Integer.toString(id));
        edit.apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static RemoteViews views(Context context, int id, WeeklyLedger ledger, boolean hidden, Bitmap bars) {
        RemoteViews result = new RemoteViews(context.getPackageName(), R.layout.widget_weekly_summary);
        result.setTextViewText(R.id.weekly_today_expense, amountLabel(ledger.expenses[WeeklyLedger.TODAY], hidden));
        result.setTextViewText(R.id.weekly_today_income, amountLabel(ledger.incomes[WeeklyLedger.TODAY], hidden));
        result.setImageViewResource(R.id.weekly_show_amounts,
                hidden ? R.drawable.ic_weekly_eye_off : R.drawable.ic_weekly_eye);
        result.setContentDescription(R.id.weekly_show_amounts, context.getString(hidden
                ? R.string.weekly_widget_show_amounts : R.string.weekly_widget_hide_amounts));

        Intent toggle = new Intent(context, WeeklySummaryWidget.class).setAction(TOGGLE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        result.setOnClickPendingIntent(R.id.weekly_show_amounts, PendingIntent.getBroadcast(context, id, toggle, FLAGS));
        Intent quickRecord = new Intent(context, MainActivity.class).setAction(MainActivity.ACTION_QUICK_RECORD)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        result.setOnClickPendingIntent(R.id.weekly_quick_record, PendingIntent.getActivity(context, 0, quickRecord, FLAGS));

        StringBuilder chartDescription = new StringBuilder();
        for (int i = 0; i < WeeklyLedger.DAYS; i++) {
            String day = i == WeeklyLedger.TODAY ? context.getString(R.string.weekly_widget_today)
                    : ledger.dateAt(i).format(WEEKDAY);
            result.setTextViewText(DAYS[i], day);
            if (!hidden) {
                if (i > 0) chartDescription.append('；');
                chartDescription.append(context.getString(R.string.weekly_widget_day_description,
                        day, money(ledger.expenses[i]), money(ledger.incomes[i])));
            }
        }
        result.setImageViewBitmap(R.id.weekly_chart_bars, bars);
        result.setContentDescription(R.id.weekly_chart_bars, hidden
                ? context.getString(R.string.weekly_widget_chart) : chartDescription.toString());
        return result;
    }

    static Bitmap drawBars(Context context, WeeklyLedger ledger) {
        double max = 0;
        for (int i = 0; i < WeeklyLedger.DAYS; i++) {
            max = Math.max(max, Math.max(Math.abs(ledger.expenses[i]), Math.abs(ledger.incomes[i])));
        }
        Bitmap bitmap = Bitmap.createBitmap(BAR_WIDTH, BAR_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int expense = context.getColor(R.color.weekly_widget_expense);
        int income = context.getColor(R.color.weekly_widget_income);
        int zero = context.getColor(R.color.weekly_widget_zero);
        float column = (float) BAR_WIDTH / WeeklyLedger.DAYS;
        for (int i = 0; i < WeeklyLedger.DAYS; i++) {
            float center = (i + 0.5f) * column;
            drawBar(canvas, paint, center - 8, ledger.expenses[i], max, expense, zero);
            drawBar(canvas, paint, center + 8, ledger.incomes[i], max, income, zero);
        }
        return bitmap;
    }

    private static void drawBar(Canvas canvas, Paint paint, float x, double value, double max, int color, int zero) {
        boolean empty = value == 0.0;
        float height = empty ? 8f : Math.max(13f, (float) (Math.abs(value) / max * (BAR_HEIGHT - 2)));
        paint.setColor(empty ? zero : color);
        canvas.drawRoundRect(x - 6, BAR_HEIGHT - height, x + 6, BAR_HEIGHT, 5, 5, paint);
    }

    private static String money(double amount) { return "¥ " + TransactionDisplay.money(amount); }

    private static CharSequence amountLabel(double amount, boolean hidden) {
        if (hidden) return "••••";
        SpannableString text = new SpannableString(money(amount));
        text.setSpan(new RelativeSizeSpan(0.75f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }
}
