package com.demo.memory.storage;

import com.demo.memory.HistoryEntry;
import com.demo.model.Message;
import com.demo.storage.StorageFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Append-only archive journal. Readers see commit-marked entries only, in commit order. */
public final class HistoryRepository {
    final StorageFiles files;
    private final MemoryJournal journal;
    public HistoryRepository(Path directory) { this(new StorageFiles(directory)); }
    public HistoryRepository(StorageFiles files) {
        this.files = files;
        journal = new MemoryJournal(files, "local/history.jsonl");
    }

    public record Snapshot(List<HistoryEntry> entries, boolean incompleteTail) {
        public Snapshot { entries = List.copyOf(entries); }
    }

    public Snapshot read() {
        try {
            State state = state();
            return new Snapshot(state.committed.stream().map(state.byOperation::get).toList(), state.incompleteTail);
        } catch (IOException | SecurityException failure) { throw MemoryStorageException.io(failure); }
        catch (IllegalArgumentException failure) { throw MemoryStorageException.malformed(failure); }
    }

    State state() throws IOException {
        var read = journal.read();
        var state = new State(read.incompleteTail());
        for (var event : read.events()) {
            switch (event.type()) {
                case "ARCHIVE_PREPARED" -> {
                    var entry = MemoryJson.decode(MemoryJson.bytes(event.data().toString()), HistoryEntry.class);
                    var previous = state.byOperation.putIfAbsent(entry.operationId(), entry);
                    var previousId = state.byId.putIfAbsent(entry.id(), entry);
                    if ((previous != null && !previous.equals(entry)) || (previousId != null && !previousId.equals(entry))) {
                        throw new MemoryStorageException(MemoryStorageException.Reason.INCONSISTENT_STATE, "Conflicting archive identity");
                    }
                }
                case "ARCHIVE_COMMITTED" -> {
                    var marker = MemoryJson.decode(MemoryJson.bytes(event.data().toString()), Commit.class);
                    if (!state.byOperation.containsKey(marker.operationId())) {
                        throw new IllegalArgumentException("Commit references missing archive");
                    }
                    state.committed.add(marker.operationId());
                }
                default -> throw new IllegalArgumentException("Unknown history journal event");
            }
        }
        return state;
    }

    void prepare(HistoryEntry entry) throws IOException {
        State state = state();
        var previous = state.byOperation.get(entry.operationId());
        if (previous != null) {
            if (!previous.equals(entry)) throw MemoryStorageException.conflict();
            return;
        }
        if (state.byId.containsKey(entry.id())) throw MemoryStorageException.conflict();
        journal.append("ARCHIVE_PREPARED", entry);
    }

    void commit(String operationId) throws IOException {
        State state = state();
        if (!state.byOperation.containsKey(operationId)) throw new IllegalArgumentException("Unknown archive operation");
        if (!state.committed.contains(operationId)) journal.append("ARCHIVE_COMMITTED", new Commit(operationId));
    }

    private record Commit(String operationId) {
        private Commit { Message.requireId(operationId); }
    }

    static final class State {
        final Map<String, HistoryEntry> byOperation = new LinkedHashMap<>();
        final Map<String, HistoryEntry> byId = new LinkedHashMap<>();
        final Set<String> committed = new LinkedHashSet<>();
        final boolean incompleteTail;
        State(boolean incompleteTail) { this.incompleteTail = incompleteTail; }
    }
}
