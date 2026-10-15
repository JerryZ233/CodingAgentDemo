package com.demo.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Registration, admission and first cancellation share a single short critical section. */
public final class CancellationSource {
    private final Object lock = new Object();
    private boolean cancelled;
    private final List<Callback> callbacks = new ArrayList<>();
    private final List<Failure> failures = new ArrayList<>();
    private final CancellationToken token = new CancellationToken() {
        public boolean isCancellationRequested() { synchronized (lock) { return cancelled; } }
        public Registration onCancellation(Runnable action) {
            Callback callback = new Callback(Objects.requireNonNull(action, "action"));
            synchronized (lock) {
                if (!cancelled) {
                    callbacks.add(callback);
                    return () -> { synchronized (lock) { callbacks.remove(callback); } };
                }
            }
            invoke(callback);
            return () -> { };
        }
        public void runIfActive(Runnable action) {
            synchronized (lock) {
                throwIfCancellationRequested();
                action.run();
            }
        }
    };

    public CancellationToken token() { return token; }
    public boolean cancel() { return cancel(() -> { }); }

    /** onAccepted must only publish a short state/event update, not perform IO. */
    public boolean cancel(Runnable onAccepted) {
        List<Callback> pending;
        synchronized (lock) {
            if (cancelled) return false;
            cancelled = true;
            try { onAccepted.run(); }
            catch (RuntimeException e) { failures.add(new Failure(new Callback(onAccepted), e)); }
            pending = new ArrayList<>(callbacks);
            callbacks.clear();
        }
        pending.forEach(this::invoke);
        return true;
    }

    public List<RuntimeException> callbackFailures() {
        synchronized (lock) { return failures.stream().map(Failure::error).toList(); }
    }

    /** Retry signalling failed resources during service close, not during normal execution. */
    public void retryFailedCallbacks() {
        List<Failure> pending;
        synchronized (lock) { pending = List.copyOf(failures); }
        for (Failure failure : pending) {
            try {
                failure.callback.action.run();
                synchronized (lock) { failures.remove(failure); }
            } catch (RuntimeException ignored) { /* Keep failure so close cannot report false success. */ }
        }
        synchronized (lock) {
            if (!failures.isEmpty()) throw new IllegalStateException("Cancellation resource cleanup is still incomplete");
        }
    }

    private void invoke(Callback callback) {
        try { callback.action.run(); }
        catch (RuntimeException e) { synchronized (lock) { failures.add(new Failure(callback, e)); } }
    }

    private record Callback(Runnable action) { }
    private record Failure(Callback callback, RuntimeException error) { }
}
