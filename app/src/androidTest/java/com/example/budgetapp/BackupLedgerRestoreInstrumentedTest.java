package com.example.budgetapp;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.budgetapp.database.AppDatabase;
import com.example.budgetapp.database.AssetAccount;
import com.example.budgetapp.database.Goal;
import com.example.budgetapp.database.RecurringRule;
import com.example.budgetapp.database.Transaction;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BackupLedgerRestoreInstrumentedTest {
    @Test public void repairsOldImportAndKeepsBothIdenticalBillsInTheBackup() {
        AppDatabase db = memoryDatabase();
        try {
            long localAssetId = db.assetAccountDao().insert(new AssetAccount("储蓄卡", 999.75, 0));
            db.assetAccountDao().insert(new AssetAccount("本机现金", 80, 0));
            RecurringRule oldRule = rule();
            oldRule.id = 0;
            long localRuleId = db.recurringRuleDao().insert(oldRule);
            db.transactionDao().insertAll(Collections.singletonList(bill()));

            BackupManager.ZipRestoreResult result = merge(db, fixture());
            assertEquals(0, result.assetsAdded);
            assertEquals(0, result.rulesAdded);
            assertEquals(1, result.billsAdded);
            assertEquals(1, result.goalsAdded);
            assertEquals(2, result.linksRepaired);
            assertEquals(999.75, db.assetAccountDao().getAssetByIdSync((int) localAssetId).amount, 0.001);
            assertEquals(localAssetId, db.recurringRuleDao().getRuleByIdSync((int) localRuleId).assetId);
            List<Transaction> bills = db.transactionDao().getAllTransactionsSync();
            assertEquals(2, bills.size());
            for (Transaction bill : bills) {
                assertEquals(localAssetId, bill.assetId);
                assertEquals(localRuleId, bill.recurringRuleId);
                assertFalse(bill.isSettled);
                assertTrue(bill.excludeFromBudget);
            }
            Goal goal = db.goalDao().getAllGoalsSync().get(0);
            assertTrue(goal.isFinished);
            assertEquals(400, goal.savedAmount, 0.001);

            BackupManager.ZipRestoreResult again = merge(db, fixture());
            assertEquals(0, again.billsAdded + again.assetsAdded + again.goalsAdded + again.rulesAdded + again.linksRepaired);
            assertEquals(2, db.transactionDao().getAllTransactionsSync().size());
        } finally {
            db.close();
        }
    }

    @Test public void aCollidingSourceIdCannotLinkToAnUnrelatedLocalAsset() {
        AppDatabase db = memoryDatabase();
        try {
            AssetAccount unrelated = new AssetAccount("其他账户", 80, 0);
            unrelated.id = 4242;
            db.assetAccountDao().insert(unrelated);
            BackupManager.ZipRestoreResult result = merge(db, fixture());
            assertEquals(1, result.assetsAdded);
            assertEquals(1, result.rulesAdded);
            Transaction bill = db.transactionDao().getAllTransactionsSync().get(0);
            assertNotEquals(4242, bill.assetId);
            assertEquals("储蓄卡", db.assetAccountDao().getAssetByIdSync(bill.assetId).name);
            assertEquals(bill.assetId, db.recurringRuleDao().getRuleByIdSync(bill.recurringRuleId).assetId);
        } finally {
            db.close();
        }
    }

    private static AppDatabase memoryDatabase() {
        return Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                AppDatabase.class).build();
    }

    private static BackupManager.ZipRestoreResult merge(AppDatabase db, BackupData data) {
        BackupLedgerRestore restore = new BackupLedgerRestore();
        db.runInTransaction(() -> restore.merge(db, data));
        return restore.result;
    }

    private static BackupData fixture() {
        AssetAccount asset = new AssetAccount("储蓄卡", 420.75, 0);
        asset.id = 4242;
        Goal goal = new Goal("旅行基金", 400, 400, true, 1000);
        goal.id = 992;
        goal.isFinished = true;
        goal.finishedDate = 2000;
        BackupData data = new BackupData(Arrays.asList(bill(), bill()), Collections.singletonList(asset),
                Collections.singletonList(goal));
        data.recurringRules = Collections.singletonList(rule());
        return data;
    }

    private static RecurringRule rule() {
        RecurringRule rule = new RecurringRule();
        rule.id = 919;
        rule.assetId = 4242;
        rule.amount = 23.45;
        rule.category = "购物";
        rule.periodUnit = "MONTH";
        rule.periodInterval = 1;
        rule.createTime = 1000;
        return rule;
    }

    private static Transaction bill() {
        Transaction bill = new Transaction(3000, 0, "购物", 23.45, "", "周期采购");
        bill.assetId = 4242;
        bill.recurringRuleId = 919;
        bill.excludeFromBudget = true;
        bill.isSettled = false;
        bill.scheduledExecuteTime = 3000;
        return bill;
    }
}
