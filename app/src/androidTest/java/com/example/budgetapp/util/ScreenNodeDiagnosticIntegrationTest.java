package com.example.budgetapp.util;

import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ScreenNodeDiagnosticIntegrationTest {
    private static final String WECHAT = "com.tencent.mm";

    private static AccessibilityNodeInfo node() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(WECHAT);
        node.setClassName("android.widget.FrameLayout");
        node.setVisibleToUser(true);
        return node;
    }

    @Test public void acceptedRootCanBeDiagnosedWithoutLoggingItsText() {
        AccessibilityNodeInfo node = node();
        try {
            node.setText("private-screen-text");
            node.setContentDescription("private-description");
            ScreenNodeCopyReport report = new ScreenNodeCopyReport();
            assertNotNull(AccessibilityScreenReader.copyForShizuku(node, WECHAT, report));
            String log = AccessibilityScreenReader.describe(node) + report.summary();
            assertTrue(log.contains("package=com.tencent.mm"));
            assertTrue(log.contains("root=ACCEPTED"));
            assertFalse(log.contains("private-screen-text"));
            assertFalse(log.contains("private-description"));
        } finally { node.recycle(); }
    }

    @Test public void invisibleRootIsRejectedWithTheSameResultWithOrWithoutDiagnostics() {
        AccessibilityNodeInfo node = node();
        try {
            node.setVisibleToUser(false);
            ScreenNodeCopyReport report = new ScreenNodeCopyReport();
            assertNull(AccessibilityScreenReader.copyForShizuku(node, WECHAT));
            assertNull(AccessibilityScreenReader.copyForShizuku(node, WECHAT, report));
            assertEquals("root=NOT_VISIBLE", report.rootResult());
        } finally { node.recycle(); }
    }

    @Test public void sensitiveRootIsNeverCopiedByDiagnosticMode() {
        if (Build.VERSION.SDK_INT < 34) return;
        AccessibilityNodeInfo node = node();
        try {
            node.setAccessibilityDataSensitive(true);
            node.setText("private-screen-text");
            ScreenNodeCopyReport report = new ScreenNodeCopyReport();
            assertNull(AccessibilityScreenReader.copyForShizuku(node, WECHAT, report));
            assertEquals("root=DATA_SENSITIVE", report.rootResult());
            assertFalse(report.summary().contains("private-screen-text"));
        } finally { node.recycle(); }
    }
}
