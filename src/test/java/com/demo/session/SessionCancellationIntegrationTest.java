package com.demo.session;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.session.storage.JsonSessionRepository;
import com.demo.session.storage.SessionRepository;
import com.demo.session.storage.SessionStorageException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SessionCancellationIntegrationTest {
    @TempDir Path temp;

    @Test void cancelsBetweenRealFileWritesAndRetainsSuccessfulResultWithCompleteToolProtocol() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<RunHandle> handle = new AtomicReference<>();
        JsonSessionRepository disk = new JsonSessionRepository(temp.resolve("sessions"));
        SessionRepository repository = new SessionRepository() {
            public void save(SessionSnapshot snapshot) {
                disk.save(snapshot);
                if (!snapshot.messages().isEmpty()) {
                    Message tail = snapshot.messages().get(snapshot.messages().size() - 1);
                    if ("tool".equals(tail.getRole()) && "a".equals(tail.getToolCallId()) && Boolean.TRUE.equals(tail.getToolSuccess())) {
                        handle.get().cancel();
                    }
                }
            }
            public SessionSnapshot load(String id) { return disk.load(id); }
            public SessionCatalog list() { return disk.list(); }
        };
        LLMClient model = (messages, tools) -> {
            if (messages.stream().filter(message -> "user".equals(message.getRole())).count() > 1) {
                return new LLMClient.LLMResponse("continued", null);
            }
            entered.countDown(); await(proceed);
            return new LLMClient.LLMResponse("writing files", List.of(
                    new ToolCall("a", "write_file", "{\"path\":\"a.txt\",\"content\":\"done\"}"),
                    new ToolCall("b", "write_file", "{\"path\":\"b.txt\",\"content\":\"not done\"}")));
        };
        try (var service = new DefaultSessionService(Config.defaults(), model, repository, Clock.systemUTC())) {
            String id = service.createSession(temp).id();
            RunHandle run = service.submitTurn(id, "write both");
            handle.set(run);
            assertTrue(entered.await(3, TimeUnit.SECONDS)); proceed.countDown();
            var cancelled = run.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(SessionRun.Status.CANCELLED, cancelled.lastRun().status());
            assertEquals("done", Files.readString(temp.resolve("a.txt")));
            assertFalse(Files.exists(temp.resolve("b.txt")));
            assertEquals(4, cancelled.messages().size());
            assertTrue(cancelled.messages().get(2).getToolSuccess());
            assertFalse(cancelled.messages().get(3).getToolSuccess());
            assertTrue(cancelled.messages().get(3).getContent().contains("before execution"));
            assertEquals(1, run.eventsAfter(0).stream().filter(event -> event.type() == SessionEvent.Type.TOOL_STARTED).count());
            assertEquals(2, run.eventsAfter(0).stream().filter(event -> event.type() == SessionEvent.Type.TOOL_RESULT).count());
            assertEquals(SessionRun.Status.CANCELLED, disk.load(id).lastRun().status());
            assertEquals("continued", service.runTurn(id, "continue").lastRun().message());
        } finally { proceed.countDown(); }
    }

    @Test void lateModelResponseCannotAppendTextOrExecuteItsTools() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        LLMClient model = (messages, tools) -> {
            entered.countDown(); await(proceed);
            return new LLMClient.LLMResponse("late text", List.of(new ToolCall("write_file",
                    "{\"path\":\"late.txt\",\"content\":\"must not write\"}")));
        };
        try (var service = new DefaultSessionService(Config.defaults(), model,
                new JsonSessionRepository(temp.resolve("sessions")), Clock.systemUTC())) {
            String id = service.createSession(temp).id();
            var run = service.submitTurn(id, "work");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            run.cancel(); proceed.countDown();
            var result = run.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(1, result.messages().size());
            assertFalse(Files.exists(temp.resolve("late.txt")));
            assertTrue(run.eventsAfter(0).stream().noneMatch(event -> event.type() == SessionEvent.Type.ASSISTANT_MESSAGE));
        } finally { proceed.countDown(); }
    }

    @Test void saveFailureWinsOverCancellationAndRetainsUnsavedState() throws Exception {
        var disk = new JsonSessionRepository(temp.resolve("sessions"));
        AtomicBoolean failing = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        SessionRepository repository = new SessionRepository() {
            public void save(SessionSnapshot snapshot) {
                if (failing.get()) throw new SessionStorageException(SessionStorageException.Reason.IO_FAILURE, "disk unavailable");
                disk.save(snapshot);
            }
            public SessionSnapshot load(String id) { return disk.load(id); }
            public SessionCatalog list() { return disk.list(); }
        };
        LLMClient model = (messages, tools) -> { entered.countDown(); await(proceed); return new LLMClient.LLMResponse("late", null); };
        try (var service = new DefaultSessionService(Config.defaults(), model, repository, Clock.systemUTC())) {
            String id = service.createSession(temp).id();
            var run = service.submitTurn(id, "work");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            failing.set(true); run.cancel(); proceed.countDown();
            assertInstanceOf(SessionStorageException.class, assertThrows(ExecutionException.class,
                    () -> run.completion().toCompletableFuture().get(3, TimeUnit.SECONDS)).getCause());
            assertTrue(service.getSession(id).unsaved());
            assertEquals(SessionRun.Status.STORAGE_ERROR, service.getSession(id).lastRun().status());
            assertEquals(1, run.eventsAfter(0).stream().filter(event -> event.type() == SessionEvent.Type.RUN_FINISHED).count());
            assertEquals(SessionException.Reason.UNSAVED_SESSION, assertThrows(SessionException.class,
                    () -> service.submitTurn(id, "next")).getReason());
            failing.set(false);
            assertFalse(service.retrySave(id).unsaved());
        } finally { failing.set(false); proceed.countDown(); }
    }

    @Test void storageFailureCannotHideFailedCancellationCleanupAfterSaveIsRetried() throws Exception {
        var disk = new JsonSessionRepository(temp.resolve("sessions"));
        AtomicBoolean failing = new AtomicBoolean();
        AtomicBoolean cleanupFailing = new AtomicBoolean(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        SessionRepository repository = new SessionRepository() {
            public void save(SessionSnapshot snapshot) {
                if (failing.get()) throw new SessionStorageException(SessionStorageException.Reason.IO_FAILURE, "disk unavailable");
                disk.save(snapshot);
            }
            public SessionSnapshot load(String id) { return disk.load(id); }
            public SessionCatalog list() { return disk.list(); }
        };
        LLMClient model = new LLMClient() {
            public LLMResponse sendMessage(List<Message> messages, String tools) { throw new AssertionError(); }
            public LLMResponse sendMessage(List<Message> messages, String tools, com.demo.execution.CancellationToken token) {
                try (var registration = token.onCancellation(() -> {
                    if (cleanupFailing.get()) throw new IllegalStateException("resource still alive");
                })) {
                    entered.countDown(); await(proceed);
                    token.throwIfCancellationRequested();
                    return new LLMResponse("done", null);
                }
            }
        };
        var service = new DefaultSessionService(Config.defaults(), model, repository, Clock.systemUTC());
        try {
            String id = service.createSession(temp).id();
            var run = service.submitTurn(id, "work");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            failing.set(true); run.cancel(); proceed.countDown();
            assertInstanceOf(SessionStorageException.class, assertThrows(ExecutionException.class,
                    () -> run.completion().toCompletableFuture().get(3, TimeUnit.SECONDS)).getCause());
            failing.set(false);
            service.retrySave(id);
            assertEquals(SessionException.Reason.RESOURCE_FAILURE, assertThrows(SessionException.class,
                    () -> service.submitTurn(id, "next")).getReason());
            assertEquals(SessionException.Reason.RESOURCE_FAILURE,
                    assertThrows(SessionException.class, service::close).getReason());
        } finally {
            failing.set(false); cleanupFailing.set(false); proceed.countDown(); service.close();
        }
    }

    @Test void finalAnswerIsPublishedExactlyOnceAndTerminalEventHasNoDuplicateAnswer() throws Exception {
        try (var service = new DefaultSessionService(Config.defaults(), (messages, tools) -> new LLMClient.LLMResponse("final", null),
                new JsonSessionRepository(temp.resolve("sessions")), Clock.systemUTC())) {
            String id = service.createSession(temp).id();
            var run = service.submitTurn(id, "work");
            run.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            var events = run.eventsAfter(0);
            assertEquals(1, events.stream().filter(event -> event.type() == SessionEvent.Type.ASSISTANT_MESSAGE).count());
            var text = events.stream().filter(event -> event.type() == SessionEvent.Type.ASSISTANT_MESSAGE).findFirst().orElseThrow();
            assertEquals(new SessionEvent.AssistantMessage(1, "final", true), text.payload());
            var end = events.get(events.size() - 1);
            assertEquals(SessionEvent.Type.RUN_FINISHED, end.type());
            assertNull(((SessionEvent.RunFinished) end.payload()).diagnostic());
            assertTrue(run.eventsAfter(end.sequence()).isEmpty());
        }
    }

    @Test void cancelDuringFinalStatusSaveIsTooLateAndCannotRewriteCompletedResult() throws Exception {
        CountDownLatch finishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var disk = new JsonSessionRepository(temp.resolve("sessions"));
        SessionRepository repository = new SessionRepository() {
            public void save(SessionSnapshot snapshot) {
                if (snapshot.lastRun() != null && snapshot.lastRun().status() == SessionRun.Status.COMPLETED) {
                    finishing.countDown(); await(release);
                }
                disk.save(snapshot);
            }
            public SessionSnapshot load(String id) { return disk.load(id); }
            public SessionCatalog list() { return disk.list(); }
        };
        try (var service = new DefaultSessionService(Config.defaults(), (messages, tools) -> new LLMClient.LLMResponse("done", null),
                repository, Clock.systemUTC())) {
            String id = service.createSession(temp).id();
            var run = service.submitTurn(id, "work");
            assertTrue(finishing.await(3, TimeUnit.SECONDS));
            assertEquals(RunHandle.State.FINISHING, run.state());
            assertEquals(RunHandle.CancelResult.TOO_LATE, run.cancel());
            release.countDown();
            assertEquals(SessionRun.Status.COMPLETED, run.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).lastRun().status());
            assertTrue(run.eventsAfter(0).stream().noneMatch(event -> event.type() == SessionEvent.Type.CANCEL_REQUESTED));
        } finally { release.countDown(); }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
}
