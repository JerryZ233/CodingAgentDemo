package com.demo.memory;

import com.demo.memory.compaction.CompactionException;
import com.demo.memory.compaction.CompactionPlanner;
import com.demo.memory.compaction.MemoryModel;
import com.demo.memory.compaction.SessionArchiveAccess;
import com.demo.memory.context.ContextAssembler;
import com.demo.memory.context.ConversationBlockBuilder;
import com.demo.memory.context.ContextBlockedException;
import com.demo.memory.context.ContextBudget;
import com.demo.memory.context.SessionMemoryState;
import com.demo.memory.context.TokenEstimator;
import com.demo.memory.model.HistoryEntry;
import com.demo.memory.model.MemoryItem;
import com.demo.memory.context.ContextPreparation;
import com.demo.config.MemoryConfig;
import com.demo.execution.CancellationToken;
import com.demo.memory.storage.HistoryRepository;
import com.demo.memory.storage.LongTermMemoryRepository;
import com.demo.memory.storage.MemoryDocument;
import com.demo.memory.storage.MemoryStorageException;
import com.demo.model.Message;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Read-only assembly plus optional host-coordinated automatic compaction. */
public final class MemoryHarness {
    public enum CompactionPhase { STARTED, COMPLETED, FAILED, CANCELLED }
    private final MemoryConfig config;
    private final Path globalDirectory;
    private final ContextAssembler assembler;
    private final ProjectLocator projects = new ProjectLocator();

    public MemoryHarness(MemoryConfig config, int outputTokens, Path globalDirectory) {
        this.config = Objects.requireNonNull(config);
        this.globalDirectory = Objects.requireNonNull(globalDirectory).toAbsolutePath().normalize();
        assembler = new ContextAssembler(new TokenEstimator(), new ContextBudget(config, outputTokens));
    }

    public static Path defaultGlobalDirectory() {
        return Path.of(System.getProperty("user.home"), ".coding-agent", "global");
    }
    public Path globalDirectory() { return globalDirectory; }

    public ContextPreparation preparation(String sessionId, Path workspace, Supplier<SessionMemoryState> state) {
        Objects.requireNonNull(state);
        return (context, token) -> prepare(context, token, sessionId, workspace, state.get());
    }

    public ContextPreparation preparation(String sessionId, Path workspace, Supplier<SessionMemoryState> state,
            SessionArchiveAccess archives, MemoryModel model, java.time.Clock clock,
            java.util.function.Consumer<CompactionPhase> phases,
            java.util.function.Consumer<com.demo.llm.LLMFailure> onRetry) {
        return (context, token) -> {
            Attempt attempt;
            try { attempt = attempt(context, token, sessionId, workspace, state.get()); }
            catch (ContextBlockedException blocked) {
                if (blocked.reason() != ContextBlockedException.Reason.UNCOMMITTED_SUMMARY) throw blocked;
                archives.recover(projectDirectory(workspace));
                attempt = attempt(context, token, sessionId, workspace, state.get());
            }
            if (!attempt.report().compactionRecommended()) return attempt.requireInput();
            var planner = new CompactionPlanner(config, new TokenEstimator());
            while (attempt.report().estimatedInputTokens() > attempt.report().compactTargetTokens()) {
                token.throwIfCancellationRequested();
                if (attempt.report().exceeded().stream().anyMatch(limit -> limit == ContextBudget.Limit.GLOBAL_MEMORY
                        || limit == ContextBudget.Limit.PROJECT_MEMORY || limit == ContextBudget.Limit.CONTINUATION_SUMMARY)) {
                    return attempt.requireInput();
                }
                var selected = planner.select(attempt.analysis(), attempt.report());
                if (selected.isEmpty()) return attempt.requireInput();
                var expected = archives.snapshot();
                if (expected.contentRevision() != context.contentRevision()
                        || !expected.messages().stream().map(Message::toJson).toList()
                            .equals(context.messages().stream().map(Message::toJson).toList())) {
                    throw new java.util.ConcurrentModificationException("Session changed since request preparation");
                }
                phases.accept(CompactionPhase.STARTED);
                try {
                    var summary = model.summarize(selected, expected.memoryState().compression().continuationSummary(), token, onRetry);
                    var estimator = new TokenEstimator();
                    long projected = attempt.report().estimatedInputTokens() - estimator.messages(selected)
                            - attempt.report().summaryTokens() + estimator.message(ContextAssembler.summaryMessage(summary.continuationSummary()));
                    if (projected >= attempt.report().estimatedInputTokens()) {
                        throw new CompactionException(CompactionException.Reason.INVALID_OUTPUT);
                    }
                    var observedAt = clock.instant();
                    var now = observedAt.isBefore(expected.updatedAt()) ? expected.updatedAt() : observedAt;
                    var covered = selected.stream().map(Message::getId).toList();
                    var entry = new HistoryEntry(java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(),
                            sessionId, expected.contentRevision(), covered, expected.memoryState().compression().historyId(),
                            summary.archiveSummary(), summary.continuationSummary(), summary.keywords(), summary.sourceRefs(), now, MemoryModel.PROMPT_VERSION);
                    token.throwIfCancellationRequested();
                    archives.commit(projectDirectory(workspace), expected, entry, now);
                    phases.accept(CompactionPhase.COMPLETED);
                } catch (CompactionException failed) {
                    phases.accept(CompactionPhase.FAILED);
                    com.demo.logging.Logging.logger(MemoryHarness.class).warn("Compaction failed reason={}", failed.reason());
                    var fallback = attempt(context, token, sessionId, workspace, state.get());
                    if (fallback.input() != null) return fallback.input();
                    throw new ContextBlockedException(ContextBlockedException.Reason.COMPACTION_FAILED, fallback.report(), failed);
                } catch (com.demo.execution.OperationCancelledException cancelled) {
                    phases.accept(CompactionPhase.CANCELLED); throw cancelled;
                } catch (RuntimeException failed) {
                    phases.accept(CompactionPhase.FAILED); throw failed;
                }
                token.throwIfCancellationRequested();
                attempt = attempt(context, token, sessionId, workspace, state.get());
            }
            return attempt.requireInput();
        };
    }

    private Path projectDirectory(Path workspace) {
        try { return projects.locate(workspace).memoryDirectory(); }
        catch (IOException | SecurityException unavailable) {
            throw new ContextBlockedException(ContextBlockedException.Reason.MEMORY_UNAVAILABLE, null, unavailable);
        }
    }

    private record Attempt(ContextPreparation.Input input, ContextBudget.Report report, ConversationBlockBuilder.Analysis analysis) {
        ContextPreparation.Input requireInput() {
            if (input != null) return input;
            throw new ContextBlockedException(ContextBlockedException.Reason.BUDGET_EXCEEDED, report);
        }
    }

    private Attempt attempt(ContextPreparation.Request context, CancellationToken token, String sessionId, Path workspace, SessionMemoryState state) {
        token.throwIfCancellationRequested();
        var analysis = analyze(context, state);
        try {
            var input = prepare(context, token, sessionId, workspace, state, analysis);
            return new Attempt(input, input.budget().orElseThrow(), analysis);
        } catch (ContextBlockedException blocked) {
            if (blocked.reason() != ContextBlockedException.Reason.BUDGET_EXCEEDED) throw blocked;
            return new Attempt(null, blocked.report().orElseThrow(), analysis);
        }
    }

    private ContextPreparation.Input prepare(ContextPreparation.Request context, CancellationToken token,
            String sessionId, Path workspace, SessionMemoryState state) {
        token.throwIfCancellationRequested();
        return prepare(context, token, sessionId, workspace, state, analyze(context, state));
    }

    private ConversationBlockBuilder.Analysis analyze(ContextPreparation.Request request, SessionMemoryState state) {
        try { return new ConversationBlockBuilder().analyze(request.messages(), state.compression(), new TokenEstimator()); }
        catch (IllegalArgumentException invalid) {
            throw new ContextBlockedException(ContextBlockedException.Reason.INVALID_HISTORY, null, invalid);
        }
    }

    private ContextPreparation.Input prepare(ContextPreparation.Request context, CancellationToken token,
            String sessionId, Path workspace, SessionMemoryState state, ConversationBlockBuilder.Analysis analysis) {
        token.throwIfCancellationRequested();
        try {
            Path projectDirectory = projects.locate(workspace).memoryDirectory();
            var raw = context.messages();
            state.validateRevision(context.contentRevision());
            if (state.compression().version() > 0) {
                verifySummary(sessionId, raw, state, new HistoryRepository(projectDirectory).read().entries(), analysis.covered());
            }
            String global = activeText(new LongTermMemoryRepository(globalDirectory).read());
            String project = activeText(new LongTermMemoryRepository(projectDirectory).read());
            token.throwIfCancellationRequested();
            return assembler.assemble(context.systemPrompt(), context.toolsDescription(),
                    global, project, state.compression(), raw, analysis);
        } catch (IOException | MemoryStorageException | SecurityException unavailable) {
            throw new ContextBlockedException(ContextBlockedException.Reason.MEMORY_UNAVAILABLE, null, unavailable);
        } catch (IllegalArgumentException invalid) {
            throw new ContextBlockedException(ContextBlockedException.Reason.INVALID_HISTORY, null, invalid);
        }
    }

    private static String activeText(MemoryDocument document) {
        return document.items().stream().filter(MemoryItem::active).map(MemoryItem::body)
                .collect(Collectors.joining("\n\n"));
    }

    private static void verifySummary(String sessionId, List<Message> raw, SessionMemoryState state,
                                      List<HistoryEntry> entries, int covered) {
        var compression = state.compression();
        var byId = new HashMap<String, HistoryEntry>();
        entries.forEach(entry -> byId.put(entry.id(), entry));
        var chain = new ArrayList<HistoryEntry>();
        var seen = new HashSet<String>();
        for (String id = compression.historyId(); id != null;) {
            var entry = byId.get(id);
            if (!seen.add(id) || entry == null || !sessionId.equals(entry.sessionId())
                    || entry.sourceRevision() > state.lastOrganizedRevision()) {
                throw new ContextBlockedException(ContextBlockedException.Reason.UNCOMMITTED_SUMMARY, null);
            }
            chain.add(entry);
            id = entry.previousSummaryId();
        }
        var latest = chain.get(0);
        if (chain.size() != compression.version()
                || latest.sourceRevision() != state.lastOrganizedRevision()
                || !latest.continuationSummary().equals(compression.continuationSummary())
                || !latest.coveredThroughMessageId().equals(compression.coveredThroughMessageId())) {
            throw new ContextBlockedException(ContextBlockedException.Reason.UNCOMMITTED_SUMMARY, null);
        }
        List<String> coveredIds = new ArrayList<>();
        long previousRevision = 0;
        for (int index = chain.size() - 1; index >= 0; index--) {
            var entry = chain.get(index);
            if (entry.sourceRevision() < previousRevision) {
                throw new ContextBlockedException(ContextBlockedException.Reason.UNCOMMITTED_SUMMARY, null);
            }
            coveredIds.addAll(entry.coveredMessageIds());
            previousRevision = entry.sourceRevision();
        }
        if (!raw.subList(0, covered).stream().map(Message::getId).toList().equals(coveredIds)) {
            throw new ContextBlockedException(ContextBlockedException.Reason.UNCOMMITTED_SUMMARY, null);
        }
    }
}
