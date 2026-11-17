package com.example.budgetapp.util;

import com.example.budgetapp.database.Transaction;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/** Spending and available budgets for the dates of one calendar month. */
public final class DailyBudgetPlan {
    public static final String PREF_DYNAMIC = "dynamic_daily_budget";

    private final YearMonth month;
    private final LocalDate today;
    private final boolean dynamic;
    private final int elapsedDays;
    private final double[] spending;
    private final double[] reserved;
    private final double[] limits;
    private final double remainingAfterKnownExpenses;
    private final double futureDailyBudget;

    public DailyBudgetPlan(YearMonth month, LocalDate today, ZoneId zone, double monthlyBudget,
            boolean dynamic, List<Transaction> transactions) {
        this.month = month;
        this.today = today;
        this.dynamic = dynamic;
        int numDays = month.lengthOfMonth();
        YearMonth present = YearMonth.from(today);
        elapsedDays = month.isBefore(present) ? numDays : month.equals(present) ? today.getDayOfMonth() : 0;
        spending = new double[numDays];
        reserved = new double[numDays];
        limits = new double[numDays];

        long start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        double knownExpenses = 0;
        for (Transaction tx : transactions) {
            if (tx.date >= start && tx.date < end && tx.type == 0
                    && !tx.excludeFromBudget && !"资产互转".equals(tx.category)) {
                int day = Instant.ofEpochMilli(tx.date).atZone(zone).getDayOfMonth();
                // Reserve known expenses once, regardless of when their status changes to settled.
                knownExpenses += tx.amount;
                if (!tx.isSettled || day > elapsedDays) reserved[day - 1] += tx.amount;
                if (tx.isSettled && (!dynamic || day <= elapsedDays)) spending[day - 1] += tx.amount;
            }
        }
        remainingAfterKnownExpenses = monthlyBudget - knownExpenses;
        int futureDays = numDays - elapsedDays;
        futureDailyBudget = futureDays > 0 ? Math.max(0, remainingAfterKnownExpenses) / futureDays : 0;

        if (dynamic) {
            double spentBefore = 0;
            for (int i = 0; i < elapsedDays; i++) {
                limits[i] = Math.max(0, monthlyBudget - spentBefore) / (numDays - i);
                spentBefore += spending[i];
            }
            Arrays.fill(limits, elapsedDays, numDays, futureDailyBudget);
        } else {
            Arrays.fill(limits, monthlyBudget / numDays);
        }
    }

    public boolean isFor(YearMonth month, LocalDate today) {
        return this.month.equals(month) && this.today.equals(today);
    }

    public double spendingOn(int day) { return spending[day - 1]; }

    public boolean isForecastDay(int day) { return dynamic && day > elapsedDays; }

    public double reservedOn(int day) { return reserved[day - 1]; }

    /** Card usage is planned spending on forecast dates and recorded spending otherwise. */
    public double usageOn(int day) { return isForecastDay(day) ? reservedOn(day) : spendingOn(day); }

    public double limitOn(int day) { return limits[day - 1]; }

    public double remainingAfterKnownExpenses() { return remainingAfterKnownExpenses; }

    public double futureDailyBudget() { return futureDailyBudget; }

    public boolean isOverBudget(int day) {
        // Use the same cent precision shown on the budget card.
        return Math.round(usageOn(day) * 100) > Math.round(limitOn(day) * 100);
    }
}
