package com.example.budgetapp.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.budgetapp.R;
import com.example.budgetapp.database.TransactionMinimal;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class WeeklyWidgetInstrumentedTest {
    @Test public void expensesGoOnTheLeftAndIncomeOnTheRightWithGrayZeros() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.of(2026, 11, 19);
        WeeklyLedger ledger = new WeeklyLedger(today, zone);
        ledger.add(entry(today.minusDays(6), zone, 0, 24));
        ledger.add(entry(today, zone, 1, 36));
        Bitmap chart = WeeklySummaryWidget.drawBars(context, ledger);
        int expense = context.getColor(R.color.weekly_widget_expense);
        int income = context.getColor(R.color.weekly_widget_income);
        int zero = context.getColor(R.color.weekly_widget_zero);
        for (int day = 0; day < WeeklyLedger.DAYS; day++) {
            assertTrue(containsColor(chart, day, false, day == 0 ? expense : zero));
            assertTrue(containsColor(chart, day, true, day == WeeklyLedger.TODAY ? income : zero));
            assertFalse(containsColor(chart, day, false, income));
            assertFalse(containsColor(chart, day, true, expense));
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FrameLayout parent = new FrameLayout(context);
            View shown = WeeklySummaryWidget.views(context, 0, ledger, false, chart).apply(context, parent);
            assertEquals("周五", text(shown, R.id.weekly_day_0));
            assertEquals("今日", text(shown, R.id.weekly_day_6));
            assertEquals("¥ 0.00", text(shown, R.id.weekly_today_expense));
            assertEquals("¥ 36.00", text(shown, R.id.weekly_today_income));
            View hidden = WeeklySummaryWidget.views(context, 0, ledger, true, null).apply(context, parent);
            assertEquals("••••", text(hidden, R.id.weekly_today_income));
            assertFalse(hidden.findViewById(R.id.weekly_chart_bars).getContentDescription().toString().contains("36.00"));
        });
    }

    private static String text(View root, int id) {
        return ((TextView) root.findViewById(id)).getText().toString();
    }

    private static boolean containsColor(Bitmap chart, int day, boolean right, int color) {
        int width = chart.getWidth() / WeeklyLedger.DAYS;
        int start = day * width + (right ? width / 2 : 0);
        for (int x = start; x < start + width / 2; x++) {
            for (int y = 0; y < chart.getHeight(); y++) {
                if (chart.getPixel(x, y) == color) return true;
            }
        }
        return false;
    }

    private static TransactionMinimal entry(LocalDate day, ZoneId zone, int type, double amount) {
        TransactionMinimal transaction = new TransactionMinimal();
        transaction.date = day.atStartOfDay(zone).toInstant().toEpochMilli();
        transaction.type = type;
        transaction.amount = amount;
        return transaction;
    }
}
