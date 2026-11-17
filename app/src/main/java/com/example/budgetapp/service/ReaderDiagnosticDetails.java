package com.example.budgetapp.service;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import com.example.budgetapp.util.AccessibilityScreenReader;
import com.example.budgetapp.util.DiagnosticValue;
import com.example.budgetapp.util.ScreenNodePolicy;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Extra queries run only for bounded, explicit diagnostic captures. */
final class ReaderDiagnosticDetails {
    private ReaderDiagnosticDetails() { }

    static void serviceInfo(ReaderDiagnosticSession run, UiAutomation automation, String phase) {
        try {
            AccessibilityServiceInfo info = automation.getServiceInfo();
            if (info == null) { run.trace.event(phase, "serviceInfo=<null>"); return; }
            run.trace.event(phase, "flags=0x" + Integer.toHexString(info.flags)
                    + "; capabilities=0x" + Integer.toHexString(info.getCapabilities())
                    + "; retrieveContent=" + ((info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0)
                    + "; interactiveWindows=" + ((info.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0)
                    + "; eventTypes=0x" + Integer.toHexString(info.eventTypes)
                    + "; feedbackType=0x" + Integer.toHexString(info.feedbackType)
                    + "; notificationTimeout=" + info.notificationTimeout
                    + "; accessibilityTool=" + (Build.VERSION.SDK_INT >= 31 ? info.isAccessibilityTool() : "not_supported")
                    + "; packages=" + DiagnosticValue.of(Arrays.toString(info.packageNames)));
        } catch (RuntimeException | LinkageError e) { run.trace.error(phase, e); }
    }

    static void compare(ReaderDiagnosticSession run, UiAutomation automation, BooleanSupplier permitted) {
        long started = SystemClock.elapsedRealtime();
        if (!inScope(run, permitted, "reader.compare.scope")) return;
        run.trace.event("reader.compare.start", "Optional metadata probe " + run.deepProbeCount()
                + "; no alternate authorization flags or input actions");
        serviceInfo(run, automation, "reader.compare.service_info");
        refreshComparison(run, automation, permitted);
        cacheComparison(run, automation, permitted);
        run.trace.event("reader.compare.end", "elapsedMs=" + (SystemClock.elapsedRealtime() - started));
    }

    private static void refreshComparison(ReaderDiagnosticSession run, UiAutomation automation, BooleanSupplier permitted) {
        AccessibilityNodeInfo root = null;
        String stage = "reader.compare.root_before_refresh";
        try {
            if (!inScope(run, permitted, stage)) return;
            root = automation.getRootInActiveWindow();
            describe(run, stage, root);
            if (root == null) return;
            int mask = AccessibilityScreenReader.rejectedBy(root, run.selected);
            if ((mask & (ScreenNodePolicy.PACKAGE_MISSING | ScreenNodePolicy.PACKAGE_MISMATCH
                    | ScreenNodePolicy.PASSWORD | ScreenNodePolicy.EDITABLE | ScreenNodePolicy.DATA_SENSITIVE)) != 0) {
                run.trace.event("reader.compare.skip", "Refresh skipped for out-of-scope or protected root; " + ScreenNodePolicy.reasons(mask));
                return;
            }
            stage = "reader.compare.refresh";
            if (!inScope(run, permitted, stage)) return;
            long before = SystemClock.elapsedRealtime();
            boolean refreshed = root.refresh();
            run.trace.event(stage, "refresh=" + refreshed + "; elapsedMs=" + (SystemClock.elapsedRealtime() - before));
            describe(run, "reader.compare.root_after_refresh", root);
        } catch (RuntimeException | LinkageError e) {
            run.trace.error(stage, e);
        } finally { if (root != null) root.recycle(); }
    }

    private static void cacheComparison(ReaderDiagnosticSession run, UiAutomation automation, BooleanSupplier permitted) {
        AccessibilityNodeInfo root = null;
        String stage = "reader.compare.clear_own_cache";
        try {
            if (Build.VERSION.SDK_INT < 33) {
                run.trace.event(stage, "Not supported by this Android version");
                return;
            }
            if (!inScope(run, permitted, stage)) return;
            run.trace.event(stage, "clearCache=" + automation.clearCache() + "; only this UiAutomation connection");
            stage = "reader.compare.root_after_cache_clear";
            if (!inScope(run, permitted, stage)) return;
            root = automation.getRootInActiveWindow();
            describe(run, stage, root);
            // Comparison roots are never submitted to accounting or copied into the accepted snapshot.
        } catch (RuntimeException | LinkageError e) {
            run.trace.error(stage, e);
        } finally { if (root != null) root.recycle(); }
    }

    private static boolean inScope(ReaderDiagnosticSession run, BooleanSupplier permitted, String stage) {
        try {
            if (permitted.getAsBoolean()) return true;
            run.trace.event(stage, "Skipped: capture deadline, screen state or foreground changed");
        } catch (RuntimeException | LinkageError e) { run.trace.error(stage + ".scope", e); }
        return false;
    }

    private static void describe(ReaderDiagnosticSession run, String stage, AccessibilityNodeInfo root) {
        run.trace.event(stage, AccessibilityScreenReader.describe(root)
                + (root == null ? "; result=SYSTEM_ROOT_NULL"
                : "; result=" + ScreenNodePolicy.reasons(AccessibilityScreenReader.rejectedBy(root, run.selected))));
    }
}
