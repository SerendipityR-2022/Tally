package com.example.budgetapp.service;

/** Timing and transport policy for the experimental reader (monotonic uptime milliseconds). */
final class ScreenSampleCadence {
    static final int IDLE_PROBE_MS = 450;
    static final int TARGET_PROBE_MS = 800;
    static final int EVENT_SETTLE_MS = 70;
    static final int ERROR_RETRY_MS = 1000;
    static final int MIN_TREE_INTERVAL_MS = 650;
    static final int INITIAL_TREE_INTERVAL_MS = 180;
    static final int WINDOW_SETTLE_MS = 1200;
    static final int QUIET_TREE_INTERVAL_MS = 4000;
    static final int FOCUS_FALLBACK_MS = 1200;
    static final int IDLE_WATCHDOG_MS = 30000;
    static final int MAX_QUIET_TREE_INTERVAL_MS = 16000;
    static final int MAX_UNREADABLE_INTERVAL_MS = 4000;
    private static final int FOCUS_RECHECK_MS = 1800;

    static final String STATE = "state";
    static final String PACKAGE = "package";
    static final String HIERARCHY = "hierarchy";
    static final String DELAY = "delay";
    static final int IDLE = 0, TARGET = 1, UNREADABLE = 2, PAUSED = 3;

    private ScreenSampleCadence() { }

    /** Immutable timing shared by the sampler and the accessibility event thread. */
    static final class TreeTiming {
        static final TreeTiming EMPTY = new TreeTiming(-1, -1);
        final long lastReadAt;
        private final long initialRetryUntil;

        TreeTiming(long lastReadAt, long initialRetryUntil) {
            this.lastReadAt = lastReadAt;
            this.initialRetryUntil = initialRetryUntil;
        }

        boolean allowsInitialRetry(long now) {
            return now < initialRetryUntil;
        }

        long contentSampleAt(long now) {
            long settledAt = now + EVENT_SETTLE_MS;
            if (lastReadAt < 0) return settledAt;
            long fastAt = Math.max(settledAt, lastReadAt + INITIAL_TREE_INTERVAL_MS);
            if (allowsInitialRetry(fastAt)) return fastAt;
            return Math.max(settledAt, lastReadAt + MIN_TREE_INTERVAL_MS);
        }
    }

    static boolean needsForegroundHint(boolean taskEvents, String lastHint, boolean taskChanged,
            long lastFocusAt, long now, boolean verifiedTarget) {
        return !taskEvents || lastHint == null || taskChanged || lastFocusAt < 0
                || now - lastFocusAt >= IDLE_WATCHDOG_MS
                || (!lastHint.isEmpty() && !verifiedTarget);
    }

    static long treeDelay(long lastTreeAt, long now, boolean changed) {
        return treeDelay(lastTreeAt, now, changed, 0, 0);
    }

    static long treeDelay(long lastTreeAt, long now, boolean changed, int quietSamples, int unreadableSamples) {
        return treeDelay(lastTreeAt, now, changed, quietSamples, unreadableSamples, false);
    }

    static long treeDelay(long lastTreeAt, long now, boolean changed, int quietSamples, int unreadableSamples,
            boolean initialWindowRetry) {
        if (lastTreeAt < 0) return 0;
        long interval = initialWindowRetry && (changed || unreadableSamples > 0) ? INITIAL_TREE_INTERVAL_MS
                : changed ? MIN_TREE_INTERVAL_MS : unreadableSamples > 0
                ? Math.min(MAX_UNREADABLE_INTERVAL_MS, MIN_TREE_INTERVAL_MS << Math.min(3, unreadableSamples - 1))
                : Math.min(MAX_QUIET_TREE_INTERVAL_MS, QUIET_TREE_INTERVAL_MS << Math.min(2, Math.max(0, quietSamples)));
        return Math.max(0, interval - (now - lastTreeAt));
    }

    static int probeDelay(boolean taskEvents, boolean hintAvailable, boolean hintIsTarget, boolean verifiedTarget) {
        if (!hintAvailable) return FOCUS_FALLBACK_MS;
        if (!taskEvents) return verifiedTarget ? TARGET_PROBE_MS : IDLE_PROBE_MS;
        // A task behind an overlay needs a short retry: task events alone cannot see overlay dismissal.
        return hintIsTarget && !verifiedTarget ? IDLE_PROBE_MS : IDLE_WATCHDOG_MS;
    }

    static long focusDelay(long lastFocusAt, long now, boolean hintAvailable, boolean hintChanged,
            boolean hintIsTarget, boolean verifiedTarget) {
        if (lastFocusAt < 0 || hintChanged) return 0;
        // A task is only a scheduling hint, not window ownership (e.g. dialogs or multi-window).
        int interval = !hintAvailable ? FOCUS_FALLBACK_MS
                : hintIsTarget && !verifiedTarget ? IDLE_PROBE_MS : FOCUS_RECHECK_MS;
        return Math.max(0, interval - (now - lastFocusAt));
    }

    static int nextDelay(int normalDelay, long earlierDeadline) {
        return (int) Math.max(EVENT_SETTLE_MS, Math.min(normalDelay, earlierDeadline));
    }
}
