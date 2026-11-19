package com.example.budgetapp.service;

import org.junit.Test;

import static com.example.budgetapp.service.ScreenSampleCadence.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScreenSampleCadenceTest {
    @Test public void newForegroundHintTriggersImmediateAuthoritativeFocusCheck() {
        assertEquals(0, focusDelay(-1, 500, true, false, false, false));
        assertEquals(0, focusDelay(480, 500, true, true, true, false));
        assertEquals(0, focusDelay(480, 500, true, true, false, true));
    }

    @Test public void quietNonTargetUsesCheapProbesBetweenFocusChecks() {
        assertEquals(IDLE_PROBE_MS, nextDelay(IDLE_PROBE_MS,
                focusDelay(0, 650, true, false, false, false)));
        assertEquals(0, focusDelay(0, 1800, true, false, false, false));
    }

    @Test public void missingHintOrTargetBehindAnotherWindowStillHasFallback() {
        assertEquals(50, focusDelay(0, 1150, false, false, false, false));
        assertEquals(0, focusDelay(0, 1200, false, false, false, false));
        assertEquals(100, focusDelay(0, 350, true, false, true, false));
        assertEquals(0, focusDelay(0, 450, true, false, true, false));
    }

    @Test public void changesAfterQuietPeriodCanReadImmediatelyButBurstsAreBounded() {
        assertEquals(0, treeDelay(-1, 50, false));
        assertEquals(150, treeDelay(0, 500, true));
        assertEquals(0, treeDelay(0, 650, true));
        assertEquals(0, treeDelay(0, 1500, true));
    }

    @Test public void sameRevisionDoesNotReadUntilRecoverySampleIsDue() {
        assertEquals(2500, treeDelay(0, 1500, false));
        assertEquals(0, treeDelay(0, 4000, false));
    }

    @Test public void scheduledProbeDoesNotOvershootShortDeadlinesOrSpin() {
        assertEquals(150, nextDelay(TARGET_PROBE_MS, 150));
        assertEquals(EVENT_SETTLE_MS, nextDelay(TARGET_PROBE_MS, 0));
        assertEquals(TARGET_PROBE_MS, nextDelay(TARGET_PROBE_MS, Long.MAX_VALUE));
    }

    @Test public void taskNotificationsPermitIdleSleepButOverlaysStillHaveRecovery() {
        assertEquals(30000, probeDelay(true, true, false, false));
        assertEquals(30000, probeDelay(true, true, true, true));
        assertEquals(450, probeDelay(true, true, true, false));
        assertEquals(450, probeDelay(false, true, false, false));
        assertEquals(800, probeDelay(false, true, true, true));
        assertEquals(1200, probeDelay(false, false, false, false));
        assertEquals(1200, probeDelay(true, false, false, false));
    }

    @Test public void staticPagesBackOffButAnyContentEventBypassesBackoff() {
        assertEquals(2500, treeDelay(0, 1500, false, 0, 0));
        assertEquals(6500, treeDelay(0, 1500, false, 1, 0));
        assertEquals(14500, treeDelay(0, 1500, false, 2, 0));
        assertEquals(14500, treeDelay(0, 1500, false, 100, 0));
        assertEquals(0, treeDelay(0, 1500, true, 100, 0));
        assertEquals(150, treeDelay(0, 500, true, 2, 0));
        assertEquals(0, treeDelay(-1, 500, false, 2, 0));
    }

    @Test public void unreadableTreesRetryWithoutPermanentFastPolling() {
        assertEquals(650, treeDelay(100, 100, false, 0, 1));
        assertEquals(1300, treeDelay(100, 100, false, 0, 2));
        assertEquals(2600, treeDelay(100, 100, false, 0, 3));
        assertEquals(4000, treeDelay(100, 100, false, 0, 100));
        assertEquals(0, treeDelay(0, 1000, true, 0, 100));
        assertEquals(150, treeDelay(0, 500, true, 0, 100));
    }

    @Test public void freshWindowsGetOneFastFollowUpWithoutSpeedingUpQuietPolling() {
        assertEquals(80, treeDelay(0, 100, true, 0, 0, true));
        assertEquals(0, treeDelay(0, 180, true, 0, 0, true));
        assertEquals(80, treeDelay(0, 100, false, 0, 1, true));
        assertEquals(3900, treeDelay(0, 100, false, 0, 0, true));
        assertEquals(470, treeDelay(0, 180, true, 0, 0, false));
        assertEquals(0, treeDelay(-1, 100, true, 0, 0, true));
    }

    @Test public void contentBurstsShareTheNextEligibleTreeReadWithoutAddingAnEarlyProbe() {
        TreeTiming timing = new TreeTiming(1000, -1);
        assertEquals(1650, timing.contentSampleAt(1001));
        assertEquals(1650, timing.contentSampleAt(1200));
        assertEquals(1650, timing.contentSampleAt(1500));
        assertEquals(1770, timing.contentSampleAt(1700));
        assertEquals(1070, TreeTiming.EMPTY.contentSampleAt(1000));
    }

    @Test public void aFirstFrameCanFollowUpFastOnlyWhileItsWindowIsStillSettling() {
        TreeTiming timing = new TreeTiming(500, 1200);
        assertEquals(680, timing.contentSampleAt(510));
        assertEquals(970, timing.contentSampleAt(900));
        TreeTiming lateFirstFrame = new TreeTiming(1100, 1200);
        assertEquals(1750, lateFirstFrame.contentSampleAt(1110));
    }

    @Test public void steadyRefreshesReuseTheTaskHintButWindowChangesAndFallbacksRequery() {
        assertFalse(needsForegroundHint(true, "payment/.Receipt", false, 0, 650, true));
        assertTrue(needsForegroundHint(true, "payment/.Receipt", true, 0, 650, true));
        assertTrue(needsForegroundHint(true, "payment/.Receipt", false, 0, 650, false));
        assertTrue(needsForegroundHint(false, "payment/.Receipt", false, 0, 650, true));
        assertTrue(needsForegroundHint(true, null, false, 0, 650, true));
        assertFalse(needsForegroundHint(true, "", false, 0, IDLE_WATCHDOG_MS - 1, false));
        assertTrue(needsForegroundHint(true, "", false, 0, IDLE_WATCHDOG_MS, false));
    }
}
