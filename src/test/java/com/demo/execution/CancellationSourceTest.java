package com.demo.execution;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CancellationSourceTest {
    @Test void cancellationIsIdempotentAndRegistrationIsDisposable() {
        CancellationSource source = new CancellationSource();
        AtomicInteger calls = new AtomicInteger();
        source.token().onCancellation(calls::incrementAndGet);
        var removed = source.token().onCancellation(() -> calls.addAndGet(100));
        removed.close();
        assertTrue(source.cancel());
        assertFalse(source.cancel());
        source.token().onCancellation(calls::incrementAndGet);
        assertEquals(2, calls.get());
        assertThrows(OperationCancelledException.class, source.token()::throwIfCancellationRequested);
        assertThrows(OperationCancelledException.class, () -> source.token().runIfActive(() -> calls.addAndGet(100)));
    }

    @Test void concurrentRegistrationAndCancellationInvokeEachCallbackExactlyOnce() throws Exception {
        CancellationSource source = new CancellationSource();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var registrations = pool.submit(() -> {
                await(start);
                for (int i = 0; i < 200; i++) source.token().onCancellation(calls::incrementAndGet);
            });
            var cancelling = pool.submit(() -> { await(start); source.cancel(); });
            start.countDown();
            registrations.get(3, TimeUnit.SECONDS);
            cancelling.get(3, TimeUnit.SECONDS);
            assertEquals(200, calls.get());
        } finally { pool.shutdownNow(); }
    }

    @Test void failingCallbackDoesNotPreventOtherResourcesFromBeingCancelled() {
        CancellationSource source = new CancellationSource();
        AtomicInteger calls = new AtomicInteger();
        source.token().onCancellation(() -> { throw new IllegalStateException("failed cleanup"); });
        source.token().onCancellation(calls::incrementAndGet);
        source.cancel();
        assertEquals(1, calls.get());
        assertEquals(1, source.callbackFailures().size());
    }

    @Test void failedResourceSignallingCanBeRetriedWithoutReinvokingSuccessfulCallbacks() {
        CancellationSource source = new CancellationSource();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger successful = new AtomicInteger();
        source.token().onCancellation(() -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("temporary failure");
        });
        source.token().onCancellation(successful::incrementAndGet);
        source.cancel();
        assertEquals(1, source.callbackFailures().size());
        source.retryFailedCallbacks();
        assertTrue(source.callbackFailures().isEmpty());
        assertEquals(2, attempts.get());
        assertEquals(1, successful.get());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
}
