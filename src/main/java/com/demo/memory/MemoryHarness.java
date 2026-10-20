package com.demo.memory;

import com.demo.agent.Context;
import com.demo.agent.ContextPreparation;
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

/** Stage 3 is read-only: preparation never initializes memory storage or calls a model. */
public final class MemoryHarness {
    private final Path globalDirectory;
    private final ContextAssembler assembler;
    private final ProjectLocator projects = new ProjectLocator();

    public MemoryHarness(MemoryConfig config, int outputTokens, Path globalDirectory) {
        this.globalDirectory = Objects.requireNonNull(globalDirectory).toAbsolutePath().normalize();
        assembler = new ContextAssembler(new TokenEstimator(), new ContextBudget(config, outputTokens));
    }

    public static Path defaultGlobalDirectory() {
        return Path.of(System.getProperty("user.home"), ".coding-agent", "global");
    }

    public ContextPreparation preparation(String sessionId, Path workspace, Supplier<SessionMemoryState> state) {
        Objects.requireNonNull(state);
        return (context, token) -> prepare(context, token, sessionId, workspace, state.get());
    }

    private ContextPreparation.Input prepare(Context context, CancellationToken token,
            String sessionId, Path workspace, SessionMemoryState state) {
        token.throwIfCancellationRequested();
        try {
            Path projectDirectory = projects.locate(workspace).memoryDirectory();
            var raw = context.getMessages();
            state.validateRevision(context.getContentRevision());
            if (state.compression().version() > 0) {
                verifySummary(sessionId, raw, state, new HistoryRepository(projectDirectory).read().entries());
            }
            String global = activeText(new LongTermMemoryRepository(globalDirectory).read());
            String project = activeText(new LongTermMemoryRepository(projectDirectory).read());
            token.throwIfCancellationRequested();
            return assembler.assemble(context.buildSystemPrompt(), context.getToolDescriptions(),
                    global, project, state.compression(), raw);
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
                                      List<HistoryEntry> entries) {
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
        int covered = compression.coveredCount(raw);
        if (!raw.subList(0, covered).stream().map(Message::getId).toList().equals(coveredIds)) {
            throw new ContextBlockedException(ContextBlockedException.Reason.UNCOMMITTED_SUMMARY, null);
        }
    }
}
