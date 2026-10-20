package com.demo.session;

import com.demo.config.Config;
import com.demo.config.MemoryConfig;
import com.demo.llm.LLMClient;
import com.demo.memory.HistoryEntry;
import com.demo.memory.MemoryHarness;
import com.demo.memory.storage.ArchiveCommitter;
import com.demo.memory.storage.HistoryRepository;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.session.storage.JsonSessionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ContextPreparationIntegrationTest {
    @TempDir Path temp;
    private final Instant now = Instant.parse("2026-10-20T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private String id() { return UUID.randomUUID().toString(); }
    private Config enabled() { return Config.defaults().withMemory(MemoryConfig.defaults().withEnabled(true)); }
    private Path global() { return temp.resolve("global"); }
    private JsonSessionRepository repository() { return new JsonSessionRepository(temp.resolve("sessions")); }

    @BeforeEach void isolateProjectFromTheRepositoryContainingBuildTemp() throws Exception {
        Files.createDirectory(temp.resolve(".git"));
    }

    private DefaultSessionService service(Config config, LLMClient model, JsonSessionRepository repository) {
        return new DefaultSessionService(config, model, repository, clock, DefaultSessionService.ClientOwnership.BORROWED,
                new MemoryHarness(config.getMemory(), config.getMaxTokens(), global()));
    }

    private String item(String body, boolean active) {
        return "<!-- memory:item id=\"" + id() + "\" kind=\"rule\" status=\"" + (active ? "active" : "inactive")
                + "\" -->\n- " + body + "\n<!-- /memory:item -->\n";
    }

    private SessionSnapshot stored(JsonSessionRepository repository, Path workspace, List<Message> messages) {
        var snapshot = new SessionSnapshot(id(), "existing", workspace.toAbsolutePath(), now, now, messages,
                SessionRun.started(now).finish(SessionRun.Status.COMPLETED, now, "done"), false);
        repository.save(snapshot);
        return repository.load(snapshot.id());
    }

    private HistoryEntry entry(SessionSnapshot snapshot, int from, int to, String summary) {
        var covered = snapshot.messages().subList(from, to).stream().map(Message::getId).toList();
        return new HistoryEntry(id(), id(), snapshot.id(), snapshot.contentRevision(), covered,
                snapshot.memoryState().compression().historyId(), "Archived work", summary,
                List.of("API"), covered, now, "test-v1");
    }

    @Test void enabledReadsActiveLayersFromGitRootAndLeavesMissingStorageUncreated() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.createDirectory(project.resolve(".git"));
        Path child = Files.createDirectories(project.resolve("module"));
        Path memory = Files.createDirectories(project.resolve(".coding-agent"));
        Files.writeString(memory.resolve("MEMORY.md"), item("Use Java 17", true) + item("Obsolete rule", false));
        List<List<Message>> requests = new ArrayList<>();
        LLMClient model = (messages, tools) -> { requests.add(messages); return new LLMClient.LLMResponse("done", null); };
        var sessions = repository();
        try (var service = service(enabled(), model, sessions)) {
            String sessionId = service.createSession(child).id();
            var handle = service.submitTurn(sessionId, "current request");
            assertEquals(SessionRun.Status.COMPLETED, handle.completion().toCompletableFuture().join().lastRun().status());
            String input = requests.get(0).stream().map(Message::getContent).reduce("", (left, right) -> left + right);
            assertTrue(input.contains("Use Java 17"));
            assertFalse(input.contains("Obsolete rule"));
            assertFalse(Files.exists(global()));
            assertFalse(Files.exists(memory.resolve("local")));
            assertFalse(Files.exists(child.resolve(".coding-agent")));
            assertTrue(handle.eventsAfter(0).stream().anyMatch(event -> event.type() == SessionEvent.Type.CONTEXT_PREPARED));
            Files.createDirectories(global());
            Files.writeString(global().resolve("MEMORY.md"), item("Explain in Chinese", true));
            Files.writeString(memory.resolve("MEMORY.md"), item("Use Java 21", true));
            service.runTurn(sessionId, "next request");
            var second = requests.get(1);
            assertTrue(second.get(1).getContent().contains("Explain in Chinese"));
            assertTrue(second.get(2).getContent().contains("Use Java 21"));
            assertFalse(second.stream().anyMatch(message -> message.getContent() != null && message.getContent().contains("Use Java 17")));
        }
    }

    @Test void aCompleteCommittedSummaryChainReplacesOnlyItsPrefixAfterRestart() throws Exception {
        var sessions = repository();
        var original = stored(sessions, temp.toRealPath(), List.of(Message.user("old constraint"),
                Message.assistant("old answer"), Message.user("old follow-up"), Message.assistant("old second answer")));
        var history = new HistoryRepository(temp.resolve(".coding-agent"));
        var committer = new ArchiveCommitter(sessions, history);
        var first = committer.commit(original, entry(original, 0, 2, "Preserve public API"), now);
        var archived = committer.commit(first, entry(first, 2, 4, "Preserve public API; tests pending"), now);
        var rawBefore = archived.messages().stream().map(Message::toJson).toList();
        AtomicInteger calls = new AtomicInteger();
        LLMClient model = (messages, tools) -> {
            calls.incrementAndGet();
            assertTrue(messages.stream().anyMatch(message -> message.getContent().contains("Preserve public API; tests pending")));
            assertFalse(messages.stream().anyMatch(message -> "old answer".equals(message.getContent())));
            assertEquals("continue", messages.get(messages.size() - 1).getContent());
            return new LLMClient.LLMResponse("new answer", null);
        };
        try (var service = service(enabled(), model, sessions)) {
            var result = service.runTurn(original.id(), "continue");
            assertEquals(SessionRun.Status.COMPLETED, result.lastRun().status());
            assertEquals(archived.memoryState(), result.memoryState());
            assertEquals(rawBefore, result.messages().subList(0, 4).stream().map(Message::toJson).toList());
            assertEquals(6, result.messages().size());
        }
        assertEquals(1, calls.get());
    }

    @Test void uncommittedSummaryBlocksWithoutDeletingRawHistoryOrReplayingOperations() throws Exception {
        var sessions = repository();
        var original = stored(sessions, temp.toRealPath(), List.of(Message.user("must remain"), Message.assistant("old")));
        var history = new HistoryRepository(temp.resolve(".coding-agent"));
        var crashing = new ArchiveCommitter(sessions, history) {
            @Override protected void checkpoint(Step step) { if (step == Step.CHECKPOINTED) throw new IllegalStateException("crash"); }
        };
        assertThrows(IllegalStateException.class, () -> crashing.commit(original, entry(original, 0, 2, "summary"), now));
        var before = sessions.load(original.id());
        assertTrue(history.read().entries().isEmpty());
        LLMClient never = (messages, tools) -> { fail("Must not send an unverified summary"); return null; };
        try (var service = service(enabled(), never, sessions)) {
            var result = service.runTurn(original.id(), "continue");
            assertEquals(SessionRun.Status.CONTEXT_BLOCKED, result.lastRun().status());
            assertTrue(result.lastRun().message().contains("UNCOMMITTED_SUMMARY"));
            assertEquals(before.memoryState(), result.memoryState());
            assertEquals(3, result.messages().size());
            assertEquals("must remain", result.messages().get(0).getContent());
        }
    }

    @Test void oversizedInputIsPersistedAsBlockedAndCanResumeAfterBudgetIsIncreased() throws Exception {
        var sessions = repository();
        var defaults = MemoryConfig.defaults();
        var small = new MemoryConfig(true, 16000, 2048, .8, .6, 1024, 3072, 1200, 2,
                defaults.consolidation(), defaults.maintenance());
        var smallConfig = Config.defaults().withMemory(small);
        String user = "中".repeat(13000);
        LLMClient never = (messages, tools) -> { fail("Over-budget input must not be sent"); return null; };
        String sessionId;
        try (var service = service(smallConfig, never, sessions)) {
            sessionId = service.createSession(temp).id();
            var run = service.submitTurn(sessionId, user);
            var result = run.completion().toCompletableFuture().join();
            assertEquals(SessionRun.Status.CONTEXT_BLOCKED, result.lastRun().status());
            assertTrue(result.lastRun().message().contains("BUDGET_EXCEEDED"));
            assertEquals(1, result.messages().size());
            assertEquals(user, result.messages().get(0).getContent());
            assertFalse(run.eventsAfter(0).stream().anyMatch(event -> event.type() == SessionEvent.Type.LLM_REQUEST_STARTED));
            assertEquals(SessionRun.Status.CONTEXT_BLOCKED, sessions.load(sessionId).lastRun().status());
        }
        try (var service = service(enabled(), (messages, tools) -> {
            assertTrue(messages.stream().anyMatch(message -> user.equals(message.getContent())));
            return new LLMClient.LLMResponse("done", null);
        }, sessions)) {
            var result = service.runTurn(sessionId, "continue");
            assertEquals(SessionRun.Status.COMPLETED, result.lastRun().status());
            assertEquals(3, result.messages().size());
        }
    }

    @Test void largeToolResultBlocksTheNextRequestAndPreservesCompleteProtocol() throws Exception {
        var calls = new AtomicInteger();
        LLMClient model = (messages, tools) -> {
            assertEquals(0, calls.getAndIncrement());
            return new LLMClient.LLMResponse(null, List.of(new ToolCall("many", "list_files", "{}")));
        };
        // Use a fake tool to exceed the budget without changing production tool output limits.
        var context = new com.demo.agent.Context();
        context.addUserMessage("work");
        context.setPreparation((state, token) -> {
            return new com.demo.memory.ContextAssembler(new com.demo.memory.TokenEstimator(),
                    new com.demo.memory.ContextBudget(enabled().getMemory(), 2048))
                    .assemble("", "[]", "", "", com.demo.memory.CompressionState.empty(), state.getMessages());
        });
        var tool = new com.demo.tools.Tool() {
            public String getName() { return "list_files"; }
            public String getDescription() { return "test"; }
            public com.demo.model.ToolResult execute(String arguments) { return com.demo.model.ToolResult.success(getName(), "中".repeat(40000)); }
        };
        var result = new com.demo.agent.AgentLoop(model, java.util.Map.of("list_files", tool)).run(context);
        assertEquals(com.demo.agent.AgentRunResult.Status.CONTEXT_BLOCKED, result.getStatus());
        assertEquals(1, calls.get());
        assertEquals(3, context.getMessages().size());
        assertEquals(40000, context.getMessages().get(2).getContent().length());
        assertTrue(new com.demo.memory.ConversationBlockBuilder().build(context.getMessages()).stream().allMatch(block -> block.complete()));
    }

    @Test void malformedMemoryBlocksAndDoesNotLeakFileContentToTranscript() throws Exception {
        Path memory = Files.createDirectories(temp.resolve(".coding-agent"));
        Files.writeString(memory.resolve("MEMORY.md"), "<!-- memory:item secret-invalid-marker -->");
        LLMClient never = (messages, tools) -> { fail("Malformed memory must not be silently skipped"); return null; };
        var sessions = repository();
        try (var service = service(enabled(), never, sessions)) {
            var result = service.runTurn(service.createSession(temp).id(), "work");
            assertEquals(SessionRun.Status.CONTEXT_BLOCKED, result.lastRun().status());
            assertTrue(result.lastRun().message().contains("MEMORY_UNAVAILABLE"));
            assertFalse(result.lastRun().message().contains("secret-invalid-marker"));
            assertEquals(1, result.messages().size());
        }
    }

    @Test void disabledMemoryDoesNotReadMalformedFilesAndKeepsLegacyBehavior() throws Exception {
        Path memory = Files.createDirectories(temp.resolve(".coding-agent"));
        Files.writeString(memory.resolve("MEMORY.md"), "<!-- memory:item invalid -->");
        var sessions = repository();
        try (var service = service(Config.defaults(), (messages, tools) -> new LLMClient.LLMResponse("done", null), sessions)) {
            var run = service.submitTurn(service.createSession(temp).id(), "work");
            assertEquals(SessionRun.Status.COMPLETED, run.completion().toCompletableFuture().join().lastRun().status());
            assertFalse(run.eventsAfter(0).stream().anyMatch(event -> event.type() == SessionEvent.Type.CONTEXT_PREPARED));
        }
    }
}
