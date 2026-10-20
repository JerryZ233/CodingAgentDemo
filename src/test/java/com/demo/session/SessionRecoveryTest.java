package com.demo.session;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionRecoveryTest {

    @Test
    void cancellingIsInterruptedOnRestartButCompletedCancellationIsNotChanged() {
        var cancelling = snapshot(List.of(Message.user("work")), SessionRun.started(now).cancelling());
        assertEquals(SessionRun.Status.INTERRUPTED, recovery.recover(cancelling, now).snapshot().lastRun().status());
        var cancelled = snapshot(List.of(Message.user("work")),
                SessionRun.started(now).finish(SessionRun.Status.CANCELLED, now, "cancelled"));
        assertFalse(recovery.recover(cancelled, now).changed());
        assertEquals(SessionRun.Status.CANCELLED, recovery.recover(cancelled, now).snapshot().lastRun().status());
    }
    private final Instant now = Instant.parse("2026-10-15T10:00:00Z");
    private final SessionRecovery recovery = new SessionRecovery();

    private SessionSnapshot snapshot(List<Message> messages, SessionRun run) {
        return new SessionSnapshot(UUID.randomUUID().toString(), "test", Path.of(".").toAbsolutePath(),
                now, now, messages, run, false);
    }

    @Test
    void runningUserOnlyOrFinalTextTurnsAreInterruptedWithoutLosingContent() {
        for (List<Message> history : List.of(List.of(Message.user("work")),
                List.of(Message.user("work"), Message.assistant("done")))) {
            var result = recovery.recover(snapshot(history, SessionRun.started(now)), now.plusSeconds(1));
            assertTrue(result.changed());
            assertEquals(SessionRun.Status.INTERRUPTED, result.snapshot().lastRun().status());
            assertEquals(history.size(), result.snapshot().messages().size());
            assertEquals(history.get(history.size() - 1).getContent(),
                    result.snapshot().messages().get(history.size() - 1).getContent());
            assertFalse(recovery.recover(result.snapshot(), now.plusSeconds(2)).changed());
        }
    }

    @Test
    void fillsOnlyMissingResultsAndPreservesKnownResultsAndOrder() {
        List<Message> history = List.of(Message.user("work"), Message.assistantToolCalls(null, List.of(
                new ToolCall("a", "write_file", "{}"), new ToolCall("b", "write_file", "{}"),
                new ToolCall("c", "read_file", "{}"))), Message.tool("a", "write_file", "written", true, null));
        var result = recovery.recover(snapshot(history, SessionRun.started(now).atIteration(2)), now);
        var messages = result.snapshot().messages();
        assertEquals(5, messages.size());
        assertEquals("written", messages.get(2).getContent());
        assertTrue(messages.get(2).getToolSuccess());
        assertEquals("b", messages.get(3).getToolCallId());
        assertEquals("c", messages.get(4).getToolCallId());
        assertFalse(messages.get(3).getToolSuccess());
        assertEquals(SessionRecovery.UNKNOWN_TOOL_RESULT, messages.get(3).getContent());
        assertEquals(2, result.snapshot().lastRun().iterations());
        assertEquals(5, result.snapshot().contentRevision());
        for (int i = 0; i < history.size(); i++) {
            assertEquals(history.get(i).getId(), messages.get(i).getId());
        }
        assertNotEquals(messages.get(3).getId(), messages.get(4).getId());
        assertFalse(recovery.recover(result.snapshot(), now).changed());
        assertEquals(3, history.size());
    }

    @Test
    void metadataRecoveryDoesNotDirtyPreviouslyOrganizedContent() {
        var source = snapshot(List.of(Message.user("work")), SessionRun.started(now));
        var organized = new SessionSnapshot(source.id(), source.title(), source.workspace(), source.createdAt(),
                source.updatedAt(), source.messages(), source.lastRun(), false, 10,
                new com.demo.memory.SessionMemoryState(10, now, com.demo.memory.CompressionState.empty()));
        var repaired = recovery.recover(organized, now.plusSeconds(1)).snapshot();
        assertEquals(10, repaired.contentRevision());
        assertEquals(organized.memoryState(), repaired.memoryState());
        assertFalse(repaired.dirty());
        assertEquals(SessionRun.Status.INTERRUPTED, repaired.lastRun().status());
    }

    @Test
    void emptyAndFinishedSessionsAreUnchangedButStorageErrorsBecomeInterrupted() {
        SessionSnapshot empty = snapshot(List.of(), null);
        assertFalse(recovery.recover(empty, now).changed());
        SessionRun run = SessionRun.started(now).finish(SessionRun.Status.COMPLETED, now, "done");
        assertFalse(recovery.recover(snapshot(List.of(Message.user("work"), Message.assistant("done")), run), now).changed());
        var failed = snapshot(List.of(Message.user("work")), run.finish(SessionRun.Status.STORAGE_ERROR, now, "save failed"));
        assertEquals(SessionRun.Status.INTERRUPTED, recovery.recover(failed, now).snapshot().lastRun().status());
    }
}
