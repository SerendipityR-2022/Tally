package com.google.android.accessibility.selecttospeak;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.content.ContextCompat;
import com.example.budgetapp.service.ScreenAccountingEngine;
import com.example.budgetapp.service.ScreenDebugCapture;
import com.example.budgetapp.util.AccessibilityScreenReader;
import com.example.budgetapp.util.AssistantConfig;
import com.example.budgetapp.util.AutoTrackLogManager;
import com.example.budgetapp.util.KeywordManager;
import com.example.budgetapp.util.ScreenCapturePolicy;
import com.example.budgetapp.util.ScreenCaptureSettings;

import java.util.Arrays;

/** Accessibility event source only; all payment adapters and save behavior are shared. */
public class SelectToSpeakService extends AccessibilityService {
    private static final long DIAGNOSTIC_SAMPLE_MS = 750;
    private static SelectToSpeakService connectedService;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScreenAccountingEngine engine;
    private View keepAliveView;
    private boolean destroyed;
    private boolean screenReceiverRegistered;
    private String[] rulePackages = new String[0];
    private ScreenDebugCapture.Run diagnostic;
    private final Runnable diagnosticProbe = this::probeDiagnostic;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) finishDiagnostic(true, "Screen turned off");
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (prefs, key) -> handler.post(() -> {
        if (new ScreenCaptureSettings(this).isEnabled()) {
            // The experimental mode must not retain this app's accessibility grant.
            finishDiagnostic(false, "Screen reader mode changed");
            if (engine != null) engine.close();
            disableSelf();
        } else if (!new AssistantConfig(this).isEnabled() && engine != null) engine.pause();
        refreshKeepAliveWindow();
    });
    private final SharedPreferences.OnSharedPreferenceChangeListener rulesListener = (prefs, key) ->
            handler.post(() -> { if (!destroyed) rulePackages = KeywordManager.getCustomScreenPackages(this); });
    private final Runnable scan = () -> {
        if (destroyed || engine == null || engine.isBusy() || !new AssistantConfig(this).isEnabled()
                || new ScreenCaptureSettings(this).isEnabled() || ScreenDebugCapture.isAccessibilityCaptureActive()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
            if (getPackageName().equals(pkg)) return;
            if (ScreenCapturePolicy.isTargetPackage(pkg) || AutoTrackLogManager.isLogEnabled(this)) {
                engine.analyze(AccessibilityScreenReader.copy(root));
            } else if (Arrays.binarySearch(rulePackages, pkg) >= 0) {
                engine.analyze(AccessibilityScreenReader.copyScoped(root, pkg, null));
            }
        } catch (RuntimeException ignored) {
            // Transient/recycled/oversized trees are skipped, never interpreted partially.
        } finally { root.recycle(); }
    };

    public static boolean isConnected() { return connectedService != null; }

    /** Called from the visible diagnostic activity on the main thread. */
    public static ScreenDebugCapture.Run startDiagnostic(String pkg) {
        SelectToSpeakService service = connectedService;
        if (service == null || new ScreenCaptureSettings(service).isEnabled()
                || !ScreenCapturePolicy.canInspect(pkg, service.getPackageName())) return null;
        ScreenDebugCapture.Run run = ScreenDebugCapture.start(pkg, ScreenDebugCapture.Source.ACCESSIBILITY);
        service.handler.removeCallbacks(service.scan);
        service.handler.removeCallbacks(service.diagnosticProbe);
        if (service.engine != null) service.engine.pause();
        service.diagnostic = run;
        if (service.isScreenAvailable()) service.handler.post(service.diagnosticProbe);
        else service.finishDiagnostic(true, "Screen off or locked");
        return run;
    }

    private boolean isScreenAvailable() {
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
    }

    private void probeDiagnostic() {
        ScreenDebugCapture.Run run = diagnostic;
        if (run == null) return;
        if (ScreenDebugCapture.latest() != run || !run.isActive()) {
            finishDiagnostic(false, "Capture time limit reached");
            return;
        }
        if (!isScreenAvailable()) {
            finishDiagnostic(true, "Screen off or locked");
            return;
        }
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            run.readAccessibilityFrame(root);
        } catch (RuntimeException error) {
            run.error("accessibility.active_window", error);
        } finally {
            if (root != null) root.recycle();
        }
        handler.postDelayed(diagnosticProbe, DIAGNOSTIC_SAMPLE_MS);
    }

    private void finishDiagnostic(boolean locked, String reason) {
        if (diagnostic != null) diagnostic.finishAccessibility(locked, reason);
        diagnostic = null;
        handler.removeCallbacks(diagnosticProbe);
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        if (new ScreenCaptureSettings(this).isEnabled()) { disableSelf(); return; }
        engine = new ScreenAccountingEngine(this);
        engine.setPresentationGuard(() -> !destroyed && !new ScreenCaptureSettings(this).isEnabled()
                && !ScreenDebugCapture.isAccessibilityCaptureActive());
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(this, screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            screenReceiverRegistered = true;
        }
        connectedService = this;
        rulePackages = KeywordManager.getCustomScreenPackages(this);
        KeywordManager.rulePreferences(this).registerOnSharedPreferenceChangeListener(rulesListener);
        getSharedPreferences(ScreenCaptureSettings.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(settingsListener);
        getSharedPreferences("app_prefs", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(settingsListener);
        refreshKeepAliveWindow();
    }

    private void refreshKeepAliveWindow() {
        boolean allow = !destroyed && new AssistantConfig(this).isEnabled()
                && !new ScreenCaptureSettings(this).isEnabled() && Settings.canDrawOverlays(this);
        WindowManager manager = getSystemService(WindowManager.class);
        if (manager == null) return;
        if (allow && keepAliveView == null) {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            try {
                View view = new View(this);
                manager.addView(view, params);
                keepAliveView = view;
            } catch (RuntimeException ignored) { }
        } else if (!allow && keepAliveView != null) {
            try { manager.removeViewImmediate(keepAliveView); }
            catch (RuntimeException ignored) { }
            keepAliveView = null;
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (destroyed || event == null || event.getPackageName() == null) return;
        if (new ScreenCaptureSettings(this).isEnabled()) { disableSelf(); return; }
        if (ScreenDebugCapture.isAccessibilityCaptureActive()) return;
        String pkg = event.getPackageName().toString();
        if (getPackageName().equals(pkg) || !new AssistantConfig(this).isEnabled()) return;
        if (!ScreenCapturePolicy.isTargetPackage(pkg) && Arrays.binarySearch(rulePackages, pkg) < 0
                && !AutoTrackLogManager.isLogEnabled(this)) return;
        handler.removeCallbacks(scan);
        if (engine != null && !engine.isBusy()) handler.postDelayed(scan, 300);
    }

    @Override public void onInterrupt() {
        handler.removeCallbacks(scan);
        if (engine != null) engine.pause();
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (connectedService == this) connectedService = null;
        if (diagnostic != null) diagnostic.accessibilityUnavailable();
        if (screenReceiverRegistered) unregisterReceiver(screenOff);
        refreshKeepAliveWindow();
        handler.removeCallbacksAndMessages(null);
        KeywordManager.rulePreferences(this).unregisterOnSharedPreferenceChangeListener(rulesListener);
        getSharedPreferences(ScreenCaptureSettings.PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(settingsListener);
        getSharedPreferences("app_prefs", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(settingsListener);
        if (engine != null) engine.close();
        super.onDestroy();
    }
}
