package com.example.budgetapp.ui;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** The toolbar starts just below the safe screen area; content supplies its own gutters. */
public final class SettingsPage {
    private SettingsPage() { }

    public static void applyInsets(View root) {
        applyInsets(root, false);
    }

    public static void applyInsets(View root, boolean resizeForKeyboard) {
        int types = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();
        final int safeArea = types | (resizeForKeyboard ? WindowInsetsCompat.Type.ime() : 0);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(safeArea);
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
