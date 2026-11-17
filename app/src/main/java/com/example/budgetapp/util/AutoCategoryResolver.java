package com.example.budgetapp.util;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Bounded, single-flight AI classification with an exactly-once local fallback. */
public final class AutoCategoryResolver implements AutoCloseable {
    public interface AiProvider {
        AutoCategoryClassifier.Result classify() throws Exception;
    }

    private final Executor callbackExecutor;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "screen-category-ai");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "screen-category-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Set<Handle> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public AutoCategoryResolver(Executor callbackExecutor) {
        this.callbackExecutor = callbackExecutor;
    }

    public final class Handle {
        private final AtomicBoolean delivered = new AtomicBoolean();
        private volatile boolean cancelled;
        private volatile ScheduledFuture<?> timeout;
        private final Consumer<AutoCategoryClassifier.Result> callback;

        private Handle(Consumer<AutoCategoryClassifier.Result> callback) {
            this.callback = callback;
        }

        private void deliver(AutoCategoryClassifier.Result result) {
            if (!delivered.compareAndSet(false, true)) return;
            if (timeout != null) timeout.cancel(false);
            pending.remove(this);
            callbackExecutor.execute(() -> {
                if (!closed && !cancelled) callback.accept(result);
            });
        }

        public void cancel() {
            cancelled = true;
            delivered.set(true);
            if (timeout != null) timeout.cancel(false);
            pending.remove(this);
        }
    }

    public Handle resolve(List<AutoCategoryClassifier.Category> catalog, AutoCategoryClassifier.Result local,
            boolean useSubCategories, boolean enableAi, AiProvider provider,
            Consumer<AutoCategoryClassifier.Result> callback) {
        return resolve(catalog, local, useSubCategories, enableAi, provider, TimeUnit.SECONDS.toMillis(AutoCategoryManager.DEFAULT_AI_TIMEOUT_SECONDS), callback);
    }

    public Handle resolve(List<AutoCategoryClassifier.Category> catalog, AutoCategoryClassifier.Result local,
            boolean useSubCategories, boolean enableAi, AiProvider provider, long deadlineMs,
            Consumer<AutoCategoryClassifier.Result> callback) {
        Handle handle = new Handle(callback);
        if (closed) { handle.cancel(); return handle; }
        pending.add(handle);
        if (local.source == AutoCategoryClassifier.Source.LEARNED || !enableAi
                || !busy.compareAndSet(false, true)) {
            handle.deliver(local);
            return handle;
        }
        long budgetMs = Math.max(1, deadlineMs);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        handle.timeout = timer.schedule(() -> handle.deliver(local), budgetMs, TimeUnit.MILLISECONDS);
        worker.execute(() -> {
            AutoCategoryClassifier.Result resolved = local;
            try {
                if (!closed && !handle.cancelled && System.nanoTime() - deadline < 0) {
                    AutoCategoryClassifier.Result ai = provider.classify();
                    if (ai != null) resolved = AutoCategoryClassifier.applyAi(catalog, local,
                            ai.category, ai.subCategory, useSubCategories);
                }
            } catch (Exception ignored) {
                // Do not log bill data/API credentials, notify, or interrupt accounting.
            } finally {
                busy.set(false);
                handle.deliver(System.nanoTime() - deadline < 0 ? resolved : local);
            }
        });
        return handle;
    }

    @Override public void close() {
        closed = true;
        for (Handle handle : pending) handle.cancel();
        worker.shutdownNow();
        timer.shutdownNow();
    }
}
