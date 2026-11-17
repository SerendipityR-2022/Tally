package com.example.budgetapp.service;

/** Reconciles actual screen state after wake signals, without wake locks or wake-up alarms. */
final class ScreenStateWatcher {
    static final int UNLOCK_RETRY_MS = 250;
    static final int UNLOCK_SETTLE_MS = 10000;
    static final int LOCKED_CHECK_MS = 2000;
    static final int SUSPENDED_CHECK_MS = 30000;

    static final class State {
        final boolean interactive, locked;
        State(boolean interactive, boolean locked) { this.interactive = interactive; this.locked = locked; }
        boolean available() { return interactive && !locked; }
    }
    interface Source { State read(); }
    interface Scheduler {
        long uptimeMillis();
        void post(Runnable task, int delay);
        void remove(Runnable task);
    }
    interface Observer {
        void onState(State state, String reason);
        void onError(RuntimeException error);
    }

    private final Source source;
    private final Scheduler scheduler;
    private final Observer observer;
    private final Runnable check = this::check;
    private boolean started;
    private long settleUntil;
    private String reason = "start";

    ScreenStateWatcher(Source source, Scheduler scheduler, Observer observer) {
        this.source = source;
        this.scheduler = scheduler;
        this.observer = observer;
    }

    void start() {
        if (started) return;
        started = true;
        signal("reader_ready");
    }

    void signal(String reason) {
        if (!started) return;
        this.reason = reason;
        settleUntil = scheduler.uptimeMillis() + UNLOCK_SETTLE_MS;
        scheduler.remove(check);
        scheduler.post(check, 0);
    }

    private void check() {
        if (!started) return;
        State state;
        try { state = source.read(); }
        catch (RuntimeException error) {
            observer.onError(error);
            if (started) scheduler.post(check, SUSPENDED_CHECK_MS);
            return;
        }
        observer.onState(state, reason);
        if (!started || state.available()) return;
        reason = "paused_recheck";
        // Handler uptime does not advance in deep sleep. This check cannot wake the device.
        int delay = !state.interactive ? SUSPENDED_CHECK_MS
                : scheduler.uptimeMillis() < settleUntil ? UNLOCK_RETRY_MS : LOCKED_CHECK_MS;
        scheduler.post(check, delay);
    }

    void stop() {
        started = false;
        scheduler.remove(check);
    }
}
