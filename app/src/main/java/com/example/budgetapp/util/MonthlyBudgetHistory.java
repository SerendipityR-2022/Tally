package com.example.budgetapp.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.example.budgetapp.BackupManager;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

/** Keeps the effective monthly amount after the month closes, separately from editable settings. */
public final class MonthlyBudgetHistory {
    static final String SNAPSHOT_PREFIX = "budget_history_month_";
    static final String RECORDED_THROUGH = "budget_history_recorded_through";
    private static final String START_TIME = "budget_start_time";
    private static final String DETAILED = "is_detailed_budget_enabled";
    private static final Object SNAPSHOT_LOCK = new Object();

    private MonthlyBudgetHistory() { }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
    }

    public static String overrideKey(YearMonth month) {
        return "budget_" + month.getYear() + "_" + month.getMonthValue();
    }

    public static double configuredAmount(Context context, YearMonth month) {
        SharedPreferences prefs = preferences(context);
        return prefs.getBoolean(DETAILED, false)
                ? detailedAmount(prefs, CategoryManager.getExpenseCategories(context)) : ordinaryAmount(prefs, month);
    }

    @Nullable
    public static Double amountFor(Context context, YearMonth month, YearMonth present) {
        if (!month.isBefore(present)) return configuredAmount(context, month);
        SharedPreferences prefs = preferences(context);
        Double saved = readSnapshot(prefs, month);
        // An explicit amount is all we can recover for a month before snapshot tracking started.
        return saved != null ? saved : readAmount(prefs, overrideKey(month));
    }

    /** Includes legacy per-month budgets even if an older backup lacks the start timestamp. */
    public static YearMonth earliestMonth(Context context, YearMonth fallback) {
        SharedPreferences prefs = preferences(context);
        long start = prefs.getLong(START_TIME, 0L);
        YearMonth earliest = start > 0 ? YearMonth.from(Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())) : fallback;
        if (earliest.getYear() < 1900 || earliest.isAfter(fallback)) earliest = fallback;
        for (String key : prefs.getAll().keySet()) {
            YearMonth month = null;
            if (key.startsWith(SNAPSHOT_PREFIX)) {
                month = parseMonth(key.substring(SNAPSHOT_PREFIX.length()));
            } else if (key.startsWith("budget_")) {
                String[] parts = key.split("_");
                if (parts.length == 3) {
                    try { month = YearMonth.of(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])); }
                    catch (NumberFormatException | java.time.DateTimeException ignored) { }
                }
            }
            if (month != null && month.getYear() >= 1900 && month.isBefore(earliest)) earliest = month;
        }
        return earliest;
    }

    public static void recordCurrent(Context context) {
        record(context, YearMonth.now());
    }

    static void record(Context context, YearMonth present) {
        SharedPreferences prefs = preferences(context);
        // Read the category catalog before the snapshot lock; category edits can also checkpoint budgets.
        boolean detailed = prefs.getBoolean(DETAILED, false);
        List<String> categories = detailed ? CategoryManager.getExpenseCategories(context) : java.util.Collections.emptyList();
        synchronized (SNAPSHOT_LOCK) {
            YearMonth previous = parseMonth(prefs.getString(RECORDED_THROUGH, ""));
            if (previous != null && previous.isAfter(present)) return;
            boolean enabled = new AssistantConfig(context).isBudgetEnabled();
            double detailedTotal = detailedAmount(prefs, categories);
            double current = enabled ? (detailed ? detailedTotal : ordinaryAmount(prefs, present)) : 0;
            if (previous == null && current <= 0) return;

            SharedPreferences.Editor editor = prefs.edit();
            boolean changed = false;
            if (previous != null) {
                // Settings have not changed since the last checkpoint; fill months when the app was unused.
                for (YearMonth month = previous.plusMonths(1); month.isBefore(present); month = month.plusMonths(1)) {
                    if (readSnapshot(prefs, month) != null) continue;
                    double amount = enabled ? (detailed ? detailedTotal : ordinaryAmount(prefs, month)) : 0;
                    editor.putString(SNAPSHOT_PREFIX + month, Double.toString(amount));
                    changed = true;
                }
            }
            if (!present.equals(previous)) {
                editor.putString(RECORDED_THROUGH, present.toString());
                changed = true;
            }
            String currentKey = SNAPSHOT_PREFIX + present;
            Double stored = readSnapshot(prefs, present);
            if ((enabled || stored == null) && (stored == null || Double.compare(current, stored) != 0)) {
                editor.putString(currentKey, Double.toString(current));
                changed = true;
            }
            if (prefs.getLong(START_TIME, 0L) == 0L) {
                editor.putLong(START_TIME, present.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
                changed = true;
            }
            if (changed) editor.apply();
        }
    }

    public static void changeSettings(Context context, Consumer<SharedPreferences.Editor> change) {
        changeSettings(context, YearMonth.now(), change);
        BackupManager.triggerAutoUploadIfEnabled(context);
    }

    static void changeSettings(Context context, YearMonth present, Consumer<SharedPreferences.Editor> change) {
        checkpointBeforeSettingsChange(context, present);
        SharedPreferences.Editor editor = preferences(context).edit();
        change.accept(editor);
        editor.apply();
        record(context, present);
    }

    public static void checkpointBeforeSettingsChange(Context context) {
        recordCurrent(context);
    }

    private static void checkpointBeforeSettingsChange(Context context, YearMonth present) {
        if (preferences(context).contains(RECORDED_THROUGH)) record(context, present);
    }

    private static double ordinaryAmount(SharedPreferences prefs, YearMonth month) {
        Double amount = readAmount(prefs, overrideKey(month));
        if (amount == null) amount = readAmount(prefs, "monthly_budget");
        return amount == null ? 0 : amount;
    }

    private static double detailedAmount(SharedPreferences prefs, List<String> categories) {
        double total = 0;
        for (String category : categories) {
            Double amount = readAmount(prefs, "budget_cat_" + category);
            if (amount != null) total += amount;
        }
        return total;
    }

    @Nullable private static Double readSnapshot(SharedPreferences prefs, YearMonth month) {
        try {
            String stored = prefs.getString(SNAPSHOT_PREFIX + month, null);
            if (stored == null) return null;
            double amount = Double.parseDouble(stored);
            return Double.isFinite(amount) && amount >= 0 ? amount : null;
        } catch (ClassCastException | NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable private static Double readAmount(SharedPreferences prefs, String key) {
        double amount;
        try {
            amount = prefs.getFloat(key, Float.NaN);
        } catch (ClassCastException ignored) {
            // Older CSV backups can restore a budget using another numeric type.
            try {
                amount = Double.parseDouble(prefs.getString(key, ""));
            } catch (NumberFormatException e) {
                return null;
            } catch (ClassCastException e) {
                Object value = prefs.getAll().get(key);
                if (!(value instanceof Number)) return null;
                amount = ((Number) value).doubleValue();
            }
        }
        return Double.isFinite(amount) && amount >= 0 ? amount : null;
    }

    @Nullable private static YearMonth parseMonth(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            YearMonth month = YearMonth.parse(value);
            return month.getYear() >= 1900 ? month : null;
        } catch (java.time.DateTimeException ignored) {
            return null;
        }
    }
}
