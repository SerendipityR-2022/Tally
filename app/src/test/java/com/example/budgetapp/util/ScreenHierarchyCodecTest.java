package com.example.budgetapp.util;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenHierarchyCodecTest {
    private static final String PAY = "com.unionpay";

    @Test public void transportPreservesNodeOrderIdsAndEscapedChineseText() throws Exception {
        ScreenNode label = new ScreenNode("商户 \"可乐&奶茶\"", "<付款成功>\n¥26.70", PAY,
                "android.widget.TextView", PAY + ":id/merchant", Collections.emptyList());
        ScreenNode amount = new ScreenNode("¥26.70", null, PAY, "android.widget.TextView", null, Collections.emptyList());
        ScreenNode root = new ScreenNode(null, null, PAY, "android.widget.LinearLayout", null, Arrays.asList(label, amount));
        ScreenNode roundTrip = ScreenHierarchyCodec.decode(ScreenHierarchyCodec.encode(root), PAY);
        assertEquals(2, roundTrip.getChildCount());
        assertNull(roundTrip.getText());
        assertEquals(label.getText(), roundTrip.getChild(0).getText());
        assertEquals(label.getContentDescription(), roundTrip.getChild(0).getContentDescription());
        assertEquals(label.getViewIdResourceName(), roundTrip.getChild(0).getViewIdResourceName());
        assertEquals(amount.getText(), roundTrip.getChild(1).getText());
    }

    @Test public void rejectsOtherPackagesAndMalformedTrees() {
        rejects("<hierarchy><node package=\"com.android.settings\"/></hierarchy>");
        rejects("<hierarchy><node package=\"com.unionpay\"><node package=\"com.android.settings\"/></node></hierarchy>");
        rejects("<hierarchy><node package=\"com.unionpay\"/><node package=\"com.unionpay\"/></hierarchy>");
        rejects("<node package=\"com.unionpay\"/>");
        rejects("<hierarchy><hierarchy/><node package=\"com.unionpay\"/></hierarchy>");
        rejects("<hierarchy><node package=\"com.unionpay\"></hierarchy>");
    }

    @Test public void rejectsEntitiesAndOversizedInput() {
        rejects("<!DOCTYPE hierarchy [<!ENTITY secret SYSTEM \"file:///nope\">]><hierarchy><node package=\"com.unionpay\" text=\"&secret;\"/></hierarchy>");
        rejects(new String(new char[ScreenHierarchyCodec.MAX_CHARS + 1]));
        StringBuilder deep = new StringBuilder("<hierarchy><node package=\"com.unionpay\">");
        for (int i = 0; i <= ScreenHierarchyCodec.MAX_DEPTH; i++) deep.append("<node>");
        for (int i = 0; i <= ScreenHierarchyCodec.MAX_DEPTH; i++) deep.append("</node>");
        rejects(deep.append("</node></hierarchy>").toString());
    }

    private static void rejects(String xml) {
        assertThrows(Exception.class, () -> ScreenHierarchyCodec.decode(xml, PAY));
    }
    @Test public void diagnosticCodecDoesNotChangeProductionAllowlistOrTrustMixedPackages() throws Exception {
        String owner = "com.example.budgetapp", other = "com.example.receipt";
        String xml = "<hierarchy><node package=\"" + other + "\" text=\"demo\"/></hierarchy>";
        assertEquals(other, ScreenHierarchyCodec.decodeDiagnostic(xml, other, owner).getPackageName());
        try {
            ScreenHierarchyCodec.decode(xml, other);
            fail("production codec must reject unapproved packages");
        } catch (IllegalArgumentException expected) { }
        try {
            ScreenHierarchyCodec.decodeDiagnostic(xml, other, other);
            fail("diagnostic codec must reject the owner");
        } catch (IllegalArgumentException expected) { }
        try {
            ScreenHierarchyCodec.decodeDiagnostic(xml.replace("/></hierarchy>", "><node package=\"com.unionpay\"/></node></hierarchy>"), other, owner);
            fail("diagnostic codec must reject a mixed tree");
        } catch (Exception expected) { }
    }
}
