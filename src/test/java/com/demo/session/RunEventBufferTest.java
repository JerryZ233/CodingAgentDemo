package com.demo.session;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunEventBufferTest {
    @Test void concurrentWritersHaveOneSequenceAndReadersReceiveImmutableIncrementalSnapshots() throws Exception {
        var buffer = new RunEventBuffer("session", "run", Clock.systemUTC());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { for (int i = 0; i < 100; i++) buffer.append(SessionEvent.Type.ITERATION_STARTED, new SessionEvent.Progress(i)); });
            var b = pool.submit(() -> { for (int i = 0; i < 100; i++) buffer.append(SessionEvent.Type.LLM_REQUEST_STARTED, new SessionEvent.Progress(i)); });
            a.get(3, TimeUnit.SECONDS); b.get(3, TimeUnit.SECONDS);
            var events = buffer.after(0);
            assertEquals(200, events.size());
            for (int i = 0; i < events.size(); i++) assertEquals(i + 1, events.get(i).sequence());
            assertEquals(50, buffer.after(150).size());
            assertTrue(buffer.after(Long.MAX_VALUE).isEmpty());
            assertThrows(UnsupportedOperationException.class, events::clear);
            assertThrows(IllegalArgumentException.class, () -> buffer.after(-1));
            buffer.append(SessionEvent.Type.RUN_FINISHED, new SessionEvent.RunFinished(SessionRun.Status.COMPLETED, 1, false, null));
            buffer.append(SessionEvent.Type.RUN_FINISHED, null);
            buffer.append(SessionEvent.Type.LLM_REQUEST_STARTED, null);
            assertEquals(201, buffer.after(0).size());
            assertEquals(200, events.size());
        } finally { pool.shutdownNow(); }
    }

    @Test void handleDistinguishesCancellationFromCompletionAndDoesNotExposeWritableFuture() {
        String id = UUID.randomUUID().toString();
        var run = new ActiveRun(id, Clock.systemUTC());
        assertEquals(RunHandle.CancelResult.REQUESTED, run.cancel());
        assertEquals(RunHandle.CancelResult.ALREADY_REQUESTED, run.cancel());
        assertTrue(run.beginFinishing());
        assertEquals(RunHandle.CancelResult.TOO_LATE, run.cancel());
        var now = Clock.systemUTC().instant();
        var snapshot = new SessionSnapshot(id, "test", Path.of(".").toAbsolutePath(), now, now, List.of(),
                SessionRun.started(run.runId(), now).finish(SessionRun.Status.CANCELLED, now, "cancelled"), false);
        var copy = run.completion().toCompletableFuture();
        copy.cancel(true);
        assertFalse(run.completion().toCompletableFuture().isDone());
        run.finish(snapshot, null);
        assertEquals(RunHandle.CancelResult.ALREADY_FINISHED, run.cancel());
        assertEquals(1, run.eventsAfter(0).stream().filter(event -> event.type() == SessionEvent.Type.CANCEL_REQUESTED).count());
        assertEquals(1, run.eventsAfter(0).stream().filter(event -> event.type() == SessionEvent.Type.RUN_FINISHED).count());
        var next = new ActiveRun(id, Clock.systemUTC());
        run.cancel();
        assertFalse(next.cancellation.token().isCancellationRequested());
    }
}
