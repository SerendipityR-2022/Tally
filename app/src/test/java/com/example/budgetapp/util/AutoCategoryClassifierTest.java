package com.example.budgetapp.util;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static com.example.budgetapp.util.AutoCategoryClassifier.*;
import static org.junit.Assert.*;

public class AutoCategoryClassifierTest {
    private Category cat(String name, String... children) { return new Category(name, Arrays.asList(children)); }
    private final List<Category> catalog = Arrays.asList(cat("餐饮", "咖啡", "奶茶饮品", "午餐"),
            cat("购物", "便利店", "超市", "电商"), cat("交通", "打车", "地铁"), cat("通讯"), cat("其他"));
    private Input input(String note) { return new Input("com.tencent.mm", note, 0, "购物"); }
    private Result local(Input input, List<Category> categories) {
        return classifyLocal(input, categories, Collections.emptyList(), Collections.emptyList(), true);
    }
    private Learned learned(String note, String category, String sub) {
        return new Learned("com.tencent.mm", learningIdentity(note), 0, category, sub);
    }

    @Test public void recognizesSubCategoryFromMerchantBrand() {
        Result result = local(input("11-15 12:34 蜜雪冰城大学城店"), catalog);
        assertEquals("餐饮", result.category);
        assertEquals("奶茶饮品", result.subCategory);
    }
    @Test public void recognizesCoffeeSubCategory() {
        Result result = local(input("瑞幸咖啡"), catalog);
        assertEquals("餐饮", result.category);
        assertEquals("咖啡", result.subCategory);
    }
    @Test public void understandsRenamedPrimaryUsingLocalSemantics() {
        Result result = local(input("美团外卖"), Arrays.asList(cat("吃喝开销", "午餐"), cat("其他")));
        assertEquals("吃喝开销", result.category);
    }
    @Test public void recognizesNewPrimaryWithoutHardCodedDestination() {
        Result result = local(input("给宠物买猫粮"), Arrays.asList(cat("毛孩子", "猫粮"), cat("其他")));
        assertEquals("毛孩子", result.category);
        assertEquals("猫粮", result.subCategory);
    }
    @Test public void specificNewPrimaryBeatsBroadDining() {
        Result result = local(input("瑞幸"), Arrays.asList(cat("餐饮"), cat("咖啡基金"), cat("其他")));
        assertEquals("咖啡基金", result.category);
    }
    @Test public void exactNewCategoryNameIsImmediatelyRecognized() {
        Result result = local(input("参加周末陶艺课"), Arrays.asList(cat("陶艺", "材料"), cat("其他")));
        assertEquals("陶艺", result.category);
    }
    @Test public void confirmedLearningBeatsAnExplicitRuleForTheSameMerchant() {
        Result result = classifyLocal(input("瑞幸"), catalog,
                Arrays.asList(new Rule("瑞幸", "购物", "超市", 0)),
                Arrays.asList(learned("瑞幸", "餐饮", "咖啡")), true);
        assertEquals(Source.LEARNED, result.source);
        assertEquals("餐饮", result.category);
    }
    @Test public void firstMatchingRuleWinsAndMovingRulesChangesPriority() {
        Rule broad = new Rule("咖啡", "购物", "", 0);
        Rule specific = new Rule("瑞幸咖啡", "餐饮", "咖啡", 0);
        Input in = input("瑞幸咖啡");
        assertEquals("购物", classifyLocal(in, catalog, Arrays.asList(broad, specific), Collections.emptyList(), true).category);
        assertEquals("餐饮", classifyLocal(in, catalog, Arrays.asList(specific, broad), Collections.emptyList(), true).category);
    }
    @Test public void expenseScreenDoesNotApplyIncomeRule() {
        Result result = classifyLocal(input("瑞幸"), catalog,
                Arrays.asList(new Rule("瑞幸", "购物", "", 1)), Collections.emptyList(), true);
        assertEquals("餐饮", result.category);
        assertNotEquals(Source.RULE, result.source);
    }
    @Test public void deletedRuleDestinationIsSkipped() {
        Result result = classifyLocal(input("瑞幸"), catalog,
                Arrays.asList(new Rule("瑞幸", "已删除", "", 0), new Rule("瑞幸", "购物", "", 0)),
                Collections.emptyList(), true);
        assertEquals("购物", result.category);
        assertEquals(Source.RULE, result.source);
    }
    @Test public void invalidRuleChildIsSkippedRatherThanInvented() {
        Result result = classifyLocal(input("瑞幸"), catalog,
                Arrays.asList(new Rule("瑞幸", "购物", "不存在的细分", 0)), Collections.emptyList(), true);
        assertEquals("餐饮", result.category);
    }
    @Test public void learningSurvivesDifferentTimestamps() {
        Result result = classifyLocal(input("11-16 09:30 咖啡店"), catalog, Collections.emptyList(),
                Arrays.asList(learned("11-15 12:34 咖啡店", "购物", "超市")), true);
        assertEquals(Source.LEARNED, result.source);
        assertEquals("购物", result.category);
        assertEquals("餐饮", applyAi(catalog, result, "餐饮", "咖啡", true).category);
    }
    @Test public void learningDoesNotLeakAcrossApps() {
        Result result = classifyLocal(new Input("com.eg.android.AlipayGphone", "瑞幸", 0, "购物"), catalog,
                Collections.emptyList(), Arrays.asList(learned("瑞幸", "购物", "")), true);
        assertNotEquals(Source.LEARNED, result.source);
    }
    @Test public void learningDoesNotLeakAcrossTypes() {
        Result result = classifyLocal(new Input("com.tencent.mm", "瑞幸", 1, "其他"), catalog,
                Collections.emptyList(), Arrays.asList(learned("瑞幸", "购物", "")), true);
        assertNotEquals(Source.LEARNED, result.source);
    }
    @Test public void renamedCategoryInvalidatesOldLearning() {
        Result result = classifyLocal(input("瑞幸"), Arrays.asList(cat("吃喝", "咖啡"), cat("其他")),
                Collections.emptyList(), Arrays.asList(learned("瑞幸", "餐饮", "咖啡")), true);
        assertNotEquals(Source.LEARNED, result.source);
        assertEquals("吃喝", result.category);
    }
    @Test public void deletedChildInvalidatesOldLearning() {
        Result result = classifyLocal(input("瑞幸"), catalog, Collections.emptyList(),
                Arrays.asList(learned("瑞幸", "购物", "已删除细分")), true);
        assertNotEquals(Source.LEARNED, result.source);
    }
    @Test public void subCategoryToggleIsRespectedByAllSources() {
        Result local = classifyLocal(input("瑞幸"), catalog, Collections.emptyList(), Collections.emptyList(), false);
        assertEquals("", local.subCategory);
        assertEquals("", applyAi(catalog, local, "餐饮", "咖啡", false).subCategory);
        Result learned = classifyLocal(input("瑞幸"), catalog, Collections.emptyList(),
                Arrays.asList(learned("瑞幸", "购物", "超市")), false);
        assertEquals(Source.LEARNED, learned.source);
        assertEquals("", learned.subCategory);
    }
    @Test public void genericOrTimeOnlyPagesAreNotLearnable() {
        assertEquals("", learningIdentity("11-15 12:34"));
        assertEquals("", learningIdentity("11-15 12:34 微信支付"));
        assertEquals("", learningIdentity("微信账单 (后台)"));
        assertEquals("", learningIdentity("12345678"));
    }
    @Test public void merchantNumbersAreNotMistakenForDates() {
        assertEquals("711便利店", learningIdentity("7-11便利店"));
        assertEquals("711便利店", learningIdentity("2026-11-15 09:30 7-11便利店"));
    }
    @Test public void faceMaskIsShoppingNotFood() {
        assertEquals("购物", local(input("保湿面膜"), catalog).category);
    }
    @Test public void phoneStoreIsNotClassifiedAsDining() {
        assertEquals("购物", local(input("小米手机店"), catalog).category);
    }
    @Test public void currentCommunicationsCategoryIsUsed() {
        assertEquals("通讯", local(input("中国移动话费"), catalog).category);
    }
    @Test public void validAiCanOverrideHeuristics() {
        Result result = applyAi(catalog, local(input("未知商户"), catalog), "餐饮", "午餐", true);
        assertEquals(Source.AI, result.source);
        assertEquals("午餐", result.subCategory);
    }
    @Test public void unknownAiCategoryFallsBack() {
        Result original = local(input("瑞幸"), catalog);
        assertSame(original, applyAi(catalog, original, "不存在", "", true));
    }
    @Test public void aiChildUnderWrongParentFallsBack() {
        Result original = local(input("瑞幸"), catalog);
        Result result = applyAi(catalog, original, "购物", "咖啡", true);
        assertEquals(Source.AI, result.source);
        assertEquals("购物", result.category);
        assertEquals("", result.subCategory);
    }
    @Test public void emptyAiResultFallsBack() {
        Result original = local(input("瑞幸"), catalog);
        assertSame(original, applyAi(catalog, original, "", "", true));
    }
    @Test public void unknownHintFallsBackToOther() {
        Result result = local(new Input("unknown", "陌生商户", 0, "已删除分类"), catalog);
        assertEquals("其他", result.category);
        assertEquals(Source.FALLBACK, result.source);
    }
    @Test public void pageHintUsesCurrentRenamedCategory() {
        Result result = local(new Input("com.sankuai.meituan", "", 0, "餐饮"),
                Arrays.asList(cat("吃喝"), cat("其他")));
        assertEquals("吃喝", result.category);
        assertEquals(Source.HINT, result.source);
    }
    @Test public void redPacketHintMapsToExistingGiftCategory() {
        Result result = local(new Input("com.tencent.mm", "11-15 12:34", 1, "红包"),
                Arrays.asList(cat("礼金", "红包"), cat("其他")));
        assertEquals("礼金", result.category);
        assertEquals("红包", result.subCategory);
        assertEquals(Source.HINT, result.source);
    }

}
