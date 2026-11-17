package com.example.budgetapp.util;

/** Local node eligibility shared by copying and its diagnostics. Never changes node flags. */
public final class ScreenNodePolicy {
    public static final int PACKAGE_MISSING = 1, PACKAGE_MISMATCH = 2, NOT_VISIBLE = 4,
            EDITABLE = 8, PASSWORD = 16, DATA_SENSITIVE = 32;
    private static final int[] FLAGS = {PACKAGE_MISSING, PACKAGE_MISMATCH, NOT_VISIBLE, EDITABLE, PASSWORD, DATA_SENSITIVE};
    private static final String[] NAMES = {"PACKAGE_MISSING", "PACKAGE_MISMATCH", "NOT_VISIBLE", "EDITABLE", "PASSWORD", "DATA_SENSITIVE"};

    private ScreenNodePolicy() { }

    public static int rejectedBy(String expected, String actual, boolean visible, boolean editable,
            boolean password, boolean sensitive) {
        if (expected == null) throw new IllegalArgumentException("Expected package is required");
        int reasons = actual == null || actual.isEmpty() ? PACKAGE_MISSING
                : expected.equals(actual) ? 0 : PACKAGE_MISMATCH;
        if (!visible) reasons |= NOT_VISIBLE;
        if (editable) reasons |= EDITABLE;
        if (password) reasons |= PASSWORD;
        if (sensitive) reasons |= DATA_SENSITIVE;
        return reasons;
    }

    public static String reasons(int mask) {
        if (mask == 0) return "ACCEPTED";
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < FLAGS.length; i++) {
            if ((mask & FLAGS[i]) == 0) continue;
            if (names.length() > 0) names.append('|');
            names.append(NAMES[i]);
        }
        return names.toString();
    }
}
