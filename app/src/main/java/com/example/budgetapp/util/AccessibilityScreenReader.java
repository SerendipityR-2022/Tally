package com.example.budgetapp.util;

import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Copies fields once and releases every child binder handle, including on failure. */
public final class AccessibilityScreenReader {
    private AccessibilityScreenReader() { }

    public static ScreenNode copy(AccessibilityNodeInfo root) {
        return copy(root, 0, new int[]{0}, null, null);
    }

    public static ScreenNode copyForShizuku(AccessibilityNodeInfo root, String expectedPackage) {
        return copyForShizuku(root, expectedPackage, null);
    }

    public static ScreenNode copyForShizuku(AccessibilityNodeInfo root, String expectedPackage, ScreenNodeCopyReport report) {
        return copyScoped(root, expectedPackage, report);
    }

    public static ScreenNode copyScoped(AccessibilityNodeInfo root, String expectedPackage, ScreenNodeCopyReport report) {
        if (report != null && root == null) report.missingRoot();
        return copy(root, 0, new int[]{0}, expectedPackage, report);
    }

    public static int rejectedBy(AccessibilityNodeInfo node, String expectedPackage) {
        return ScreenNodePolicy.rejectedBy(expectedPackage, value(node.getPackageName()), node.isVisibleToUser(),
                node.isEditable(), node.isPassword(), Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive());
    }

    private static ScreenNode copy(AccessibilityNodeInfo node, int depth, int[] count, String expectedPackage,
            ScreenNodeCopyReport report) {
        if (node == null) return null;
        if (depth > ScreenHierarchyCodec.MAX_DEPTH || ++count[0] > ScreenHierarchyCodec.MAX_NODES) {
            throw new IllegalArgumentException("Screen tree exceeds safe limits");
        }
        if (expectedPackage != null) {
            if (report == null) {
                // Keep the normal accounting path's short-circuit checks and cost unchanged.
                if (!expectedPackage.equals(value(node.getPackageName())) || !node.isVisibleToUser()
                        || node.isEditable() || node.isPassword()
                        || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive())) return null;
            } else {
                int reasons = rejectedBy(node, expectedPackage);
                if (report.inspect(depth, reasons)) report.example(depth, reasons, describe(node));
                if (reasons != 0) return null;
            }
        }
        List<ScreenNode> children = new ArrayList<>();
        // Password nodes are never copied or logged, even in diagnostic mode.
        if (!node.isPassword()) {
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) {
                    if (report != null) report.nullChild();
                    continue;
                }
                try {
                    ScreenNode copied = copy(child, depth + 1, count, expectedPackage, report);
                    if (copied != null) children.add(copied);
                }
                finally { child.recycle(); }
            }
        }
        ScreenNode result = new ScreenNode(node.isPassword() ? "" : value(node.getText()),
                node.isPassword() ? "" : value(node.getContentDescription()),
                value(node.getPackageName()), value(node.getClassName()), value(node.getViewIdResourceName()), children);
        if (report != null) report.copied();
        return result;
    }

    /** No text, description, extras, unique ID or node.toString(): those can contain private input. */
    public static String describe(AccessibilityNodeInfo node) {
        if (node == null) return "node=<null>";
        StringBuilder out = new StringBuilder();
        field(out, "package", node::getPackageName);
        field(out, "class", node::getClassName);
        field(out, "viewId", node::getViewIdResourceName);
        field(out, "windowId", node::getWindowId);
        field(out, "children", node::getChildCount);
        field(out, "visible", node::isVisibleToUser);
        field(out, "editable", node::isEditable);
        field(out, "password", node::isPassword);
        field(out, "dataSensitive", () -> Build.VERSION.SDK_INT >= 34 ? node.isAccessibilityDataSensitive() : "not_supported");
        field(out, "enabled", node::isEnabled);
        field(out, "important", node::isImportantForAccessibility);
        field(out, "focusable", node::isFocusable);
        field(out, "focused", node::isFocused);
        field(out, "a11yFocused", node::isAccessibilityFocused);
        field(out, "clickable", node::isClickable);
        field(out, "scrollable", node::isScrollable);
        field(out, "drawingOrder", node::getDrawingOrder);
        field(out, "bounds", () -> {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            return bounds.toShortString();
        });
        return out.toString();
    }

    private static void field(StringBuilder out, String key, Supplier<?> read) {
        if (out.length() > 0) out.append("; ");
        out.append(key).append('=');
        try {
            Object value = read.get();
            out.append(DiagnosticValue.of(value == null ? null : value.toString()));
        } catch (RuntimeException | LinkageError e) {
            out.append("unavailable(").append(e.getClass().getName()).append(": ")
                    .append(DiagnosticValue.of(e.getMessage())).append(')');
        }
    }

    private static String value(CharSequence text) {
        if (text == null) return null;
        if (text.length() > 2000) throw new IllegalArgumentException("Screen field exceeds safe limits");
        return text.toString();
    }
}
