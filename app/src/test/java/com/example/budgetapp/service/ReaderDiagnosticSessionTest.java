package com.example.budgetapp.service;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReaderDiagnosticSessionTest {
    @Test public void lastAcceptedFrameRemainsAvailableWhenHostReturnsAfterDeadline() {
        ReaderDiagnosticSession run = new ReaderDiagnosticSession(1, "com.example.demo", 2000);
        run.recordFrame("checkout", 1500);
        run.recordFrame("payment-success", 1800);
        assertFalse(run.active(2000));
        run.recordFrame("late-frame", 2000);
        assertEquals(2, run.frame);
        assertEquals("payment-success", run.frameAfter(0));
        assertEquals("payment-success", run.frameAfter(1));
        assertNull(run.frameAfter(2));
    }

    @Test public void screenLockStopsCaptureAndDoesNotOverwriteThePriorFrame() {
        ReaderDiagnosticSession run = new ReaderDiagnosticSession(1, "com.example.demo", 2000);
        run.recordFrame("read-before-lock", 1000);
        run.stop("screen locked", true);
        run.stop("time expired", false);
        run.recordFrame("read-after-lock", 1500);
        assertTrue(run.isLocked());
        assertFalse(run.active(1500));
        assertEquals("read-before-lock", run.frameAfter(0));
    }

    @Test public void readerRejectsExpiredOrUnboundedRequests() {
        assertTrue(ReaderDiagnosticSession.validTime(91000, 1000));
        assertFalse(ReaderDiagnosticSession.validTime(91001, 1000));
        assertFalse(ReaderDiagnosticSession.validTime(1000, 1000));
        assertFalse(ReaderDiagnosticSession.validTime(900, 1000));
    }

    @Test public void extraNodeQueriesAreLimitedInTimeAndCount() {
        ReaderDiagnosticSession run = new ReaderDiagnosticSession(1, "com.tencent.mm", 90000);
        assertTrue(run.claimDeepProbe(1000));
        assertFalse(run.claimDeepProbe(5999));
        assertTrue(run.claimDeepProbe(6000));
        assertFalse(run.claimDeepProbe(6001));
        assertTrue(run.claimDeepProbe(11000));
        assertFalse(run.claimDeepProbe(20000));
        assertEquals(3, run.deepProbeCount());
        run.stop("user", false);
        assertFalse(run.claimDeepProbe(25000));
        ReaderDiagnosticSession expired = new ReaderDiagnosticSession(2, "com.tencent.mm", 5000);
        assertFalse(expired.claimDeepProbe(5000));
    }

    @Test public void nodeOutcomesSurviveStopAndDoNotAccumulateAcrossTreeReads() {
        ReaderDiagnosticSession run = new ReaderDiagnosticSession(1, "com.tencent.mm", 90000);
        run.beginTreeRead();
        run.nodeResult("active", "root=NOT_VISIBLE");
        run.nodeResult("focused_window", "root=NOT_VISIBLE");
        assertEquals("active: root=NOT_VISIBLE; focused_window: root=NOT_VISIBLE", run.nodeSummary());
        run.beginTreeRead();
        run.nodeResult("active", "root=DATA_SENSITIVE");
        run.stop("user", false);
        assertEquals("active: root=DATA_SENSITIVE", run.nodeSummary());
    }

    @Test public void eventSamplesAreBoundedButTotalsRemainAccurateAndStopWithTheSession() {
        ReaderDiagnosticSession run = new ReaderDiagnosticSession(1, "com.tencent.mm", 90000);
        for (int i = 0; i < 20; ++i) run.event(1000 + i, 32, 4, 0, "android.view.View");
        run.event(2000, 2048, 4, 1, "android.view.View");
        run.stop("user", false);
        run.event(2100, 2048, 4, 1, "event-after-stop");
        String trace = run.trace.content();
        assertEquals(12, trace.split("reader.events.sample", -1).length - 1);
        assertTrue(trace.contains("delivered=21"));
        assertTrue(trace.contains("32=20"));
        assertTrue(trace.contains("2048=1"));
        assertFalse(trace.contains("event-after-stop"));
    }
}
