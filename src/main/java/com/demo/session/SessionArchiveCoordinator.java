package com.demo.session;

import com.demo.memory.CompactionException;
import com.demo.memory.HistoryEntry;
import com.demo.memory.SessionArchiveAccess;
import com.demo.memory.storage.ArchiveCommitter;
import com.demo.memory.storage.HistoryRepository;
import com.demo.memory.storage.MemoryStorageException;
import com.demo.session.storage.SessionRepository;
import com.demo.session.storage.SessionStorageException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;

/** One admitted foreground writer; filesystem CAS protects it from another process. */
final class SessionArchiveCoordinator implements SessionArchiveAccess {
    private final Session session;
    private final SessionRepository repository;
    private final Function<Path, ArchiveCommitter> committers;

    SessionArchiveCoordinator(Session session, SessionRepository repository) {
        this(session, repository, path -> new ArchiveCommitter(repository, new HistoryRepository(path)));
    }

    SessionArchiveCoordinator(Session session, SessionRepository repository, Function<Path, ArchiveCommitter> committers) {
        this.session = session;
        this.repository = repository;
        this.committers = committers;
    }

    public SessionSnapshot snapshot() { return session.publish(); }

    public void recover(Path directory) {
        try { committers.apply(directory).recover(session.id); }
        catch (MemoryStorageException failed) {
            throw new SessionStorageException(SessionStorageException.Reason.IO_FAILURE, "Archive recovery failed", failed);
        }
    }

    public void commit(Path directory, SessionSnapshot expected, HistoryEntry entry, Instant organizedAt) {
        if (!snapshot().samePersistedState(expected) || session.unsaved) {
            throw new SessionStorageException(SessionStorageException.Reason.CONFLICT, "Live session changed before compaction commit");
        }
        var committer = committers.apply(directory);
        try { adopt(expected, committer.commit(expected, entry, organizedAt)); }
        catch (MemoryStorageException | SessionStorageException failed) {
            // A checkpoint may have succeeded before the history commit marker failed.
            var actual = repository.load(session.id);
            if (!sameContentAndRun(expected, actual)) {
                throw new SessionStorageException(SessionStorageException.Reason.CONFLICT, "Session changed during compaction", failed);
            }
            if (actual.samePersistedState(expected)) {
                throw new CompactionException(CompactionException.Reason.PERSISTENCE_FAILED, failed);
            }
            if (!entry.id().equals(actual.memoryState().compression().historyId())) {
                throw new SessionStorageException(SessionStorageException.Reason.CONFLICT, "Another archive changed the session", failed);
            }
            adopt(expected, actual);
            recover(directory);
        }
    }

    private void adopt(SessionSnapshot expected, SessionSnapshot replacement) {
        if (!sameContentAndRun(expected, replacement)) {
            throw new SessionStorageException(SessionStorageException.Reason.CONFLICT, "Archive changed raw content");
        }
        session.memoryState = replacement.memoryState();
        session.updatedAt = replacement.updatedAt();
        session.persisted = replacement;
        session.unsaved = false;
        session.publish(); // Keep the same Context reference held by AgentLoop.
    }

    private boolean sameContentAndRun(SessionSnapshot left, SessionSnapshot right) {
        return left.id().equals(right.id()) && left.title().equals(right.title()) && left.workspace().equals(right.workspace())
                && left.createdAt().equals(right.createdAt()) && left.contentRevision() == right.contentRevision()
                && Objects.equals(left.lastRun(), right.lastRun())
                && left.messages().stream().map(com.demo.model.Message::toJson).toList()
                    .equals(right.messages().stream().map(com.demo.model.Message::toJson).toList());
    }
}
