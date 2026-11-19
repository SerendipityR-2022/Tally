package com.example.budgetapp.widget;

import com.example.budgetapp.database.TransactionMinimal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

final class WeeklyLedger {
    static final int DAYS = 7;
    static final int TODAY = DAYS - 1;
    private final LocalDate first;
    private final ZoneId zone;
    final double[] expenses = new double[DAYS];
    final double[] incomes = new double[DAYS];

    WeeklyLedger(LocalDate today, ZoneId zone) {
        this.first = today.minusDays(TODAY);
        this.zone = zone;
    }

    LocalDate dateAt(int index) { return first.plusDays(index); }

    long start() { return first.atStartOfDay(zone).toInstant().toEpochMilli(); }

    long endExclusive() { return dateAt(DAYS).atStartOfDay(zone).toInstant().toEpochMilli(); }

    void add(TransactionMinimal transaction) {
        LocalDate date = Instant.ofEpochMilli(transaction.date).atZone(zone).toLocalDate();
        long index = ChronoUnit.DAYS.between(first, date);
        if (index < 0 || index >= DAYS || !Double.isFinite(transaction.amount)) return;
        if (transaction.type == 0) expenses[(int) index] += transaction.amount;
        else if (transaction.type == 1) incomes[(int) index] += transaction.amount;
    }
}
