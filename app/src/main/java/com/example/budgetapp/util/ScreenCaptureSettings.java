package com.example.budgetapp.util;

import android.content.Context;
import android.content.SharedPreferences;

/** Device-local consent, deliberately excluded from portable assistant configuration backups. */
public final class ScreenCaptureSettings {
    public static final String PREFS = "assistant_settings";
    public static final String KEY_ENABLED = "experimental_no_accessibility_enabled";
    private static final String LEGACY_READER_MODE = "experimental_reader_mode";
    public static final String KEY_KEEP_ALIVE = "shizuku_keep_alive_enabled";
    private static final String KEY_SILENT_ACCOUNTING = "silent_accounting_enabled";
    private final SharedPreferences prefs;

    public ScreenCaptureSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.contains(LEGACY_READER_MODE)) prefs.edit().remove(LEGACY_READER_MODE).apply();
    }
    public boolean isEnabled() { return prefs.getBoolean(KEY_ENABLED, false); }
    public void setEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_ENABLED, enabled).apply(); }
    public boolean isSilentAccountingEnabled() { return prefs.getBoolean(KEY_SILENT_ACCOUNTING, false); }
    public void setSilentAccountingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SILENT_ACCOUNTING, enabled).apply();
    }
    public boolean isKeepAliveEnabled() { return prefs.getBoolean(KEY_KEEP_ALIVE, false); }
    public void setKeepAliveEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply();
    }
}
