package com.demo.memory.consolidation;

import com.demo.memory.context.TokenEstimator;
import com.demo.memory.model.HistoryEntry;
import com.demo.memory.model.MemoryEdit;
import com.demo.memory.model.MemoryItem;
import com.demo.memory.model.MemoryProposal;
import com.demo.memory.model.MemorySource;
import com.demo.memory.ProjectLocator;
import com.demo.config.MemoryConfig;
import com.demo.execution.CancellationToken;
import com.demo.memory.storage.*;
import com.demo.session.SessionSnapshot;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

/** Host-bound project; durable plans precede writes, cursor advances only after every outcome is durable. */
public final class ConsolidationService {
    private final Path workspace;
    private final ProjectLocator.Project project;
    private final MemoryConfig config;
    private final LongTermMemoryRepository local, global;
    private final ConsolidationRepository state;
    private final ConsolidationModel model;
    private final Clock clock;
    public ConsolidationService(Path workspace, Path globalDirectory, MemoryConfig config, ConsolidationModel model, Clock clock) {
        this.workspace = workspace; this.project = locate(workspace); this.config = config;
        local = new LongTermMemoryRepository(project.memoryDirectory()); global = new LongTermMemoryRepository(globalDirectory);
        state = new ConsolidationRepository(project.memoryDirectory()); this.model = model; this.clock = clock;
    }
    private void boundary() {
        if (!locate(workspace).root().equals(project.root())) throw new IllegalStateException("Project boundary changed");
    }
    private static ProjectLocator.Project locate(Path path) {
        try { return new ProjectLocator().locate(path); }
        catch (java.io.IOException e) { throw new IllegalStateException("Project unavailable", e); }
    }
    private static long memoryCost(MemoryDocument document) {
        String text = document.items().stream().filter(MemoryItem::active).map(MemoryItem::body).collect(java.util.stream.Collectors.joining("\n\n"));
        return text.isBlank() ? 0 : new TokenEstimator().message(com.demo.model.Message.user("[Recorded background: Project long-term memory]\n" + text + "\n[End recorded background]"));
    }
    public int unprocessed(CancellationToken token) {
        boundary(); var done = state.read().processed();
        return (int) new HistoryRepository(project.memoryDirectory()).read(token).entries().stream().filter(h -> !done.contains(h.id())).count();
    }
    public boolean needsRecovery() {
        var saved=state.read();return !saved.prepared().isEmpty() || !saved.batch().isEmpty();
    }
    public int consolidate(boolean force, CancellationToken token) {
        boundary(); token.throwIfCancellationRequested(); recover(token);
        var before = state.read();
        var batch = new HistoryRepository(project.memoryDirectory()).read(token).entries().stream()
                .filter(h -> !before.processed().contains(h.id())).limit(config.consolidation().batchSize()).toList();
        if (batch.isEmpty() || !force && unprocessed(token) < config.consolidation().newHistoryThreshold()) return 0;
        var localDoc = local.read(); var globalDoc = global.read();
        while (batch.size() > 1 && !model.fits(batch, localDoc, globalDoc)) batch = batch.subList(0, batch.size() - 1);
        var proposals = model.extract(batch, localDoc, globalDoc, clock.instant(), token);
        boundary(); token.throwIfCancellationRequested();
        if (!local.read().version().equals(localDoc.version()) || !global.read().version().equals(globalDoc.version()))
            throw new IllegalStateException("Memory changed during extraction; retry");
        List<MemoryProposal> pending = new ArrayList<>(before.pending()), prepared = new ArrayList<>();
        var projected = localDoc;
        for (var proposal : proposals) {
            var scopeDocument = proposal.scope() == MemoryProposal.Scope.PROJECT ? projected : globalDoc;
            if (proposal.edit().action() == MemoryEdit.Action.ADD && scopeDocument.items().stream().anyMatch(i -> i.active()
                    && i.body().equals(proposal.edit().body()) && i.kind() == proposal.edit().kind())) {
                prepared.add(duplicateOutcome(proposal, projected.version())); continue;
            }
            if (pending.stream().anyMatch(p -> p.scope() == proposal.scope() && p.edit().action() == proposal.edit().action()
                    && Objects.equals(p.edit().body(), proposal.edit().body()) && p.edit().kind() == proposal.edit().kind()
                    && (p.edit().action() == MemoryEdit.Action.ADD || Objects.equals(p.edit().itemId(), proposal.edit().itemId())))) {
                prepared.add(duplicateOutcome(proposal, projected.version())); continue;
            }
            if (!proposal.automatic()) { pending.add(proposal); continue; }
            var next = reversion(proposal, projected.version());
            var preview = local.preview(projected, List.of(next.edit()));
            if (memoryCost(preview) > config.projectBudgetTokens()) {
                pending.add(reversion(proposal, localDoc.version())); continue;
            }
            prepared.add(next); projected = preview;
        }
        state.save(before, before.processed(), pending, prepared, batch.stream().map(HistoryEntry::id).toList());
        recover(token);
        return batch.size();
    }
    private static MemoryProposal reversion(MemoryProposal p, String version) {
        return new MemoryProposal(p.id(), p.scope(), version, p.edit(), p.sources(), p.reason(), p.createdAt(), p.automatic());
    }
    private static MemoryProposal duplicateOutcome(MemoryProposal p, String version) {
        return new MemoryProposal(p.id(), MemoryProposal.Scope.PROJECT, version, MemoryEdit.ignore(), p.sources(),
                "Duplicate knowledge ignored: " + p.reason(), p.createdAt(), true);
    }
    public void recover(CancellationToken token) {
        boundary(); var before = state.read();
        if (before.prepared().isEmpty() && before.batch().isEmpty()) return;
        for (int index = 0; index < before.prepared().size(); index++) {
            var proposal = before.prepared().get(index);
            token.throwIfCancellationRequested(); boundary();
            var repository = proposal.scope() == MemoryProposal.Scope.PROJECT ? local : global;
            try { repository.apply(proposal.update()); } // Stable operation ID makes retries safe, including partial transactions.
            catch (MemoryStorageException conflict) {
                if (conflict.getReason() != MemoryStorageException.Reason.CONFLICT || repository.pendingUpdate().isPresent()) throw conflict;
                // A newer document wins. Unapplied operations become explicit candidates, never rebased writes.
                var pending = new ArrayList<>(before.pending());
                pending.addAll(before.prepared().subList(index, before.prepared().size()));
                var processed = new LinkedHashSet<>(before.processed()); processed.addAll(before.batch());
                state.save(before, List.copyOf(processed), pending, List.of(), List.of());
                return;
            }
        }
        var processed = new LinkedHashSet<>(before.processed()); processed.addAll(before.batch());
        state.save(before, List.copyOf(processed), before.pending(), List.of(), List.of());
    }
    /** Immediate proposals always remain candidates; the source is a real current-session user message. */
    public MemoryProposal propose(SessionSnapshot snapshot, MemoryProposal.Scope scope, MemoryEdit edit, String reason) {
        boundary();
        if (!locate(snapshot.workspace()).root().equals(project.root())) throw new IllegalArgumentException("Foreign source");
        var user = snapshot.messages().stream().filter(m -> m.getRole().equals("user")).reduce((a,b) -> b).orElseThrow();
        var document = (scope == MemoryProposal.Scope.PROJECT ? local : global).read();
        if (edit.action() != MemoryEdit.Action.ADD && document.items().stream().noneMatch(i -> i.id().equals(edit.itemId())))
            throw new IllegalArgumentException("Unknown memory item");
        var proposal = new MemoryProposal(UUID.randomUUID().toString(), scope, document.version(), edit,
                List.of(new MemorySource(snapshot.id(), user.getId(), null)), reason, clock.instant(), false);
        var before = state.read();
        var duplicate = before.pending().stream().filter(p -> p.scope() == scope && p.sources().equals(proposal.sources())
                && p.edit().action() == edit.action() && p.edit().kind() == edit.kind() && Objects.equals(p.edit().body(), edit.body())
                && (edit.action() == MemoryEdit.Action.ADD || Objects.equals(p.edit().itemId(), edit.itemId()))).findFirst();
        if (duplicate.isPresent()) return duplicate.get();
        var pending = new ArrayList<>(before.pending()); pending.add(proposal);
        state.save(before, before.processed(), pending, before.prepared(), before.batch());
        return proposal;
    }
    public record Display(MemoryProposal proposal, String currentBody) { }
    /** The caller binds this exact display to a session; later candidates cannot change its target. */
    public Optional<Display> display() {
        boundary(); var before = state.read();
        if (before.pending().isEmpty()) return Optional.empty();
        var candidate = before.pending().get(0);
        if (candidate.scope() == MemoryProposal.Scope.GLOBAL && global.changes().stream().anyMatch(c -> c.update().equals(candidate.update()))) {
            var pending = new ArrayList<>(before.pending()); pending.remove(candidate);
            var decisions = new ArrayList<>(before.decisions());
            decisions.add(new ConsolidationRepository.Decision(candidate.id(), "APPROVED_GLOBAL_RECOVERED", clock.instant(), null, null));
            state.save(before, before.processed(), pending, before.prepared(), before.batch(), decisions);
            return display();
        }
        var document = (candidate.scope() == MemoryProposal.Scope.PROJECT ? local : global).read();
        boolean interruptedGlobal = candidate.scope() == MemoryProposal.Scope.GLOBAL
                && global.pendingUpdate().filter(candidate.update()::equals).isPresent();
        var refreshed = interruptedGlobal ? candidate : reversion(candidate, document.version());
        if (!refreshed.equals(candidate)) {
            var pending = new ArrayList<>(before.pending()); pending.set(0, refreshed);
            state.save(before, before.processed(), pending, before.prepared(), before.batch());
        }
        var old = document.items().stream().filter(i -> i.id().equals(candidate.edit().itemId())).map(MemoryItem::body).findFirst().orElse("（无已有条目）");
        return Optional.of(new Display(refreshed, old));
    }
    /** Deterministic user intent only; no model gets authority to choose an approval target. */
    public boolean resolve(Display displayed, String userReply, CancellationToken token) {
        return resolve(displayed, userReply, null, token);
    }
    public boolean resolve(Display displayed, String userReply, String actorSessionId, CancellationToken token) {
        String reply = userReply.strip().toLowerCase(Locale.ROOT);
        boolean reject = Set.of("保留旧的", "拒绝", "keep old", "reject").contains(reply);
        boolean approve = displayed.proposal().scope() == MemoryProposal.Scope.GLOBAL
                ? Set.of("批准全局记忆", "approve global memory").contains(reply)
                : Set.of("采用新的", "批准项目记忆", "approve project memory", "use new").contains(reply);
        if (!reject && !approve) return false;
        boundary(); token.throwIfCancellationRequested(); recover(token);
        var before = state.read(); var proposal = displayed.proposal();
        if (!before.pending().contains(proposal)) throw new IllegalStateException("Displayed candidate changed; show it again");
        boolean alreadyApplied = false;
        if (approve) {
            var repository = proposal.scope() == MemoryProposal.Scope.PROJECT ? local : global;
            if (proposal.scope() == MemoryProposal.Scope.GLOBAL) {
                global.recover();
                alreadyApplied = global.changes().stream().anyMatch(change -> change.update().equals(proposal.update()));
            }
            var document = repository.read();
            if (!alreadyApplied && !document.version().equals(proposal.expectedVersion())) throw new IllegalStateException("Memory changed; show candidate again");
            var preview = alreadyApplied ? document : repository.preview(document, List.of(proposal.edit()));
            int budget = proposal.scope() == MemoryProposal.Scope.PROJECT ? config.projectBudgetTokens() : config.globalBudgetTokens();
            if (memoryCost(preview) > budget) throw new IllegalArgumentException("Memory budget exceeded");
        }
        var pending = new ArrayList<>(before.pending()); pending.remove(proposal);
        boolean applyGlobal=approve && !alreadyApplied && proposal.scope()==MemoryProposal.Scope.GLOBAL;
        var decisions = new ArrayList<>(before.decisions());
        decisions.add(new ConsolidationRepository.Decision(proposal.id(), approve ? "APPROVED" : "REJECTED", clock.instant(), userReply, actorSessionId));
        state.save(before, before.processed(), pending, approve && proposal.scope() == MemoryProposal.Scope.PROJECT ? List.of(proposal) : List.of(), List.of(), decisions,
                () -> { if(applyGlobal) { token.throwIfCancellationRequested(); global.apply(proposal.update()); } });
        recover(token); return true;
    }
}
