package com.demo.memory.storage;

import com.demo.memory.model.MemoryProposal;
import com.demo.storage.StorageFiles;
import java.nio.file.Path;
import java.util.*;

/** Versioned local plan. Prepared operations survive crashes before the processed cursor advances. */
public final class ConsolidationRepository {
    private static final String PATH = "local/consolidation.json";
    private final StorageFiles files;
    public ConsolidationRepository(Path directory) { files = new StorageFiles(directory); }
    public record State(int schemaVersion, long revision, List<String> processed, List<MemoryProposal> pending,
                        List<MemoryProposal> prepared, List<String> batch, List<Decision> decisions) {
        public State {
            if (schemaVersion != 1 || revision < 0) throw new IllegalArgumentException("Invalid consolidation state");
            processed = List.copyOf(processed); pending = List.copyOf(pending);
            prepared = List.copyOf(prepared); batch = List.copyOf(batch);
            decisions = List.copyOf(decisions);
            processed.forEach(com.demo.model.Message::requireId);
            batch.forEach(com.demo.model.Message::requireId);
            if (new HashSet<>(processed).size() != processed.size() || new HashSet<>(batch).size() != batch.size())
                throw new IllegalArgumentException("Duplicate consolidation cursor");
            var ids = new HashSet<String>();
            for (var proposal : pending) if (!ids.add(proposal.id())) throw new IllegalArgumentException("Duplicate candidate");
            for (var proposal : prepared) if (!ids.add(proposal.id())) throw new IllegalArgumentException("Duplicate prepared operation");
            if (prepared.stream().anyMatch(p -> p.scope() == MemoryProposal.Scope.GLOBAL)) throw new IllegalArgumentException("Global approval cannot be replayed from project state");
        }
        public static State empty() { return new State(1, 0, List.of(), List.of(), List.of(), List.of(), List.of()); }
    }
    public record Decision(String proposalId, String outcome, java.time.Instant createdAt, String userReply, String sessionId) {
        public Decision { com.demo.model.Message.requireId(proposalId); if (sessionId != null) com.demo.model.Message.requireId(sessionId); }
    }
    public State read() {
        try { var bytes = files.read(PATH); return bytes.isEmpty() ? State.empty() : MemoryJson.decode(bytes.get(), State.class); }
        catch (java.io.IOException failure) { throw MemoryStorageException.io(failure); }
    }
    public State save(State expected, List<String> processed, List<MemoryProposal> pending,
                      List<MemoryProposal> prepared, List<String> batch) {
        return save(expected, processed, pending, prepared, batch, expected.decisions());
    }
    public State save(State expected, List<String> processed, List<MemoryProposal> pending,
                      List<MemoryProposal> prepared, List<String> batch, List<Decision> decisions) {
        return save(expected,processed,pending,prepared,batch,decisions,() -> {});
    }
    /** A short approved global write may run after candidate CAS, before its durable removal. Never a model call. */
    public State save(State expected, List<String> processed, List<MemoryProposal> pending,
                      List<MemoryProposal> prepared, List<String> batch, List<Decision> decisions, Runnable beforeSave) {
        try (var lock = files.lock("local/consolidation.lock")) {
            if (!read().equals(expected)) throw MemoryStorageException.conflict();
            var next = new State(1, expected.revision() + 1, processed, pending, prepared, batch, decisions);
            try(var memoryLock=files.lock(MemoryDirectory.LOCK)) { MemoryDirectory.initialize(files); }
            beforeSave.run();
            files.replace(PATH, MemoryJson.bytes(MemoryJson.GSON.toJson(next)));
            return next;
        } catch (java.io.IOException failure) { throw MemoryStorageException.io(failure); }
    }
}
