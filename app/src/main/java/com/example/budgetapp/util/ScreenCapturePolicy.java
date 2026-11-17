package com.example.budgetapp.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fail-closed scope and authorization decisions, independently testable without Android. */
public final class ScreenCapturePolicy {
    public static final String PAYMENT_DEMO = "cn.serendipityr.tally.paymentdemo";
    private static final Set<String> PACKAGES = new HashSet<>(Arrays.asList(
            "com.eg.android.AlipayGphone", "com.tencent.mm", "com.xunmeng.pinduoduo",
            "com.ss.android.ugc.aweme", "com.jingdong.app.mall", "com.aliyun.tongyi",
            "com.unionpay", "com.ss.android.ugc.lifeservices",
            "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew",
            "com.sankuai.meituan.tuan"));
    private static final String PACKAGE = "(?:android|[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+)";
    private static final String WINDOW_KEY = "([a-fA-F0-9]{1,8})[ \\t]+u(\\d+)[ \\t]+";
    private static final Pattern PACKAGE_NAME = Pattern.compile(PACKAGE);
    private static final Pattern DISPLAY = Pattern.compile("(?m)^[ \\t]*Display:[ \\t]+mDisplayId=(\\d+)");
    private static final Pattern TOP_DISPLAY = Pattern.compile("(?m)^[ \\t]*mTopFocusedDisplayId=(-?\\d+)[ \\t]*$");
    private static final Pattern FOCUS = Pattern.compile(
            "(?m)^[ \\t]*mCurrentFocus[ \\t]*=[ \\t]*Window\\{" + WINDOW_KEY + "([^}\\r\\n]+)\\}[ \\t]*$");
    private static final Pattern WINDOW_TITLE = Pattern.compile("^(" + PACKAGE + ")(?:/[A-Za-z0-9_.$]+)?$");
    private static final Pattern WINDOW_ENTRY = Pattern.compile(
            "(?m)^[ \\t]*Window #\\d+[ \\t]+Window\\{" + WINDOW_KEY + "[^}\\r\\n]+\\}:[ \\t]*$");
    private static final Pattern WINDOW_DISPLAY = Pattern.compile("(?m)^[ \\t]*mDisplayId=(\\d+)(?:[ \\t]|$)");
    private static final Pattern WINDOW_OWNER = Pattern.compile(
            "(?m)^[ \\t]*mOwnerUid=(\\d+)[^\\r\\n]*?[ \\t]package=(" + PACKAGE + ")(?:[ \\t]|$)");
    private static final int UID_USER_RANGE = 100000;

    private ScreenCapturePolicy() { }

    public static boolean isTargetPackage(String packageName) {
        return PACKAGES.contains(packageName);
    }

    /** A diagnostic request is scoped to one explicitly chosen package, never the owner. */
    public static boolean canInspect(String selected, String owner) {
        return selected != null && selected.length() <= 255 && !selected.equals(owner)
                && PACKAGE_NAME.matcher(selected).matches();
    }

    public static boolean isAllowedWindow(String pkg, String selected, String own, long now, long expires) {
        if (selected == null) return isTargetPackage(pkg);
        return now < expires && canInspect(selected, own) && selected.equals(pkg);
    }

    public static boolean mayCapture(boolean enabled, boolean autoTrack, boolean authorized,
            boolean interactive, boolean locked, boolean overlayRequirementMet, boolean notificationsAllowed) {
        return enabled && autoTrack && authorized && interactive && !locked
                && overlayRequirementMet && notificationsAllowed;
    }

    public static boolean mayRestoreAccessibility(boolean keepAlive, boolean experimental,
            boolean autoTrack, boolean authorized) {
        return keepAlive && !experimental && autoTrack && authorized;
    }

    public static String[] targetPackages() { return PACKAGES.toArray(new String[0]); }

    public static final class FocusedWindow {
        public final String id, packageName;
        private final int userId;
        private FocusedWindow(String id, String packageName, int userId) {
            this.id = id;
            this.packageName = packageName;
            this.userId = userId;
        }
        public boolean matches(FocusedWindow other) {
            return other != null && userId == other.userId && id.equals(other.id) && packageName.equals(other.packageName);
        }
    }

    /** Only a focused window on the active default display and belonging to this Android user counts. */
    public static FocusedWindow focusedWindow(String dump, int userId) {
        Matcher focus = defaultDisplayFocus(dump, userId);
        if (focus == null) return null;
        Matcher title = WINDOW_TITLE.matcher(focus.group(3));
        return title.matches() ? new FocusedWindow(focus.group(1), title.group(1), userId) : null;
    }

    public static boolean hasUnidentifiedFocus(String dump, int userId) {
        Matcher focus = defaultDisplayFocus(dump, userId);
        return focus != null && !WINDOW_TITLE.matcher(focus.group(3)).matches();
    }

    /** OEM/custom titles are resolved using the same WindowState's owner, never a task hint. */
    public static FocusedWindow focusedWindowFromOwner(String displays, String windows, int userId) {
        Matcher focus = defaultDisplayFocus(displays, userId);
        if (focus == null || windows == null) return null;
        Matcher entry = WINDOW_ENTRY.matcher(windows);
        FocusedWindow chosen = null;
        boolean found = false;
        boolean hasEntry = entry.find();
        while (hasEntry) {
            boolean same = focus.group(1).equals(entry.group(1));
            String entryUser = entry.group(2);
            int start = entry.end();
            hasEntry = entry.find();
            if (!same) continue;
            if (found || !String.valueOf(userId).equals(entryUser)) return null;
            found = true;
            int end = hasEntry ? entry.start() : windows.length();
            Matcher display = WINDOW_DISPLAY.matcher(windows).region(start, end);
            Matcher owner = WINDOW_OWNER.matcher(windows).region(start, end);
            if (!display.find() || !"0".equals(display.group(1)) || !owner.find()) return null;
            try {
                if (Integer.parseInt(owner.group(1)) / UID_USER_RANGE != userId) return null;
            } catch (NumberFormatException e) { return null; }
            chosen = new FocusedWindow(focus.group(1), owner.group(2), userId);
        }
        return chosen;
    }

    private static Matcher defaultDisplayFocus(String dump, int userId) {
        if (dump == null) return null;
        Matcher top = TOP_DISPLAY.matcher(dump);
        if (top.find() && !"0".equals(top.group(1))) return null;
        Matcher display = DISPLAY.matcher(dump);
        while (display.find()) {
            if (!"0".equals(display.group(1))) continue;
            int start = display.end();
            int end = display.find() ? display.start() : dump.length();
            Matcher focus = FOCUS.matcher(dump).region(start, end);
            if (!focus.find() || !String.valueOf(userId).equals(focus.group(2)) || focus.group(3).endsWith(" EXITING")) return null;
            return focus;
        }
        return null;
    }
}
