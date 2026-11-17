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
        DailyBudgetPlan plan = plan(true, expense(3, 20), expense(2, 50), expense(1, 30));
        assertEquals(20.00, plan.spendingOn(3), CENT_FRACTION);
        assertEquals(50.714286, plan.limitOn(3), CENT_FRACTION);
        assertEquals(51.851852, plan.limitOn(4), CENT_FRACTION);
        assertEquals(plan.limitOn(4), plan.limitOn(7), 0);
        assertEquals(plan.limitOn(4), plan.limitOn(30), 0);
    }

    @Test public void futureBudgetReservesDatedBillsAndPendingRecurringExpenses() {
        Transaction nextMonth = new Transaction(midnight(APRIL.plusMonths(1).atDay(1)), 0, "购物", 900);
        Transaction excluded = expense(15, 400);
        excluded.excludeFromBudget = true;
        DailyBudgetPlan plan = plan(true, expense(1, 30), expense(2, 50), expense(3, 20),
                expense(7, 260), pendingExpense(18, 90), nextMonth, excluded);

        assertEquals(50.714286, plan.limitOn(3), CENT_FRACTION);
        assertFalse(plan.isForecastDay(3));
        assertEquals(1050.00, plan.remainingAfterKnownExpenses(), CENT_FRACTION);
        assertEquals(38.888889, plan.futureDailyBudget(), CENT_FRACTION);
        assertEquals(plan.futureDailyBudget(), plan.limitOn(4), 0);
        assertEquals(plan.futureDailyBudget(), plan.limitOn(18), 0);
        assertEquals(plan.futureDailyBudget(), plan.limitOn(30), 0);
        assertEquals(260.00, plan.reservedOn(7), CENT_FRACTION);
        assertEquals(90.00, plan.reservedOn(18), CENT_FRACTION);
        assertEquals(0.00, plan.spendingOn(7), CENT_FRACTION);
        assertTrue(plan.isForecastDay(7));
        assertFalse(plan.isOverBudget(7));
    }

    @Test public void settlementMovesAPendingExpenseToSpendingWithoutDeductingItAgain() {
        Transaction pending = pendingExpense(3, 70);
        DailyBudgetPlan before = plan(true, expense(1, 30), pending);
        pending.isSettled = true;
        DailyBudgetPlan after = plan(true, expense(1, 30), pending);

        assertEquals(70.00, before.reservedOn(3), CENT_FRACTION);
        assertEquals(0.00, before.spendingOn(3), CENT_FRACTION);
        assertEquals(0.00, after.reservedOn(3), CENT_FRACTION);
        assertEquals(70.00, after.spendingOn(3), CENT_FRACTION);
        assertEquals(1400.00, after.remainingAfterKnownExpenses(), CENT_FRACTION);
        assertEquals(before.futureDailyBudget(), after.futureDailyBudget(), 0);
        assertEquals(before.limitOn(3), after.limitOn(3), 0);
    }

    @Test public void onlySettledBudgetExpensesInTheSelectedMonthCountAsHistoricalUsage() {
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

    @Test public void fixedModeKeepsTheOriginalDailyLimitEvenWithUpcomingBills() {
        DailyBudgetPlan fixed = plan(false, expense(1, 30), expense(2, 50.50), expense(7, 80), pendingExpense(8, 30));
        assertEquals(50.00, fixed.limitOn(2), CENT_FRACTION);
        assertEquals(50.00, fixed.limitOn(30), CENT_FRACTION);
        assertTrue(fixed.isOverBudget(2));
        assertFalse(fixed.isForecastDay(7));
        assertEquals(80.00, fixed.spendingOn(7), CENT_FRACTION);
    }

    @Test public void aWholeFutureMonthSharesItsBudgetAfterReservations() {
        DailyBudgetPlan future = new DailyBudgetPlan(APRIL, LocalDate.of(2026, 3, 31), ZONE, 1500, true,
                Arrays.asList(expense(1, 100), pendingExpense(5, 30)));
        assertEquals(45.666667, future.limitOn(1), CENT_FRACTION);
        assertEquals(45.666667, future.limitOn(30), CENT_FRACTION);
    }

    @Test public void forecastCannotBeNegativeAndIsZeroOnTheLastDay() {
        DailyBudgetPlan exhausted = plan(true, expense(2, 100), pendingExpense(30, 1500));
        assertEquals(-100.00, exhausted.remainingAfterKnownExpenses(), CENT_FRACTION);
        assertEquals(0.00, exhausted.limitOn(4), CENT_FRACTION);

        DailyBudgetPlan lastDay = new DailyBudgetPlan(APRIL, APRIL.atEndOfMonth(), ZONE, 1500, true,
                Arrays.asList(expense(30, 100), pendingExpense(30, 40)));
        assertEquals(1360.00, lastDay.remainingAfterKnownExpenses(), CENT_FRACTION);
        assertEquals(0.00, lastDay.futureDailyBudget(), CENT_FRACTION);
        assertFalse(lastDay.isForecastDay(30));
    }

    private static DailyBudgetPlan plan(boolean dynamic, Transaction... transactions) {
        return new DailyBudgetPlan(APRIL, APRIL.atDay(3), ZONE, 1500, dynamic, Arrays.asList(transactions));
    }

    private static Transaction expense(int day, double amount) {
        return new Transaction(midnight(APRIL.atDay(day)), 0, "餐饮", amount);
    }

    private static Transaction pendingExpense(int day, double amount) {
        Transaction expense = expense(day, amount);
        expense.isSettled = false;
        expense.recurringRuleId = 42;
        expense.scheduledExecuteTime = expense.date;
        return expense;
    }

    private static long midnight(LocalDate date) { return date.atStartOfDay(ZONE).toInstant().toEpochMilli(); }
}
