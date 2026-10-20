package com.demo.session;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.session.storage.JsonSessionRepository;
import com.demo.session.storage.SessionRepository;
import com.demo.session.storage.SessionStorageException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SessionServiceTest {
    @TempDir Path temp;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC);

    private DefaultSessionService service(LLMClient model, SessionRepository repository) {
        return new DefaultSessionService(Config.defaults(), model, repository, clock);
    }

    private LLMClient textModel() { return (messages, tools) -> new LLMClient.LLMResponse("done", null); }

    @Test
    void createsListsAndRunsWithUnicodeTitleAndImmutableSnapshots() throws Exception {
        JsonSessionRepository repository = new JsonSessionRepository(temp.resolve("sessions"));
        List<List<Message>> requests = new ArrayList<>();
        try (var service = service((messages, tools) -> {
            requests.add(messages);
            return new LLMClient.LLMResponse("done", null);
        }, repository)) {
            var empty = service.createSession(temp);
            assertEquals("新会话", empty.title());
            assertEquals(temp.toRealPath(), empty.workspace());
            assertNull(empty.lastRun());
            assertFalse(empty.unsaved());
            assertEquals(0, empty.contentRevision());
            String first = "😀".repeat(45) + "\nsecond line";
            var firstTurn = service.runTurn(empty.id(), first);
            assertEquals("😀".repeat(40), firstTurn.title());
            assertEquals(SessionRun.Status.COMPLETED, firstTurn.lastRun().status());
            assertEquals(2, firstTurn.contentRevision());
            var second = service.runTurn(empty.id(), "again");
            assertEquals(4, second.messages().size());
            assertEquals(4, second.contentRevision());
            assertEquals(second.contentRevision(), repository.load(empty.id()).contentRevision());
            assertEquals(firstTurn.messages().get(0).getId(), second.messages().get(0).getId());
            assertEquals(firstTurn.title(), second.title());
            assertEquals(0, empty.messages().size());
            assertEquals(2, firstTurn.messages().size());
            assertEquals(4, requests.get(1).size()); // system + prior user/assistant + new user
            assertTrue(requests.get(0).get(0).getContent().contains(temp.toRealPath().toString()));
            assertEquals(1, service.listSessions().sessions().size());
            assertFalse(repository.load(empty.id()).unsaved());
            assertThrows(UnsupportedOperationException.class, () -> second.messages().clear());
        }
    }

    @Test
    void rejectsInvalidMessagesIdsAndMissingSessionsWithoutCallingModel() {
        AtomicInteger calls = new AtomicInteger();
        try (var service = service((messages, tools) -> {
            calls.incrementAndGet();
            return new LLMClient.LLMResponse("done", null);
        }, new JsonSessionRepository(temp.resolve("sessions")))) {
            var session = service.createSession(temp);
            for (String input : new String[] {null, "", " \n\t"}) {
                assertReason(SessionException.Reason.INVALID_INPUT, () -> service.runTurn(session.id(), input));
            }
            for (String id : new String[] {null, "../bad", "bad"}) {
                assertReason(SessionException.Reason.INVALID_INPUT, () -> service.getSession(id));
            }
            assertReason(SessionException.Reason.NOT_FOUND, () -> service.getSession(UUID.randomUUID().toString()));
            assertEquals(0, calls.get());
            assertTrue(service.getSession(session.id()).messages().isEmpty());
        }
    }

    @Test
    void initialSaveFailureDoesNotPublishCreatedSession() {
        FaultRepository repository = new FaultRepository(temp.resolve("sessions"));
        repository.failAt = 1;
        try (var service = service(textModel(), repository)) {
            assertThrows(SessionStorageException.class, () -> service.createSession(temp));
            assertTrue(service.listSessions().sessions().isEmpty());
        }
    }

    @Test
    void checkpointFailuresStopSideEffectsAndRetryRepairsWithoutReplaying() throws Exception {
        for (int failAt : List.of(2, 3, 4)) {
            Path workspace = Files.createDirectory(temp.resolve("workspace-" + failAt));
            FaultRepository repository = new FaultRepository(temp.resolve("sessions-" + failAt));
            AtomicInteger modelCalls = new AtomicInteger();
            LLMClient model = (messages, tools) -> {
                modelCalls.incrementAndGet();
                if ("tool".equals(messages.get(messages.size() - 1).getRole())) {
                    return new LLMClient.LLMResponse("done", null);
                }
                return new LLMClient.LLMResponse(null, List.of(
                        new ToolCall("a", "write_file", "{\"path\":\"a.txt\",\"content\":\"a\"}"),
                        new ToolCall("b", "write_file", "{\"path\":\"b.txt\",\"content\":\"b\"}")));
            };
            try (var service = service(model, repository)) {
                var created = service.createSession(workspace);
                repository.failAt = failAt;
                assertThrows(SessionStorageException.class, () -> service.runTurn(created.id(), "write files"));
                var failed = service.getSession(created.id());
                assertTrue(failed.unsaved());
                assertEquals(SessionRun.Status.STORAGE_ERROR, failed.lastRun().status());
                assertEquals(failAt == 4, Files.exists(workspace.resolve("a.txt")));
                assertFalse(Files.exists(workspace.resolve("b.txt")));
                assertEquals(failAt == 2 ? 0 : 1, modelCalls.get());
                assertReason(SessionException.Reason.UNSAVED_SESSION, () -> service.runTurn(created.id(), "next"));
                var restored = service.retrySave(created.id());
                assertFalse(restored.unsaved());
                assertEquals(SessionRun.Status.INTERRUPTED, restored.lastRun().status());
                assertFalse(Files.exists(workspace.resolve("b.txt")));
                if (failAt >= 3) {
                    assertEquals(4, restored.messages().size());
                    Message firstResult = restored.messages().get(2);
                    assertEquals(failAt == 4, firstResult.getToolSuccess());
                    assertFalse(restored.messages().get(3).getToolSuccess());
                }
                assertEquals(restored.messages().size(), repository.load(created.id()).messages().size());
            }
        }
    }

    @Test
    void finalStatusSaveFailureRetainsFinalAnswerButRestartsAsInterrupted() {
        FaultRepository repository = new FaultRepository(temp.resolve("sessions"));
        var first = service(textModel(), repository);
        var session = first.createSession(temp);
        repository.failAt = 4; // creation, user, final text checkpoint, final status
        assertThrows(SessionStorageException.class, () -> first.runTurn(session.id(), "work"));
        assertEquals("done", first.getSession(session.id()).messages().get(1).getContent());
        assertEquals(SessionRun.Status.RUNNING, repository.load(session.id()).lastRun().status());
        try (var restarted = service(textModel(), repository)) {
            var restored = restarted.getSession(session.id());
            assertEquals(SessionRun.Status.INTERRUPTED, restored.lastRun().status());
            assertEquals("done", restored.messages().get(1).getContent());
            assertFalse(restored.unsaved());
        }
        // Simulated crash: deliberately do not call close() on the abandoned first service.
    }

    @Test
    void busyExecutionAllowsPublishedReadsAndRejectsMutationsWithoutQueuing() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var service = service((messages, tools) -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            return new LLMClient.LLMResponse("done", null);
        }, new JsonSessionRepository(temp.resolve("sessions")))) {
            var session = service.createSession(temp);
            var job = executor.submit(() -> service.runTurn(session.id(), "work"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var published = service.getSession(session.id());
                assertEquals(SessionRun.Status.RUNNING, published.lastRun().status());
                assertEquals(1, published.messages().size());
                assertFalse(published.unsaved());
                assertEquals(1, service.listSessions().sessions().size());
                assertReason(SessionException.Reason.BUSY, () -> service.runTurn(session.id(), "duplicate"));
                assertReason(SessionException.Reason.BUSY, () -> service.createSession(temp));
                assertReason(SessionException.Reason.BUSY, () -> service.retrySave(session.id()));
                assertTrue(service.getActiveRun().isPresent());
            } finally {
                release.countDown();
            }
            assertEquals(SessionRun.Status.COMPLETED, job.get(5, TimeUnit.SECONDS).lastRun().status());
            assertEquals(2, service.getSession(session.id()).messages().size());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void modelErrorsAndUnexpectedExceptionsDoNotDestroySession() {
        AtomicInteger request = new AtomicInteger();
        try (var service = service((messages, tools) -> {
            return switch (request.getAndIncrement()) {
                case 0 -> LLMClient.LLMResponse.error("offline");
                case 1 -> throw new IllegalStateException("private provider data");
                default -> new LLMClient.LLMResponse("recovered", null);
            };
        }, new JsonSessionRepository(temp.resolve("sessions")))) {
            var session = service.createSession(temp);
            assertEquals(SessionRun.Status.LLM_ERROR, service.runTurn(session.id(), "first").lastRun().status());
            var failed = service.runTurn(session.id(), "second");
            assertEquals(SessionRun.Status.FAILED, failed.lastRun().status());
            assertFalse(failed.lastRun().message().contains("private provider data"));
            assertEquals(SessionRun.Status.COMPLETED, service.runTurn(session.id(), "third").lastRun().status());
        }
    }

    @Test
    void missingWorkspaceAllowsHistoryButRejectsExecution() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        var repository = new JsonSessionRepository(temp.resolve("sessions"));
        String id;
        try (var service = service(textModel(), repository)) { id = service.createSession(workspace).id(); }
        Files.delete(workspace);
        try (var service = service(textModel(), repository)) {
            assertEquals(id, service.getSession(id).id());
            assertReason(SessionException.Reason.INVALID_WORKSPACE, () -> service.runTurn(id, "work"));
            assertTrue(service.getSession(id).messages().isEmpty());
        }
    }

    @Test
    void maximumIterationsEndsTurnAndAllowsNextRequest() {
        Config defaults = Config.defaults();
        Config config = new Config(defaults.getApiUrl(), defaults.getModel(), "", defaults.getMaxTokens(),
                defaults.getTemperature(), 1, defaults.getWorkspaceDir(), defaults.getEnabledTools());
        AtomicInteger calls = new AtomicInteger();
        LLMClient model = (messages, tools) -> calls.getAndIncrement() == 0
                ? new LLMClient.LLMResponse(null, List.of(new ToolCall("missing", "{}")))
                : new LLMClient.LLMResponse("next turn", null);
        try (var service = new DefaultSessionService(config, model,
                new JsonSessionRepository(temp.resolve("sessions")), clock)) {
            String id = service.createSession(temp).id();
            assertEquals(SessionRun.Status.MAX_ITERATIONS, service.runTurn(id, "first").lastRun().status());
            assertEquals(SessionRun.Status.COMPLETED, service.runTurn(id, "second").lastRun().status());
        }
    }

    @Test
    void failedRecoveryCheckpointRetainsRepairInMemoryAndBlocksOtherSessionUntilSaved() {
        FaultRepository repository = new FaultRepository(temp.resolve("sessions"));
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        repository.save(new SessionSnapshot(id, "interrupted", temp.toAbsolutePath(), now, now,
                List.of(Message.user("work"), Message.assistantToolCalls(null,
                        List.of(new ToolCall("a", "write_file", "{}")))), SessionRun.started(now), false));
        try (var service = service(textModel(), repository)) {
            String other = service.createSession(temp).id();
            repository.failAt = 3;
            assertThrows(SessionStorageException.class, () -> service.getSession(id));
            var pending = service.getSession(id);
            assertTrue(pending.unsaved());
            assertEquals(3, pending.messages().size());
            assertEquals(SessionRun.Status.RUNNING, repository.load(id).lastRun().status());
            assertReason(SessionException.Reason.UNSAVED_SESSION, () -> service.runTurn(other, "work"));
            service.retrySave(id);
            assertEquals(SessionRun.Status.INTERRUPTED, repository.load(id).lastRun().status());
            assertFalse(service.getSession(id).unsaved());
            assertEquals(SessionRun.Status.COMPLETED, service.runTurn(other, "work").lastRun().status());
        }
    }

    @Test
    void backwardsClockMovementDoesNotProduceUnrestorableTimestamps() {
        AtomicInteger reads = new AtomicInteger();
        Clock movingClock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return clock.instant().plusSeconds(reads.getAndIncrement() == 1 ? 10 : 0); }
        };
        var repository = new JsonSessionRepository(temp.resolve("sessions"));
        try (var service = new DefaultSessionService(Config.defaults(), textModel(), repository, movingClock)) {
            String id = service.createSession(temp).id();
            var finished = service.runTurn(id, "work");
            assertFalse(finished.updatedAt().isBefore(finished.lastRun().startedAt()));
            assertEquals(finished.lastRun(), repository.load(id).lastRun());
        }
    }

    @Test
    void closeRetriesUnsavedDataAndHonorsClientOwnership() {
        AtomicInteger closed = new AtomicInteger();
        LLMClient model = new LLMClient() {
            public LLMResponse sendMessage(List<Message> messages, String tools) { return new LLMResponse("done", null); }
            public void close() { closed.incrementAndGet(); }
        };
        FaultRepository repository = new FaultRepository(temp.resolve("sessions"));
        var service = new DefaultSessionService(Config.defaults(), model, repository, clock,
                DefaultSessionService.ClientOwnership.OWNED);
        var session = service.createSession(temp);
        repository.failAt = 2;
        assertThrows(SessionStorageException.class, () -> service.runTurn(session.id(), "work"));
        repository.failAt = 3;
        assertThrows(SessionStorageException.class, service::close);
        assertEquals(0, closed.get());
        assertTrue(service.getSession(session.id()).unsaved());
        service.close();
        service.close();
        assertEquals(1, closed.get());
        assertReason(SessionException.Reason.CLOSED, () -> service.createSession(temp));
        assertReason(SessionException.Reason.CLOSED, () -> service.getSession(session.id()));
        assertReason(SessionException.Reason.CLOSED, service::listSessions);
        try (var borrowed = service(model, repository)) { borrowed.getSession(session.id()); }
        assertEquals(1, closed.get());
    }

    @Test
    void writesAndOverwritesDoNotPrintBehindTheFrontend() throws Exception {
        PrintStream beforeOut = System.out;
        PrintStream beforeErr = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream sink = new PrintStream(output, true, StandardCharsets.UTF_8);
             var service = service((messages, tools) -> {
                 if ("tool".equals(messages.get(messages.size() - 1).getRole())) return new LLMClient.LLMResponse("done", null);
                 return new LLMClient.LLMResponse("writing", List.of(new ToolCall("write_file",
                         "{\"path\":\"a.txt\",\"content\":\"hello\"" +
                         (messages.stream().filter(m -> "user".equals(m.getRole())).count() > 1
                         ? ",\"expected_sha256\":\"" + com.demo.tools.fs.WorkspaceFileAccess.sha256("hello".getBytes(StandardCharsets.UTF_8)) + "\"" : "") + "}")));
             }, new JsonSessionRepository(temp.resolve("sessions")))) {
            System.setOut(sink);
            System.setErr(sink);
            var session = service.createSession(temp);
            service.runTurn(session.id(), "write");
            var overwritten = service.runTurn(session.id(), "write again");
            assertTrue(overwritten.messages().stream().anyMatch(message -> message.getContent() != null
                    && message.getContent().contains("\"action\":\"overwritten\"")));
        } finally {
            System.setOut(beforeOut);
            System.setErr(beforeErr);
        }
        assertEquals("", output.toString(StandardCharsets.UTF_8));
    }

    private void assertReason(SessionException.Reason reason, org.junit.jupiter.api.function.Executable action) {
        assertEquals(reason, assertThrows(SessionException.class, action).getReason());
    }

    private static class FaultRepository implements SessionRepository {
        final JsonSessionRepository delegate;
        int attempts;
        int failAt = -1;

        FaultRepository(Path path) { delegate = new JsonSessionRepository(path); }
        public void save(SessionSnapshot snapshot) {
            if (++attempts == failAt) {
                throw new SessionStorageException(SessionStorageException.Reason.IO_FAILURE, "Simulated checkpoint failure");
            }
            delegate.save(snapshot);
        }
        public SessionSnapshot load(String id) { return delegate.load(id); }
        public SessionCatalog list() { return delegate.list(); }
    }
}
