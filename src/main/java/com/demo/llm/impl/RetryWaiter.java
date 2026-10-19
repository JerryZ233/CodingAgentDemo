package com.demo.llm.impl;

import com.demo.execution.CancellationToken;
import com.demo.execution.OperationCancelledException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@FunctionalInterface
interface RetryWaiter {
    void await(long millis, CancellationToken token);

    static RetryWaiter cancellable() {
        return (millis, token) -> {
            CountDownLatch cancelled = new CountDownLatch(1);
            try (var registration = token.onCancellation(cancelled::countDown)) {
                token.throwIfCancellationRequested();
                cancelled.await(millis, TimeUnit.MILLISECONDS);
                token.throwIfCancellationRequested();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OperationCancelledException();
            }
        };
    }
}
