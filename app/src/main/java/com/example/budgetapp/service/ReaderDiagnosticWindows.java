package com.example.budgetapp.service;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Build;
import android.util.SparseArray;
import android.view.Display;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

/** Owns every returned window, including entries on other displays; logs structural fields only. */
final class ReaderDiagnosticWindows implements AutoCloseable {
    private final SparseArray<List<AccessibilityWindowInfo>> displays;

    ReaderDiagnosticWindows(UiAutomation automation) {
        if (Build.VERSION.SDK_INT >= 30) {
            SparseArray<List<AccessibilityWindowInfo>> result = automation.getWindowsOnAllDisplays();
            displays = result == null ? new SparseArray<>() : result;
        }
        else {
            displays = new SparseArray<>();
            displays.put(Display.DEFAULT_DISPLAY, automation.getWindows());
        }
    }

    List<AccessibilityWindowInfo> defaults() { return displays.get(Display.DEFAULT_DISPLAY); }

    void describe(ReaderDiagnosticSession run) {
        StringBuilder counts = new StringBuilder("display window counts:");
        for (int i = 0; i < displays.size(); ++i) {
            List<AccessibilityWindowInfo> windows = displays.valueAt(i);
            counts.append(' ').append(displays.keyAt(i)).append('=').append(windows == null ? "null" : windows.size());
        }
        run.trace.event("reader.windows", counts.toString());
        List<AccessibilityWindowInfo> windows = defaults();
        if (windows == null) {
            run.trace.event("reader.windows", "Default display has no window list");
            return;
        }
        for (int i = 0; i < Math.min(40, windows.size()); ++i) {
            AccessibilityWindowInfo window = windows.get(i);
            if (window == null) continue;
            try {
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                run.trace.event("reader.windows", "index=" + i + "; id=" + window.getId()
                        + "; display=" + (Build.VERSION.SDK_INT >= 30 ? window.getDisplayId() : Display.DEFAULT_DISPLAY)
                        + "; type=" + type(window.getType()) + "; layer=" + window.getLayer()
                        + "; active=" + window.isActive() + "; focused=" + window.isFocused()
                        + "; a11yFocused=" + window.isAccessibilityFocused() + "; children=" + window.getChildCount()
                        + "; pip=" + window.isInPictureInPictureMode() + "; bounds=" + bounds.toShortString()
                        + (Build.VERSION.SDK_INT >= 34 ? "; transitionUptimeMs=" + window.getTransitionTimeMillis() : ""));
            } catch (RuntimeException | LinkageError e) {
                run.trace.error("reader.windows.metadata", e);
            }
        }
        if (windows.size() > 40) run.trace.event("reader.windows", "Window metadata limited to first 40 entries");
    }

    private static String type(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "APPLICATION";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "INPUT_METHOD";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "ACCESSIBILITY_OVERLAY";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "SYSTEM";
            default: return String.valueOf(type);
        }
    }

    @Override public void close() {
        for (int i = 0; i < displays.size(); ++i) {
            List<AccessibilityWindowInfo> windows = displays.valueAt(i);
            if (windows != null) for (AccessibilityWindowInfo window : windows) if (window != null) window.recycle();
        }
    }
}
