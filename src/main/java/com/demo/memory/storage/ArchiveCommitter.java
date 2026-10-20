package com.demo.memory.storage;

import com.demo.memory.context.CompressionState;
import com.demo.memory.model.HistoryEntry;
import com.demo.memory.context.SessionMemoryState;
import com.demo.model.Message;
import com.demo.session.SessionSnapshot;
import com.demo.session.storage.SessionRepository;
import com.demo.session.storage.SessionStorageException;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Disk transaction boundary. Foreground/background admission is added by the runtime coordinator later. */
public class ArchiveCommitter {
    private final SessionRepository sessions;
    private final HistoryRepository history;
    public enum Step { PREPARED, ARCHIVED, CHECKPOINTED, COMMITTED }
    protected void checkpoint(Step step) { }
    public ArchiveCommitter(SessionRepository sessions, HistoryRepository history) {
        this.sessions = Objects.requireNonNull(sessions);
        this.history = Objects.requireNonNull(history);
    }

    public SessionSnapshot commit(SessionSnapshot expected, HistoryEntry entry, Instant organizedAt) {
        try (var lock = history.files.lock(MemoryDirectory.LOCK)) {
            MemoryDirectory.initialize(history.files);
            var state = history.state();
            var existing = state.byOperation.get(entry.operationId());
            if (existing != null && !existing.equals(entry)) throw MemoryStorageException.conflict();
            var existingId = state.byId.get(entry.id());
            if (existingId != null && !existingId.equals(entry)) throw MemoryStorageException.conflict();
            var actual = sessions.load(expected.id());
            if (existing != null && references(actual, entry, state)) {
                history.commit(entry.operationId());
                history.files.delete(transactionPath(entry.operationId()));
                return actual;
            }
            if (state.committed.contains(entry.operationId())) {
                throw new MemoryStorageException(MemoryStorageException.Reason.INCONSISTENT_STATE, "Committed archive is no longer referenced");
            }
            if (!actual.samePersistedState(expected)) throw MemoryStorageException.conflict();
            var replacement = replacement(expected, entry, organizedAt);
            // Prior summaries must already be committed before extending them.
            if (entry.previousSummaryId() != null) {
                var previous = state.byId.get(entry.previousSummaryId());
                if (previous == null || !state.committed.contains(previous.operationId())) {
                    throw new MemoryStorageException(MemoryStorageException.Reason.INCONSISTENT_STATE, "Previous summary is not committed");
                }
            }
            String transaction = transactionPath(entry.operationId());
            var pending = history.files.read(transaction);
            if (pending.isPresent() && !MemoryJson.decode(pending.get(), Transaction.class).entry().equals(entry)) {
                throw MemoryStorageException.conflict();
            }
            history.files.replace(transaction, MemoryJson.bytes(MemoryJson.GSON.toJson(new Transaction(1, entry))));
            checkpoint(Step.PREPARED);
            history.prepare(entry);
            checkpoint(Step.ARCHIVED);
            sessions.compareAndSave(expected, replacement);
            checkpoint(Step.CHECKPOINTED);
            history.commit(entry.operationId());
            checkpoint(Step.COMMITTED);
            history.files.delete(transaction);
            return replacement;
        } catch (IOException | SecurityException failure) { throw MemoryStorageException.io(failure); }
        catch (IllegalArgumentException failure) { throw MemoryStorageException.malformed(failure); }
    }

    private SessionSnapshot replacement(SessionSnapshot expected, HistoryEntry entry, Instant organizedAt) {
        if (expected.unsaved() || !entry.sessionId().equals(expected.id()) || entry.sourceRevision() != expected.contentRevision()
                || !Objects.equals(entry.previousSummaryId(), expected.memoryState().compression().historyId())
                || organizedAt.isBefore(expected.updatedAt()) || entry.createdAt().isBefore(expected.createdAt())
                || entry.createdAt().isAfter(organizedAt)) {
            throw new IllegalArgumentException("Archive does not match a saved session snapshot");
        }
        int start = expected.memoryState().compression().coveredCount(expected.messages());
        int end = start + entry.coveredMessageIds().size();
        if (end > expected.messages().size() || !expected.messages().subList(start, end).stream().map(Message::getId).toList()
                .equals(entry.coveredMessageIds())) throw new IllegalArgumentException("Archive must cover the next contiguous prefix");
        var compression = new CompressionState(entry.coveredThroughMessageId(), entry.id(), entry.continuationSummary(),
                Math.incrementExact(expected.memoryState().compression().version()));
        return new SessionSnapshot(expected.id(), expected.title(), expected.workspace(), expected.createdAt(), organizedAt,
                expected.messages(), expected.lastRun(), false, expected.contentRevision(),
                new SessionMemoryState(entry.sourceRevision(), organizedAt, compression));
    }

    public record Recovery(int committed, int pending) { }
    private record Transaction(int schemaVersion, HistoryEntry entry) {
        private Transaction {
            if (schemaVersion != 1) throw new MemoryStorageException(MemoryStorageException.Reason.UNSUPPORTED_VERSION,
                    "Unsupported archive transaction version");
            Objects.requireNonNull(entry);
        }
    }

    /** Recovery input only; these entries must never be returned by normal history search. */
    public List<HistoryEntry> pending() {
        try {
            var state = history.state();
            var result = new LinkedHashMap<String, HistoryEntry>();
            for (var entry : state.byOperation.values()) {
                if (!state.committed.contains(entry.operationId())) result.put(entry.operationId(), entry);
            }
            for (String name : history.files.list("local/transactions")) {
                if (!name.startsWith("archive-") || !name.endsWith(".json")) continue;
                var entry = readTransaction(name);
                if (entry == null) continue; // A concurrent successful commit may remove its transaction.
                var existing = state.byOperation.get(entry.operationId());
                if (existing != null && !existing.equals(entry)) throw MemoryStorageException.conflict();
                if (!state.committed.contains(entry.operationId())) result.put(entry.operationId(), entry);
            }
            return List.copyOf(result.values());
        } catch (IOException | SecurityException failure) { throw MemoryStorageException.io(failure); }
        catch (IllegalArgumentException failure) { throw MemoryStorageException.malformed(failure); }
    }

    private HistoryEntry readTransaction(String name) throws IOException {
        var bytes = history.files.read("local/transactions/" + name);
        if (bytes.isEmpty()) return null;
        var entry = MemoryJson.decode(bytes.get(), Transaction.class).entry();
        if (!name.equals("archive-" + entry.operationId() + ".json")) throw new IllegalArgumentException("Archive transaction identity mismatch");
        return entry;
    }

    public Recovery recover() {
        return recover(null);
    }

    /** Runtime recovery is restricted to its own session; it never repairs another active writer. */
    public Recovery recover(String sessionId) {
        try (var lock = history.files.lock(MemoryDirectory.LOCK)) {
            var state = history.state();
            int committed = 0, pending = 0;
            // Transactions created before the journal append are preserved and can be retried.
            for (String name : history.files.list("local/transactions")) {
                if (!name.startsWith("archive-") || !name.endsWith(".json")) continue;
                var entry = readTransaction(name);
                if (entry == null) continue;
                if (sessionId != null && !sessionId.equals(entry.sessionId())) continue;
                var existing = state.byOperation.get(entry.operationId());
                if (existing != null && !existing.equals(entry)) throw MemoryStorageException.conflict();
                if (existing == null) pending++;
                if (state.committed.contains(entry.operationId())) history.files.delete(transactionPath(entry.operationId()));
            }
            for (var entry : state.byOperation.values()) {
                if (sessionId != null && !sessionId.equals(entry.sessionId())) continue;
                if (state.committed.contains(entry.operationId())) continue;
                SessionSnapshot snapshot;
                try { snapshot = sessions.load(entry.sessionId()); }
                catch (SessionStorageException failure) {
                    if (failure.getReason() != SessionStorageException.Reason.NOT_FOUND) throw failure;
                    pending++;
                    continue;
                }
                if (references(snapshot, entry, state)) {
                    history.commit(entry.operationId());
                    history.files.delete(transactionPath(entry.operationId()));
                    committed++;
                } else pending++;
            }
            return new Recovery(committed, pending);
        } catch (IOException | SecurityException failure) { throw MemoryStorageException.io(failure); }
        catch (IllegalArgumentException failure) { throw MemoryStorageException.malformed(failure); }
    }

    private boolean references(SessionSnapshot snapshot, HistoryEntry wanted, HistoryRepository.State state) {
        String id = snapshot.memoryState().compression().historyId();
        var seen = new HashSet<String>();
        int expectedEnd = snapshot.memoryState().compression().coveredCount(snapshot.messages());
        boolean head = true;
        while (id != null) {
            if (!seen.add(id)) throw new IllegalArgumentException("History summary cycle");
            var entry = state.byId.get(id);
            if (entry == null) return false;
            int start = expectedEnd - entry.coveredMessageIds().size();
            if (!entry.sessionId().equals(snapshot.id()) || entry.sourceRevision() > snapshot.contentRevision() || start < 0
                    || !snapshot.messages().subList(start, expectedEnd).stream().map(Message::getId).toList().equals(entry.coveredMessageIds())
                    || (head && !entry.continuationSummary().equals(snapshot.memoryState().compression().continuationSummary()))) {
                throw new IllegalArgumentException("Session/archive reference mismatch");
            }
            if (id.equals(wanted.id())) return true;
            expectedEnd = start;
            id = entry.previousSummaryId();
            head = false;
        }
        return false;
    }

    private String transactionPath(String operationId) { return "local/transactions/archive-" + operationId + ".json"; }
}
