package com.example.budgetapp.util;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ScreenCategoryTextExtractorTest {
    @Test public void extractsLabelledMerchantInsteadOfWholeGenericPaymentScreen() {
        assertEquals("瑞幸咖啡", ScreenCategoryTextExtractor.extract(Arrays.asList(
                "支付成功", "19.90", "商户名称", "瑞幸咖啡", "付款方式", "招商银行(1234)"), "11-15 12:34"));
    }
    @Test public void extractsInlineProductAndRemovesPrice() {
        assertEquals("猫粮", ScreenCategoryTextExtractor.extract(Arrays.asList("商品名称：猫粮 ¥19.90"), "京东购物"));
    }
    @Test public void preservesDedicatedAdapterMerchant() {
        assertEquals("蜜雪冰城", ScreenCategoryTextExtractor.extract(Arrays.asList("商户", "第三方支付平台"), "11-15 12:34 蜜雪冰城"));
    }
    @Test public void doesNotUsePaymentAccountAsMerchant() {
        assertEquals("", ScreenCategoryTextExtractor.extract(Arrays.asList("商户名称", "付款方式", "招商银行(1234)", "余额", "88.00"), "抖音购物"));
    }
    @Test public void doesNotForwardUnstructuredWholeScreen() {
        assertEquals("", ScreenCategoryTextExtractor.extract(Arrays.asList("支付成功 19.90 招商银行 1234 我的订单"), "美团消费"));
    }
    @Test public void platformPlaceholdersCannotBecomeMerchantLearningKeys() {
        for (String text : Arrays.asList("京东购物", "拼多多购物", "抖音购物", "美团消费", "抖省省消费")) {
            assertFalse(text, ScreenCategoryTextExtractor.hasMerchant(text));
        }
    }
    @Test public void preservesMerchantDigitsButRejectsLongAccountIdentifiers() {
        assertEquals("7-11便利店", ScreenCategoryTextExtractor.extract(Arrays.asList("商户名称", "7-11便利店"), ""));
        assertEquals("", ScreenCategoryTextExtractor.extract(Arrays.asList("商户名称", "6222123412341234"), ""));
    }
    @Test public void ignoresPriceAndAddressLabelsWhileLookingForMerchant() {
        assertEquals("瑞幸", ScreenCategoryTextExtractor.extract(Arrays.asList(
                "商品名称", "价格", "19.90", "收货地址", "私人地址", "商户名称", "瑞幸"), "京东购物"));
    }

}
