package com.example.budgetapp.ai;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Lifecycle-safe, latest-request-wins discovery. Results/credentials are never persisted. */
public final class AiModelDiscovery implements AutoCloseable {
    public interface Loader { List<String> load(String baseUrl, String apiKey) throws Exception; }
    public static final class Outcome {
        public final List<String> models;
        public final AiModelCatalog.Failure failure;
        private Outcome(List<String> models, AiModelCatalog.Failure failure) {
            this.models = models; this.failure = failure;
        }
    }

    private final Loader loader;
    private final Executor callbackExecutor;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "ai-model-discovery");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean closed;
    private Future<?> task;

    public AiModelDiscovery(Loader loader, Executor callbackExecutor) {
        this.loader = loader; this.callbackExecutor = callbackExecutor;
    }

    public synchronized void request(String baseUrl, String apiKey, Consumer<Outcome> callback) {
        invalidate();
        if (closed) return;
        long request = generation.get();
        task = worker.submit(() -> {
            Outcome outcome;
            try { outcome = new Outcome(Collections.unmodifiableList(new java.util.ArrayList<>(loader.load(baseUrl, apiKey))), null); }
            catch (AiModelCatalog.CatalogException e) { outcome = new Outcome(Collections.emptyList(), e.failure); }
            catch (Exception e) { outcome = new Outcome(Collections.emptyList(), AiModelCatalog.Failure.NETWORK); }
            final Outcome result = outcome;
            callbackExecutor.execute(() -> {
                if (!closed && generation.get() == request) callback.accept(result);
            });
        });
    }

    public synchronized void invalidate() {
        generation.incrementAndGet();
        if (task != null) task.cancel(true);
        task = null;
    }

    @Override public synchronized void close() {
        closed = true;
        invalidate();
        worker.shutdownNow();
    }
}
