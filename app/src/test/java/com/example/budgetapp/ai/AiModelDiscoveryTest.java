package com.example.budgetapp.ai;

import org.junit.Test;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class AiModelDiscoveryTest {
    @Test public void newestCredentialsWinEvenIfOldNetworkIgnoresCancellation() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<AiModelDiscovery.Outcome> result = new AtomicReference<>();
        try (AiModelDiscovery discovery = new AiModelDiscovery((url, key) -> {
            if (key.equals("old")) {
                started.countDown();
                boolean waiting = true;
                while (waiting) { try { release.await(); waiting = false; } catch (InterruptedException ignored) { } }
            }
            return Arrays.asList(key + "-model");
        }, Runnable::run)) {
            discovery.request("example.test", "old", value -> callbacks.incrementAndGet());
            assertTrue(started.await(2, TimeUnit.SECONDS));
            discovery.request("example.test", "new", value -> { result.set(value); callbacks.incrementAndGet(); done.countDown(); });
            assertTrue(done.await(2, TimeUnit.SECONDS));
            release.countDown();
            Thread.sleep(80);
            assertEquals(Arrays.asList("new-model"), result.get().models);
            assertEquals(1, callbacks.get());
        } finally { release.countDown(); }
    }
    @Test public void invalidationAndCloseSuppressAlreadyQueuedCallbacks() throws Exception {
        LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        AtomicInteger delivered = new AtomicInteger();
        try (AiModelDiscovery discovery = new AiModelDiscovery((url, key) -> Arrays.asList("fixture"), callbacks::add)) {
            discovery.request("example.test", "key", result -> delivered.incrementAndGet());
            Runnable first = callbacks.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            discovery.invalidate(); first.run();
            discovery.request("example.test", "key", result -> delivered.incrementAndGet());
            Runnable second = callbacks.poll(2, TimeUnit.SECONDS);
            assertNotNull(second);
            discovery.close(); second.run();
            assertEquals(0, delivered.get());
        }
    }
    @Test public void failureNeverExposesUrlOrKeyAndRetryCanSucceed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LinkedBlockingQueue<AiModelDiscovery.Outcome> results = new LinkedBlockingQueue<>();
        try (AiModelDiscovery discovery = new AiModelDiscovery((url, key) -> {
            if (calls.incrementAndGet() == 1) throw new java.io.IOException("SECRET_KEY_ECHO");
            return Arrays.asList("recovered");
        }, Runnable::run)) {
            discovery.request("example.test", "key", results::add);
            AiModelDiscovery.Outcome error = results.poll(2, TimeUnit.SECONDS);
            assertNotNull(error); assertEquals(AiModelCatalog.Failure.NETWORK, error.failure); assertTrue(error.models.isEmpty());
            discovery.request("example.test", "key", results::add);
            AiModelDiscovery.Outcome success = results.poll(2, TimeUnit.SECONDS);
            assertNotNull(success); assertNull(success.failure); assertEquals(Arrays.asList("recovered"), success.models);
        }
    }
}
