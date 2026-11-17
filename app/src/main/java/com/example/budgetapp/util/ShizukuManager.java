package com.example.budgetapp.util;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.view.accessibility.AccessibilityManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

public class ShizukuManager {

    /**
     * 判断当前系统是否运行了 Shizuku 服务
     */
    public static boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 判断当前 App 是否获得了 Shizuku 的权限
     */
    public static boolean hasShizukuPermission() {
        if (!isShizukuAvailable()) {
            return false;
        }
        try {
            if (Shizuku.isPreV11()) {
                return false;
            }
            // The SDK caches a successful grant, so check the server for current permission.
            IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            return service != null && service.checkSelfPermission();
        } catch (Exception t) {
            return false;
        }
    }

    private static String execCommand(String command) {
        try {
            Method newProcessMethod = Shizuku.class.getDeclaredMethod(
                    "newProcess",
                    String[].class,
                    String[].class,
                    String.class
            );
            newProcessMethod.setAccessible(true);

            Process process = (Process) newProcessMethod.invoke(
                    null,
                    new Object[]{new String[]{"sh", "-c", command}, null, null}
            );

            if (process == null) return null;

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            return process.waitFor() == 0 ? output.toString().trim() : null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean isAccessibilityRunning(Context context) {
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (enabledServices != null) {
            for (AccessibilityServiceInfo service : enabledServices) {
                if (service != null && AccessibilityServiceIdentity.matches(
                        context.getPackageName(), service.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean shouldRestoreAccessibility(Context context) {
        ScreenCaptureSettings settings = new ScreenCaptureSettings(context);
        return ScreenCapturePolicy.mayRestoreAccessibility(settings.isKeepAliveEnabled(),
                settings.isEnabled(), new AssistantConfig(context).isEnabled(), hasShizukuPermission());
    }

    public static void restoreKeepAliveIfAllowed(Context context) {
        if (!new ScreenCaptureSettings(context).isKeepAliveEnabled() || !hasShizukuPermission()) return;
        grantBackgroundKeepAlive(context);
        if (shouldRestoreAccessibility(context) && !isAccessibilityRunning(context)) enableAccessibilityService(context);
    }

    public static void restoreKeepAliveAsync(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> restoreKeepAliveIfAllowed(app), "ShizukuKeepAlive").start();
    }

    public static boolean enableAccessibilityService(Context context) {
        if (!shouldRestoreAccessibility(context)) return false;

        String settingsForUser = "settings --user " + (android.os.Process.myUid() / 100000);
        String currentServices = execCommand(settingsForUser + " get secure enabled_accessibility_services");
        if (currentServices == null) return false;
        Set<String> serviceSet = new LinkedHashSet<>();
        if (!currentServices.isEmpty() && !"null".equalsIgnoreCase(currentServices)) {
            String[] split = currentServices.split(":");
            serviceSet.addAll(Arrays.asList(split));
        }

        serviceSet.add(AccessibilityServiceIdentity.componentId(context.getPackageName()));
        String updatedServices = String.join(":", serviceSet);

        if (!shouldRestoreAccessibility(context)) return false;
        if (execCommand(settingsForUser + " put secure enabled_accessibility_services '" + updatedServices.replace("'", "'\"'\"'") + "'") == null) return false;
        if (!shouldRestoreAccessibility(context)) return false;
        return execCommand(settingsForUser + " put secure accessibility_enabled 1") != null;
    }

    public static void grantBackgroundKeepAlive(Context context) {
        if (!new ScreenCaptureSettings(context).isKeepAliveEnabled() || !hasShizukuPermission()) return;

        String pkgName = context.getPackageName();
        execCommand("dumpsys deviceidle whitelist +" + pkgName);
        execCommand("cmd appops set " + pkgName + " RUN_IN_BACKGROUND allow");
        execCommand("cmd appops set " + pkgName + " RUN_ANY_IN_BACKGROUND allow");
    }
}