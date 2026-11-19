package com.example.budgetapp.widget;

import com.example.budgetapp.database.TransactionMinimal;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class WeeklyLedgerTest {
    @Test public void aggregatesSevenLocalDaysAcrossTheEndOfDaylightSaving() {
        ZoneId zone = ZoneId.of("America/New_York");
        WeeklyLedger ledger = new WeeklyLedger(LocalDate.of(2026, 11, 3), zone);
        assertEquals(Instant.parse("2026-10-28T04:00:00Z").toEpochMilli(), ledger.start());
        assertEquals(Instant.parse("2026-11-04T05:00:00Z").toEpochMilli(), ledger.endExclusive());
        ledger.add(entry("2026-10-28T03:59:59Z", 0, 200));
        ledger.add(entry("2026-10-28T04:00:00Z", 0, 12.50));
        // Both occurrences of 01:30 on the day the clock moves back belong to the same column.
        ledger.add(entry("2026-11-01T05:30:00Z", 0, 6));
        ledger.add(entry("2026-11-01T06:30:00Z", 0, 3));
        ledger.add(entry("2026-11-01T06:30:00Z", 1, 19));
        ledger.add(entry("2026-11-04T04:59:59Z", 1, 30));
        ledger.add(entry("2026-11-04T05:00:00Z", 1, 500));
        assertArrayEquals(new double[]{12.50, 0, 0, 0, 9, 0, 0}, ledger.expenses, 0.001);
        assertArrayEquals(new double[]{0, 0, 0, 0, 19, 0, 30}, ledger.incomes, 0.001);
        assertEquals(LocalDate.of(2026, 11, 3), ledger.dateAt(WeeklyLedger.TODAY));
    }

    private static TransactionMinimal entry(String utc, int type, double amount) {
        TransactionMinimal transaction = new TransactionMinimal();
        transaction.date = Instant.parse(utc).toEpochMilli();
        transaction.type = type;
        transaction.amount = amount;
        return transaction;
    }
}
