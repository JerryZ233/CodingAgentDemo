package com.demo.memory.storage;

import com.demo.memory.model.MemoryEdit;
import com.demo.memory.model.MemoryUpdate;
import com.demo.storage.StorageFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One directory/scope. No model calls, global authorization, or automatic initialization on read. */
public class LongTermMemoryRepository {
    private static final String DOCUMENT = "MEMORY.md";
    private static final String PENDING = "local/transactions/memory.json";
    private static final int MAX_DOCUMENT_BYTES = 1024 * 1024;
    private final StorageFiles files;
    private final MemoryMarkdown markdown = new MemoryMarkdown();
    private final MemoryJournal changes;
    public LongTermMemoryRepository(Path directory) { this(new StorageFiles(directory)); }
    public LongTermMemoryRepository(StorageFiles files) {
        this.files = files;
        changes = new MemoryJournal(files, "local/changes.jsonl");
    }

    public enum Step { PREPARED, REPLACED, AUDITED }
    protected void checkpoint(Step step) { }

    public MemoryDocument read() {
        try { return readDocument(); }
        catch (IOException | SecurityException failure) { throw MemoryStorageException.io(failure); }
        catch (IllegalArgumentException failure) { throw MemoryStorageException.malformed(failure); }
    }
    public java.util.Optional<MemoryUpdate> pendingUpdate() {
        try {
            var bytes = files.read(PENDING);
            return bytes.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(MemoryJson.decode(bytes.get(), Transaction.class).update());
        }
        catch (IOException failure) { throw MemoryStorageException.io(failure); }
    }

    private MemoryDocument readDocument() throws IOException {
        var bytes = files.read(DOCUMENT);
        if (bytes.isPresent() && bytes.get().length > MAX_DOCUMENT_BYTES) throw new IllegalArgumentException("Memory document exceeds limit");
        String text = bytes.isEmpty() ? "" : MemoryJson.text(bytes.get());
        return new MemoryDocument(bytes.isPresent(), bytes.isEmpty() ? "missing" : MemoryJson.version(bytes.get()),
                text, markdown.read(text));
    }

    public MemoryDocument apply(MemoryUpdate update) {
        try (var lock = files.lock(MemoryDirectory.LOCK)) {
            MemoryDirectory.initialize(files);
            recoverLocked();
            var previous = findChange(update.operationId());
            if (previous != null) {
                if (!previous.update().equals(update)) throw MemoryStorageException.conflict();
                return readDocument();
            }
            var before = readDocument();
            if (!before.version().equals(update.expectedVersion())) throw MemoryStorageException.conflict();
            String after = markdown.apply(before.text(), update.edits());
            boolean exists = before.exists() || update.edits().stream().anyMatch(edit -> edit.action() != MemoryEdit.Action.IGNORE);
            if (MemoryJson.bytes(after).length > MAX_DOCUMENT_BYTES) throw new IllegalArgumentException("Memory document exceeds limit");
            var transaction = new Transaction(1, update, before.exists(), before.text(), exists, after);
            backup(transaction);
            files.replace(PENDING, MemoryJson.bytes(MemoryJson.GSON.toJson(transaction)));
            checkpoint(Step.PREPARED);
            complete(transaction);
            return readDocument();
        } catch (IOException | SecurityException failure) { throw MemoryStorageException.io(failure); }
        catch (IllegalArgumentException failure) { throw MemoryStorageException.malformed(failure); }
    }

    /** Pure preview for host budget checks and preparing a recoverable sequence. */
    public MemoryDocument preview(MemoryDocument before, List<MemoryEdit> edits) {
        String after = markdown.apply(before.text(), edits);
        boolean exists = before.exists() || edits.stream().anyMatch(e -> e.action() != MemoryEdit.Action.IGNORE);
        return new MemoryDocument(exists, exists ? MemoryJson.version(after) : "missing", after, markdown.read(after));
    }

    /** Explicit write operation; ordinary reads are safe in read-only clones. */
    public MemoryDocument recover() {
        try (var lock = files.lock(MemoryDirectory.LOCK)) {
            recoverLocked();
            return readDocument();
        } catch (IOException | SecurityException failure) { throw MemoryStorageException.io(failure); }
        catch (IllegalArgumentException failure) { throw MemoryStorageException.malformed(failure); }
    }

    private void recoverLocked() throws IOException {
        var bytes = files.read(PENDING);
        if (bytes.isEmpty()) return;
        var transaction = MemoryJson.decode(bytes.get(), Transaction.class);
        var change = findChange(transaction.update().operationId());
        if (change != null) {
            if (!change.equals(change(transaction))) throw MemoryStorageException.conflict();
            files.delete(PENDING); // A later manual edit must not be rolled back after audit committed.
        } else {
            backup(transaction);
            complete(transaction);
        }
    }

    private void backup(Transaction transaction) throws IOException {
        if (!transaction.beforeExists()) return;
        String path = "local/versions/memory-" + transaction.update().operationId() + ".md";
        var old = files.read(path);
        byte[] bytes = MemoryJson.bytes(transaction.before());
        if (old.isPresent() && !java.util.Arrays.equals(old.get(), bytes)) throw MemoryStorageException.conflict();
        if (old.isEmpty()) files.replace(path, bytes);
    }

    private void complete(Transaction transaction) throws IOException {
        var document = readDocument();
        var change = change(transaction);
        if (document.version().equals(change.beforeVersion())) {
            if (transaction.afterExists()) files.replace(DOCUMENT, MemoryJson.bytes(transaction.after()));
        } else if (!document.version().equals(change.afterVersion())) {
            throw MemoryStorageException.conflict();
        }
        checkpoint(Step.REPLACED);
        changes.append("MEMORY_APPLIED", change);
        checkpoint(Step.AUDITED);
        files.delete(PENDING);
    }

    public List<Change> changes() {
        try { return readChanges(); }
        catch (IOException | SecurityException failure) { throw MemoryStorageException.io(failure); }
        catch (IllegalArgumentException failure) { throw MemoryStorageException.malformed(failure); }
    }

    private Change findChange(String id) throws IOException {
        return readChanges().stream().filter(change -> change.update().operationId().equals(id)).findFirst().orElse(null);
    }

    private List<Change> readChanges() throws IOException {
        List<Change> result = new ArrayList<>();
        for (var event : changes.read().events()) {
            if (!event.type().equals("MEMORY_APPLIED")) throw new IllegalArgumentException("Unknown memory change event");
            var value = MemoryJson.decode(MemoryJson.bytes(event.data().toString()), Change.class);
            var previous = result.stream().filter(change -> change.update().operationId().equals(value.update().operationId())).findFirst();
            if (previous.isPresent() && !previous.get().equals(value)) throw MemoryStorageException.conflict();
            if (previous.isEmpty()) result.add(value);
        }
        return List.copyOf(result);
    }

    private Change change(Transaction transaction) {
        return new Change(transaction.update(), transaction.update().expectedVersion(),
                transaction.afterExists() ? MemoryJson.version(transaction.after()) : "missing");
    }

    public record Change(MemoryUpdate update, String beforeVersion, String afterVersion) {
        public Change {
            Objects.requireNonNull(update);
            if (!Objects.equals(beforeVersion, update.expectedVersion()) || afterVersion == null
                    || !(afterVersion.equals("missing") || afterVersion.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("Invalid change versions");
            }
        }
    }

    private record Transaction(int schemaVersion, MemoryUpdate update, boolean beforeExists, String before,
                               boolean afterExists, String after) {
        private Transaction {
            if (schemaVersion != 1) throw new MemoryStorageException(MemoryStorageException.Reason.UNSUPPORTED_VERSION,
                    "Unsupported memory transaction version");
            Objects.requireNonNull(update);
            Objects.requireNonNull(before);
            Objects.requireNonNull(after);
            if (!update.expectedVersion().equals(beforeExists ? MemoryJson.version(before) : "missing")
                    || (!beforeExists && !before.isEmpty()) || (!afterExists && !after.isEmpty())
                    || afterExists != (beforeExists || update.edits().stream().anyMatch(edit -> edit.action() != MemoryEdit.Action.IGNORE))
                    || !new MemoryMarkdown().apply(before, update.edits()).equals(after)) {
                throw new IllegalArgumentException("Invalid memory transaction");
            }
        }
    }
}
