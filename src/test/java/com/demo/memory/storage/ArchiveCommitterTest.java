package com.demo.memory.storage;

import com.demo.memory.HistoryEntry;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.session.SessionRun;
import com.demo.session.SessionSnapshot;
import com.demo.session.storage.JsonSessionRepository;
import com.demo.session.storage.SessionStorageException;
import com.demo.storage.StorageFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveCommitterTest {
    @TempDir Path temp;
    private final Instant now = Instant.parse("2026-10-20T00:00:00Z");
    private String id() { return UUID.randomUUID().toString(); }
    private static final class Crash extends RuntimeException { }

    private SessionSnapshot session(JsonSessionRepository repository) {
        var messages = List.of(Message.user("Keep public API unchanged"),
                Message.assistantToolCalls(null, List.of(new ToolCall("read", "read_file", "{}"))),
                Message.tool("read", "read_file", "Java 17", true, null), Message.assistant("Tests still pending"));
        String id = id();
        repository.save(new SessionSnapshot(id, "project", temp.toAbsolutePath(), now, now, messages,
                SessionRun.started(now).finish(SessionRun.Status.COMPLETED, now, "Tests still pending"), false));
        return repository.load(id);
    }

    private HistoryEntry entry(SessionSnapshot snapshot, int start, int end) {
        var covered = snapshot.messages().subList(start, end).stream().map(Message::getId).toList();
        return new HistoryEntry(id(), id(), snapshot.id(), snapshot.contentRevision(), covered,
                snapshot.memoryState().compression().historyId(), "Observed Java 17; tests pending.",
                "Keep public API unchanged. Tests remain pending.", List.of("Java", "测试"), covered,
                now.plusSeconds(30), "summary-v1");
    }

    @Test void emptyReadsHaveNoSideEffectsAndSuccessfulCommitPreservesRawMessages() {
        Path root = temp.resolve("memory");
        var history = new HistoryRepository(root);
        assertTrue(history.read().entries().isEmpty());
        assertFalse(Files.exists(root));
        var sessions = new JsonSessionRepository(temp.resolve("sessions"));
        var original = session(sessions);
        var entry = entry(original, 0, 3);
        var committer = new ArchiveCommitter(sessions, history);
        var result = committer.commit(original, entry, now.plusSeconds(60));
        assertEquals(original.contentRevision(), result.contentRevision());
        assertEquals(original.messages().stream().map(Message::toJson).toList(), result.messages().stream().map(Message::toJson).toList());
        assertEquals(entry.id(), result.memoryState().compression().historyId());
        assertEquals(3, result.memoryState().compression().coveredCount(result.messages()));
        assertFalse(result.dirty());
        assertEquals(List.of(entry), history.read().entries());
        assertTrue(result.samePersistedState(sessions.load(result.id())));
        assertTrue(result.samePersistedState(committer.commit(original, entry, now.plusSeconds(60))));
        assertTrue(result.samePersistedState(committer.commit(result, entry, now.plusSeconds(60))));
        assertEquals(1, history.read().entries().size());
    }

    @Test void everyCommitBoundaryCanRecoverOrRetryWithoutLosingOrDuplicatingMessages() throws Exception {
        for (var step : ArchiveCommitter.Step.values()) {
            Path root = temp.resolve(step.name());
            var sessions = new JsonSessionRepository(root.resolve("sessions"));
            var original = session(sessions);
            var entry = entry(original, 0, 3);
            var history = new HistoryRepository(root.resolve("memory"));
            var crashing = new ArchiveCommitter(sessions, history) {
                @Override protected void checkpoint(Step current) { if (current == step) throw new Crash(); }
            };
            assertThrows(Crash.class, () -> crashing.commit(original, entry, now.plusSeconds(60)));
            assertEquals(step == ArchiveCommitter.Step.COMMITTED ? 1 : 0, history.read().entries().size());
            var normal = new ArchiveCommitter(sessions, history);
            assertEquals(step == ArchiveCommitter.Step.COMMITTED ? List.of() : List.of(entry), normal.pending());
            var report = normal.recover();
            if (step == ArchiveCommitter.Step.PREPARED || step == ArchiveCommitter.Step.ARCHIVED) {
                assertEquals(1, report.pending());
                assertTrue(original.samePersistedState(sessions.load(original.id())));
                assertTrue(history.read().entries().isEmpty());
            } else assertEquals(1, history.read().entries().size());
            normal.commit(original, entry, now.plusSeconds(60));
            assertEquals(List.of(entry), history.read().entries());
            assertTrue(normal.pending().isEmpty());
            assertEquals(4, sessions.load(original.id()).messages().size());
            assertEquals(4, sessions.load(original.id()).contentRevision());
            assertEquals(2, Files.readAllLines(root.resolve("memory/local/history.jsonl")).size());
            assertFalse(Files.exists(root.resolve("memory/local/transactions/archive-" + entry.operationId() + ".json")));
        }
    }

    @Test void staleSnapshotCannotOverwriteMessagesAddedDuringCommit() {
        Path directory = temp.resolve("sessions");
        var sessions = new JsonSessionRepository(directory);
        var original = session(sessions);
        var history = new HistoryRepository(temp.resolve("memory"));
        var concurrent = new JsonSessionRepository(directory) {
            @Override public void compareAndSave(SessionSnapshot expected, SessionSnapshot replacement) {
                var messages = new ArrayList<>(expected.messages());
                messages.add(Message.user("New requirement"));
                save(new SessionSnapshot(expected.id(), expected.title(), expected.workspace(), expected.createdAt(),
                        now.plusSeconds(45), messages, expected.lastRun(), false,
                        expected.contentRevision() + 1, expected.memoryState()));
                super.compareAndSave(expected, replacement);
            }
        };
        var entry = entry(original, 0, 3);
        var failure = assertThrows(SessionStorageException.class,
                () -> new ArchiveCommitter(concurrent, history).commit(original, entry, now.plusSeconds(60)));
        assertEquals(SessionStorageException.Reason.CONFLICT, failure.getReason());
        assertTrue(history.read().entries().isEmpty());
        var changed = sessions.load(original.id());
        assertEquals(5, changed.messages().size());
        assertEquals("New requirement", changed.messages().get(4).getContent());
        assertNull(changed.memoryState().compression().historyId());
        assertEquals(1, new ArchiveCommitter(sessions, history).recover().pending());
    }

    @Test void rejectsNonContiguousCoverageAndIncompleteToolBlocks() {
        var sessions = new JsonSessionRepository(temp.resolve("sessions"));
        var original = session(sessions);
        var history = new HistoryRepository(temp.resolve("memory"));
        var committer = new ArchiveCommitter(sessions, history);
        for (var entry : List.of(entry(original, 1, 3), entry(original, 0, 2))) {
            assertEquals(MemoryStorageException.Reason.MALFORMED_DATA,
                    assertThrows(MemoryStorageException.class, () -> committer.commit(original, entry, now.plusSeconds(60))).getReason());
            assertTrue(history.read().entries().isEmpty());
            assertTrue(original.samePersistedState(sessions.load(original.id())));
        }
    }

    @Test void extendingSummaryUsesOnlyTheNextPrefixAndPreservesCommitOrder() {
        var sessions = new JsonSessionRepository(temp.resolve("sessions"));
        var original = session(sessions);
        var history = new HistoryRepository(temp.resolve("memory"));
        var committer = new ArchiveCommitter(sessions, history);
        var first = entry(original, 0, 3);
        var updated = committer.commit(original, first, now.plusSeconds(60));
        var second = entry(updated, 3, 4);
        var result = committer.commit(updated, second, now.plusSeconds(120));
        assertEquals(List.of(first, second), history.read().entries());
        assertEquals(2, result.memoryState().compression().version());
        assertEquals(4, result.memoryState().compression().coveredCount(result.messages()));
        assertTrue(result.samePersistedState(committer.commit(original, first, now.plusSeconds(120))));
    }

    @Test void partialCommitMarkerIsHiddenAndRepairedAfterCheckpoint() throws Exception {
        var sessions = new JsonSessionRepository(temp.resolve("sessions"));
        var original = session(sessions);
        Path root = temp.resolve("memory");
        var broken = new StorageFiles(root) {
            @Override public void append(String path, byte[] bytes) throws IOException {
                if (new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("ARCHIVE_COMMITTED")) {
                    super.append(path, java.util.Arrays.copyOf(bytes, 12));
                    throw new IOException("injected partial write");
                }
                super.append(path, bytes);
            }
        };
        var entry = entry(original, 0, 3);
        assertEquals(MemoryStorageException.Reason.IO_FAILURE, assertThrows(MemoryStorageException.class,
                () -> new ArchiveCommitter(sessions, new HistoryRepository(broken)).commit(original, entry, now.plusSeconds(60))).getReason());
        var history = new HistoryRepository(root);
        assertTrue(history.read().incompleteTail());
        assertTrue(history.read().entries().isEmpty());
        assertEquals(entry.id(), sessions.load(original.id()).memoryState().compression().historyId());
        assertEquals(1, new ArchiveCommitter(sessions, history).recover().committed());
        assertFalse(history.read().incompleteTail());
        assertEquals(List.of(entry), history.read().entries());
        try (var backups = Files.list(root.resolve("local/versions"))) { assertEquals(1, backups.count()); }
    }

    @Test void incompleteFinalRecordDoesNotHideEarlierCommittedEntries() throws Exception {
        var sessions = new JsonSessionRepository(temp.resolve("sessions"));
        var original = session(sessions);
        Path root = temp.resolve("memory");
        var history = new HistoryRepository(root);
        var committer = new ArchiveCommitter(sessions, history);
        var first = entry(original, 0, 3);
        var updated = committer.commit(original, first, now.plusSeconds(60));
        Path path = root.resolve("local/history.jsonl");
        Files.writeString(path, "{broken", StandardOpenOption.APPEND);
        assertEquals(List.of(first), history.read().entries());
        assertTrue(history.read().incompleteTail());
        var second = entry(updated, 3, 4);
        committer.commit(updated, second, now.plusSeconds(120));
        assertEquals(List.of(first, second), history.read().entries());
        assertFalse(history.read().incompleteTail());
    }

    @Test void completeMalformedRecordIsNeverSilentlyTruncated() throws Exception {
        Path root = temp.resolve("memory");
        Files.createDirectories(root.resolve("local"));
        Path path = root.resolve("local/history.jsonl");
        Files.writeString(path, "not-json\n");
        byte[] before = Files.readAllBytes(path);
        var history = new HistoryRepository(root);
        assertEquals(MemoryStorageException.Reason.MALFORMED_DATA,
                assertThrows(MemoryStorageException.class, history::read).getReason());
        var sessions = new JsonSessionRepository(temp.resolve("sessions"));
        assertThrows(MemoryStorageException.class, () -> new ArchiveCommitter(sessions, history).recover());
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @Test void futureJournalVersionIsRejectedAndCommitNeedsAnArchive() throws Exception {
        Path root = temp.resolve("memory");
        Files.createDirectories(root.resolve("local"));
        Path path = root.resolve("local/history.jsonl");
        Files.writeString(path, "{\"schemaVersion\":99,\"type\":\"ARCHIVE_PREPARED\",\"data\":{}}\n");
        assertEquals(MemoryStorageException.Reason.UNSUPPORTED_VERSION,
                assertThrows(MemoryStorageException.class, () -> new HistoryRepository(root).read()).getReason());
        Files.writeString(path, "{\"schemaVersion\":1,\"type\":\"ARCHIVE_COMMITTED\",\"data\":{\"operationId\":\"" + id() + "\"}}\n");
        assertEquals(MemoryStorageException.Reason.MALFORMED_DATA,
                assertThrows(MemoryStorageException.class, () -> new HistoryRepository(root).read()).getReason());
    }

    @Test void duplicateOperationWithDifferentContentIsRejected() {
        var sessions = new JsonSessionRepository(temp.resolve("sessions"));
        var original = session(sessions);
        var history = new HistoryRepository(temp.resolve("memory"));
        var entry = entry(original, 0, 3);
        var committer = new ArchiveCommitter(sessions, history);
        committer.commit(original, entry, now.plusSeconds(60));
        var changed = new HistoryEntry(entry.id(), entry.operationId(), entry.sessionId(), entry.sourceRevision(),
                entry.coveredMessageIds(), entry.previousSummaryId(), "Different archive", entry.continuationSummary(),
                entry.keywords(), entry.sourceRefs(), entry.createdAt(), entry.promptVersion());
        assertEquals(MemoryStorageException.Reason.CONFLICT,
                assertThrows(MemoryStorageException.class, () -> committer.commit(original, changed, now.plusSeconds(60))).getReason());
        assertEquals(List.of(entry), history.read().entries());
    }
}
