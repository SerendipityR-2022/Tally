package com.example.budgetapp.util;

import com.example.budgetapp.database.Transaction;

import org.junit.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Arrays;

import static org.junit.Assert.*;

public class DailyBudgetPlanTest {
    private static final YearMonth APRIL = YearMonth.of(2026, 4);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final double CENT_FRACTION = 0.00001;

    @Test public void allocatingTheMonthlyBudgetUsesOnlySpendingBeforeEachHistoricalDay() {
        DailyBudgetPlan plan = plan(true, expense(1, 30), expense(2, 50));
        assertEquals(30.00, plan.spendingOn(1), CENT_FRACTION);
        assertEquals(50.00, plan.limitOn(1), CENT_FRACTION);
        assertEquals(50.00, plan.spendingOn(2), CENT_FRACTION);
        assertEquals(50.689655, plan.limitOn(2), CENT_FRACTION);
        assertEquals(50.714286, plan.limitOn(3), CENT_FRACTION);
        assertEquals(52.592593, plan.limitOn(4), CENT_FRACTION);
        assertEquals(plan.limitOn(4), plan.limitOn(30), 0);
    }

    @Test public void todaysSpendingChangesTheFutureForecastWithoutChangingTodaysLimit() {
        DailyBudgetPlan plan = plan(true, expense(7, 250), expense(3, 20), expense(2, 50), expense(1, 30));
        assertEquals(20.00, plan.spendingOn(3), CENT_FRACTION);
        assertEquals(50.714286, plan.limitOn(3), CENT_FRACTION);
        assertEquals(51.851852, plan.limitOn(4), CENT_FRACTION);
        assertEquals(plan.limitOn(4), plan.limitOn(7), 0);
        assertEquals(plan.limitOn(4), plan.limitOn(30), 0);
    }

    @Test public void onlySettledBudgetExpensesInTheSelectedMonthCount() {
        Transaction excluded = expense(1, 90);
        excluded.excludeFromBudget = true;
        Transaction pending = expense(1, 60);
        pending.isSettled = false;
        Transaction transfer = expense(1, 40);
        transfer.category = "资产互转";
        Transaction income = expense(1, 100);
        income.type = 1;
        Transaction previousMonth = new Transaction(midnight(APRIL.atDay(1)) - 1, 0, "餐饮", 70);

        DailyBudgetPlan plan = plan(true, excluded, pending, transfer, income, previousMonth, expense(1, 30));
        assertEquals(30.00, plan.spendingOn(1), CENT_FRACTION);
        assertEquals(50.689655, plan.limitOn(2), CENT_FRACTION);
        assertFalse(plan.isOverBudget(1));
    }

    @Test public void clampsAnExhaustedBudgetAndHandlesTheEndOfALeapMonth() {
        YearMonth february = YearMonth.of(2024, 2);
        Transaction first = new Transaction(midnight(february.atDay(1)), 0, "餐饮", 290);
        Transaction last = new Transaction(midnight(february.atEndOfMonth()), 0, "交通", 1);
        DailyBudgetPlan plan = new DailyBudgetPlan(february, LocalDate.of(2024, 3, 1), ZONE, 290, true,
                Arrays.asList(first, last));
        assertEquals(10.00, plan.limitOn(1), CENT_FRACTION);
        assertEquals(0.00, plan.limitOn(2), CENT_FRACTION);
        assertFalse(plan.isOverBudget(2));
        assertEquals(0.00, plan.limitOn(29), CENT_FRACTION);
        assertTrue(plan.isOverBudget(29));
    }

    @Test public void fixedModeAndAWholeFutureMonthUseTheOriginalAverage() {
        DailyBudgetPlan fixed = plan(false, expense(1, 30), expense(2, 50.50));
        assertEquals(50.00, fixed.limitOn(2), CENT_FRACTION);
        assertEquals(50.00, fixed.limitOn(30), CENT_FRACTION);
        assertTrue(fixed.isOverBudget(2));

        DailyBudgetPlan future = new DailyBudgetPlan(APRIL, LocalDate.of(2026, 3, 31), ZONE, 1500, true,
                Arrays.asList(expense(1, 100), expense(5, 30)));
        assertEquals(50.00, future.limitOn(1), CENT_FRACTION);
        assertEquals(50.00, future.limitOn(30), CENT_FRACTION);
    }

    private static DailyBudgetPlan plan(boolean dynamic, Transaction... transactions) {
        return new DailyBudgetPlan(APRIL, APRIL.atDay(3), ZONE, 1500, dynamic, Arrays.asList(transactions));
    }

    private static Transaction expense(int day, double amount) {
        return new Transaction(midnight(APRIL.atDay(day)), 0, "餐饮", amount);
    }

    private static long midnight(LocalDate date) { return date.atStartOfDay(ZONE).toInstant().toEpochMilli(); }
}
