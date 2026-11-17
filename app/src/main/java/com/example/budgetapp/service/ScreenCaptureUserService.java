package com.example.budgetapp.service;

import android.app.ActivityManager;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.annotation.Keep;
import com.example.budgetapp.util.AccessibilityScreenReader;
import com.example.budgetapp.util.KeywordManager;
import com.example.budgetapp.util.ScreenCapturePolicy;
import com.example.budgetapp.util.ScreenHierarchyCodec;
import com.example.budgetapp.util.ScreenNode;
import com.example.budgetapp.util.ScreenNodeCopyReport;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.example.budgetapp.service.ScreenSampleCadence.*;

/** Non-daemon Shizuku service; only the owning app can request a verified, read-only snapshot. */
@Keep
public final class ScreenCaptureUserService extends IScreenCaptureService.Stub {
    private static final String TAG = "TallyScreenReader";
    private final int appUid;
    private final String ownerPackage;
    private String diagnosticPackage;
    private long diagnosticDeadline;
    private static final int DIAGNOSTIC_PROBE_MS = 750;
    private static final int DIAGNOSTIC_QUIET_TREE_MS = 1200;
    private volatile ReaderDiagnosticSession diagnostic;
    private HandlerThread diagnosticThread;
    private volatile Handler diagnosticHandler;
    private final Runnable diagnosticTick = this::runDiagnosticTick;
    private String[] rulePackages = new String[0];
    private final int userId;
    private final Context packageContext;
    private final ReaderScreenState screenState = new ReaderScreenState();
    private final WindowFocusProbe windowFocus = new WindowFocusProbe();
    private final ScreenTreeFingerprint fingerprint = new ScreenTreeFingerprint();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicLong focusRevision = new AtomicLong();
    private final AtomicBoolean wakeQueued = new AtomicBoolean();
    private final AtomicBoolean focusWakeQueued = new AtomicBoolean();
    private final TaskForegroundObserver taskObserver = new TaskForegroundObserver(() -> {
        focusRevision.incrementAndGet();
        revision.incrementAndGet();
        notifyChanged(focusWakeQueued);
    });
    private volatile IScreenCaptureListener listener;
    private IBinder.DeathRecipient clientDeath;
    private volatile String watchedPackage;
    private UiAutomation automation;
    private HandlerThread automationThread;
    private String lastHint;
    private ActivityManager activities;
    private boolean taskHintUnavailable;
    private ScreenCapturePolicy.FocusedWindow verifiedFocus;
    private long lastFocusAt = -1;
    private long lastTreeAt = -1;
    private long sampledRevision;
    private long checkedFocusRevision;
    private int quietSamples;
    private int unreadableSamples;
    private long windowEnteredAt;
    private int windowSamples;
    private byte[] lastDigest;

    /** Shizuku v13+ supplies the owning package context. No exported component binds the reader. */
    @Keep public ScreenCaptureUserService(Context context) {
        packageContext = context;
        ownerPackage = context.getPackageName();
        appUid = context.getApplicationInfo().uid;
        userId = appUid / 100000;
    }

    private void requireAppCaller() {
        if ((Process.myUid() != 0 && Process.myUid() != 2000) || Binder.getCallingUid() != appUid) {
            throw new SecurityException("Screen reader is restricted to the owning app");
        }
    }

    @Override public synchronized int initialize(IScreenCaptureListener listener) throws RemoteException {
        requireAppCaller();
        IBinder client = listener.asBinder();
        IBinder.DeathRecipient death = () -> onClientDied(client);
        client.linkToDeath(death, 0);
        if (this.listener != null) {
            detachClient();
            releaseClientState();
        }
        this.listener = listener;
        clientDeath = death;
        return Process.myUid();
    }

    private synchronized void onClientDied(IBinder client) {
        if (listener == null || listener.asBinder() != client) return;
        listener = null;
        clientDeath = null;
        releaseClientState();
    }

    private void detachClient() {
        if (listener != null) listener.asBinder().unlinkToDeath(clientDeath, 0);
        listener = null;
        clientDeath = null;
    }

    private void releaseClientState() {
        long identity = Binder.clearCallingIdentity();
        try {
            stopDiagnosticSampling("Host connection closed", false);
            diagnostic = null;
            taskObserver.stop();
            focusWakeQueued.set(false);
            reset();
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized Bundle sample(boolean allowHierarchy, String[] approved) {
        requireAppCaller();
        long identity = Binder.clearCallingIdentity();
        try {
            if (diagnosticHandler != null) {
                if (diagnostic.active(SystemClock.elapsedRealtime())) return result(IDLE, IDLE_WATCHDOG_MS, null, null);
                stopDiagnosticSampling("Capture deadline reached", false);
            }
            if (!screenAvailable()) return pause();
            if (!Arrays.equals(rulePackages, approved)) {
                rulePackages = checkedRulePackages(approved);
                reset();
            }
            // Task hints are acknowledged on every probe. Content events stay coalesced until an
            // actual tree read, so an animated page cannot turn the tree rate limit into IPC polling.
            focusWakeQueued.set(false);
            boolean taskEvents = taskObserver.start();
            long observedFocusRevision = focusRevision.get();
            String hint = foregroundHint();
            boolean hintChanged = !Objects.equals(lastHint, hint) || checkedFocusRevision != observedFocusRevision;
            lastHint = hint;
            long now = SystemClock.uptimeMillis();
            boolean target = verifiedFocus != null;
            boolean hintIsTarget = hint != null && !hint.isEmpty();
            int probe = probeDelay(taskEvents, hint != null, hintIsTarget, target);
            if (!allowHierarchy && target) probe = Math.min(probe, TARGET_PROBE_MS);
            long untilFocus = taskEvents && hint != null && !(hintIsTarget && !target)
                    ? (lastFocusAt < 0 || hintChanged ? 0 : Math.max(0, IDLE_WATCHDOG_MS - (now - lastFocusAt)))
                    : focusDelay(lastFocusAt, now, hint != null, hintChanged, hintIsTarget, target);
            long untilTree = allowHierarchy && target ? nextTree(now) : Long.MAX_VALUE;
            if (untilFocus > 0 && untilTree > 0) {
                return result(target ? TARGET : IDLE, nextDelay(probe, Math.min(untilFocus, untilTree)), null, null);
            }

            ScreenCapturePolicy.FocusedWindow focus = windowFocus.read(userId, hintIsTarget);
            checkedFocusRevision = observedFocusRevision;
            lastFocusAt = SystemClock.uptimeMillis();
            if (!screenAvailable()) return pause();
            if (focus == null || !isAllowed(focus.packageName)) {
                if (verifiedFocus != null || automation != null) releaseReader();
                verifiedFocus = null;
                return result(IDLE, probeDelay(taskEvents, hint != null, hintIsTarget, false), null, null);
            }
            rememberFocus(focus);
            now = SystemClock.uptimeMillis();
            untilTree = allowHierarchy ? nextTree(now) : Long.MAX_VALUE;
            // While confirmation/classification is busy, retain a bounded resume check.
            probe = allowHierarchy ? probeDelay(taskEvents, hint != null, hintIsTarget, true) : TARGET_PROBE_MS;
            if (untilTree > 0) return result(TARGET, nextDelay(probe, untilTree), null, null);
            return readHierarchy(focus, probe, null);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "Unable to initialize UiAutomation", e);
            reset();
            throw new IllegalStateException("System UiAutomation unavailable", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void rememberFocus(ScreenCapturePolicy.FocusedWindow focus) {
        if (!focus.matches(verifiedFocus)) {
            lastTreeAt = -1;
            lastDigest = null;
            quietSamples = unreadableSamples = 0;
            windowEnteredAt = SystemClock.uptimeMillis();
            windowSamples = 0;
        }
        verifiedFocus = focus;
    }

    @Override public synchronized boolean startDiagnostic(long id, String selected, long deadline) {
        requireAppCaller();
        long identity = Binder.clearCallingIdentity();
        try {
            if (listener == null || !ScreenCapturePolicy.canInspect(selected, ownerPackage)
                    || !ReaderDiagnosticSession.validTime(deadline, SystemClock.elapsedRealtime())) {
                throw new IllegalStateException("Invalid diagnostic session, target or deadline");
            }
            stopDiagnosticSampling("Replaced by another capture", false);
            taskObserver.stop();
            reset();
            ReaderDiagnosticSession run = new ReaderDiagnosticSession(id, selected, deadline);
            diagnostic = run;
            run.note("Reader PID=" + Process.myPid() + ", UID=" + Process.myUid() + ", Android user=" + userId
                    + ", target=" + selected + ", remaining ms=" + (deadline - SystemClock.elapsedRealtime()));
            try {
                run.at("reader.start.screen_state");
                if (!screenState.describe(run.trace)) {
                    stopDiagnosticSampling("Screen off or locked before start", true);
                    return false;
                }
                run.at("reader.start.worker");
                diagnosticPackage = selected;
                diagnosticDeadline = deadline;
                diagnosticThread = new HandlerThread("TallyDiagnosticCapture");
                diagnosticThread.start();
                diagnosticHandler = new Handler(diagnosticThread.getLooper());
                if (!diagnosticHandler.post(diagnosticTick)) throw new IllegalStateException("Diagnostic worker rejected first probe");
                run.note("Capture armed; focus transport: " + windowFocus.transport());
                Log.i(TAG, "Diagnostic capture armed");
                return true;
            } catch (RuntimeException | LinkageError e) {
                run.failure(e);
                stopDiagnosticSampling("Reader could not start", false);
                return false;
            }
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized Bundle readDiagnostic(long id, int seenFrame, boolean stop) {
        requireAppCaller();
        long identity = Binder.clearCallingIdentity();
        try {
            ReaderDiagnosticSession run = diagnostic;
            if (run == null || run.id != id) return null;
            long now = SystemClock.elapsedRealtime();
            if (now >= run.deadline) stopDiagnosticSampling("Capture deadline reached", false);
            else if (stop) stopDiagnosticSampling("Stop requested by Tally", false);
            Bundle report = new Bundle();
            report.putBoolean(ReaderDiagnosticSession.RUNNING, run.active(now));
            report.putBoolean(ReaderDiagnosticSession.LOCKED, run.isLocked());
            report.putInt(ReaderDiagnosticSession.PROBES, run.probes);
            report.putInt(ReaderDiagnosticSession.FOCUSED, run.focused);
            report.putInt(ReaderDiagnosticSession.NO_FOCUS, run.noFocus);
            report.putInt(ReaderDiagnosticSession.UNREADABLE, run.unreadable);
            report.putInt(ReaderDiagnosticSession.ERRORS, run.errors);
            report.putInt(ReaderDiagnosticSession.FRAME, run.frame);
            report.putString(ReaderDiagnosticSession.NODE_SUMMARY, run.nodeSummary());
            report.putInt(ReaderDiagnosticSession.TRACE_LENGTH, run.trace.length());
            String frame = run.frameAfter(seenFrame);
            if (frame != null) report.putString(HIERARCHY, frame);
            return report;
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized String readDiagnosticTrace(long id, int offset) {
        requireAppCaller();
        ReaderDiagnosticSession run = diagnostic;
        return run != null && run.id == id ? run.trace.chunk(offset) : null;
    }

    private synchronized void runDiagnosticTick() {
        ReaderDiagnosticSession run = diagnostic;
        if (diagnosticHandler == null || run == null || Looper.myLooper() != diagnosticHandler.getLooper()) return;
        if (!run.active(SystemClock.elapsedRealtime())) {
            stopDiagnosticSampling("Capture deadline reached", false);
            return;
        }
        try {
            run.at("reader.probe.screen_state");
            if (!screenAvailable()) {
                stopDiagnosticSampling("Screen off or locked", true);
                return;
            }
            run.probes++;
            run.at("reader.probe.focus");
            run.note("Probe " + run.probes + ": checking focus");
            ScreenCapturePolicy.FocusedWindow focus = windowFocus.read(userId, true, run.trace);
            if (focus == null || !isAllowed(focus.packageName)) {
                if (focus == null) run.noFocus++;
                run.note(focus == null ? "Focus not verified" : "Target is not foreground");
                if (verifiedFocus != null || automation != null) reset();
            } else {
                run.focused++;
                run.note("Target window verified: WindowManager id=" + focus.id + ", package=" + focus.packageName
                        + ", display=0, Android user=" + userId);
                rememberFocus(focus);
                long now = SystemClock.uptimeMillis();
                // Even when an OEM drops content events, re-read a stable diagnostic window promptly.
                if (nextTree(now) == 0 || now - lastTreeAt >= DIAGNOSTIC_QUIET_TREE_MS) {
                    long readStarted = SystemClock.elapsedRealtime();
                    Bundle sample = readHierarchy(focus, DIAGNOSTIC_PROBE_MS, run);
                    int state = sample.getInt(STATE);
                    run.trace.event("reader.read.result", "state=" + state + "; elapsedMs="
                            + (SystemClock.elapsedRealtime() - readStarted) + "; " + run.nodeSummary());
                    if (state == PAUSED) {
                        stopDiagnosticSampling("Screen off or locked during read", true);
                        return;
                    }
                    if (state == UNREADABLE) run.unreadable++;
                    run.recordFrame(sample.getString(HIERARCHY), SystemClock.elapsedRealtime());
                } else run.note("Tree read deferred by sampling cadence");
            }
        } catch (Exception | LinkageError e) {
            run.failure(e);
            if (run.errors == 1) Log.w(TAG, "Diagnostic probe failed: " + e.getClass().getSimpleName());
            reset();
        }
        if (run.active(SystemClock.elapsedRealtime()) && diagnosticHandler != null) {
            diagnosticHandler.removeCallbacks(diagnosticTick);
            diagnosticHandler.postDelayed(diagnosticTick, DIAGNOSTIC_PROBE_MS);
        } else stopDiagnosticSampling("Capture deadline reached", false);
    }

    private void stopDiagnosticSampling(String reason, boolean locked) {
        if (diagnostic != null) diagnostic.stop(reason, locked);
        Handler handler = diagnosticHandler;
        diagnosticHandler = null;
        if (handler != null) handler.removeCallbacks(diagnosticTick);
        if (diagnosticThread != null) diagnosticThread.quitSafely();
        diagnosticThread = null;
        if (diagnosticPackage == null) return;
        try { reset(); }
        finally { diagnosticPackage = null; diagnosticDeadline = 0; }
    }

    private static void phase(ReaderDiagnosticSession run, String stage) {
        if (run != null) run.at(stage);
    }

    private long nextTree(long now) {
        // A just-opened window can expose its old/loading tree before the receipt is drawn. Allow
        // one early follow-up, then retain the normal rate limit even during continuous animation.
        boolean initialRetry = windowSamples == 1 && now - windowEnteredAt < WINDOW_SETTLE_MS;
        return treeDelay(lastTreeAt, now, revision.get() != sampledRevision, quietSamples, unreadableSamples, initialRetry);
    }

    private Bundle readHierarchy(ScreenCapturePolicy.FocusedWindow focus, int probe, ReaderDiagnosticSession run)
            throws ReflectiveOperationException {
        watchedPackage = focus.packageName;
        if (run != null) run.beginTreeRead();
        DiagnosticTrace trace = run == null ? null : run.trace;
        if (automation == null) {
            try { ensureAutomation(run); }
            catch (RuntimeException e) {
                if (run == null) {
                    Log.w(TAG, "Unable to initialize UiAutomation", e);
                    reset();
                }
                throw e;
            }
            // A fresh connection can be slow. Check the Android user / focused window again after connecting.
            phase(run, "reader.after_connect.screen_state");
            if (!screenAvailable()) return pause();
            phase(run, "reader.after_connect.focus");
            ScreenCapturePolicy.FocusedWindow confirmed = windowFocus.read(userId, true, trace);
            if (!focus.matches(confirmed) || !isAllowed(focus.packageName)) {
                if (run != null) run.note("Focus changed before reading the tree");
                return focusChanged(confirmed);
            }
        }
        phase(run, "reader.before_root.screen_state");
        if (!screenAvailable()) return pause();
        if (!isAllowed(focus.packageName)) {
            if (run != null) run.note("Target authorization or deadline changed");
            return focusChanged(null);
        }
        wakeQueued.set(false);
        long observedRevision = revision.get();
        boolean contentChanged = observedRevision != sampledRevision;
        lastTreeAt = SystemClock.uptimeMillis();
        windowSamples = Math.min(2, windowSamples + 1);
        phase(run, "reader.root.active");
        long rootStarted = run == null ? 0 : SystemClock.elapsedRealtime();
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (run != null) run.trace.event("reader.root.active.query", "elapsedMs=" + (SystemClock.elapsedRealtime() - rootStarted));
        phase(run, "reader.root.copy");
        ScreenNode snapshot = copyPackageRoot(root, focus.packageName, run, "active");
        if (snapshot == null && run != null) {
            run.note(root == null ? "Active root is null" : "Active root did not pass package/node checks");
            phase(run, "reader.before_focused_root.screen_state");
            if (isAllowed(focus.packageName) && screenAvailable()) {
                phase(run, "reader.root.focused_window");
                snapshot = readFocusedAccessibilityWindow(focus.packageName, run);
            }
        }
        if (snapshot == null) {
            if (run != null) {
                run.note("No readable, in-scope tree; " + run.nodeSummary());
                if (run.claimDeepProbe(SystemClock.elapsedRealtime())) {
                    ReaderDiagnosticDetails.compare(run, automation, () -> diagnosticScope(run, focus));
                }
            }
            // Retry missing trees with bounded backoff; a fresh event always restores the fast path.
            sampledRevision = observedRevision;
            unreadableSamples = Math.min(4, unreadableSamples + 1);
            quietSamples = 0;
            lastDigest = null;
            return result(UNREADABLE, nextDelay(probe, nextTree(SystemClock.uptimeMillis())), null, null);
        }
        phase(run, "reader.after_root.screen_state");
        if (!screenAvailable()) return pause();
        phase(run, "reader.after_root.focus");
        ScreenCapturePolicy.FocusedWindow confirmed = windowFocus.read(userId, true, trace);
        if (!focus.matches(confirmed) || !isAllowed(focus.packageName)) {
            if (run != null) run.note("Focus changed; discarded snapshot");
            return focusChanged(confirmed);
        }
        phase(run, "reader.before_encode.screen_state");
        if (!screenAvailable()) return pause();
        lastFocusAt = SystemClock.uptimeMillis();
        sampledRevision = observedRevision;
        unreadableSamples = 0;
        phase(run, "reader.tree.fingerprint");
        byte[] currentDigest = fingerprint.of(snapshot);
        if (Arrays.equals(currentDigest, lastDigest)) {
            if (run != null) run.note("Tree unchanged");
            quietSamples = contentChanged ? 0 : Math.min(2, quietSamples + 1);
            return result(TARGET, nextDelay(probe, nextTree(SystemClock.uptimeMillis())), null, null);
        }
        quietSamples = 0;
        phase(run, "reader.tree.encode");
        String xml = ScreenHierarchyCodec.encode(snapshot);
        lastDigest = currentDigest;
        return result(TARGET, nextDelay(probe, nextTree(SystemClock.uptimeMillis())), focus.packageName, xml);
    }

    private String[] checkedRulePackages(String[] approved) {
        if (approved == null || approved.length > KeywordManager.MAX_CUSTOM_PACKAGES) return new String[0];
        for (String pkg : approved) {
            if (!ScreenCapturePolicy.canInspect(pkg, ownerPackage) || ScreenCapturePolicy.PAYMENT_DEMO.equals(pkg)) {
                return new String[0];
            }
        }
        return approved;
    }

    private boolean diagnosticScope(ReaderDiagnosticSession run, ScreenCapturePolicy.FocusedWindow focus) {
        return run == diagnostic && run.active(SystemClock.elapsedRealtime()) && listener != null
                && isAllowed(focus.packageName) && screenAvailable()
                && focus.matches(windowFocus.read(userId, true, run.trace)) && screenAvailable();
    }

    private static ScreenNode copyPackageRoot(AccessibilityNodeInfo root, String pkg, ReaderDiagnosticSession run, String route) {
        if (run == null) {
            if (root == null) return null;
            try {
                CharSequence name = root.getPackageName();
                return name != null && pkg.contentEquals(name) ? AccessibilityScreenReader.copyForShizuku(root, pkg) : null;
            } finally { root.recycle(); }
        }
        ScreenNodeCopyReport report = new ScreenNodeCopyReport();
        long start = SystemClock.elapsedRealtime();
        run.trace.event("reader.root." + route, AccessibilityScreenReader.describe(root));
        try {
            return AccessibilityScreenReader.copyForShizuku(root, pkg, report);
        } finally {
            run.nodeResult(route, report.rootResult());
            run.trace.event("reader.copy." + route, report.summary() + "; elapsedMs=" + (SystemClock.elapsedRealtime() - start));
            if (root != null) root.recycle();
        }
    }

    /** Some OEMs lag when choosing the active accessibility window after an app transition. */
    private ScreenNode readFocusedAccessibilityWindow(String pkg, ReaderDiagnosticSession run) {
        run.trace.event("reader.windows.query", "Inspecting default-display window metadata; only the focused application root may be copied");
        try (ReaderDiagnosticWindows inventory = new ReaderDiagnosticWindows(automation)) {
            inventory.describe(run);
            List<AccessibilityWindowInfo> windows = inventory.defaults();
            if (windows == null) {
                run.nodeResult("focused_window", "NO_DEFAULT_DISPLAY_WINDOW_LIST");
                return null;
            }
            AccessibilityWindowInfo focused = null;
            for (AccessibilityWindowInfo window : windows) {
                if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isFocused()) continue;
                if (focused != null) {
                    run.nodeResult("focused_window", "AMBIGUOUS_FOCUSED_APPLICATION_WINDOWS");
                    return null;
                }
                focused = window;
            }
            if (focused == null) {
                run.nodeResult("focused_window", "NO_FOCUSED_APPLICATION_WINDOW");
                return null;
            }
            if (!run.active(SystemClock.elapsedRealtime()) || !isAllowed(pkg) || !screenAvailable()) {
                run.nodeResult("focused_window", "CAPTURE_SCOPE_ENDED");
                return null;
            }
            run.trace.event("reader.windows.selected", "Accessibility window id=" + focused.getId());
            run.at("reader.root.focused_window.get_root");
            long start = SystemClock.elapsedRealtime();
            AccessibilityNodeInfo root = focused.getRoot();
            run.trace.event("reader.root.focused_window.query", "elapsedMs=" + (SystemClock.elapsedRealtime() - start));
            run.at("reader.root.focused_window.copy");
            return copyPackageRoot(root, pkg, run, "focused_window");
        }
    }

    private boolean isAllowed(String pkg) {
        return diagnosticPackage == null ? ScreenCapturePolicy.isTargetPackage(pkg) || (pkg != null && Arrays.binarySearch(rulePackages, pkg) >= 0)
                : ScreenCapturePolicy.isAllowedWindow(pkg, diagnosticPackage, ownerPackage,
                        SystemClock.elapsedRealtime(), diagnosticDeadline);
    }

    private Bundle focusChanged(ScreenCapturePolicy.FocusedWindow now) {
        if (now != null && isAllowed(now.packageName)) {
            // Throw away the raced snapshot, but reuse UiAutomation for the next verified window.
            verifiedFocus = now;
            lastTreeAt = -1;
            lastDigest = null;
            quietSamples = unreadableSamples = 0;
            windowEnteredAt = SystemClock.uptimeMillis();
            windowSamples = 0;
            lastFocusAt = -1;
            watchedPackage = now.packageName;
            return result(TARGET, EVENT_SETTLE_MS, null, null);
        }
        reset();
        return result(IDLE, EVENT_SETTLE_MS, null, null);
    }

    private Bundle pause() {
        taskObserver.stop();
        reset();
        return result(PAUSED, IDLE_PROBE_MS, null, null);
    }

    /** Outside packages are not retained. This hint only decides when to ask WindowManager again. */
    @SuppressWarnings("deprecation")
    private String foregroundHint() {
        if (Build.VERSION.SDK_INT < 29 || taskHintUnavailable) return null;
        try {
            if (activities == null) activities = packageContext.getSystemService(ActivityManager.class);
            if (activities == null) {
                taskHintUnavailable = true;
                return null;
            }
            List<ActivityManager.RunningTaskInfo> tasks = activities.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return null;
            ComponentName top = tasks.get(0).topActivity;
            if (top == null) return null;
            return isAllowed(top.getPackageName()) ? top.flattenToShortString() : "";
        } catch (RuntimeException | LinkageError ignored) {
            taskHintUnavailable = true;
            return null;
        }
    }

    private static Bundle result(int state, int delay, String pkg, String xml) {
        Bundle result = new Bundle();
        result.putInt(STATE, state);
        result.putInt(DELAY, delay);
        if (xml != null) {
            result.putString(PACKAGE, pkg);
            result.putString(HIERARCHY, xml);
        }
        return result;
    }

    private boolean screenAvailable() {
        return screenState.isAvailable();
    }

    private void ensureAutomation(ReaderDiagnosticSession run) throws ReflectiveOperationException {
        phase(run, "reader.automation.thread");
        automationThread = new HandlerThread("TallyScreenReader");
        automationThread.start();
        phase(run, "reader.automation.bridge");
        Class<?> connection = Class.forName("android.app.IUiAutomationConnection");
        Object bridge = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
        phase(run, "reader.automation.constructor");
        Constructor<UiAutomation> constructor = UiAutomation.class.getDeclaredConstructor(android.os.Looper.class, connection);
        constructor.setAccessible(true);
        automation = constructor.newInstance(automationThread.getLooper(), bridge);
        // Unlike bare `uiautomator dump`, do not suppress the user's other accessibility services.
        phase(run, "reader.automation.connect_method");
        java.lang.reflect.Method connect = UiAutomation.class.getDeclaredMethod("connect", int.class);
        connect.setAccessible(true);
        phase(run, "reader.automation.flags");
        int flags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES;
        if (Build.VERSION.SDK_INT >= 34) {
            // UiAutomation defaults to a trusted accessibility tool. Opt out of sensitive data access.
            flags |= UiAutomation.class.getField("FLAG_NOT_ACCESSIBILITY_TOOL").getInt(null);
        }
        phase(run, "reader.automation.connect");
        if (run != null) run.note("Connecting UiAutomation, flags=0x" + Integer.toHexString(flags));
        connect.invoke(automation, flags);
        phase(run, "reader.automation.get_service_info");
        AccessibilityServiceInfo info = automation.getServiceInfo();
        if (info == null) throw new IllegalStateException("UiAutomation connection failed");
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        if (diagnosticPackage == null) {
            String[] builtin = ScreenCapturePolicy.targetPackages();
            info.packageNames = Arrays.copyOf(builtin, builtin.length + rulePackages.length);
            System.arraycopy(rulePackages, 0, info.packageNames, builtin.length, rulePackages.length);
        } else {
            info.packageNames = new String[]{diagnosticPackage};
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        }
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED | AccessibilityEvent.TYPE_VIEW_SCROLLED;
        phase(run, "reader.automation.set_service_info");
        automation.setServiceInfo(info);
        phase(run, "reader.automation.set_listener");
        automation.setOnAccessibilityEventListener(this::onScreenChanged);
        if (run != null) {
            run.note("UiAutomation ready");
            ReaderDiagnosticDetails.serviceInfo(run, automation, "reader.automation.effective_service_info");
        }
    }

    private void onScreenChanged(AccessibilityEvent event) {
        try {
            String watching = watchedPackage;
            if (watching == null || !watching.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) return;
            ReaderDiagnosticSession run = diagnostic;
            if (run != null && diagnosticHandler != null) run.event(SystemClock.elapsedRealtime(), event.getEventType(),
                    event.getWindowId(), event.getContentChangeTypes(), event.getClassName());
            revision.incrementAndGet();
            notifyChanged(wakeQueued);
        } finally {
            event.recycle();
        }
    }

    private void notifyChanged(AtomicBoolean queued) {
        Handler handler = diagnosticHandler;
        if (handler != null) {
            if (queued.compareAndSet(false, true)) {
                handler.removeCallbacks(diagnosticTick);
                handler.postDelayed(diagnosticTick, EVENT_SETTLE_MS);
            }
            return;
        }
        IScreenCaptureListener target = listener;
        if (target == null || !queued.compareAndSet(false, true)) return;
        try { target.onScreenChanged(); }
        catch (RemoteException ignored) {
            queued.set(false);
            // The death recipient releases reader state if the owning app has gone away.
        }
    }

    @Override public synchronized void releaseAutomation() {
        requireAppCaller();
        long identity = Binder.clearCallingIdentity();
        try { stopDiagnosticSampling("Reader released by host", true); taskObserver.stop(); reset(); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    private void reset() {
        verifiedFocus = null;
        lastFocusAt = -1;
        lastHint = null;
        releaseReader();
    }

    private void releaseReader() {
        watchedPackage = null;
        lastTreeAt = -1;
        lastDigest = null;
        quietSamples = unreadableSamples = 0;
        windowSamples = 0;
        wakeQueued.set(false);
        if (automation != null) {
            DiagnosticTrace trace = diagnosticPackage == null || diagnostic == null ? null : diagnostic.trace;
            // The connection may have failed before a listener could be registered.
            try { automation.setOnAccessibilityEventListener(null); }
            catch (RuntimeException | LinkageError e) {
                if (trace != null) trace.error("reader.automation.clear_listener", e);
            }
            try {
                java.lang.reflect.Method disconnect = UiAutomation.class.getDeclaredMethod("disconnect");
                disconnect.setAccessible(true);
                disconnect.invoke(automation);
            } catch (Exception | LinkageError e) {
                if (trace != null) trace.error("reader.automation.disconnect", e);
            }
            automation = null;
        }
        if (automationThread != null) {
            automationThread.quitSafely();
            automationThread = null;
        }
    }

    @Override public synchronized void destroy() {
        int caller = Binder.getCallingUid();
        if (caller != Process.myUid() && caller != appUid) throw new SecurityException("Not the owner");
        detachClient();
        releaseClientState();
        System.exit(0);
    }
}
