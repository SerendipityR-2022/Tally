package com.example.budgetapp.viewmodel;

import android.app.Application;
import android.content.Context;
import android.os.SystemClock;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.budgetapp.database.AppDatabase;
import com.example.budgetapp.database.Transaction;
import com.example.budgetapp.service.ScreenAccountingEngine;
import com.example.budgetapp.util.AssistantConfig;
import com.example.budgetapp.util.AutoCategoryClassifier;
import com.example.budgetapp.util.AutoCategoryManager;
import com.example.budgetapp.util.CategoryManager;
import com.example.budgetapp.util.CategoryTestContext;
import com.example.budgetapp.util.ScreenCaptureSettings;
import com.example.budgetapp.util.ScreenNode;
import com.google.gson.Gson;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** The same update method is called by details, record and statistics editors. */
@RunWith(AndroidJUnit4.class)
public class CategoryEditLearningInstrumentedTest {
    private static final String PACKAGE = "com.unionpay";
    private CategoryTestContext context;
    private AppDatabase database;
    private FinanceViewModel viewModel;
    private ScreenAccountingEngine engine;

    @Before public void setup() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context = new CategoryTestContext(target);
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
        CategoryManager.saveExpenseCategories(context, Arrays.asList("餐饮", "购物"));
        CategoryManager.saveIncomeCategories(context, Arrays.asList("工资"));
        CategoryManager.saveSubCategories(context, "餐饮", Arrays.asList("咖啡", "奶茶"));
        CategoryManager.saveSubCategories(context, "购物", Arrays.asList("超市", "百货"));
        CategoryManager.setSubCategoryEnabled(context, true);
        viewModel = new FinanceViewModel(new TestApplication(context), database);
    }

    @After public void cleanup() {
        if (engine != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(engine::close);
        database.close();
        context.clear();
    }

    @Test public void editingASilentlySavedBillLearnsThenReplacesTheSameMerchant() {
        new AssistantConfig(context).setEnabled(true);
        new AssistantConfig(context).setAssetsEnabled(false);
        new ScreenCaptureSettings(context).setSilentAccountingEnabled(true);
        List<ScreenNode> fields = new ArrayList<>();
        for (String text : Arrays.asList("支付成功", "瑞幸咖啡", "¥26.70")) {
            fields.add(new ScreenNode(text, null, PACKAGE, "android.widget.TextView", null, Collections.emptyList()));
        }
        ScreenNode receipt = new ScreenNode(null, null, PACKAGE, "android.widget.LinearLayout", null, fields);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            engine = new TestEngine(context, database);
            engine.analyze(receipt);
        });
        await(() -> !database.transactionDao().getAllTransactionsSync().isEmpty());
        Transaction saved = database.transactionDao().getAllTransactionsSync().get(0);
        assertEquals(PACKAGE, saved.screenSourcePackage);
        assertEquals("瑞幸咖啡", saved.screenMerchantIdentity);
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());

        Transaction updated = copy(saved);
        updated.category = "购物";
        updated.subCategory = "超市";
        updated.remark = "为同事购买";
        viewModel.updateTransactionWithAssetSync(updated);
        await(() -> AutoCategoryManager.getLearned(context).size() == 1);
        assertLearned("购物", "超市");
        assertEquals(saved.screenMerchantIdentity, database.transactionDao().getByIdSync(saved.id).screenMerchantIdentity);

        updated = copy(database.transactionDao().getByIdSync(saved.id));
        updated.subCategory = "百货";
        viewModel.updateTransactionWithAssetSync(updated);
        await(() -> "百货".equals(AutoCategoryManager.getLearned(context).get(0).subCategory));
        assertEquals(1, AutoCategoryManager.getLearned(context).size());
        assertLearned("购物", "百货");
    }

    @Test public void remarkOnlyEditsPreserveTheStoredSourceAndDoNotTeach() {
        Transaction saved = screenBill();
        saved.id = 11;
        database.transactionDao().insert(saved);
        Transaction updated = copy(saved);
        updated.remark = "无关备注";
        updated.screenSourcePackage = "forged.package";
        updated.screenMerchantIdentity = "错误商户";
        viewModel.updateTransactionWithAssetSync(updated);
        await(() -> "无关备注".equals(database.transactionDao().getByIdSync(saved.id).remark));
        assertEquals(PACKAGE, database.transactionDao().getByIdSync(saved.id).screenSourcePackage);
        assertEquals("瑞幸咖啡", database.transactionDao().getByIdSync(saved.id).screenMerchantIdentity);
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
    }

    @Test public void manualAndLegacyBillsCannotLearnFromAnEditableRemark() {
        Transaction saved = new Transaction(1000, 0, "餐饮", 19.9, null, "瑞幸咖啡");
        saved.id = 12;
        database.transactionDao().insert(saved);
        Transaction updated = copy(saved);
        updated.category = "购物";
        updated.subCategory = "超市";
        updated.screenSourcePackage = PACKAGE;
        updated.screenMerchantIdentity = "瑞幸咖啡";
        viewModel.updateTransactionWithAssetSync(updated);
        await(() -> "购物".equals(database.transactionDao().getByIdSync(saved.id).category));
        assertNull(database.transactionDao().getByIdSync(saved.id).screenSourcePackage);
        assertNull(database.transactionDao().getByIdSync(saved.id).screenMerchantIdentity);
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
    }

    @Test public void disabledLearningAndMissingBillsDoNotCreateRules() {
        Transaction saved = screenBill();
        saved.id = 13;
        database.transactionDao().insert(saved);
        AutoCategoryManager.setLearningEnabled(context, false);
        Transaction updated = copy(saved);
        updated.category = "购物";
        updated.subCategory = "超市";
        viewModel.updateTransactionWithAssetSync(updated);
        await(() -> "购物".equals(database.transactionDao().getByIdSync(saved.id).category));
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());

        AutoCategoryManager.setLearningEnabled(context, true);
        updated = copy(saved);
        updated.id = 999;
        updated.category = "购物";
        updated.subCategory = "超市";
        viewModel.updateTransactionWithAssetSync(updated);
        // A later update of the same existing row gives the writer time to finish both operations.
        Transaction marker = copy(database.transactionDao().getByIdSync(saved.id));
        marker.remark = "完成";
        viewModel.updateTransactionWithAssetSync(marker);
        await(() -> "完成".equals(database.transactionDao().getByIdSync(saved.id).remark));
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
        assertNull(database.transactionDao().getByIdSync(999));
    }

    @Test public void changedDirectionClearsTheSourceAndDoesNotTeachAnotherTransactionType() {
        Transaction saved = screenBill();
        saved.id = 14;
        database.transactionDao().insert(saved);
        Transaction updated = copy(saved);
        updated.type = 1;
        updated.category = "工资";
        updated.subCategory = null;
        viewModel.updateTransactionWithAssetSync(updated);
        await(() -> database.transactionDao().getByIdSync(saved.id).type == 1);
        assertNull(database.transactionDao().getByIdSync(saved.id).screenSourcePackage);
        assertNull(database.transactionDao().getByIdSync(saved.id).screenMerchantIdentity);
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
    }

    private Transaction screenBill() {
        Transaction bill = new Transaction(1000, 0, "餐饮", 19.9, null, "可编辑备注");
        bill.subCategory = "咖啡";
        AutoCategoryManager.attachScreenSource(bill, new AutoCategoryClassifier.Input(PACKAGE, "瑞幸咖啡", 0, "购物"));
        return bill;
    }

    private void assertLearned(String category, String subCategory) {
        AutoCategoryClassifier.Result result = AutoCategoryManager.classifyLocal(context,
                new AutoCategoryClassifier.Input(PACKAGE, "瑞幸咖啡", 0, "购物"));
        assertEquals(AutoCategoryClassifier.Source.LEARNED, result.source);
        assertEquals(category, result.category);
        assertEquals(subCategory, result.subCategory);
    }

    private static Transaction copy(Transaction bill) { return new Gson().fromJson(new Gson().toJson(bill), Transaction.class); }

    private static void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertTrue("Database or learning update did not finish", condition.getAsBoolean());
    }

    private static final class TestApplication extends Application {
        TestApplication(Context context) { attachBaseContext(context); }
    }

    private static final class TestEngine extends ScreenAccountingEngine {
        TestEngine(Context context, AppDatabase database) { super(context, database); }
    }
}
