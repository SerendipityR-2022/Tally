package com.example.budgetapp.util;

/** Identifies this app's service without matching other apps that reuse its class name. */
public final class AccessibilityServiceIdentity {
    private static final String SERVICE_CLASS =
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService";

    private AccessibilityServiceIdentity() { }

    public static String componentId(String appPackage) {
        return appPackage + "/" + SERVICE_CLASS;
    }

    public static boolean matches(String appPackage, String serviceId) {
        return appPackage != null && !appPackage.isEmpty()
                && componentId(appPackage).equals(serviceId);
    }
}
