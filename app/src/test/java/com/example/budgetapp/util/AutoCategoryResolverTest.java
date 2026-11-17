package com.example.budgetapp.util;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static com.example.budgetapp.util.AutoCategoryClassifier.*;
import static org.junit.Assert.*;

public class AutoCategoryResolverTest {
    private final List<Category> catalog = Arrays.asList(new Category("购物", Arrays.asList("超市")),
            new Category("餐饮", Arrays.asList("咖啡")));
    private final Result local = new Result("购物", "超市", Source.HINT);

    @Test public void disabledAiNeverCallsProvider() {
        try (AutoCategoryResolver resolver = new AutoCategoryResolver(Runnable::run)) {
            AtomicReference<Result> result = new AtomicReference<>();
            resolver.resolve(catalog, local, true, false, () -> { fail("AI must stay off"); return null; }, result::set);
            assertSame(local, result.get());
        }
    }
    @Test public void providerFailureFallsBackExactlyOnce() throws Exception {
        try (AutoCategoryResolver resolver = new AutoCategoryResolver(Runnable::run)) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Result> result = new AtomicReference<>();
            AtomicInteger callbacks = new AtomicInteger();
            resolver.resolve(catalog, local, true, true, () -> { throw new java.io.IOException("offline"); }, 100,
                    value -> { result.set(value); callbacks.incrementAndGet(); done.countDown(); });
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertSame(local, result.get());
            Thread.sleep(150);
            assertEquals(1, callbacks.get());
        }
    }
    @Test public void validAiResultIsUsed() throws Exception {
        try (AutoCategoryResolver resolver = new AutoCategoryResolver(Runnable::run)) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Result> result = new AtomicReference<>();
            resolver.resolve(catalog, local, true, true, () -> new Result("餐饮", "咖啡", Source.AI),
                    value -> { result.set(value); done.countDown(); });
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(Source.AI, result.get().source);
            assertEquals("咖啡", result.get().subCategory);
        }
    }
    @Test public void invalidAiDestinationFallsBack() throws Exception {
        try (AutoCategoryResolver resolver = new AutoCategoryResolver(Runnable::run)) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Result> result = new AtomicReference<>();
            resolver.resolve(catalog, local, true, true, () -> new Result("新造分类", "", Source.AI),
                    value -> { result.set(value); done.countDown(); });
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertSame(local, result.get());
        }
    }
    @Test public void timeoutFallsBackAndLateSuccessCannotReplaceIt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (AutoCategoryResolver resolver = new AutoCategoryResolver(Runnable::run)) {
            CountDownLatch done = new CountDownLatch(1);
            CountDownLatch aiReturned = new CountDownLatch(1);
            AtomicReference<Result> result = new AtomicReference<>();
            AtomicInteger callbacks = new AtomicInteger();
            resolver.resolve(catalog, local, true, true, () -> {
                release.await();
                aiReturned.countDown();
                return new Result("餐饮", "咖啡", Source.AI);
            }, 40, value -> { result.set(value); callbacks.incrementAndGet(); done.countDown(); });
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertSame(local, result.get());
            release.countDown();
            assertTrue(aiReturned.await(2, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertEquals(1, callbacks.get());
            assertSame(local, result.get());
        } finally { release.countDown(); }
    }
    @Test public void busyWorkerDoesNotQueueAnotherNetworkRequest() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (AutoCategoryResolver resolver = new AutoCategoryResolver(Runnable::run)) {
            CountDownLatch started = new CountDownLatch(1);
            resolver.resolve(catalog, local, true, true, () -> {
                started.countDown(); release.await(); return local;
            }, 3000, value -> { });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            AtomicReference<Result> result = new AtomicReference<>();
            resolver.resolve(catalog, local, true, true, () -> { fail("must not enqueue"); return null; }, result::set);
            assertSame(local, result.get());
        } finally { release.countDown(); }
    }
    @Test public void cancellationAndServiceCloseSuppressLateCallbacks() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AutoCategoryResolver resolver = new AutoCategoryResolver(Runnable::run);
        AtomicInteger callbacks = new AtomicInteger();
        try {
            CountDownLatch started = new CountDownLatch(1);
            AutoCategoryResolver.Handle handle = resolver.resolve(catalog, local, true, true, () -> {
                started.countDown(); release.await(); return local;
            }, 40, value -> callbacks.incrementAndGet());
            assertTrue(started.await(2, TimeUnit.SECONDS));
            handle.cancel();
            resolver.close();
            release.countDown();
            Thread.sleep(100);
            assertEquals(0, callbacks.get());
        } finally { release.countDown(); resolver.close(); }
    }
    @Test public void validAiCanOverrideAnExplicitRule() throws Exception {
        try (AutoCategoryResolver resolver = new AutoCategoryResolver(Runnable::run)) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Result> result = new AtomicReference<>();
            resolver.resolve(catalog, new Result("购物", "超市", Source.RULE), true, true,
                    () -> new Result("餐饮", "咖啡", Source.AI), 300,
                    value -> { result.set(value); done.countDown(); });
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(Source.AI, result.get().source);
        }
    }
    @Test public void enabledAiIsTriedEvenWhenLocalHasMatched() throws Exception {
        for (Source source : Arrays.asList(Source.LOCAL, Source.HINT, Source.LEARNED)) {
            try (AutoCategoryResolver resolver = new AutoCategoryResolver(Runnable::run)) {
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<Result> result = new AtomicReference<>();
                resolver.resolve(catalog, new Result("购物", "超市", source), true, true,
                        () -> new Result("餐饮", "咖啡", Source.AI),
                        value -> { result.set(value); done.countDown(); });
                assertTrue(done.await(2, TimeUnit.SECONDS));
                assertEquals(Source.AI, result.get().source);
            }
        }
    }
    @Test public void aiFirstFailureRetainsRuleInsteadOfDefaultLocalOrder() throws Exception {
        try (AutoCategoryResolver resolver = new AutoCategoryResolver(Runnable::run)) {
            Result rule = new Result("购物", "超市", Source.RULE);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Result> result = new AtomicReference<>();
            resolver.resolve(catalog, rule, true, true,
                    () -> { throw new java.io.IOException("offline"); }, 100,
                    value -> { result.set(value); done.countDown(); });
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertSame(rule, result.get());
        }
    }

}
