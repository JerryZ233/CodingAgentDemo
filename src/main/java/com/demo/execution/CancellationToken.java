package com.demo.execution;

/** Shared cancellation contract, independent of sessions, providers and tools. */
public interface CancellationToken {
    boolean isCancellationRequested();
    Registration onCancellation(Runnable action);

    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) throw new OperationCancelledException();
    }

    /** Atomically admits a short operation (never use for IO or waiting). */
    default void runIfActive(Runnable action) {
        throwIfCancellationRequested();
        action.run();
    }

    static CancellationToken none() { return None.INSTANCE; }

    @FunctionalInterface
    interface Registration extends AutoCloseable {
        @Override void close();
    }

    enum None implements CancellationToken {
        INSTANCE;
        public boolean isCancellationRequested() { return false; }
        public Registration onCancellation(Runnable action) { return () -> { }; }
    }
}
