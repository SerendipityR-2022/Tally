package com.example.budgetapp.service;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** App-scoped, append-only diagnostic log. Offsets are UTF-16 units, as in a Binder String. */
final class DiagnosticTrace {
    static final int READER_LIMIT = 1024 * 1024;
    static final int CHUNK_CHARS = 16 * 1024;
    private static final String LIMIT_NOTICE = "[Log limit reached. Later entries were omitted.]\n";
    private final int maxChars;
    private final StringBuilder text = new StringBuilder();
    private final Map<String, Integer> stackIds = new HashMap<>();
    private boolean full;

    DiagnosticTrace(int maxChars) { this.maxChars = maxChars; }

    synchronized void event(String stage, String message) { append(entry(stage, message)); }

    synchronized void error(String stage, Throwable failure) {
        if (full) return;
        // Logcat may shorten exceptions; keep the message, all frames, causes and suppressed errors.
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        String stack = writer.toString();
        Integer seen = stackIds.get(stack);
        if (seen != null) {
            append(entry(stage, "ERROR (same stack as #" + seen + ")"));
        } else {
            int id = stackIds.size() + 1;
            if (append(entry(stage, "ERROR #" + id) + stack)) stackIds.put(stack, id);
        }
    }

    private static String entry(String stage, String message) {
        return Instant.now() + " [" + stage + "] " + message + '\n';
    }

    private boolean append(String entry) {
        if (full) return false;
        if (entry.length() > maxChars - LIMIT_NOTICE.length() - text.length()) {
            text.append(LIMIT_NOTICE);
            full = true;
            return false;
        }
        text.append(entry);
        return true;
    }

    synchronized int length() { return text.length(); }
    synchronized String content() { return text.toString(); }

    synchronized String chunk(int offset) {
        if (offset < 0 || offset >= text.length()) return null;
        return text.substring(offset, Math.min(offset + CHUNK_CHARS, text.length()));
    }

    interface ChunkSource { String read(int offset) throws Exception; }

    /** Read exactly the advertised prefix. The remote may append while a live report is fetched. */
    static void readDelta(int offset, int end, StringBuilder out, ChunkSource source) throws Exception {
        if (offset < 0 || end < offset || end > READER_LIMIT) throw new IOException("Invalid reader log length: " + offset + ".." + end);
        while (offset < end) {
            String chunk = source.read(offset);
            if (chunk == null || chunk.isEmpty() || chunk.length() > CHUNK_CHARS) {
                throw new IOException("Invalid reader log chunk at " + offset + " of " + end);
            }
            int count = Math.min(end - offset, chunk.length());
            out.append(chunk, 0, count);
            offset += count;
        }
    }
}
