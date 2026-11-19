package com.example.budgetapp.service;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.pm.ApplicationInfo;
import android.util.DisplayMetrics;
import com.example.budgetapp.util.DiagnosticValue;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import com.example.budgetapp.BuildConfig;
import com.example.budgetapp.util.AccessibilityScreenReader;
import com.example.budgetapp.util.ScreenCaptureSettings;
import com.example.budgetapp.util.ScreenHierarchyCodec;
import com.example.budgetapp.util.ScreenNode;
import com.example.budgetapp.util.ScreenNodeCopyReport;
import java.time.Instant;

import static com.example.budgetapp.service.ScreenSampleCadence.HIERARCHY;

/** Shared state for a user-controlled diagnostic from either screen reader. */
public final class ScreenDebugCapture {
    static final DiagnosticTrace CONNECTION = new DiagnosticTrace(64 * 1024);
    private static long sequence;
    private static volatile Run latest;

    private ScreenDebugCapture() { }

    public enum Source { ACCESSIBILITY, EXPERIMENTAL, IMPORTED }

    public static final class Run {
        public final String selected;
        public final Source source;
        final long id, deadline;
        final DiagnosticTrace host = new DiagnosticTrace(256 * 1024);
        boolean armed, rejected, lost, locked, reportInFlight, finalReadRequested, finalRead;
        int seenFrame, finalReadFailures;
        private volatile boolean stopped;
        private boolean readerComplete;
        private int probes, focused, noFocus, unreadable, errors, transportErrors, readerFrames, expectedTraceChars;
        private final StringBuilder readerTrace = new StringBuilder();
        private ScreenNode snapshot;
        private String xml, nodeSummary;

        private Run(String selected, Source source) {
            this.selected = selected;
            this.source = source;
            id = ++sequence;
            deadline = SystemClock.elapsedRealtime() + ReaderDiagnosticSession.MAX_DURATION_MS;
        }

        public boolean isActive() { return !stopped && SystemClock.elapsedRealtime() < deadline; }
        public boolean isArming() { return isActive() && !armed; }
        public boolean isRejected() { return rejected; }
        public boolean isLost() { return lost; }
        public boolean isLocked() { return locked; }
        public boolean hasFinalReport() { return finalRead; }
        public int getProbed() { return probes; }
        public int getFocused() { return focused; }
        public int getNoFocus() { return noFocus; }
        public int getUnreadable() { return unreadable; }
        public int getErrors() { return errors + transportErrors; }
        public ScreenNode getSnapshot() { return snapshot; }
        public String getXml() { return xml; }
        public String getNodeSummary() { return nodeSummary; }
        public void note(String stage, String message) { host.event(stage, message); }
        public void error(String stage, Throwable error) { host.error(stage, error); }
        int traceLength() { return readerTrace.length(); }

        public void readAccessibilityFrame(AccessibilityNodeInfo root) {
            if (source != Source.ACCESSIBILITY || !isActive()) return;
            probes++;
            if (root == null) {
                noFocus++;
                return;
            }
            try {
                CharSequence pkg = root.getPackageName();
                if (pkg == null || !selected.contentEquals(pkg)) return;
                focused++;
                ScreenNodeCopyReport copyReport = new ScreenNodeCopyReport();
                ScreenNode node = AccessibilityScreenReader.copyScoped(root, selected, copyReport);
                nodeSummary = copyReport.summary();
                if (node == null) {
                    unreadable++;
                    return;
                }
                String newXml = ScreenHierarchyCodec.encode(node);
                if (!newXml.equals(xml) && isActive()) {
                    snapshot = node;
                    xml = newXml;
                    note("accessibility.frame", "Accepted frame " + (++readerFrames) + ", XML chars=" + xml.length());
                }
            } catch (RuntimeException | LinkageError error) {
                reportError("accessibility.read", error);
            }
        }

        public void finishAccessibility(boolean locked, String reason) {
            if (source != Source.ACCESSIBILITY || finalRead) return;
            stopped = finalRead = true;
            this.locked = locked;
            note("accessibility.stop", reason);
        }

        public void accessibilityUnavailable() {
            if (source == Source.ACCESSIBILITY) reportUnavailable("Accessibility service disconnected");
        }

        void report(Bundle result, StringBuilder delta, String newXml, ScreenNode node) {
            readerTrace.append(delta);
            if (result == null) return;
            probes = result.getInt(ReaderDiagnosticSession.PROBES);
            focused = result.getInt(ReaderDiagnosticSession.FOCUSED);
            noFocus = result.getInt(ReaderDiagnosticSession.NO_FOCUS);
            unreadable = result.getInt(ReaderDiagnosticSession.UNREADABLE);
            errors = result.getInt(ReaderDiagnosticSession.ERRORS);
            locked = result.getBoolean(ReaderDiagnosticSession.LOCKED);
            nodeSummary = result.getString(ReaderDiagnosticSession.NODE_SUMMARY);
            expectedTraceChars = result.getInt(ReaderDiagnosticSession.TRACE_LENGTH);
            readerFrames = result.getInt(ReaderDiagnosticSession.FRAME);
            if (!result.containsKey(HIERARCHY) || node != null) seenFrame = readerFrames;
            if (node != null) {
                snapshot = node;
                xml = newXml;
            }
            if (!result.getBoolean(ReaderDiagnosticSession.RUNNING)) {
                stopped = true;
                if (readerTrace.length() == expectedTraceChars) {
                    readerComplete = finalRead = true;
                    note("host.report", "Final reader report received");
                }
            }
        }

        void reportError(String stage, Throwable error) {
            transportErrors++;
            error(stage, error);
        }

        void reject(String reason, boolean mayRead) {
            rejected = stopped = true;
            finalRead = !mayRead;
            note("host.arm", reason);
        }

        void reportUnavailable(String reason) {
            if (finalRead) return;
            lost = !rejected;
            stopped = finalRead = true;
            note("host.report.unavailable", reason);
        }
    }

    public static Run latest() { return latest; }

    private static Run active(Source source) {
        Run r = latest;
        return r != null && r.source == source && r.isActive() ? r : null;
    }

    static Run activeExperimental() { return active(Source.EXPERIMENTAL); }
    public static boolean isAccessibilityCaptureActive() { return active(Source.ACCESSIBILITY) != null; }

    public static Run start(String pkg, Source source) {
        stop();
        latest = new Run(pkg, source);
        if (source == Source.ACCESSIBILITY) latest.armed = true;
        latest.note("host.start", "Capture requested for " + pkg);
        return latest;
    }

    public static void stop() {
        Run run = latest;
        if (run == null || run.finalRead) return;
        if (run.source == Source.ACCESSIBILITY) {
            run.finishAccessibility(false, "Stopped by the user");
            return;
        }
        if (!run.finalReadRequested) run.note("host.stop", "Stop/final report requested");
        run.stopped = true;
        ExperimentalAccountingService.fetchDiagnostic(run, true);
    }

    public static void clear() { stop(); latest = null; }

    public static void showImported(String pkg, String xml, ScreenNode node) {
        stop();
        Run run = new Run(pkg, Source.IMPORTED);
        run.stopped = run.finalRead = true;
        run.xml = xml;
        run.snapshot = node;
        run.note("host.import", "Loaded XML for " + pkg + ", XML chars=" + xml.length());
        latest = run;
    }

    static void describeTarget(Context context, Run run) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(run.selected, 0);
            ApplicationInfo app = info.applicationInfo;
            run.note("host.target", "package=" + run.selected + "; version=" + DiagnosticValue.of(info.versionName)
                    + "; code=" + (Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode)
                    + (app == null ? "; applicationInfo=<null>" : "; uid=" + app.uid + "; Android user=" + app.uid / 100000
                    + "; targetSdk=" + app.targetSdkVersion + "; minSdk=" + app.minSdkVersion + "; enabled=" + app.enabled
                    + "; flags=0x" + Integer.toHexString(app.flags) + "; process=" + DiagnosticValue.of(app.processName)));
        } catch (PackageManager.NameNotFoundException | RuntimeException | LinkageError e) { run.error("host.target", e); }
        try {
            DisplayMetrics display = context.getResources().getDisplayMetrics();
            Configuration config = context.getResources().getConfiguration();
            run.note("host.display", "widthPixels=" + display.widthPixels + "; heightPixels=" + display.heightPixels
                    + "; densityDpi=" + display.densityDpi + "; orientation=" + config.orientation
                    + "; widthDp=" + config.screenWidthDp + "; heightDp=" + config.screenHeightDp
                    + "; uiMode=0x" + Integer.toHexString(config.uiMode));
        } catch (RuntimeException | LinkageError e) { run.error("host.display", e); }
    }

    public static String createLog(Context context, Run run) {
        Source source = run != null ? run.source : new ScreenCaptureSettings(context).isEnabled()
                ? Source.EXPERIMENTAL : Source.ACCESSIBILITY;
        StringBuilder log = new StringBuilder();
        log.append("Tally screen accounting diagnostics\nTally ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")")
                .append("\nExported: ").append(Instant.now())
                .append("\nAndroid: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT)
                .append(", security patch ").append(Build.VERSION.SECURITY_PATCH).append(")")
                .append("\nDevice: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("\nAndroid build: ").append(Build.DISPLAY)
                .append("\nReader mode: ").append(source == Source.EXPERIMENTAL ? "Companion UiAutomation"
                        : source == Source.ACCESSIBILITY ? "Accessibility service" : "Imported XML")
                .append("\nHost PID: ").append(Process.myPid()).append('\n');
        if (source == Source.EXPERIMENTAL) {
            log.append("Service: ").append(context.getString(ExperimentalAccountingService.getStatus())).append('\n');
            try {
                PackageInfo manager = context.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
                log.append("Shizuku manager (moe.shizuku.privileged.api): ").append(manager.versionName).append('\n');
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                log.append("Shizuku manager package version unavailable\n");
            }
        }
        if (run == null) {
            log.append("Capture: no session in this app process\n");
        } else {
            log.append("\nSession: ").append(run.id).append("\nTarget: ").append(run.selected)
                    .append("\nProbes: ").append(run.probes).append("; target focused: ").append(run.focused)
                    .append("; unverified focus: ").append(run.noFocus).append("; unreadable tree: ").append(run.unreadable)
                    .append("; reader errors: ").append(run.errors).append("; host errors: ").append(run.transportErrors)
                    .append("; reader frames: ").append(run.readerFrames)
                    .append("; XML available: ").append(run.xml != null)
                    .append("\nLatest node diagnosis: ").append(run.nodeSummary == null ? "not available" : run.nodeSummary)
                    .append("\nArm rejected: ").append(run.rejected).append("; screen locked: ").append(run.locked)
                    .append("; reader lost: ").append(run.lost).append('\n');
            if (source == Source.EXPERIMENTAL) {
                log.append("Reader trace: ").append(run.readerComplete ? "final report received"
                                : run.finalRead && !run.armed ? "reader did not start; see host entries"
                                : "partial or unavailable; see host entries")
                        .append("; received ").append(run.readerTrace.length()).append(" chars (last reported: ")
                        .append(run.expectedTraceChars).append(")\n");
            }
        }
        if (source == Source.EXPERIMENTAL) log.append("\n--- Tally / Shizuku service lifecycle ---\n").append(CONNECTION.content());
        if (run != null) {
            log.append("\n--- Tally capture and rule replay ---\n").append(run.host.content());
            if (source == Source.EXPERIMENTAL) log.append("\n--- Shizuku reader ---\n").append(run.readerTrace);
        }
        return log.toString();
    }
}
