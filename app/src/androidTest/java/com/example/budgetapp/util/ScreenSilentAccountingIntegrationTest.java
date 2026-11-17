package com.example.budgetapp.util;

import android.content.Context;
import android.os.SystemClock;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.budgetapp.database.AppDatabase;
import com.example.budgetapp.database.AssetAccount;
import com.example.budgetapp.database.Transaction;
import com.example.budgetapp.service.ScreenAccountingEngine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ScreenSilentAccountingIntegrationTest {
    private static final String PAYMENT_APP = "com.unionpay";
    private CategoryTestContext context;
    private ScreenCaptureSettings settings;
    private AssistantConfig config;
    private AppDatabase db;
    private ScreenAccountingEngine engine;

    @Before public void setup() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context = new CategoryTestContext(target);
        settings = new ScreenCaptureSettings(context);
        config = new AssistantConfig(context);
        config.setEnabled(true);
        db = Room.inMemoryDatabaseBuilder(target, AppDatabase.class).build();
        CategoryManager.saveExpenseCategories(context, Arrays.asList("餐饮", "购物"));
        CategoryManager.saveSubCategories(context, "餐饮", Collections.singletonList("咖啡"));
        CategoryManager.setSubCategoryEnabled(context, true);
        runMain(() -> engine = new TestEngine(context, db));
    }

    @After public void teardown() {
        if (engine != null) runMain(engine::close);
        if (db != null) db.close();
        if (context != null) context.clear();
    }

    @Test public void defaultsToOptOutAndCanBeChangedWithoutTogglingTheScreenSource() {
        assertFalse(settings.isSilentAccountingEnabled());
        settings.setSilentAccountingEnabled(true);
        assertTrue(new ScreenCaptureSettings(context).isSilentAccountingEnabled());
        assertFalse(settings.isEnabled());
        assertTrue(config.isEnabled());
    }

    @Test public void completedScreenSavesOnceWithCategoryAndDefaultAccountWithoutConfirmation() {
        settings.setSilentAccountingEnabled(true);
        AssetAccount asset = new AssetAccount("测试默认账户", 100, 0);
        asset.id = 51;
        db.assetAccountDao().insert(asset);
        config.setDefaultAssetId(asset.id);
        AssetAccount unrelatedDebt = new AssetAccount("瑞幸咖啡", 300, 1);
        unrelatedDebt.id = 52;
        db.assetAccountDao().insert(unrelatedDebt);
        ScreenNode receipt = receipt("支付成功", "瑞幸咖啡", "¥26.70");

        runMain(() -> {
            engine.requireConfirmation(); // Exercise the same opt-in path as the experimental source.
            engine.analyze(receipt);
        });
        Transaction saved = awaitSaved();
        assertEquals(0, saved.type);
        assertEquals("餐饮", saved.category);
        assertEquals("咖啡", saved.subCategory);
        assertEquals(26.70, saved.amount, 0.001);
        assertEquals(asset.id, saved.assetId);
        assertEquals("¥", saved.currencySymbol);
        assertNull(saved.note);
        assertEquals("瑞幸咖啡", saved.remark);
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
        assertFalse(engine.hasConfirmationWindow());
        long deadline = SystemClock.elapsedRealtime() + 4000;
        while (db.assetAccountDao().getAssetByIdSync(asset.id).amount == 100 && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20);
        }
        assertEquals(73.30, db.assetAccountDao().getAssetByIdSync(asset.id).amount, 0.001);
        assertEquals(300, db.assetAccountDao().getAssetByIdSync(unrelatedDebt.id).amount, 0.001);

        runMain(() -> engine.analyze(receipt));
        SystemClock.sleep(200);
        assertEquals(1, db.transactionDao().getAllTransactionsSync().size());
        assertFalse(engine.hasConfirmationWindow());
    }

    @Test public void disabledAssetsAreNotLinkedEvenWhenThereIsADefaultAccount() {
        settings.setSilentAccountingEnabled(true);
        config.setAssetsEnabled(false);
        AssetAccount account = new AssetAccount("不应变更", 100, 0);
        account.id = 29;
        db.assetAccountDao().insert(account);
        config.setDefaultAssetId(account.id);
        runMain(() -> engine.analyze(receipt("支付成功", "测试商户", "¥26.70")));
        assertEquals(0, awaitSaved().assetId);
        assertEquals(100.0, db.assetAccountDao().getAssetByIdSync(account.id).amount, 0.001);
        assertFalse(engine.hasConfirmationWindow());
    }

    @Test public void optInCannotBypassScreenCaptureGuard() {
        settings.setSilentAccountingEnabled(true);
        AtomicBoolean allowed = new AtomicBoolean(true);
        runMain(() -> {
            engine.setPresentationGuard(allowed::get);
            engine.analyze(receipt("支付成功", "测试商户", "¥26.70"));
            allowed.set(false); // Stop/invalidate after detection but before queued processing.
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue(db.transactionDao().getAllTransactionsSync().isEmpty());
        assertFalse(engine.hasConfirmationWindow());
    }

    private Transaction awaitSaved() {
        long until = SystemClock.elapsedRealtime() + 4000;
        while (SystemClock.elapsedRealtime() < until) {
            List<Transaction> rows = db.transactionDao().getAllTransactionsSync();
            if (!rows.isEmpty()) return rows.get(0);
            SystemClock.sleep(20);
        }
        throw new AssertionError("No transaction persisted");
    }

    private static ScreenNode receipt(String... fields) {
        List<ScreenNode> children = new ArrayList<>();
        for (String field : fields) {
            children.add(new ScreenNode(field, null, PAYMENT_APP, "android.widget.TextView", null, Collections.emptyList()));
        }
        return new ScreenNode(null, null, PAYMENT_APP, "android.widget.LinearLayout", null, children);
    }

    private static void runMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static final class TestEngine extends ScreenAccountingEngine {
        TestEngine(Context context, AppDatabase db) { super(context, db); }
    }
}
