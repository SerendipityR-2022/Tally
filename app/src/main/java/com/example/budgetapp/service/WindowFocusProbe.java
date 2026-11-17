package com.example.budgetapp.service;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import com.example.budgetapp.util.ScreenCapturePolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.SystemServiceHelper;

/** Authoritative scope check in the Shizuku process. Never returns a window outside this Android user. */
final class WindowFocusProbe {
    private static final int MAX_DUMP_BYTES = 524288;
    private static final int TIMEOUT_MS = 2500;
    private IBinder windowManager;
    private boolean legacyTransport;

    ScreenCapturePolicy.FocusedWindow read(int userId, boolean resolveOwner) {
        return read(userId, resolveOwner, null);
    }

    String transport() { return legacyTransport ? "dumpsys fallback" : "WindowManager Binder"; }

    ScreenCapturePolicy.FocusedWindow read(int userId, boolean resolveOwner, DiagnosticTrace trace) {
        String displays = dump("displays", trace);
        ScreenCapturePolicy.FocusedWindow focus = ScreenCapturePolicy.focusedWindow(displays, userId);
        if (focus != null || !resolveOwner || !ScreenCapturePolicy.hasUnidentifiedFocus(displays, userId)) return focus;
        if (trace != null) trace.event("reader.focus.resolve_owner", "Looking up owner of default display focused window");
        return ScreenCapturePolicy.focusedWindowFromOwner(displays, dump("windows", trace), userId);
    }

    private String dump(String query, DiagnosticTrace trace) {
        if (!legacyTransport) {
            try {
                String result = binderDump(query);
                if (!result.isEmpty() && !result.startsWith("Permission Denial")) return result;
                if (trace != null) trace.event("reader.focus.binder_dump", "Empty or denied response for " + query);
            } catch (Exception e) {
                // ROMs may refuse a pipe passed to the WindowManager Binder.
                if (trace != null) trace.error("reader.focus.binder_dump." + query, e);
            }
            legacyTransport = true;
            if (trace != null) trace.event("reader.focus.transport", "Using dumpsys fallback");
        }
        return legacyDump(query);
    }

    private String binderDump(String query) throws Exception {
        if (windowManager == null) windowManager = SystemServiceHelper.getSystemService("window");
        if (windowManager == null) throw new IOException("Missing WindowManager binder");
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try (ParcelFileDescriptor readEnd = pipe[0]; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            try (ParcelFileDescriptor writeEnd = pipe[1]) {
                windowManager.dumpAsync(writeEnd.getFileDescriptor(), new String[]{query});
            }
            StructPollfd descriptor = new StructPollfd();
            descriptor.fd = readEnd.getFileDescriptor();
            descriptor.events = (short) (OsConstants.POLLIN | OsConstants.POLLHUP);
            StructPollfd[] descriptors = {descriptor};
            byte[] buffer = new byte[8192];
            long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
            while (true) {
                int remaining = (int) (deadline - SystemClock.uptimeMillis());
                if (remaining <= 0 || Os.poll(descriptors, remaining) == 0) {
                    throw new IOException("WindowManager dump timed out");
                }
                if ((descriptor.revents & (OsConstants.POLLIN | OsConstants.POLLHUP)) == 0) {
                    throw new IOException("WindowManager dump pipe failed");
                }
                int count = Os.read(descriptor.fd, buffer, 0, buffer.length);
                if (count <= 0) break;
                append(output, buffer, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void append(ByteArrayOutputStream output, byte[] bytes, int count) throws IOException {
        if (output.size() + count > MAX_DUMP_BYTES) throw new IOException("Window dump too large");
        output.write(bytes, 0, count);
    }

    private static String legacyDump(String query) {
        java.lang.Process process = null;
        FutureTask<String> output = null;
        try {
            process = new ProcessBuilder("/system/bin/dumpsys", "window", query).redirectErrorStream(true).start();
            final java.lang.Process running = process;
            output = new FutureTask<>(() -> {
                try (InputStream stream = running.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) != -1) append(bytes, buffer, count);
                    return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                }
            });
            Thread reader = new Thread(output, "TallyWindowDump");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("Window inspection timed out or was denied");
            }
            return output.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Window inspection unavailable", e);
        } finally {
            if (process != null) process.destroyForcibly();
            if (output != null) output.cancel(true);
        }
    }
}
