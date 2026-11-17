package com.example.budgetapp.util;

/** Bounded single-line metadata; node text, content descriptions and window titles are not inputs. */
public final class DiagnosticValue {
    private static final int LIMIT = 240;
    private DiagnosticValue() { }

    public static String of(CharSequence value) {
        if (value == null) return "<null>";
        if (value.length() == 0) return "<empty>";
        StringBuilder out = new StringBuilder();
        int count = Math.min(value.length(), LIMIT);
        for (int i = 0; i < count; ++i) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch) || ch == '\u2028' || ch == '\u2029'
                    || Character.getType(ch) == Character.FORMAT) {
                out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
            } else out.append(ch);
        }
        if (count < value.length()) out.append("…(chars=").append(value.length()).append(')');
        return out.toString();
    }
}
