package com.example.budgetapp.util;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static com.example.budgetapp.util.AutoCategoryClassifier.*;
import static org.junit.Assert.*;

public class AutoCategoryPriorityTest {
    private final List<Category> catalog = Arrays.asList(new Category("餐饮", Arrays.asList("咖啡")),
            new Category("购物", Arrays.asList("超市")), new Category("其他", Collections.emptyList()));
    private final Input input = new Input("merchant.app", "瑞幸咖啡", 0, "购物");
    private final List<Rule> rules = Arrays.asList(new Rule("瑞幸", "购物", "超市", 0));
    private final List<Learned> learned = Arrays.asList(new Learned("merchant.app", learningIdentity(input.description), 0, "餐饮", "咖啡"));

    @Test public void repairsUnknownDuplicatesAndPrematureFallback() {
        AutoCategoryPriority order = AutoCategoryPriority.parse("AI,FALLBACK,AI,UNKNOWN,HINT");
        assertEquals(Arrays.asList(Source.AI, Source.HINT, Source.RULE, Source.LEARNED, Source.LOCAL, Source.FALLBACK), order.getSources());
        assertEquals(AutoCategoryPriority.defaults().serialize(), AutoCategoryPriority.parse(null).serialize());
        assertEquals(order.serialize(), AutoCategoryPriority.parse(order.serialize()).serialize());
    }

    @Test public void aiAndFallbackCannotBeMovedOrDisplaced() {
        AutoCategoryPriority order = AutoCategoryPriority.defaults();
        assertEquals(order.serialize(), order.move(0, 5).serialize());
        assertEquals(order.serialize(), order.move(5, 0).serialize());
        assertEquals(order.serialize(), order.move(-1, 1).serialize());
        assertEquals(order.serialize(), order.move(2, 0).serialize());
        assertEquals(Source.LEARNED, order.move(2, 1).getSources().get(1));
    }

    @Test public void strategyListCannotBeMutated() {
        assertThrows(UnsupportedOperationException.class, () -> AutoCategoryPriority.defaults().getSources().clear());
    }

    @Test public void allLocalOrdersChooseFirstMatchAndValidAiStillWins() {
        List<Source> sources = new ArrayList<>(AutoCategoryPriority.defaults().getSources().subList(1, 5));
        verifyPermutations(sources, 0);
    }

    private void verifyPermutations(List<Source> sources, int start) {
        if (start == sources.size()) {
            AutoCategoryPriority order = new AutoCategoryPriority(sources);
            Result local = classifyLocal(input, catalog, rules, learned, true, order);
            Source firstLocal = sources.get(0);
            assertEquals(order.serialize(), firstLocal, local.source);
            Result resolved = applyAi(catalog, local, "餐饮", "咖啡", true);
            assertEquals(order.serialize(), Source.AI, resolved.source);
            assertSame(local, applyAi(catalog, local, "不存在", "", true));
            return;
        }
        for (int i = start; i < sources.size(); i++) {
            Collections.swap(sources, start, i);
            verifyPermutations(sources, start + 1);
            Collections.swap(sources, start, i);
        }
    }

    @Test public void genericOtherHintDoesNotMaskLowerRankedSemanticMatch() {
        Result result = classifyLocal(new Input("app", "瑞幸咖啡", 0, "其他"), catalog,
                Collections.emptyList(), Collections.emptyList(), true, AutoCategoryPriority.parse("HINT,LOCAL"));
        assertEquals(Source.LOCAL, result.source);
        assertEquals("咖啡", result.subCategory);
    }

    @Test public void invalidHigherRankedDestinationsFallThrough() {
        Result result = classifyLocal(input, catalog, Arrays.asList(new Rule("瑞幸", "已删除", "", 0)),
                Arrays.asList(new Learned("merchant.app", learningIdentity(input.description), 0, "餐饮", "已删除")),
                true, AutoCategoryPriority.parse("LEARNED,RULE,LOCAL"));
        assertEquals(Source.LOCAL, result.source);
    }
}
