package com.demo.session;

import com.demo.config.Config;
import com.demo.execution.CancellationToken;
import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.session.storage.JsonSessionRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AsyncSessionServiceTest {
    @TempDir Path temp;

    @Test void structuredRetryAndFailureReachSessionEventsAndOnlyFinalDiagnosticIsPersisted() {
        var error = new com.demo.llm.LLMError(com.demo.llm.LLMError.Source.SERVER, 429,
                Map.of("error.code", "\"1302\""), true, 0);
        var retry = new com.demo.llm.LLMFailure(error, 1, 2, com.demo.llm.LLMFailure.State.RETRY_SCHEDULED, 1L);
        var failed = new com.demo.llm.LLMFailure(error, 2, 2, com.demo.llm.LLMFailure.State.RETRIES_EXHAUSTED, null);
        LLMClient model = new LLMClient() {
            public LLMResponse sendMessage(List<Message> messages, String tools) { throw new AssertionError(); }
            public LLMResponse sendMessage(List<Message> messages, String tools, CancellationToken token,
                    java.util.function.Consumer<com.demo.llm.LLMFailure> onRetry) {
                onRetry.accept(retry);
                return LLMResponse.error(failed);
            }
        };
        String id;
        try (var service = service(model)) {
            id = service.createSession(temp).id();
            var run = service.submitTurn(id, "test");
            var snapshot = run.completion().toCompletableFuture().join();
            assertEquals(SessionRun.Status.LLM_ERROR, snapshot.lastRun().status());
            assertEquals(2, snapshot.messages().size());
            assertEquals(com.demo.llm.LLMErrorFormatter.format(failed), snapshot.lastRun().message());
            var events = run.eventsAfter(0);
            assertEquals(retry, ((SessionEvent.ModelFailure) events.stream().filter(e -> e.type() == SessionEvent.Type.LLM_RETRY)
                    .findFirst().orElseThrow().payload()).failure());
            assertEquals(failed, ((SessionEvent.ModelFailure) events.stream().filter(e -> e.type() == SessionEvent.Type.LLM_FAILED)
                    .findFirst().orElseThrow().payload()).failure());
        }
        try (var restored = service(model)) {
            assertEquals(com.demo.llm.LLMErrorFormatter.format(failed), restored.getSession(id).lastRun().message());
        }
    }

    @Test void returnsLiveHandleAndEventsThenCancellationLeavesSessionUsable() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        LLMClient model = cooperativeModel(entered, release, calls);
        try (var service = service(model)) {
            String id = service.createSession(temp).id();
            RunHandle run = service.submitTurn(id, "first");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(run, service.getActiveRun().orElseThrow());
            assertFalse(run.completion().toCompletableFuture().isDone());
            assertEquals(run.runId(), service.getSession(id).lastRun().id());
            assertEquals(SessionException.Reason.BUSY, assertThrows(SessionException.class,
                    () -> service.submitTurn(id, "duplicate")).getReason());
            assertTrue(run.eventsAfter(0).stream().anyMatch(event -> event.type() == SessionEvent.Type.LLM_REQUEST_STARTED));
            assertEquals(RunHandle.CancelResult.REQUESTED, run.cancel());
            var cancelled = run.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(SessionRun.Status.CANCELLED, cancelled.lastRun().status());
            assertEquals(RunHandle.State.FINISHED, run.state());
            assertTrue(service.getActiveRun().isEmpty());
            assertEquals(1, cancelled.messages().size());
            var next = service.runTurn(id, "second");
            assertEquals(SessionRun.Status.COMPLETED, next.lastRun().status());
            assertEquals(3, next.messages().size());
            assertEquals(RunHandle.CancelResult.ALREADY_FINISHED, run.cancel());
            assertEquals(2, calls.get());
        }
    }

    @Test void closeCancelsActiveWorkBeforeClosingOwnedClient() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        LLMClient delegate = cooperativeModel(entered, release, new AtomicInteger());
        LLMClient model = new LLMClient() {
            public LLMResponse sendMessage(List<Message> messages, String tools) { return delegate.sendMessage(messages, tools); }
            public LLMResponse sendMessage(List<Message> messages, String tools, CancellationToken token) {
                return delegate.sendMessage(messages, tools, token);
            }
            public void close() { closed.incrementAndGet(); }
        };
        var service = new DefaultSessionService(Config.defaults(), model, new JsonSessionRepository(temp.resolve("sessions")),
                Clock.systemUTC(), DefaultSessionService.ClientOwnership.OWNED);
        String id = service.createSession(temp).id();
        RunHandle run = service.submitTurn(id, "first");
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        service.close();
        assertEquals(SessionRun.Status.CANCELLED, run.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).lastRun().status());
        assertEquals(1, closed.get());
        service.close();
        assertEquals(1, closed.get());
    }

    @Test void closeTimeoutDoesNotPretendUncooperativeClientHasStopped() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LLMClient model = (messages, tools) -> {
            entered.countDown();
            await(release);
            return new LLMClient.LLMResponse("late", null);
        };
        var service = new DefaultSessionService(Config.defaults(), model, new JsonSessionRepository(temp.resolve("sessions")),
                Clock.systemUTC(), DefaultSessionService.ClientOwnership.BORROWED, Executors.newSingleThreadExecutor(), Duration.ofMillis(100));
        String id = service.createSession(temp).id();
        RunHandle run = service.submitTurn(id, "work");
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(SessionException.Reason.SHUTDOWN_TIMEOUT, assertThrows(SessionException.class, service::close).getReason());
            assertEquals(SessionException.Reason.CLOSING, assertThrows(SessionException.class,
                    () -> service.submitTurn(id, "new")).getReason());
            assertEquals(1, service.getSession(id).messages().size());
        } finally { release.countDown(); }
        assertEquals(SessionRun.Status.CANCELLED, run.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).lastRun().status());
        service.close();
    }

    @Test void rejectedSchedulingDoesNotAppendHistoryOrLeaveActiveRun() {
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        try (var service = new DefaultSessionService(Config.defaults(), (messages, tools) -> null,
                new JsonSessionRepository(temp.resolve("sessions")), Clock.systemUTC(),
                DefaultSessionService.ClientOwnership.BORROWED, executor, Duration.ofSeconds(1))) {
            String id = service.createSession(temp).id();
            assertEquals(SessionException.Reason.RESOURCE_FAILURE, assertThrows(SessionException.class,
                    () -> service.submitTurn(id, "work")).getReason());
            assertTrue(service.getActiveRun().isEmpty());
            assertTrue(service.getSession(id).messages().isEmpty());
        }
    }

    @Test void cancellationBeforeWorkerStartsNeverCallsModel() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> { blocked.countDown(); await(release); });
        assertTrue(blocked.await(3, TimeUnit.SECONDS));
        AtomicInteger calls = new AtomicInteger();
        try (var service = new DefaultSessionService(Config.defaults(), (messages, tools) -> {
            calls.incrementAndGet(); return new LLMClient.LLMResponse("done", null);
        }, new JsonSessionRepository(temp.resolve("sessions")), Clock.systemUTC(),
                DefaultSessionService.ClientOwnership.BORROWED, executor, Duration.ofSeconds(5))) {
            String id = service.createSession(temp).id();
            RunHandle run = service.submitTurn(id, "work");
            assertEquals(RunHandle.State.STARTING, run.state());
            assertEquals(RunHandle.CancelResult.REQUESTED, run.cancel());
            release.countDown();
            assertEquals(SessionRun.Status.CANCELLED, run.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).lastRun().status());
            assertEquals(0, calls.get());
            assertEquals(1, service.getSession(id).messages().size());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void resourceCleanupFailureBlocksNextTurnAndCloseRetriesCleanup() throws Exception {
        AtomicInteger cleaned = new AtomicInteger();
        var service = service((messages, tools) -> {
            throw new com.demo.execution.ResourceCleanupException("cleanup failed", cleaned::incrementAndGet);
        });
        String id = service.createSession(temp).id();
        var run = service.submitTurn(id, "work");
        assertInstanceOf(SessionException.class, assertThrows(java.util.concurrent.ExecutionException.class,
                () -> run.completion().toCompletableFuture().get(3, TimeUnit.SECONDS)).getCause());
        assertEquals(SessionRun.Status.FAILED, service.getSession(id).lastRun().status());
        assertEquals(SessionException.Reason.RESOURCE_FAILURE, assertThrows(SessionException.class,
                () -> service.submitTurn(id, "next")).getReason());
        service.close();
        assertEquals(1, cleaned.get());
    }

    private DefaultSessionService service(LLMClient model) {
        return new DefaultSessionService(Config.defaults(), model, new JsonSessionRepository(temp.resolve("sessions")), Clock.systemUTC());
    }
    private LLMClient cooperativeModel(CountDownLatch entered, CountDownLatch release, AtomicInteger calls) {
        return new LLMClient() {
            public LLMResponse sendMessage(List<Message> messages, String tools) { throw new AssertionError("Token overload required"); }
            public LLMResponse sendMessage(List<Message> messages, String tools, CancellationToken token) {
                if (calls.incrementAndGet() == 1) {
                    try (var registration = token.onCancellation(release::countDown)) {
                        entered.countDown();
                        await(release);
                        token.throwIfCancellationRequested();
                    }
                }
                return new LLMResponse("done", null);
            }
        };
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
}
