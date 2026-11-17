package com.example.budgetapp.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.budgetapp.BackupData;
import com.example.budgetapp.BackupManager;
import com.example.budgetapp.database.AppDatabase;
import com.example.budgetapp.database.Transaction;
import com.example.budgetapp.util.CategoryIcon;
import com.example.budgetapp.util.CategoryManager;
import com.example.budgetapp.util.CategoryTestContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class CategoryCatalogInstrumentedTest {
    private CategoryTestContext context;

    @Before public void setUp() {
        context = new CategoryTestContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @After public void tearDown() { context.clear(); }

    @Test public void referencePresetsDoNotTurnAccountActionsIntoExpenses() {
        List<String> categories = CategoryManager.getExpenseCategories(context);
        assertTrue(categories.containsAll(Arrays.asList("消费", "餐饮", "购物", "住房", "交通", "通讯", "娱乐", "医疗",
                "教育", "红包", "旅行", "投资", "美容", "亲子", "人情社交", "宠物", "快递", "其他")));
        assertFalse(categories.contains("转账"));
        assertFalse(categories.contains("借出"));
        assertFalse(categories.contains("还款"));
        assertEquals(CategoryIcon.HOME, CategoryManager.getIcon(context, true, "居家"));
        assertEquals(CategoryIcon.TRANSFER, LedgerCategory.icon(context, "资产互转", 2));
        assertEquals(CategoryIcon.REPAYMENT, LedgerCategory.icon(context, "还款", 2));
    }

    @Test public void upgradeKeepsUserOrderAndDoesNotReAddDeletedCategories() {
        context.getSharedPreferences("category_prefs", Context.MODE_PRIVATE).edit()
                .putString("key_expense_categories", "通勤,医疗,快递,自定义").apply();
        List<String> categories = CategoryManager.getExpenseCategories(context);
        assertEquals(Arrays.asList("通勤", "医疗", "快递"), categories.subList(0, 3));
        assertEquals(1, Collections.frequency(categories, "快递"));
        assertTrue(categories.contains("宠物"));
        assertFalse(categories.contains("餐饮"));
        assertEquals("自定义", categories.get(categories.size() - 1));

        categories.remove("宠物");
        CategoryManager.saveExpenseCategories(context, categories);
        assertFalse(CategoryManager.getExpenseCategories(context).contains("宠物"));
    }

    @Test public void customIconsAreIndependentAndRoundTripThroughCsv() throws Exception {
        String category = "周末";
        assertTrue(CategoryManager.saveCategory(context, true, null, category, CategoryIcon.PET));
        assertTrue(CategoryManager.saveCategory(context, false, null, category, CategoryIcon.GAME));
        assertTrue(CategoryManager.saveCategory(context, true, category, category, CategoryIcon.TRAVEL));
        assertFalse(CategoryManager.saveCategory(context, true, null, category, CategoryIcon.HEALTH));
        assertEquals(CategoryIcon.TRAVEL, LedgerCategory.icon(context, category, 0));
        assertEquals(CategoryIcon.GAME, LedgerCategory.icon(context, category, 1));

        File backup = File.createTempFile("category-icons-", ".csv", context.getCacheDir());
        CategoryTestContext restored = new CategoryTestContext(context.getBaseContext());
        try {
            Uri uri = Uri.fromFile(backup);
            BackupManager.exportToExcel(context, uri, Collections.emptyList(), Collections.emptyList());
            BackupData imported = BackupManager.importFromExcel(restored, uri);
            assertTrue(imported.expenseCategories.contains(category));
            assertTrue(imported.incomeCategories.contains(category));
            assertEquals(CategoryIcon.TRAVEL, CategoryManager.getIcon(restored, true, category));
            assertEquals(CategoryIcon.GAME, CategoryManager.getIcon(restored, false, category));
        } finally {
            restored.clear();
            backup.delete();
        }
    }

    @Test public void renamingExpenseLeavesSameNamedIncomingAndSpecialTransactionsAlone() {
        AppDatabase database = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
        try {
            List<Transaction> rows = Arrays.asList(new Transaction(1, 0, "同名", 10),
                    new Transaction(2, 1, "同名", 10), new Transaction(3, 2, "同名", 10));
            database.transactionDao().insertAll(rows);
            database.transactionDao().updateCategoryName("同名", "新名称", 0);
            for (Transaction row : database.transactionDao().getAllTransactionsSync()) {
                assertEquals(row.type == 0 ? "新名称" : "同名", row.category);
            }
        } finally {
            database.close();
        }
    }
}
