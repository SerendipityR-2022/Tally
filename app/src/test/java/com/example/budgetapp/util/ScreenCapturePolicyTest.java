package com.example.budgetapp.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenCapturePolicyTest {
    private static final String PAY = "com.unionpay";
    private static final String DISPLAY = "  Display: mDisplayId=0 (organized)\n";
    private static final String CURRENT = "  mCurrentFocus=Window{a23c u0 com.unionpay/com.unionpay.FixtureActivity}\n";

    @Test public void onlyExplicitConsentAndEveryRuntimeGuardAllowCapture() {
        assertTrue(ScreenCapturePolicy.mayCapture(true, true, true, true, false, true, true));
        assertFalse(ScreenCapturePolicy.mayCapture(false, true, true, true, false, true, true));
        assertFalse(ScreenCapturePolicy.mayCapture(true, false, true, true, false, true, true));
        assertFalse(ScreenCapturePolicy.mayCapture(true, true, false, true, false, true, true));
        assertFalse(ScreenCapturePolicy.mayCapture(true, true, true, false, false, true, true));
        assertFalse(ScreenCapturePolicy.mayCapture(true, true, true, true, true, true, true));
        assertFalse(ScreenCapturePolicy.mayCapture(true, true, true, true, false, false, true));
        assertFalse(ScreenCapturePolicy.mayCapture(true, true, true, true, false, true, false));
    }

    @Test public void authorizationAloneCannotRestoreAccessibility() {
        assertTrue(ScreenCapturePolicy.mayRestoreAccessibility(true, false, true, true));
        assertFalse(ScreenCapturePolicy.mayRestoreAccessibility(false, false, true, true));
        assertFalse(ScreenCapturePolicy.mayRestoreAccessibility(true, true, true, true));
        assertFalse(ScreenCapturePolicy.mayRestoreAccessibility(true, false, false, true));
        assertFalse(ScreenCapturePolicy.mayRestoreAccessibility(true, false, true, false));
    }

    @Test public void targetPackagesAreExact() {
        assertTrue(ScreenCapturePolicy.isTargetPackage(PAY));
        assertTrue(ScreenCapturePolicy.isTargetPackage("com.sankuai.meituan.takeoutnew"));
        assertFalse(ScreenCapturePolicy.isTargetPackage("org.fake.meituan"));
        assertFalse(ScreenCapturePolicy.isTargetPackage("com.unionpay.copy"));
        assertFalse(ScreenCapturePolicy.isTargetPackage("com.android.settings"));
    }

    @Test public void focusMustMatchUserAndDefaultDisplay() {
        ScreenCapturePolicy.FocusedWindow focus = ScreenCapturePolicy.focusedWindow(DISPLAY + CURRENT, 0);
        assertEquals(PAY, focus.packageName);
        assertEquals("a23c", focus.id);
        assertNull(ScreenCapturePolicy.focusedWindow(DISPLAY + CURRENT, 10));
        assertNull(ScreenCapturePolicy.focusedWindow(DISPLAY + "  mCurrentFocus=null\n"
                + "  Display: mDisplayId=2\n" + CURRENT, 0));
    }

    @Test public void resumedActivityBehindDialogIsNotConsideredFocused() {
        assertNull(ScreenCapturePolicy.focusedWindow(DISPLAY + "  mCurrentFocus=Window{1ab u0 NotificationShade}\n"
                + "  mFocusedApp=ActivityRecord{123 u0 com.unionpay/.FixtureActivity t62}\n", 0));
        String permissionDialog = CURRENT.replace("com.unionpay/com.unionpay.FixtureActivity",
                "com.android.permissioncontroller/.permission.ui.GrantPermissionsActivity");
        assertFalse(ScreenCapturePolicy.isTargetPackage(ScreenCapturePolicy.focusedWindow(DISPLAY + permissionDialog, 0).packageName));
    }

    @Test public void onlyTheSameFocusedWindowCanCompleteASnapshot() {
        ScreenCapturePolicy.FocusedWindow focus = ScreenCapturePolicy.focusedWindow(DISPLAY + CURRENT, 0);
        assertTrue(focus.matches(ScreenCapturePolicy.focusedWindow(DISPLAY + CURRENT, 0)));
        assertFalse(focus.matches(ScreenCapturePolicy.focusedWindow(DISPLAY + CURRENT.replace("a23c", "abc"), 0)));
        assertFalse(focus.matches(ScreenCapturePolicy.focusedWindow(DISPLAY + CURRENT.replace("com.unionpay/", "com.tencent.mm/"), 0)));
        assertFalse(focus.matches(ScreenCapturePolicy.focusedWindow(DISPLAY + CURRENT.replace("u0", "u10"), 10)));
        assertFalse(focus.matches(null));
    }
    @Test public void diagnosticsAreExactPackageScopedAndExpire() {
        String owner = "com.example.budgetapp";
        String selected = "com.example.receipt";
        assertFalse(ScreenCapturePolicy.isTargetPackage(selected));
        assertTrue(ScreenCapturePolicy.isAllowedWindow(selected, selected, owner, 100, 101));
        assertFalse(ScreenCapturePolicy.isAllowedWindow(selected, selected, owner, 101, 101));
        assertFalse(ScreenCapturePolicy.isAllowedWindow("com.tencent.mm", selected, owner, 100, 101));
        assertFalse(ScreenCapturePolicy.isAllowedWindow(owner, owner, owner, 100, 101));
        assertFalse(ScreenCapturePolicy.canInspect("bad name", owner));
        assertTrue(ScreenCapturePolicy.isAllowedWindow("com.tencent.mm", null, owner, 100, 0));
        assertFalse(ScreenCapturePolicy.isAllowedWindow(selected, null, owner, 100, 0));
    }

    @Test public void focusMayUseThePackageAsItsWindowTag() {
        String demo = ScreenCapturePolicy.PAYMENT_DEMO;
        ScreenCapturePolicy.FocusedWindow focus = ScreenCapturePolicy.focusedWindow(
                DISPLAY + "  mCurrentFocus=Window{f42b u0 " + demo + "}\n", 0);
        assertNotNull(focus);
        assertEquals(demo, focus.packageName);
        assertNull(ScreenCapturePolicy.focusedWindow(DISPLAY + "  mCurrentFocus=Window{f42b u0 "
                + demo + " EXITING}\n", 0));
    }

    @Test public void customWindowTitleCanOnlyResolveToItsExactOwner() {
        String display = DISPLAY + "  mCurrentFocus=Window{a23c u0 Payment result}\n"
                + "  mFocusedApp=ActivityRecord{123 u0 com.unionpay/.FixtureActivity t62}\n"
                + "  mTopFocusedDisplayId=0\n";
        String windows = "  Window #0 Window{3de u0 com.tencent.mm/.MainActivity}:\n"
                + "    mDisplayId=0 mSession=abc\n"
                + "    mOwnerUid=10227 showForAllUsers=false package=com.tencent.mm appop=NONE\n"
                + "  Window #1 Window{a23c u0 Payment result}:\n"
                + "    mDisplayId=0 taskId=50 mSession=def\n"
                + "    mOwnerUid=10346 showForAllUsers=false package=" + PAY + " appop=NONE\n";
        assertNull(ScreenCapturePolicy.focusedWindow(display, 0));
        assertTrue(ScreenCapturePolicy.hasUnidentifiedFocus(display, 0));
        assertEquals(PAY, ScreenCapturePolicy.focusedWindowFromOwner(display, windows, 0).packageName);
        assertNull(ScreenCapturePolicy.focusedWindowFromOwner(display, windows.replace("a23c", "c234"), 0));
        assertNull(ScreenCapturePolicy.focusedWindowFromOwner(display, windows.replace("10346", "1010346"), 0));
        assertNull(ScreenCapturePolicy.focusedWindowFromOwner(display, windows.replace("0 taskId", "2 taskId"), 0));
        assertNull(ScreenCapturePolicy.focusedWindowFromOwner(display, windows + windows, 0));
    }

    @Test public void focusOnAnotherDisplayCannotBeSubstitutedWithDefaultDisplayActivity() {
        String display = DISPLAY + CURRENT + "  Display: mDisplayId=2\n"
                + "  mTopFocusedDisplayId=2\n";
        assertNull(ScreenCapturePolicy.focusedWindow(display, 0));
        assertFalse(ScreenCapturePolicy.hasUnidentifiedFocus(display, 0));
    }
}
