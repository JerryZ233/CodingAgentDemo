package com.demo.session;

import com.demo.memory.HistoryEntry;
import com.demo.memory.storage.*;
import com.demo.model.Message;
import com.demo.session.storage.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SessionArchiveCoordinatorTest {
    @TempDir Path temp;
    private final Instant now = Instant.parse("2026-10-20T00:00:00Z");
    private String id() { return UUID.randomUUID().toString(); }

    private Session stored(JsonSessionRepository repository) {
        var snapshot = new SessionSnapshot(id(), "session", temp.toAbsolutePath(), now, now,
                List.of(Message.user("Keep public API"), Message.assistant("old work"), Message.user("current")),
                SessionRun.started(now), false);
        repository.save(snapshot);
        var session = new Session(repository.load(snapshot.id()));
        session.persisted = session.published;
        return session;
    }

    private HistoryEntry entry(SessionSnapshot snapshot) {
        var covered = snapshot.messages().subList(0, 2).stream().map(Message::getId).toList();
        return new HistoryEntry(id(), id(), snapshot.id(), snapshot.contentRevision(), covered, null,
                "Old work", "Keep public API", List.of(), covered, now, "test-v1");
    }

    @Test void failureAfterSessionCheckpointRecoversCommitAndUpdatesLiveMetadataWithoutReplacingContext() {
        var repository = new JsonSessionRepository(temp.resolve("sessions"));
        var session = stored(repository);
        var originalContext = session.context;
        var before = session.published;
        var entry = entry(before);
        Path memory = temp.resolve("memory");
        var coordinator = new SessionArchiveCoordinator(session, repository, directory ->
                new ArchiveCommitter(repository, new HistoryRepository(directory)) {
                    @Override protected void checkpoint(Step step) {
                        if (step == Step.CHECKPOINTED) throw new MemoryStorageException(MemoryStorageException.Reason.IO_FAILURE, "simulated commit-marker failure");
                    }
                });
        coordinator.commit(memory, before, entry, now);
        assertSame(originalContext, session.context);
        assertEquals(entry.id(), session.memoryState.compression().historyId());
        assertEquals(3, session.context.getMessages().size());
        assertEquals(3, session.context.getContentRevision());
        assertTrue(session.published.samePersistedState(repository.load(session.id)));
        assertTrue(session.persisted.samePersistedState(session.published));
        assertEquals(List.of(entry), new HistoryRepository(memory).read().entries());
    }

    @Test void externalContentChangesAreNotAdoptedOrOverwrittenByCompaction() {
        var repository = new JsonSessionRepository(temp.resolve("sessions"));
        var session = stored(repository);
        var expected = session.published;
        var external = new SessionSnapshot(session.id, "external title", session.workspace, now, now,
                expected.messages(), expected.lastRun(), false, expected.contentRevision(), expected.memoryState());
        repository.save(external);
        var failure = assertThrows(SessionStorageException.class, () -> new SessionArchiveCoordinator(session, repository)
                .commit(temp.resolve("memory"), expected, entry(expected), now));
        assertEquals(SessionStorageException.Reason.CONFLICT, failure.getReason());
        assertTrue(external.samePersistedState(repository.load(session.id)));
        assertEquals(expected.memoryState(), session.memoryState);
    }
}
