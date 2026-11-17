package com.example.budgetapp.util;

import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.MotionEvent;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.budgetapp.R;
import com.example.budgetapp.ai.AiConfig;
import com.example.budgetapp.ai.TransactionDraft;
import com.example.budgetapp.ai.TransactionDraftMapper;
import com.example.budgetapp.database.AppDatabase;
import com.example.budgetapp.database.Transaction;
import com.example.budgetapp.service.ScreenAccountingEngine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.io.DataInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Exercises the real common overlay/save path, with isolated preferences and an in-memory DAO. */
@RunWith(AndroidJUnit4.class)
public class ScreenCategoryIntegrationTest {
    private CategoryTestContext context;
    private ScreenAccountingEngine service;
    private AppDatabase memoryDb;
    private static final long TIME = 1794729600000L;

    @Before public void setup() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("Grant SYSTEM_ALERT_WINDOW to run screen-overlay integration tests", Settings.canDrawOverlays(target));
        context = new CategoryTestContext(target);
        CategoryManager.saveExpenseCategories(context, Arrays.asList("餐饮", "购物", "自定义"));
        CategoryManager.saveSubCategories(context, "餐饮", Arrays.asList("咖啡", "奶茶饮品"));
        CategoryManager.saveSubCategories(context, "购物", Arrays.asList("超市"));
        CategoryManager.setSubCategoryEnabled(context, true);
        AssistantConfig config = new AssistantConfig(context);
        config.setEnabled(true);
        config.setAssetsEnabled(false);
        memoryDb = Room.inMemoryDatabaseBuilder(target, AppDatabase.class).build();
        runMain(() -> {
            try {
                service = new FakeAiService(context, memoryDb);
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @After public void cleanup() {
        if (service != null) runMain(service::close);
        if (memoryDb != null) memoryDb.close();
        if (context != null) context.clear();
    }

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 30)
    public void confirmationResizesAboveTheKeyboardWithTheRemarkAndActionsVisible() throws Exception {
        assumeTrue("This geometry check requires the fixed-height test IME",
                "true".equals(InstrumentationRegistry.getArguments().getString("tallyTestIme")));
        boolean visualEvidence = "true".equals(InstrumentationRegistry.getArguments().getString("tallyCaptureOverlay"));
        if (visualEvidence) {
            new AssistantConfig(context).setAssetsEnabled(true);
            CategoryManager.saveExpenseCategories(context, Arrays.asList("消费", "餐饮", "购物", "住房", "交通", "通讯",
                    "娱乐", "医疗", "教育", "红包", "旅行", "投资", "美容", "亲子", "人情社交", "宠物", "快递", "其他", "居家", "自定义"));
        }
        View root = show("11-15 12:34 测试付款");
        android.widget.EditText remark = root.findViewById(R.id.et_window_remark);
        android.view.inputmethod.InputMethodManager keyboard =
                remark.getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
        java.util.concurrent.atomic.AtomicInteger originalHeight = new java.util.concurrent.atomic.AtomicInteger();
        try {
            // Window focus is assigned asynchronously after WindowManager.addView.
            long until = android.os.SystemClock.elapsedRealtime() + 5000;
            while (!root.hasWindowFocus() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(25);
            if (visualEvidence) captureConfirmation("confirmation-portrait-before-ime.png");
            android.graphics.Rect tapTarget = new android.graphics.Rect();
            runMain(() -> {
                assertTrue(root.hasWindowFocus());
                assertFalse(remark.hasFocus());
                originalHeight.set(root.getHeight());
                int[] origin = new int[2];
                remark.getLocationOnScreen(origin);
                tapTarget.set(origin[0], origin[1], origin[0] + remark.getWidth(), origin[1] + remark.getHeight());
            });
            tapOnce(tapTarget.centerX(), tapTarget.centerY());
            java.util.concurrent.atomic.AtomicInteger resizedHeight = new java.util.concurrent.atomic.AtomicInteger();
            until = android.os.SystemClock.elapsedRealtime() + 5000;
            do {
                runMain(() -> resizedHeight.set(root.getHeight()));
                if (resizedHeight.get() < originalHeight.get()) break;
                Thread.sleep(25);
            } while (android.os.SystemClock.elapsedRealtime() < until);
            assertTrue("IME did not reserve space in the confirmation window",
                    resizedHeight.get() < originalHeight.get());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            runMain(() -> {
                assertTrue("The first tap must focus the remark", remark.hasFocus());
                assertFullyVisible(remark);
                assertFullyVisible(root.findViewById(R.id.btn_window_cancel));
                assertFullyVisible(root.findViewById(R.id.btn_window_save));
            });
            InstrumentationRegistry.getInstrumentation().sendStringSync("FirstTap");
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            runMain(() -> {
                assertTrue(remark.getText().toString(), remark.getText().toString().contains("FirstTap"));
                assertFullyVisible(remark);
            });
            if (visualEvidence) captureConfirmation("confirmation-portrait-ime.png");
            android.os.Bundle evidence = new android.os.Bundle();
            evidence.putString("stream", "\nConfirmation window height: " + originalHeight.get() + " -> " + resizedHeight.get()
                    + "; one tap focused the remark, typed text remained visible, Cancel and Save stayed visible\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence);
        } finally {
            runMain(() -> keyboard.hideSoftInputFromWindow(remark.getWindowToken(), 0));
        }
    }

    private static void tapOnce(float x, float y) {
        long downTime = SystemClock.uptimeMillis();
        android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try { assertTrue(automation.injectInputEvent(event, true)); }
            finally { event.recycle(); }
        }
    }

    private void captureConfirmation(String filename) throws Exception {
        android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        assertNotNull(screenshot);
        java.io.File directory = context.getExternalFilesDir("qa");
        assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(directory, filename))) {
            assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
        } finally { screenshot.recycle(); }
    }
    private static void assertFullyVisible(View view) {
        android.graphics.Rect visible = new android.graphics.Rect();
        assertTrue(view.getResources().getResourceEntryName(view.getId()), view.getGlobalVisibleRect(visible));
        assertEquals(view.getResources().getResourceEntryName(view.getId()), view.getHeight(), visible.height());
    }

    @Test public void inferredChildIsDisplayedAndSavedWithoutChangingTransaction() throws Exception {
        View root = show("11-15 12:34 瑞幸");
        assertTrue(((TextView) root.findViewById(R.id.tv_window_category_result)).getText().toString().contains("咖啡"));
        runMain(() -> root.findViewById(R.id.btn_window_save).performClick());
        Transaction saved = awaitSaved();
        assertEquals("餐饮", saved.category);
        assertEquals("咖啡", saved.subCategory);
        assertEquals(19.90, saved.amount, 0.0001);
        assertEquals(0, saved.type);
        assertEquals(TIME, saved.date);
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
    }

    @Test public void manualCorrectionLearnsOnlyAfterSaveAndIsReusedNextTime() throws Exception {
        View root = show("11-15 12:34 瑞幸");
        runMain(() -> {
            RecyclerView categories = root.findViewById(R.id.rv_window_category);
            RecyclerView.ViewHolder shopping = categories.findViewHolderForAdapterPosition(1);
            assertNotNull(shopping);
            shopping.itemView.performClick();
        });
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
        runMain(() -> root.findViewById(R.id.btn_window_save).performClick());
        assertEquals("购物", awaitSaved().category);
        long until = System.currentTimeMillis() + 4000;
        while (AutoCategoryManager.getLearned(context).isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20);
        assertEquals(1, AutoCategoryManager.getLearned(context).size());
        View next = show("11-16 08:00 瑞幸");
        String summary = ((TextView) next.findViewById(R.id.tv_window_category_result)).getText().toString();
        assertTrue(summary, summary.contains("购物") && summary.contains("已学习"));
        runMain(() -> next.findViewById(R.id.btn_window_cancel).performClick());
        assertEquals(1, memoryDb.transactionDao().getAllTransactionsSync().size());
    }

    @Test public void confirmationEditsAndStoresOnlyOneRemark() throws Exception {
        View root = show("11-15 12:34 瑞幸咖啡");
        runMain(() -> {
            android.widget.EditText remark = root.findViewById(R.id.et_window_remark);
            assertEquals("瑞幸咖啡", remark.getText().toString());
            remark.setText("买给同事");
            root.findViewById(R.id.btn_window_save).performClick();
        });
        Transaction saved = awaitSaved();
        assertNull(saved.note);
        assertEquals("买给同事", saved.remark);
        assertEquals(TIME, saved.date);
    }

    @Test public void cancellationDoesNotCreateLearningOrTransaction() throws Exception {
        View root = show("11-15 12:34 瑞幸");
        runMain(() -> root.findViewById(R.id.btn_window_cancel).performClick());
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
        assertTrue(memoryDb.transactionDao().getAllTransactionsSync().isEmpty());
    }

    @Test public void enabledAiWithoutModelFallsBackToLocalOverlay() throws Exception {
        AutoCategoryManager.setAiEnabled(context, true);
        View root = show("11-15 12:34 瑞幸");
        String summary = ((TextView) root.findViewById(R.id.tv_window_category_result)).getText().toString();
        assertTrue(summary.contains("本地识别"));
        assertTrue(summary.contains("咖啡"));
    }

    @Test public void corruptAiConfigurationStillFallsBackLocally() throws Exception {
        AutoCategoryManager.setAiEnabled(context, true);
        context.getSharedPreferences(AiConfig.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(AiConfig.KEY_ENABLED, true).putInt(AiConfig.KEY_TEXT_MODEL, 7).commit();
        View root = show("11-15 12:34 瑞幸");
        assertTrue(((TextView) root.findViewById(R.id.tv_window_category_result)).getText().toString().contains("咖啡"));
    }

    @Test public void failingAiEndpointFallsBackWithoutBlockingOrDuplicateWindows() throws Exception {
        AiConfig config = new AiConfig();
        config.enabled = true;
        config.baseUrl = "http://127.0.0.1:1/v1";
        config.apiKey = "instrumentation-not-a-real-key";
        config.textModel = "unreachable-test-model";
        config.save(context);
        AutoCategoryManager.setAiEnabled(context, true);
        long start = System.currentTimeMillis();
        View root = show("11-15 12:34 瑞幸");
        assertTrue(System.currentTimeMillis() - start < 4500);
        assertTrue(((TextView) root.findViewById(R.id.tv_window_category_result)).getText().toString().contains("本地识别"));
        runMain(() -> root.findViewById(R.id.btn_window_cancel).performClick());
        Thread.sleep(200);
        assertTrue(memoryDb.transactionDao().getAllTransactionsSync().isEmpty());
    }

    @Test public void labelledMerchantCanClassifyAPlatformPlaceholder() throws Exception {
        View root = show("京东购物", "瑞幸咖啡");
        String summary = ((TextView) root.findViewById(R.id.tv_window_category_result)).getText().toString();
        assertTrue(summary, summary.contains("餐饮") && summary.contains("咖啡") && summary.contains("识别商户"));
    }

    @Test public void platformPlaceholderWithoutMerchantCannotTrainWholeApp() throws Exception {
        View root = show("京东购物");
        runMain(() -> {
            RecyclerView categories = root.findViewById(R.id.rv_window_category);
            categories.findViewHolderForAdapterPosition(0).itemView.performClick();
            root.findViewById(R.id.btn_window_save).performClick();
        });
        assertEquals("餐饮", awaitSaved().category);
        Thread.sleep(100);
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
    }

    @Test public void destroyedServiceDoesNotOpenQueuedConfirmation() throws Exception {
        runMain(() -> {
            try {
                Method method = ScreenAccountingEngine.class.getDeclaredMethod("dispatchConfirmWindow",
                        ScreenNode.class, double.class, int.class,
                        String.class, String.class, int.class, String.class, long.class, String.class);
                method.setAccessible(true);
                method.invoke(service, null, 19.90, 0, "购物", "瑞幸", 0, "¥", TIME, "com.tencent.mm");
                service.close();
            } catch (Exception e) { throw new AssertionError(e); }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertNull(get("windowRootView"));
        assertTrue(memoryDb.transactionDao().getAllTransactionsSync().isEmpty());
    }

    private static class FakeAiService extends ScreenAccountingEngine {
        FakeAiService(Context context, AppDatabase db) { super(context, db); }
        AutoCategoryResolver.AiProvider provider;
        @Override protected AutoCategoryClassifier.Result requestScreenCategory(AiConfig config,
                AutoCategoryClassifier.Input input, List<AutoCategoryClassifier.Category> catalog, boolean useSub,
                int timeoutMs) throws Exception {
            return provider == null ? super.requestScreenCategory(config, input, catalog, useSub, timeoutMs) : provider.classify();
        }
    }

    private void enableFakeAi(AutoCategoryResolver.AiProvider provider) {
        AiConfig config = new AiConfig();
        config.enabled = true;
        config.baseUrl = "https://fixture.invalid/v1";
        config.apiKey = "fixture-not-a-real-key";
        config.textModel = "fixture-model";
        config.save(context);
        ((FakeAiService) service).provider = provider;
        AutoCategoryManager.setAiEnabled(context, true);
        // Simulate an older backup which ranked a local rule before AI.
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                .putString("screen_category_strategy_order", "RULE,LEARNED,LOCAL,HINT,AI,FALLBACK").commit();
        AiCategoryRuleManager.addRule(context, new CategoryRule("瑞幸", "购物", "超市", 0));
    }

    @Test public void customHintFirstOrderControlsTheActualPaymentOverlay() throws Exception {
        AutoCategoryManager.setPriority(context, AutoCategoryPriority.parse("HINT,LOCAL"));
        View root = show("瑞幸咖啡");
        String summary = ((TextView) root.findViewById(R.id.tv_window_category_result)).getText().toString();
        assertTrue(summary, summary.contains("购物") && summary.contains("页面提示"));
    }

    @Test public void aiWinsOverLegacyRuleOrderAndIsSaved() throws Exception {
        enableFakeAi(() -> new AutoCategoryClassifier.Result("餐饮", "咖啡", AutoCategoryClassifier.Source.AI));
        View root = show("瑞幸咖啡");
        String summary = ((TextView) root.findViewById(R.id.tv_window_category_result)).getText().toString();
        assertTrue(summary, summary.contains("咖啡") && summary.contains("AI"));
        runMain(() -> root.findViewById(R.id.btn_window_save).performClick());
        Transaction saved = awaitSaved();
        assertEquals("餐饮", saved.category);
        assertEquals("咖啡", saved.subCategory);
        assertEquals(19.90, saved.amount, 0.001);
        assertEquals(0, saved.type);
        assertEquals(TIME, saved.date);
    }

    @Test public void confirmingAiOnceSkipsTheModelForTheNextPayment() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        enableFakeAi(() -> {
            calls.incrementAndGet();
            return new AutoCategoryClassifier.Result("餐饮", "咖啡", AutoCategoryClassifier.Source.AI);
        });
        View first = show("11-15 12:34 瑞幸咖啡");
        String ai = ((TextView) first.findViewById(R.id.tv_window_category_result)).getText().toString();
        assertTrue(ai, ai.contains("AI") && ai.contains("咖啡"));
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
        runMain(() -> first.findViewById(R.id.btn_window_save).performClick());
        assertEquals("咖啡", awaitSaved().subCategory);
        long deadline = System.currentTimeMillis() + 4000;
        while (AutoCategoryManager.getLearned(context).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(1, AutoCategoryManager.getLearned(context).size());
        View next = show("11-16 08:00 瑞幸咖啡");
        String learned = ((TextView) next.findViewById(R.id.tv_window_category_result)).getText().toString();
        assertTrue(learned, learned.contains("已学习") && learned.contains("咖啡"));
        assertEquals(1, calls.get());
    }

    @Test public void editedLocalOrderWhileAiIsPendingCannotOverrideValidModel() throws Exception {
        enableFakeAi(() -> {
            AutoCategoryManager.setPriority(context, AutoCategoryPriority.parse("HINT,RULE,LEARNED,LOCAL"));
            return new AutoCategoryClassifier.Result("餐饮", "咖啡", AutoCategoryClassifier.Source.AI);
        });
        View root = show("瑞幸咖啡");
        String summary = ((TextView) root.findViewById(R.id.tv_window_category_result)).getText().toString();
        assertTrue(summary, summary.contains("餐饮") && summary.contains("AI"));
    }

    @Test public void optOutWhileAiIsPendingRetainsLocalRule() throws Exception {
        enableFakeAi(() -> {
            AutoCategoryManager.setAiEnabled(context, false);
            return new AutoCategoryClassifier.Result("餐饮", "咖啡", AutoCategoryClassifier.Source.AI);
        });
        View root = show("瑞幸咖啡");
        String summary = ((TextView) root.findViewById(R.id.tv_window_category_result)).getText().toString();
        assertTrue(summary, summary.contains("购物") && summary.contains("关键词规则"));
    }

    @Test public void aiAccountingUsesSameValidCategoryWhenAiClassificationIsEnabled() throws Exception {
        AiCategoryRuleManager.addRule(context, new CategoryRule("瑞幸", "购物", "超市", 0));
        JSONObject model = new JSONObject().put("type", 0).put("amount", 19.90).put("note", "瑞幸咖啡")
                .put("category", "餐饮").put("subCategory", "咖啡");
        AutoCategoryManager.setAiEnabled(context, true);
        TransactionDraft draft = TransactionDraftMapper.fromJson(context, model);
        assertEquals("餐饮", draft.category);
        assertEquals("咖啡", draft.subCategory);
        AutoCategoryManager.setAiEnabled(context, false);
        assertEquals("购物", TransactionDraftMapper.fromJson(context, model).category);
    }

    @Test public void lateWithinBudgetHttpResponseStillChoosesAiAndSendsOnlyCategoryInput() throws Exception {
        verifyHttpClassification(4, true);
    }

    @Test public void oneSecondBudgetKeepsTheLocalResultAfterALateHttpResponse() throws Exception {
        verifyHttpClassification(1, false);
    }

    private void verifyHttpClassification(int timeoutSeconds, boolean expectAi) throws Exception {
        byte[] body = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message",
                new JSONObject().put("content", new JSONObject().put("category", "餐饮")
                        .put("subCategory", "咖啡").toString())))).toString().getBytes(StandardCharsets.UTF_8);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            CompletableFuture<String> payload = new CompletableFuture<>();
            Thread backend = new Thread(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    ByteArrayOutputStream header = new ByteArrayOutputStream();
                    int marker = 0;
                    while (marker != 0x0d0a0d0a && header.size() < 16000) {
                        int next = input.readUnsignedByte();
                        header.write(next);
                        marker = (marker << 8) | next;
                    }
                    java.util.regex.Matcher length = java.util.regex.Pattern.compile("(?im)^Content-Length: (\\d+)")
                            .matcher(header.toString("US-ASCII"));
                    if (!length.find()) throw new java.io.IOException("Fixture request missing body length");
                    byte[] sent = new byte[Integer.parseInt(length.group(1))];
                    input.readFully(sent);
                    payload.complete(new String(sent, StandardCharsets.UTF_8));
                    SystemClock.sleep(2500); // After the short budget, before the long budget.
                    client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            + "Connection: close\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().write(body);
                } catch (Exception error) { payload.completeExceptionally(error); }
            }, "classification-fixture");
            backend.setDaemon(true);
            backend.start();
            AiConfig config = new AiConfig();
            config.enabled = true;
            config.baseUrl = "http://127.0.0.1:" + server.getLocalPort() + "/v1";
            config.apiKey = "local-synthetic-key";
            config.textModel = "synthetic-classifier";
            config.save(context);
            AutoCategoryManager.setAiTimeoutSeconds(context, timeoutSeconds);
            AutoCategoryManager.setAiEnabled(context, true);
            AiCategoryRuleManager.addRule(context, new CategoryRule("瑞幸", "购物", "超市", 0));
            View root = show("11-15 12:34 瑞幸咖啡");
            String summary = ((TextView) root.findViewById(R.id.tv_window_category_result)).getText().toString();
            String expected = expectAi ? "餐饮" : "购物";
            assertTrue(summary, summary.contains(expected) && summary.contains(expectAi ? "AI" : "关键词规则"));
            runMain(() -> root.findViewById(R.id.btn_window_save).performClick());
            assertEquals(expected, awaitSaved().category);
            backend.join(4000);
            assertFalse("fixture did not finish", backend.isAlive());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, memoryDb.transactionDao().getAllTransactionsSync().size());
            assertNull(get("windowRootView"));
            JSONObject sent = new JSONObject(new JSONObject(payload.get(2, TimeUnit.SECONDS))
                    .getJSONArray("messages").getJSONObject(1).getString("content"));
            assertEquals("瑞幸咖啡", sent.getString("description"));
            assertEquals(3, sent.length());
            assertFalse(sent.has("amount"));
            assertFalse(sent.has("time"));
        }
    }

    private View show(String note) throws Exception { return show(note, null); }

    private View show(String note, String merchant) throws Exception {
        runMain(() -> {
            try {
                if (merchant == null) {
                    Method method = ScreenAccountingEngine.class.getDeclaredMethod("showConfirmWindow", double.class,
                            int.class, String.class, String.class, int.class, String.class, long.class, String.class);
                    method.setAccessible(true);
                    method.invoke(service, 19.90, 0, "购物", note, 0, "¥", TIME, "com.tencent.mm");
                } else {
                    Method method = ScreenAccountingEngine.class.getDeclaredMethod("showConfirmWindow", double.class,
                            int.class, String.class, String.class, int.class, String.class, long.class, String.class, String.class);
                    method.setAccessible(true);
                    method.invoke(service, 19.90, 0, "购物", note, 0, "¥", TIME, "com.tencent.mm", merchant);
                }
            } catch (Exception e) { throw new AssertionError(e); }
        });
        long until = System.currentTimeMillis() + 5000;
        AtomicReference<View> root = new AtomicReference<>();
        while (System.currentTimeMillis() < until) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            runMain(() -> {
                try { root.set((View) get("windowRootView")); }
                catch (Exception e) { throw new AssertionError(e); }
            });
            if (root.get() != null && root.get().isAttachedToWindow()) return root.get();
            Thread.sleep(20);
        }
        fail("Classified confirmation overlay did not appear");
        return null;
    }
    private Transaction awaitSaved() throws Exception {
        long until = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < until) {
            List<Transaction> saved = memoryDb.transactionDao().getAllTransactionsSync();
            if (!saved.isEmpty()) return saved.get(0);
            Thread.sleep(20);
        }
        fail("Transaction was not persisted");
        return null;
    }
    private Object get(String name) throws Exception {
        Field field = ScreenAccountingEngine.class.getDeclaredField(name);
        field.setAccessible(true); return field.get(service);
    }
    private void runMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
}
