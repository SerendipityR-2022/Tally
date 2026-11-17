package com.google.android.accessibility.selecttospeak;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.example.budgetapp.service.ScreenAccountingEngine;
import com.example.budgetapp.util.AccessibilityScreenReader;
import com.example.budgetapp.util.AssistantConfig;
import com.example.budgetapp.util.AutoTrackLogManager;
import com.example.budgetapp.util.ScreenCapturePolicy;
import com.example.budgetapp.util.ScreenCaptureSettings;

/** Accessibility event source only; all payment adapters and save behavior are shared. */
public class SelectToSpeakService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScreenAccountingEngine engine;
    private View keepAliveView;
    private boolean destroyed;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (prefs, key) -> handler.post(() -> {
        if (new ScreenCaptureSettings(this).isEnabled()) {
            // The experimental mode must not retain this app's accessibility grant.
            if (engine != null) engine.close();
            disableSelf();
        } else if (!new AssistantConfig(this).isEnabled() && engine != null) engine.pause();
        refreshKeepAliveWindow();
    });
    private final Runnable scan = () -> {
        if (destroyed || engine == null || engine.isBusy() || !new AssistantConfig(this).isEnabled()
                || new ScreenCaptureSettings(this).isEnabled()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
            if (!getPackageName().equals(pkg) && (ScreenCapturePolicy.isTargetPackage(pkg) || AutoTrackLogManager.isLogEnabled(this))) {
                engine.analyze(AccessibilityScreenReader.copy(root));
            }
        } catch (RuntimeException ignored) {
            // Transient/recycled/oversized trees are skipped, never interpreted partially.
        } finally { root.recycle(); }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        if (new ScreenCaptureSettings(this).isEnabled()) { disableSelf(); return; }
        engine = new ScreenAccountingEngine(this);
        engine.setPresentationGuard(() -> !destroyed && !new ScreenCaptureSettings(this).isEnabled());
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
        String pkg = event.getPackageName().toString();
        if (getPackageName().equals(pkg) || !new AssistantConfig(this).isEnabled()) return;
        if (!ScreenCapturePolicy.isTargetPackage(pkg) && !AutoTrackLogManager.isLogEnabled(this)) return;
        handler.removeCallbacks(scan);
        if (engine != null && !engine.isBusy()) handler.postDelayed(scan, 300);
    }

    @Override public void onInterrupt() {
        handler.removeCallbacks(scan);
        if (engine != null) engine.pause();
    }

    @Override public void onDestroy() {
        destroyed = true;
        refreshKeepAliveWindow();
        handler.removeCallbacksAndMessages(null);
        getSharedPreferences(ScreenCaptureSettings.PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(settingsListener);
        getSharedPreferences("app_prefs", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(settingsListener);
        if (engine != null) engine.close();
        super.onDestroy();
    }
}
