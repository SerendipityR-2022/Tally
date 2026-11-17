package com.example.budgetapp.util;

import com.example.budgetapp.database.Transaction;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/** Spending and available budgets for the dates of one calendar month. */
public final class DailyBudgetPlan {
    public static final String PREF_DYNAMIC = "dynamic_daily_budget";

    private final YearMonth month;
    private final LocalDate today;
    private final double[] spending;
    private final double[] limits;

    public DailyBudgetPlan(YearMonth month, LocalDate today, ZoneId zone, double monthlyBudget,
            boolean dynamic, List<Transaction> transactions) {
        this.month = month;
        this.today = today;
        int numDays = month.lengthOfMonth();
        spending = new double[numDays];
        limits = new double[numDays];

        long start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        for (Transaction tx : transactions) {
            if (tx.date >= start && tx.date < end && tx.isSettled && tx.type == 0
                    && !tx.excludeFromBudget && !"资产互转".equals(tx.category)) {
                int day = Instant.ofEpochMilli(tx.date).atZone(zone).getDayOfMonth();
                spending[day - 1] += tx.amount;
            }
        }

        YearMonth present = YearMonth.from(today);
        int elapsedDays = month.isBefore(present) ? numDays : month.equals(present) ? today.getDayOfMonth() : 0;
        double spentBefore = 0;
        for (int i = 0; i < numDays; i++) {
            // For future dates use the same balance and divide by the number of days after today.
            limits[i] = dynamic ? Math.max(0, monthlyBudget - spentBefore) / (numDays - Math.min(i, elapsedDays))
                    : monthlyBudget / numDays;
            if (i < elapsedDays) spentBefore += spending[i];
        }
    }

    public boolean isFor(YearMonth month, LocalDate today) {
        return this.month.equals(month) && this.today.equals(today);
    }

    public double spendingOn(int day) { return spending[day - 1]; }

    public double limitOn(int day) { return limits[day - 1]; }

    public boolean isOverBudget(int day) {
        // Use the same cent precision shown on the budget card.
        return Math.round(spendingOn(day) * 100) > Math.round(limitOn(day) * 100);
    }
}
