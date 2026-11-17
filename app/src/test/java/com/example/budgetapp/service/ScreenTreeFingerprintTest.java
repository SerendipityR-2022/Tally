package com.example.budgetapp.service;

import com.example.budgetapp.util.ScreenNode;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenTreeFingerprintTest {
    private final ScreenTreeFingerprint fingerprint = new ScreenTreeFingerprint();

    private static ScreenNode node(String text, String description, ScreenNode... children) {
        return new ScreenNode(text, description, "com.unionpay", "TextView", "receipt", Arrays.asList(children));
    }

    @Test public void detachedCopiesHaveTheSameFingerprintAcrossReads() {
        byte[] first = fingerprint.of(node("支付成功 <&>", "¥26.70", node("商户", null)));
        fingerprint.of(node("等待付款", null));
        assertArrayEquals(first, fingerprint.of(node("支付成功 <&>", "¥26.70", node("商户", null))));
    }

    @Test public void boundariesAndNullsCannotHideChangedReceiptFields() {
        assertFalse(Arrays.equals(fingerprint.of(node("12", "34")), fingerprint.of(node("123", "4"))));
        assertFalse(Arrays.equals(fingerprint.of(node(null, "")), fingerprint.of(node("", null))));
        assertFalse(Arrays.equals(fingerprint.of(node("支付成功", "26.70")), fingerprint.of(node("支付成功", "26.71"))));
    }

    @Test public void orderAndHierarchyArePartOfTheFingerprint() {
        ScreenNode a = node("a", null), b = node("b", null);
        assertFalse(Arrays.equals(fingerprint.of(node("root", null, a, b)), fingerprint.of(node("root", null, b, a))));
        assertFalse(Arrays.equals(fingerprint.of(node("root", null, a, b)),
                fingerprint.of(node("root", null, node("a", null, b)))));
    }

    @Test public void packageClassAndViewIdChangesAreNotDeduplicated() {
        ScreenNode original = node("金额", "26.70");
        for (ScreenNode changed : Arrays.asList(
                new ScreenNode("金额", "26.70", "other", "TextView", "receipt", Collections.emptyList()),
                new ScreenNode("金额", "26.70", "com.unionpay", "Button", "receipt", Collections.emptyList()),
                new ScreenNode("金额", "26.70", "com.unionpay", "TextView", "other", Collections.emptyList()))) {
            assertFalse(Arrays.equals(fingerprint.of(original), fingerprint.of(changed)));
        }
    }
}
