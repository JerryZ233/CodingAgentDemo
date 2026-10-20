package com.demo.memory;

import com.demo.execution.*;
import com.demo.session.*;
import com.demo.session.storage.SessionRepository;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** One cancellable scanner. Backoff is distinct from successful organization timestamps. */
public final class MemoryMaintenance {
    private final ScheduledExecutorService executor;
    private final CancellationSource cancellation = new CancellationSource();
    private final SessionRepository sessions;
    private final Clock clock;
    private final Duration interval;
    private final BiConsumer<SessionSnapshot, CancellationToken> organize;
    private final BiConsumer<SessionSnapshot, CancellationToken> consolidate;
    private final Map<String, Failure> failures = new ConcurrentHashMap<>();
    private volatile CancellationSource manual;
    private final Queue<CancellationSource> manualSources = new ConcurrentLinkedQueue<>();
    private record Failure(int attempts, Instant retryAt) { }
    public MemoryMaintenance(SessionRepository sessions, Clock clock, Duration interval, int scanSeconds,
                             BiConsumer<SessionSnapshot, CancellationToken> organize) {
        this(sessions, clock, interval, scanSeconds, organize, true);
    }
    public MemoryMaintenance(SessionRepository sessions, Clock clock, Duration interval, int scanSeconds,
                             BiConsumer<SessionSnapshot, CancellationToken> organize, boolean automatic) {
        this(sessions, clock, interval, scanSeconds, organize, automatic, (snapshot, token) -> {});
    }
    public MemoryMaintenance(SessionRepository sessions, Clock clock, Duration interval, int scanSeconds,
                             BiConsumer<SessionSnapshot, CancellationToken> organize, boolean automatic,
                             BiConsumer<SessionSnapshot, CancellationToken> consolidate) {
        this.sessions = sessions; this.clock = clock; this.interval = interval; this.organize = organize;
        this.consolidate = consolidate;
        executor = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "memory-maintenance"); t.setDaemon(false); return t; });
        if (automatic) executor.scheduleWithFixedDelay(this::scan, 0, scanSeconds, TimeUnit.SECONDS);
    }
    public synchronized CompletionStage<String> request(java.util.function.Function<CancellationToken, String> action) {
        if (manual != null) throw new IllegalStateException("Manual memory work is already pending");
        var source = new CancellationSource(); manual = source; manualSources.add(source);
        var result = new CompletableFuture<String>();
        try { executor.execute(() -> {
            try (var registration = cancellation.token().onCancellation(source::cancel)) {
                source.token().throwIfCancellationRequested(); String value = action.apply(source.token());
                synchronized (this) { manual = null; }
                result.complete(value);
            } catch (Throwable failure) {
                synchronized (this) { manual = null; }
                result.completeExceptionally(failure);
            } finally {
                if (source.callbackFailures().isEmpty()) manualSources.remove(source);
            }
        }); } catch (RuntimeException failure) { manual = null; manualSources.remove(source); throw failure; }
        return result.minimalCompletionStage();
    }
    public synchronized boolean cancelManual() { return manual != null && manual.cancel(); }
    public void scan() {
        try {
            cancellation.token().throwIfCancellationRequested();
            for (var summary : sessions.list().sessions()) {
                cancellation.token().throwIfCancellationRequested();
                var now = clock.instant(); var failed = failures.get(summary.id());
                if (failed != null && now.isBefore(failed.retryAt())) continue;
                try {
                    var snapshot = sessions.load(summary.id());
                    if (snapshot.messages().isEmpty()) continue;
                    // A persisted live run may belong to another process. Do not infer that it crashed.
                    if (snapshot.lastRun() != null && snapshot.lastRun().endedAt() == null) continue;
                    if (snapshot.memoryState().eligible(snapshot.contentRevision(), now, interval)) organize.accept(snapshot, cancellation.token());
                    consolidate.accept(snapshot, cancellation.token());
                    failures.remove(summary.id());
                } catch (OperationCancelledException stop) { return; }
                catch (RuntimeException failure) {
                    int attempts = failed == null ? 1 : Math.min(5, failed.attempts() + 1);
                    failures.put(summary.id(), new Failure(attempts, now.plusSeconds(Math.min(3600, 300L << (attempts - 1)))));
                    com.demo.logging.Logging.logger(MemoryMaintenance.class).warn("Memory maintenance deferred session_id={} exception={}", summary.id(), com.demo.logging.LogData.exception(failure));
                }
            }
        } catch (OperationCancelledException stop) { /* shutdown */ }
        catch (RuntimeException failure) {
            com.demo.logging.Logging.logger(MemoryMaintenance.class).warn("Memory scan deferred exception={}", com.demo.logging.LogData.exception(failure));
        }
    }
    public boolean close(long deadlineNanos) throws InterruptedException {
        cancellation.cancel(); executor.shutdown();
        boolean stopped = executor.awaitTermination(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        if (stopped) {
            cancellation.retryFailedCallbacks();
            for (var source : manualSources) source.retryFailedCallbacks();
            manualSources.clear();
        }
        return stopped;
    }
}
