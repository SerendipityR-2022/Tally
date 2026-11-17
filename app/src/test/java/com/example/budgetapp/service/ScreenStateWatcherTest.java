package com.example.budgetapp.service;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenStateWatcherTest {
    private static final class Clock implements ScreenStateWatcher.Scheduler {
        long now, due;
        Runnable pending;
        @Override public long uptimeMillis() { return now; }
        @Override public void post(Runnable task, int delay) {
            assertNull("Recovery checks must be coalesced", pending);
            pending = task; due = now + delay;
        }
        @Override public void remove(Runnable task) { if (pending == task) pending = null; }
        void runNext() {
            assertNotNull(pending);
            now = due;
            Runnable run = pending; pending = null; run.run();
        }
    }
    private static final class Rig {
        final Clock clock = new Clock();
        boolean interactive, locked = true, error;
        int failures, reads;
        final List<Boolean> allowed = new ArrayList<>();
        final ScreenStateWatcher watcher = new ScreenStateWatcher(() -> {
            reads++;
            if (error) throw new IllegalStateException("power service unavailable");
            return new ScreenStateWatcher.State(interactive, locked);
        }, clock, new ScreenStateWatcher.Observer() {
            @Override public void onState(ScreenStateWatcher.State state, String reason) { allowed.add(state.available()); }
            @Override public void onError(RuntimeException failure) { failures++; }
        });
        void start() { watcher.start(); clock.runNext(); }
        void wakeSignal() { watcher.signal("USER_PRESENT"); clock.runNext(); }
        boolean last() { return allowed.get(allowed.size()-1); }
    }

    @Test public void unlockBroadcastBeforeKeyguardClearsRecoversWithoutAnotherBroadcast() {
        Rig r = new Rig(); r.start();
        r.interactive = true;
        r.wakeSignal();
        assertFalse(r.last());
        assertEquals(ScreenStateWatcher.UNLOCK_RETRY_MS, r.clock.due-r.clock.now);
        r.clock.runNext();
        r.locked = false;
        r.clock.runNext();
        assertTrue(r.last()); assertNull(r.clock.pending);
    }
    @Test public void lostWakeSignalsStillRecoverThroughThePausedWatchdog() {
        Rig r = new Rig(); r.start();
        assertEquals(ScreenStateWatcher.SUSPENDED_CHECK_MS, r.clock.due-r.clock.now);
        r.interactive = true; r.locked = false;
        r.clock.runNext();
        assertTrue(r.last()); assertNull(r.clock.pending);
    }
    @Test public void lateScreenOffSignalCannotOverrideCurrentUnlockedState() {
        Rig r = new Rig(); r.interactive = true; r.locked = false; r.start();
        r.watcher.signal("SCREEN_OFF"); r.clock.runNext();
        assertTrue(r.last()); assertNull(r.clock.pending);
    }
    @Test public void availableScreenAddsNoPermanentPoller() {
        Rig r = new Rig(); r.interactive = true; r.locked = false; r.start();
        assertTrue(r.last()); assertNull(r.clock.pending); assertEquals(1, r.reads);
    }
    @Test public void wakeSignalSupersedesAnOverdueCheckAfterLongProcessSuspension() {
        Rig r = new Rig(); r.start();
        r.clock.now += 6 * 60 * 60 * 1000; // The process did not dispatch its old pending callback.
        r.interactive = true; r.locked = false;
        r.watcher.signal("display_state=ON");
        assertEquals(r.clock.now, r.clock.due);
        r.clock.runNext();
        assertTrue(r.last()); assertEquals(2,r.reads); assertNull(r.clock.pending);
    }
    @Test public void aLongLockedScreenBacksOffButKeepsCheckingForUnlock() {
        Rig r = new Rig(); r.interactive = true; r.start();
        while (r.clock.now < ScreenStateWatcher.UNLOCK_SETTLE_MS) {
            assertFalse(r.last()); r.clock.runNext();
        }
        assertEquals(ScreenStateWatcher.LOCKED_CHECK_MS,r.clock.due-r.clock.now);
        r.locked = false; r.clock.runNext(); assertTrue(r.last());
    }
    @Test public void fullyOffScreenUsesOnlyTheLowFrequencyFallback() {
        Rig r = new Rig(); r.start();
        for(int i=0;i<6;i++) {
            assertFalse(r.last());
            assertEquals(ScreenStateWatcher.SUSPENDED_CHECK_MS,r.clock.due-r.clock.now);
            r.clock.runNext();
        }
        assertEquals(7,r.reads);
    }
    @Test public void stopCancelsRecoveryAndLaterSignalsCannotRestartIt() {
        Rig r = new Rig(); r.start();
        r.watcher.stop(); r.watcher.signal("USER_PRESENT");
        assertNull(r.clock.pending); assertEquals(1,r.reads);
    }
    @Test public void stateQueryFailureStaysClosedAndCanRecoverLater() {
        Rig r = new Rig(); r.error = true; r.start();
        assertTrue(r.allowed.isEmpty()); assertEquals(1,r.failures);
        assertEquals(ScreenStateWatcher.SUSPENDED_CHECK_MS,r.clock.due-r.clock.now);
        r.error = false; r.interactive = true; r.locked = false;
        r.wakeSignal(); assertTrue(r.last());
    }
    @Test public void repeatedWakeSignalsHaveOnlyOnePendingCheck() {
        Rig r = new Rig(); r.start();
        r.watcher.signal("SCREEN_ON"); r.watcher.signal("USER_PRESENT"); r.watcher.signal("display_state=ON");
        r.interactive = true; r.locked = false; r.clock.runNext();
        assertEquals(2,r.reads); assertTrue(r.last());
    }
}
