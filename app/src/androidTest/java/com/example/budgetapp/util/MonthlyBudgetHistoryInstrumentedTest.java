package com.example.budgetapp.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.YearMonth;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MonthlyBudgetHistoryInstrumentedTest {
    private static final YearMonth APRIL = YearMonth.of(2026, 4);
    private static final YearMonth MAY = APRIL.plusMonths(1);
    private static final YearMonth JUNE = APRIL.plusMonths(2);
    private static final YearMonth JULY = APRIL.plusMonths(3);
    private CategoryTestContext context;
    private SharedPreferences prefs;

    @Before public void setup() {
        context = new CategoryTestContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
        prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("is_budget_enabled", true).putFloat("monthly_budget", 1500).apply();
    }

    @After public void cleanup() { context.clear(); }

    @Test public void ordinaryBudgetKeepsTheOldDefaultAcrossMissedMonthsAndUsesExplicitOverrides() {
        prefs.edit().putFloat(MonthlyBudgetHistory.overrideKey(MAY), 1800).apply();
        MonthlyBudgetHistory.record(context, APRIL);
        MonthlyBudgetHistory.changeSettings(context, JULY, edit -> edit.putFloat("monthly_budget", 2200));

        assertAmount(APRIL, JULY, 1500);
        assertAmount(MAY, JULY, 1800);
        assertAmount(JUNE, JULY, 1500);
        assertAmount(JULY, JULY, 2200);
        // A recorded amount takes precedence over an imported legacy override for the same month.
        prefs.edit().putFloat(MonthlyBudgetHistory.overrideKey(APRIL), 1900).apply();
        assertAmount(APRIL, JULY, 1500);
    }

    @Test public void lastEffectiveAmountInDetailedModeIsRetainedWhenSettingsChange() {
        prefs.edit().putFloat(MonthlyBudgetHistory.overrideKey(APRIL), 1300).apply();
        MonthlyBudgetHistory.record(context, APRIL);
        MonthlyBudgetHistory.changeSettings(context, APRIL, edit -> edit.putBoolean("is_detailed_budget_enabled", true)
                .putFloat("budget_cat_餐饮", 900).putFloat("budget_cat_交通", 200));
        assertAmount(APRIL, APRIL, 1100);
        MonthlyBudgetHistory.changeSettings(context, MAY, edit -> edit.putFloat("budget_cat_餐饮", 1200));
        MonthlyBudgetHistory.changeSettings(context, JUNE, edit -> edit.putBoolean("is_detailed_budget_enabled", false));

        assertAmount(APRIL, JUNE, 1100);
        assertAmount(MAY, JUNE, 1400);
        assertAmount(JUNE, JUNE, 1500);
    }

    @Test public void legacyMonthsOnlyShowAmountsThatWereActuallyStored() {
        prefs.edit().putFloat(MonthlyBudgetHistory.overrideKey(APRIL), 980).apply();
        MonthlyBudgetHistory.record(context, JUNE);
        MonthlyBudgetHistory.changeSettings(context, JUNE, edit -> edit.putFloat("monthly_budget", 2000));

        assertEquals(APRIL, MonthlyBudgetHistory.earliestMonth(context, JUNE));
        assertAmount(APRIL, JUNE, 980);
        assertNull(MonthlyBudgetHistory.amountFor(context, MAY, JUNE));
        assertAmount(JUNE, JUNE, 2000);
    }

    @Test public void disablingBudgetKeepsEarlierAmountsButDoesNotCreateLimitsForDisabledMonths() {
        MonthlyBudgetHistory.record(context, APRIL);
        MonthlyBudgetHistory.changeSettings(context, APRIL, edit -> edit.putBoolean("is_budget_enabled", false));
        MonthlyBudgetHistory.changeSettings(context, JULY, edit -> edit.putBoolean("is_budget_enabled", true));

        assertAmount(APRIL, JULY, 1500);
        assertAmount(MAY, JULY, 0);
        assertAmount(JUNE, JULY, 0);
        assertAmount(JULY, JULY, 1500);
    }

    private void assertAmount(YearMonth month, YearMonth present, double expected) {
        Double amount = MonthlyBudgetHistory.amountFor(context, month, present);
        assertNotNull(amount);
        assertEquals(expected, amount, 0.001);
    }
}
