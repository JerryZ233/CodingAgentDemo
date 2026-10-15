package com.demo.cli;

import com.demo.session.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Path;
import java.io.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunEventPumpTest {
    static final class Handle implements RunHandle {
        final String sessionId = UUID.randomUUID().toString(), runId = UUID.randomUUID().toString();
        final List<SessionEvent> events = new CopyOnWriteArrayList<>();
        final CompletableFuture<SessionSnapshot> future = new CompletableFuture<>();
        public String sessionId() { return sessionId; }
        public String runId() { return runId; }
        public State state() { return future.isDone() ? State.FINISHED : State.RUNNING; }
        public CompletionStage<SessionSnapshot> completion() { return future.minimalCompletionStage(); }
        public List<SessionEvent> eventsAfter(long sequence) { return events.stream().filter(e -> e.sequence() > sequence).toList(); }
        public CancelResult cancel() { return CancelResult.REQUESTED; }
        void event(SessionEvent.Type type, SessionEvent.Payload payload) {
            events.add(new SessionEvent(sessionId, runId, events.size() + 1, Instant.now(), type, payload));
        }
        SessionSnapshot snapshot() {
            Instant now = Instant.now();
            return new SessionSnapshot(sessionId, "test", Path.of(".").toAbsolutePath(), now, now, List.of(),
                    new SessionRun(runId, now, now, SessionRun.Status.COMPLETED, 1, "answer"), false);
        }
    }
    static class StubService implements SessionService {
        SessionSnapshot snapshot;
        int closes;
        RuntimeException closeFailure;
        public SessionSnapshot createSession(Path path) { throw new UnsupportedOperationException(); }
        public SessionCatalog listSessions() { return new SessionCatalog(List.of(), List.of()); }
        public SessionSnapshot getSession(String id) { return snapshot; }
        public SessionSnapshot runTurn(String id, String text) { throw new UnsupportedOperationException(); }
        public RunHandle submitTurn(String id, String text) { throw new UnsupportedOperationException(); }
        public Optional<RunHandle> getActiveRun() { return Optional.empty(); }
        public SessionSnapshot retrySave(String id) { return snapshot; }
        public void close() { closes++; if (closeFailure != null) throw closeFailure; }
    }
    @Test void consumesOnceDrainsBeforeReleasingAndKeepsRunCursorsIndependent() {
        var service = new StubService();
        var text = new StringWriter();
        var renderer = new CliRenderer(new PlainTerminalIO(new StringReader(""), new PrintWriter(text), new PrintWriter(text)));
        try (var pump = new RunEventPump(service, renderer)) {
            Handle first = new Handle();
            first.event(SessionEvent.Type.ASSISTANT_MESSAGE, new SessionEvent.AssistantMessage(1, "first-answer", true));
            pump.attach(first); pump.poll(); pump.poll();
            first.event(SessionEvent.Type.RUN_FINISHED, new SessionEvent.RunFinished(SessionRun.Status.COMPLETED, 1, false, null));
            pump.poll();
            assertSame(first, pump.active()); // The terminal event alone is not completion.
            first.future.complete(first.snapshot());
            assertNull(pump.active());
            assertEquals(1, text.toString().split("first-answer", -1).length - 1);
            Handle second = new Handle();
            pump.attach(second);
            first.event(SessionEvent.Type.ASSISTANT_MESSAGE, new SessionEvent.AssistantMessage(1, "stale-answer", true));
            second.event(SessionEvent.Type.ASSISTANT_MESSAGE, new SessionEvent.AssistantMessage(1, "second-answer", true));
            second.future.complete(second.snapshot());
            assertSame(second, pump.active()); // Future completion alone is not the terminal event.
            second.event(SessionEvent.Type.RUN_FINISHED, new SessionEvent.RunFinished(SessionRun.Status.COMPLETED, 1, false, null));
            assertNull(pump.active());
            assertFalse(text.toString().contains("stale-answer"));
            assertTrue(text.toString().contains("second-answer"));
        }
    }
    @Test void exceptionalCompletionReadsPublishedSnapshotAndReportsFailure() {
        var service = new StubService();
        var text = new StringWriter();
        var renderer = new CliRenderer(new PlainTerminalIO(new StringReader(""), new PrintWriter(text), new PrintWriter(text)));
        try (var pump = new RunEventPump(service, renderer)) {
            Handle handle = new Handle(); service.snapshot = handle.snapshot();
            pump.attach(handle);
            handle.event(SessionEvent.Type.RUN_FINISHED, new SessionEvent.RunFinished(SessionRun.Status.STORAGE_ERROR, 1, true, "checkpoint"));
            handle.future.completeExceptionally(new com.demo.session.storage.SessionStorageException(
                    com.demo.session.storage.SessionStorageException.Reason.IO_FAILURE, "disk"));
            assertSame(service.snapshot, pump.await().snapshot());
            assertTrue(text.toString().contains("/retry-save"));
        }
    }
}
