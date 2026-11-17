package com.example.budgetapp.util;

import android.content.Context;
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
        AutoCategoryManager.rememberCorrection(context, input, original, 0, "购物", "超市");
        Result next = AutoCategoryManager.classifyLocal(context,
                new Input("com.tencent.mm", "11-16 08:00 瑞幸", 0, "购物"));
        assertEquals(Source.LEARNED, next.source);
        assertEquals("购物", next.category);
        assertEquals("超市", next.subCategory);
    }
    @Test public void acceptingUnchangedSuggestionDoesNotTrain() {
        Result original = AutoCategoryManager.classifyLocal(context, input);
        AutoCategoryManager.rememberCorrection(context, input, original, 0, original.category, original.subCategory);
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
    }
    @Test public void changedTypeAndUnregisteredCustomCategoryAreNotLearned() {
        Result original = AutoCategoryManager.classifyLocal(context, input);
        AutoCategoryManager.rememberCorrection(context, input, original, 1, "工资", "");
        AutoCategoryManager.rememberCorrection(context, input, original, 0, "临时自定义", "");
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
    }
    @Test public void learningCanBeDisabledAndClearedIndependently() {
        Result original = AutoCategoryManager.classifyLocal(context, input);
        AutoCategoryManager.rememberCorrection(context, input, original, 0, "购物", "超市");
        AutoCategoryManager.setLearningEnabled(context, false);
        assertNotEquals(Source.LEARNED, AutoCategoryManager.classifyLocal(context, input).source);
        AutoCategoryManager.rememberCorrection(context, new Input("com.tencent.mm", "蜜雪", 0, "购物"),
                original, 0, "购物", "超市");
        assertEquals(1, AutoCategoryManager.getLearned(context).size());
        AutoCategoryManager.clearLearned(context);
        assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
        assertFalse(AutoCategoryManager.isLearningEnabled(context));
    }
    @Test public void changingClassificationReplacesSameMerchantLearning() {
        Result original = AutoCategoryManager.classifyLocal(context, input);
        AutoCategoryManager.rememberCorrection(context, input, original, 0, "购物", "超市");
        AutoCategoryManager.rememberCorrection(context, input,
                AutoCategoryManager.classifyLocal(context, input), 0, "餐饮", "奶茶");
        assertEquals(1, AutoCategoryManager.getLearned(context).size());
        assertEquals("奶茶", AutoCategoryManager.classifyLocal(context, input).subCategory);
    }
    @Test public void removedSubCategoryInvalidatesLearning() {
        AutoCategoryManager.rememberCorrection(context, input,
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
        AutoCategoryManager.rememberCorrection(context, input,
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
        assertEquals(Source.HINT, AutoCategoryManager.getPriority(context).getSources().get(1));
        AutoCategoryManager.setPriority(context, AutoCategoryPriority.defaults());
        assertEquals(Source.LOCAL, AutoCategoryManager.classifyLocal(context, input).source);
    }
    @Test public void restoredMalformedOrderIsSafelyNormalized() {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                .putString("screen_category_strategy_order", "FALLBACK,AI,AI,UNKNOWN,LOCAL").commit();
        assertEquals(Source.AI, AutoCategoryManager.getPriority(context).getSources().get(0));
        assertEquals(Source.FALLBACK, AutoCategoryManager.getPriority(context).getSources().get(5));
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                .putInt("screen_category_strategy_order", 123).commit();
        assertEquals(AutoCategoryPriority.defaults().serialize(), AutoCategoryManager.getPriority(context).serialize());
    }

}
