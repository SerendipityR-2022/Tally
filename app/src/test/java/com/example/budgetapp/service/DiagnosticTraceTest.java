package com.example.budgetapp.service;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticTraceTest {
    @Test public void failedReadWithoutNodesRetainsStackAndCauseAfterStop() throws Exception {
        ReaderDiagnosticSession reader = new ReaderDiagnosticSession(12, "com.example.receipt", 2000);
        reader.at("reader.automation.connect");
        IllegalStateException cause = new IllegalStateException("root-cause message");
        InvocationTargetException wrapped = new InvocationTargetException(cause, "wrapper message");
        wrapped.addSuppressed(new IOException("cleanup message"));
        reader.failure(wrapped);
        reader.failure(wrapped);
        reader.stop("capture stopped by user", false);
        reader.stop("another stop", false);

        StringBuilder report = new StringBuilder();
        DiagnosticTrace.readDelta(0, reader.trace.length(), report, reader.trace::chunk);
        String log = report.toString();
        assertNull(reader.frameAfter(0));
        assertEquals(2, reader.errors);
        assertTrue(log.contains("[reader.automation.connect] ERROR #1"));
        assertTrue(log.contains("InvocationTargetException: wrapper message"));
        assertTrue(log.contains("Caused by: java.lang.IllegalStateException: root-cause message"));
        assertTrue(log.contains("Suppressed: java.io.IOException: cleanup message"));
        assertTrue(log.contains("DiagnosticTraceTest.failedReadWithoutNodesRetainsStackAndCauseAfterStop"));
        assertTrue(log.contains("ERROR (same stack as #1)"));
        assertTrue(log.contains("capture stopped by user"));
        assertFalse(log.contains("another stop"));
    }

    @Test public void readsMultipleChunksAndDoesNotConsumeEntriesAppendedAfterAdvertisedLength() throws Exception {
        DiagnosticTrace trace = new DiagnosticTrace(DiagnosticTrace.READER_LIMIT);
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < DiagnosticTrace.CHUNK_CHARS; i++) payload.append("\u6d4b\ud83d\udcb3");
        trace.event("reader", payload.toString());
        String firstReport = trace.content();
        trace.event("reader", "another probe");
        StringBuilder received = new StringBuilder();
        DiagnosticTrace.readDelta(0, firstReport.length(), received, trace::chunk);
        assertEquals(firstReport, received.toString());
        DiagnosticTrace.readDelta(received.length(), trace.length(), received, trace::chunk);
        assertEquals(trace.content(), received.toString());
    }

    @Test public void aBrokenTransferCanResumeFromTheLastReceivedChunk() throws Exception {
        DiagnosticTrace trace = new DiagnosticTrace(DiagnosticTrace.READER_LIMIT);
        trace.event("reader", new String(new char[40000]).replace('\0', 'a'));
        StringBuilder received = new StringBuilder();
        try {
            DiagnosticTrace.readDelta(0, trace.length(), received, offset -> offset == 0 ? trace.chunk(0) : null);
            fail("Missing Binder page should be reported");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Invalid reader log chunk"));
        }
        assertEquals(DiagnosticTrace.CHUNK_CHARS, received.length());
        DiagnosticTrace.readDelta(received.length(), trace.length(), received, trace::chunk);
        assertEquals(trace.content(), received.toString());
    }

    @Test public void aSizeLimitIsVisibleRatherThanSilentlyCuttingAnErrorInHalf() {
        DiagnosticTrace trace = new DiagnosticTrace(200);
        trace.event("reader", "started");
        trace.event("reader", new String(new char[300]).replace('\0', 'a'));
        trace.event("reader", "after limit");
        String log = trace.content();
        assertTrue(log.contains("started"));
        assertTrue(log.contains("Log limit reached"));
        assertFalse(log.contains("after limit"));
        assertTrue(log.length() <= 200);
    }
}
