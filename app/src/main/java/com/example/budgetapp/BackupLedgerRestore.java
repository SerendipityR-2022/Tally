package com.example.budgetapp;

import com.example.budgetapp.database.AppDatabase;
import com.example.budgetapp.database.AssetAccount;
import com.example.budgetapp.database.Goal;
import com.example.budgetapp.database.RecurringRule;
import com.example.budgetapp.database.Transaction;
import com.example.budgetapp.util.CategoryManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Merges one ZIP snapshot in a Room transaction and translates its internal references. */
final class BackupLedgerRestore {
    final BackupManager.ZipRestoreResult result = new BackupManager.ZipRestoreResult();
    private final Map<Integer, Integer> assetIds = new HashMap<>();
    private final Map<Integer, Integer> ruleIds = new HashMap<>();

    int assetId(int sourceId) {
        return mappedId(assetIds, sourceId);
    }

    void merge(AppDatabase db, BackupData backup) {
        List<AssetAccount> localAssets = db.assetAccountDao().getAllAssetsSync();
        Set<Integer> previousAssetIds = new HashSet<>();
        for (AssetAccount account : localAssets) previousAssetIds.add(account.id);
        Map<List<?>, List<AssetAccount>> assets = index(localAssets, BackupLedgerRestore::assetKey);
        for (AssetAccount incoming : entries(backup.assets)) {
            if (incoming == null) continue;
            int sourceId = incoming.id;
            AssetAccount existing = take(assets.get(assetKey(incoming)), row -> true);
            if (existing == null) {
                incoming.id = 0;
                incoming.id = Math.toIntExact(db.assetAccountDao().insert(incoming));
                result.assetsAdded++;
            }
            if (sourceId > 0) assetIds.put(sourceId, existing == null ? incoming.id : existing.id);
        }

        List<RecurringRule> localRules = db.recurringRuleDao().getAllRulesSync();
        Set<Integer> previousRuleIds = new HashSet<>();
        for (RecurringRule rule : localRules) previousRuleIds.add(rule.id);
        Map<List<?>, List<RecurringRule>> rules = index(localRules, BackupLedgerRestore::ruleKey);
        for (RecurringRule incoming : entries(backup.recurringRules)) {
            if (incoming == null) continue;
            int sourceId = incoming.id;
            int sourceAssetId = incoming.assetId;
            int assetId = assetId(sourceAssetId);
            List<RecurringRule> candidates = rules.get(ruleKey(incoming));
            RecurringRule existing = take(candidates, row -> row.assetId == assetId);
            if (existing == null) {
                existing = take(candidates, row -> orphaned(row.assetId, sourceAssetId, previousAssetIds));
                if (existing != null) {
                    existing.assetId = assetId;
                    db.recurringRuleDao().update(existing);
                    result.linksRepaired++;
                }
            }
            if (existing == null) {
                incoming.id = 0;
                incoming.assetId = assetId;
                incoming.id = Math.toIntExact(db.recurringRuleDao().insert(incoming));
                result.rulesAdded++;
            }
            if (sourceId > 0) ruleIds.put(sourceId, existing == null ? incoming.id : existing.id);
        }

        if (backup.records != null && !backup.records.isEmpty()) {
            Map<List<?>, List<Transaction>> bills = index(db.transactionDao().getAllTransactionsSync(),
                    BackupLedgerRestore::billKey);
            List<Transaction> additions = new ArrayList<>();
            for (Transaction incoming : backup.records) {
                if (incoming == null) continue;
                int sourceAssetId = incoming.assetId;
                int sourceRuleId = incoming.recurringRuleId;
                int assetId = assetId(sourceAssetId);
                int ruleId = mappedId(ruleIds, sourceRuleId);
                List<Transaction> candidates = bills.get(billKey(incoming));
                if (take(candidates, row -> row.assetId == assetId && row.recurringRuleId == ruleId) != null) continue;
                Transaction orphan = take(candidates, row ->
                        (row.assetId == assetId || orphaned(row.assetId, sourceAssetId, previousAssetIds))
                        && (row.recurringRuleId == ruleId || orphaned(row.recurringRuleId, sourceRuleId, previousRuleIds)));
                if (orphan != null) {
                    orphan.assetId = assetId;
                    orphan.recurringRuleId = ruleId;
                    db.transactionDao().update(orphan);
                    result.linksRepaired++;
                } else {
                    incoming.id = 0;
                    incoming.assetId = assetId;
                    incoming.recurringRuleId = ruleId;
                    additions.add(incoming);
                }
            }
            if (!additions.isEmpty()) db.transactionDao().insertAll(additions);
            result.billsAdded = additions.size();
        }

        if (backup.goals != null && !backup.goals.isEmpty()) {
            Map<List<?>, List<Goal>> goals = index(db.goalDao().getAllGoalsSync(), BackupLedgerRestore::goalKey);
            List<Goal> additions = new ArrayList<>();
            for (Goal incoming : backup.goals) {
                if (incoming == null || take(goals.get(goalKey(incoming)), row -> true) != null) continue;
                incoming.id = 0;
                additions.add(incoming);
            }
            if (!additions.isEmpty()) db.goalDao().insertAll(additions);
            result.goalsAdded = additions.size();
        }
    }

    private static int mappedId(Map<Integer, Integer> ids, int sourceId) {
        Integer mapped = ids.get(sourceId);
        return mapped == null ? 0 : mapped;
    }

    private static boolean orphaned(int localId, int sourceId, Set<Integer> previousIds) {
        // Old imports left source IDs as-is. Only repair missing links, never replace a valid local link.
        return localId > 0 && localId == sourceId && !previousIds.contains(localId);
    }

    private static List<?> assetKey(AssetAccount asset) {
        return Arrays.asList(asset.name, asset.type, asset.currencySymbol);
    }

    private static List<?> ruleKey(RecurringRule rule) {
        return Arrays.asList(rule.type, rule.amount, rule.currencySymbol,
                CategoryManager.canonicalCategory(rule.type, rule.category), rule.subCategory,
                rule.periodUnit, rule.periodInterval, rule.startDate, rule.endDate, rule.triggerTime, rule.note,
                rule.isEnabled, rule.createTime);
    }

    private static List<?> billKey(Transaction bill) {
        return Arrays.asList(bill.date, bill.type, bill.amount, bill.currencySymbol,
                CategoryManager.canonicalCategory(bill.type, bill.category), bill.subCategory,
                bill.note, bill.remark, bill.photoPath, bill.targetObject, bill.excludeFromBudget, bill.isSettled,
                bill.scheduledExecuteTime);
    }

    private static List<?> goalKey(Goal goal) {
        return Arrays.asList(goal.name, goal.targetAmount, goal.savedAmount, goal.isPriority, goal.createdAt,
                goal.isFinished, goal.finishedDate);
    }

    private static <T> List<T> entries(List<T> entries) {
        return entries == null ? Collections.emptyList() : entries;
    }

    private static <T> Map<List<?>, List<T>> index(List<T> rows, Function<T, List<?>> key) {
        Map<List<?>, List<T>> index = new HashMap<>();
        for (T row : rows) index.computeIfAbsent(key.apply(row), ignored -> new ArrayList<>()).add(row);
        return index;
    }

    private static <T> T take(List<T> candidates, Predicate<T> matches) {
        if (candidates == null) return null;
        // Consume each pre-existing row at most once, so identical legitimate entries survive.
        for (Iterator<T> iterator = candidates.iterator(); iterator.hasNext(); ) {
            T candidate = iterator.next();
            if (matches.test(candidate)) {
                iterator.remove();
                return candidate;
            }
        }
        return null;
    }
}
