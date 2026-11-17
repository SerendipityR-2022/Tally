package com.example.budgetapp.service;

import com.example.budgetapp.util.DiagnosticValue;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, single-package diagnostic state owned by the Shizuku reader. */
final class ReaderDiagnosticSession {
    static final long MAX_DURATION_MS = 90000;
    static final String RUNNING = "running", PROBES = "probes", FOCUSED = "focused", NO_FOCUS = "no_focus",
            UNREADABLE = "unreadable", ERRORS = "errors", FRAME = "frame", LOCKED = "locked",
            TRACE_LENGTH = "trace_length", NODE_SUMMARY = "node_summary";

    final DiagnosticTrace trace = new DiagnosticTrace(DiagnosticTrace.READER_LIMIT);
    private String stage = "reader.start";

    final long id, deadline;
    final String selected;
    int probes, focused, noFocus, unreadable, errors, frame;
    private volatile boolean stopped;
    private boolean locked;
    private int deepProbes, events;
    private long lastDeepProbe = Long.MIN_VALUE;
    private String nodeSummary = "";
    private final Map<Integer, Integer> eventTypes = new LinkedHashMap<>();
    private String xml;

    ReaderDiagnosticSession(long id, String selected, long deadline) {
        this.id = id;
        this.selected = selected;
        this.deadline = deadline;
    }

    static boolean validTime(long deadline, long now) {
        return deadline > now && deadline - now <= MAX_DURATION_MS;
    }

    boolean active(long now) { return !stopped && now < deadline; }
    boolean isLocked() { return locked; }
    synchronized void stop(String reason, boolean locked) {
        if (!stopped) {
            trace.event("reader.events.summary", "delivered=" + events + "; counts=" + eventTypes);
            trace.event("reader.stop", reason);
        }
        stopped = true;
        this.locked |= locked;
    }

    boolean claimDeepProbe(long now) {
        if (!active(now) || deepProbes >= 3 || (deepProbes > 0 && now - lastDeepProbe < 5000)) return false;
        lastDeepProbe = now;
        deepProbes++;
        return true;
    }

    int deepProbeCount() { return deepProbes; }
    void beginTreeRead() { nodeSummary = ""; }
    String nodeSummary() { return nodeSummary; }
    void nodeResult(String route, String outcome) {
        if (!nodeSummary.isEmpty()) nodeSummary += "; ";
        nodeSummary += route + ": " + outcome;
    }

    synchronized void event(long now, int type, int windowId, int changes, CharSequence className) {
        if (!active(now)) return;
        events++;
        eventTypes.put(type, eventTypes.containsKey(type) ? eventTypes.get(type) + 1 : 1);
        if (events <= 12) trace.event("reader.events.sample", "type=0x" + Integer.toHexString(type)
                + "; windowId=" + windowId + "; contentChanges=0x" + Integer.toHexString(changes)
                + "; class=" + DiagnosticValue.of(className));
    }

    void at(String stage) { this.stage = stage; }
    void note(String message) { trace.event(stage, message); }
    void failure(Throwable error) {
        errors++;
        trace.error(stage, error);
    }

    void recordFrame(String hierarchy, long now) {
        if (active(now) && hierarchy != null) {
            xml = hierarchy;
            frame++;
            trace.event("reader.frame", "Accepted frame " + frame + ", XML chars=" + hierarchy.length());
        }
    }

    String frameAfter(int known) { return known < frame ? xml : null; }
}
