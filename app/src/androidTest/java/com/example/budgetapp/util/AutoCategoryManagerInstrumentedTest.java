package com.example.budgetapp.util;

import android.content.Context;
import com.example.budgetapp.database.Transaction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import static com.example.budgetapp.util.AutoCategoryClassifier.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AutoCategoryManagerInstrumentedTest {
    private CategoryTestContext context;
    private final Input input = new Input("com.tencent.mm", "11-15 12:34 瑞幸", 0, "购物");

    @Before public void setup() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context = new CategoryTestContext(target);
        CategoryManager.saveExpenseCategories(context, Arrays.asList("餐饮", "购物", "自定义"));
        CategoryManager.saveIncomeCategories(context, Arrays.asList("工资", "自定义"));
        CategoryManager.saveSubCategories(context, "餐饮", Arrays.asList("咖啡", "奶茶"));
        CategoryManager.saveSubCategories(context, "购物", Arrays.asList("超市"));
        CategoryManager.setSubCategoryEnabled(context, true);
    }
    @After public void cleanup() { context.clear(); }

    @Test public void defaultAiIsOffAndLocalSubCategoryWorks() {
        assertFalse(AutoCategoryManager.isAiEnabled(context));
        Result result = AutoCategoryManager.classifyLocal(context, input);
        assertEquals("餐饮", result.category);
        assertEquals("咖啡", result.subCategory);
    }
    @Test public void correctedConfirmationIsPersistedAndReused() {
        Result original = AutoCategoryManager.classifyLocal(context, input);
        remember(input, original, 0, "购物", "超市");
        Result next = AutoCategoryManager.classifyLocal(context,
                new Input("com.tencent.mm", "11-16 08:00 瑞幸", 0, "购物"));
        assertEquals(Source.LEARNED, next.source);
        assertEquals("购物", next.category);
        assertEquals("超市", next.subCategory);
    }
    @Test public void acceptingUnchangedLocalSuggestionIsPersistedAndReused() {
        Result original = AutoCategoryManager.classifyLocal(context, input);
        remember(input, original, 0, original.category, original.subCategory);
        assertEquals(1, AutoCategoryManager.getLearned(context).size());
        assertEquals(Source.LEARNED, AutoCategoryManager.classifyLocal(context, input).source);
    }
    @Test public void acceptedAiIsLearnedButAnEditStoresOnlyTheUserChoice() {
        Result model = new Result("餐饮", "咖啡", Source.AI);
        AutoCategoryManager.setLearningEnabled(context, false);
        remember(input, model, 0, "餐饮", "咖啡");
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
        AutoCategoryManager.setLearningEnabled(context, true);
        remember(input, model, 0, "餐饮", "咖啡");
        assertEquals(Source.LEARNED, AutoCategoryManager.classifyLocal(context, input).source);
        assertEquals("咖啡", AutoCategoryManager.classifyLocal(context, input).subCategory);
        remember(input, model, 0, "购物", "超市");
        assertEquals(1, AutoCategoryManager.getLearned(context).size());
        assertEquals("购物", AutoCategoryManager.getLearned(context).get(0).category);
    }

    @Test public void incomePresetsAppendOnceWithoutLosingCustomCategories() {
        context.getSharedPreferences("category_prefs", Context.MODE_PRIVATE).edit()
                .putString("key_income_categories", "自订收入,收款,自定义").remove("income_catalog_386").commit();
        assertEquals(Arrays.asList("自订收入", "收款", "薪资", "理财", "收红包", "收转账", "借入", "其他", "自定义"),
                CategoryManager.getIncomeCategories(context));
        assertEquals(CategoryIcon.RECEIPT, CategoryManager.getIcon(context, false, "收款"));
        assertEquals(CategoryIcon.BORROW, CategoryManager.getIcon(context, false, "借入"));
        assertEquals(CategoryIcon.ENVELOPE, CategoryManager.getIcon(context, false, "收红包"));
        CategoryManager.saveIncomeCategories(context, Arrays.asList("自订收入", "收款", "自定义"));
        assertEquals(Arrays.asList("自订收入", "收款", "自定义"), CategoryManager.getIncomeCategories(context));
    }

    @Test public void changedTypeAndUnregisteredCustomCategoryAreNotLearned() {
        Result original = AutoCategoryManager.classifyLocal(context, input);
        remember(input, original, 1, "工资", "");
        remember(input, original, 0, "临时自定义", "");
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
    }
    @Test public void learningCanBeDisabledAndClearedIndependently() {
        Result original = AutoCategoryManager.classifyLocal(context, input);
        remember(input, original, 0, "购物", "超市");
        AutoCategoryManager.setLearningEnabled(context, false);
        assertNotEquals(Source.LEARNED, AutoCategoryManager.classifyLocal(context, input).source);
        remember(new Input("com.tencent.mm", "蜜雪", 0, "购物"),
                original, 0, "购物", "超市");
        assertEquals(1, AutoCategoryManager.getLearned(context).size());
        AutoCategoryManager.clearLearned(context);
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
        assertFalse(AutoCategoryManager.isLearningEnabled(context));
    }
    @Test public void changingClassificationReplacesSameMerchantLearning() {
        Result original = AutoCategoryManager.classifyLocal(context, input);
        remember(input, original, 0, "购物", "超市");
        remember(input,
                AutoCategoryManager.classifyLocal(context, input), 0, "餐饮", "奶茶");
        assertEquals(1, AutoCategoryManager.getLearned(context).size());
        assertEquals("奶茶", AutoCategoryManager.classifyLocal(context, input).subCategory);
    }
    @Test public void removedSubCategoryInvalidatesLearning() {
        remember(input,
                AutoCategoryManager.classifyLocal(context, input), 0, "购物", "超市");
        CategoryManager.saveSubCategories(context, "购物", Arrays.asList("百货"));
        assertNotEquals(Source.LEARNED, AutoCategoryManager.classifyLocal(context, input).source);
    }
    @Test public void sharedKeywordRuleOrderCanBeConfigured() {
        AiCategoryRuleManager.addRule(context, new CategoryRule("瑞", "购物", "超市", 0));
        AiCategoryRuleManager.addRule(context, new CategoryRule("瑞幸", "餐饮", "咖啡", 0));
        assertEquals("购物", AutoCategoryManager.classifyLocal(context, input).category);
        AiCategoryRuleManager.moveRule(context, 1, 0);
        assertEquals("餐饮", AutoCategoryManager.classifyLocal(context, input).category);
        assertEquals("瑞幸", AiCategoryRuleManager.getRules(context).get(0).getKeyword());
        assertTrue(context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).contains("category_keyword_rules"));
    }
    @Test public void legacyAiRulesMigrateWithoutLosingOrder() throws Exception {
        JSONArray rules = new JSONArray();
        rules.put(new CategoryRule("瑞幸", "购物", "超市", 0).toJson());
        context.getSharedPreferences("ai_category_rule_prefs", Context.MODE_PRIVATE).edit()
                .putString("rules", rules.toString()).commit();
        assertEquals(Source.RULE, AutoCategoryManager.classifyLocal(context, input).source);
        assertEquals("购物", AutoCategoryManager.classifyLocal(context, input).category);
        assertTrue(context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).contains("category_keyword_rules"));
    }
    @Test public void learnedRecordsAreBoundedAndKeepNewest() throws Exception {
        JSONArray records = new JSONArray();
        for (int i = 0; i < 2000; i++) records.put(new JSONObject().put("package", "com.tencent.mm")
                .put("identity", "旧商户" + i).put("type", 0).put("category", "购物").put("subCategory", "超市"));
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                .putString("screen_category_learned_rules", records.toString()).commit();
        remember(input,
                AutoCategoryManager.classifyLocal(context, input), 0, "购物", "超市");
        assertEquals(2000, AutoCategoryManager.getLearned(context).size());
        assertEquals("旧商户1998", AutoCategoryManager.getLearned(context).get(1999).identity);
        assertEquals(learningIdentity(input.description), AutoCategoryManager.getLearned(context).get(0).identity);
    }
    @Test public void aiTimeoutKeepsTheOldDefaultAndBoundsRestoredValues() {
        assertEquals(8, AutoCategoryManager.getAiTimeoutSeconds(context));
        AutoCategoryManager.setAiTimeoutSeconds(context, 1);
        assertEquals(1, AutoCategoryManager.getAiTimeoutSeconds(context));
        AutoCategoryManager.setAiTimeoutSeconds(context, 10);
        assertEquals(10, AutoCategoryManager.getAiTimeoutSeconds(context));
        String key = "screen_category_ai_timeout_seconds";
        android.content.SharedPreferences prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        assertEquals(10, prefs.getInt(key, -1));
        prefs.edit().putInt(key, -5).commit();
        assertEquals(1, AutoCategoryManager.getAiTimeoutSeconds(context));
        prefs.edit().putInt(key, 500).commit();
        assertEquals(10, AutoCategoryManager.getAiTimeoutSeconds(context));
        prefs.edit().putString(key, "invalid").commit();
        assertEquals(8, AutoCategoryManager.getAiTimeoutSeconds(context));
    }
    @Test public void strategyOrderPersistsInBackedUpPreferencesAndChangesMatching() {
        assertEquals(AutoCategoryPriority.defaults().serialize(), AutoCategoryManager.getPriority(context).serialize());
        AutoCategoryManager.setPriority(context, AutoCategoryPriority.parse("HINT,LOCAL,AI,RULE,LEARNED"));
        assertEquals(Source.HINT, AutoCategoryManager.classifyLocal(context, input).source);
        assertEquals("购物", AutoCategoryManager.classifyLocal(context, input).category);
        assertTrue(context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).contains("screen_category_strategy_order"));
        assertEquals(Source.HINT, AutoCategoryManager.getPriority(context).getSources().get(2));
        AutoCategoryManager.setPriority(context, AutoCategoryPriority.defaults());
        assertEquals(Source.LOCAL, AutoCategoryManager.classifyLocal(context, input).source);
    }
    @Test public void restoredMalformedOrderIsSafelyNormalized() {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                .putString("screen_category_strategy_order", "FALLBACK,AI,AI,UNKNOWN,LOCAL").commit();
        assertEquals(Source.LEARNED, AutoCategoryManager.getPriority(context).getSources().get(0));
        assertEquals(Source.AI, AutoCategoryManager.getPriority(context).getSources().get(1));
        assertEquals(Source.FALLBACK, AutoCategoryManager.getPriority(context).getSources().get(5));
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                .putInt("screen_category_strategy_order", 123).commit();
        assertEquals(AutoCategoryPriority.defaults().serialize(), AutoCategoryManager.getPriority(context).serialize());
    }

    @Test public void acceptedRuleAndHintAreLearnedButUnchangedFallbackIsNot() {
        for (Source source : Arrays.asList(Source.RULE, Source.HINT, Source.LOCAL)) {
            AutoCategoryManager.clearLearned(context);
            remember(input, new Result("购物", "超市", source), 0, "购物", "超市");
            assertEquals(Source.LEARNED, AutoCategoryManager.classifyLocal(context, input).source);
        }
        AutoCategoryManager.clearLearned(context);
        remember(input, new Result("其他", "", Source.FALLBACK), 0, "其他", "");
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
    }

    @Test public void noMerchantOrMissingApplicationDoesNotTrainEvenAfterACorrection() {
        for (String text : Arrays.asList("支付宝支付", "订单金额", "20.30", "支付成功", "")) {
            remember(new Input("com.eg.android.AlipayGphone", text, 0, "购物"),
                    new Result("餐饮", "咖啡", Source.LOCAL), 0, "购物", "超市");
        }
        remember(new Input("", "瑞幸", 0, "购物"), new Result("餐饮", "咖啡", Source.AI), 0, "购物", "超市");
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
    }

    @Test public void confirmingAnOpenLearnedSuggestionRestoresAClearedRule() {
        Result local = AutoCategoryManager.classifyLocal(context, input);
        remember(input, local, 0, local.category, local.subCategory);
        Result learned = AutoCategoryManager.classifyLocal(context, input);
        assertEquals(Source.LEARNED, learned.source);
        AutoCategoryManager.clearLearned(context);
        remember(input, learned, 0, learned.category, learned.subCategory);
        assertEquals(1, AutoCategoryManager.getLearned(context).size());
    }

    private void remember(Input input, Result initial, int type, String category, String subCategory) {
        Transaction bill = new Transaction();
        bill.type = type;
        bill.category = category;
        bill.subCategory = subCategory;
        AutoCategoryManager.attachScreenSource(bill, input);
        AutoCategoryManager.rememberConfirmation(context, bill, initial);
    }
}
