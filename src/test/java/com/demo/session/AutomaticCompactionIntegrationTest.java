package com.demo.session;

import com.demo.config.*;
import com.demo.execution.CancellationToken;
import com.demo.llm.*;
import com.demo.memory.*;
import com.demo.memory.storage.HistoryRepository;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.session.storage.JsonSessionRepository;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticCompactionIntegrationTest {
    @TempDir Path temp;
    private final Instant now = Instant.parse("2026-10-20T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final MemoryConfig memory = new MemoryConfig(true, 16000, 1000, .8, .6, 500, 500, 400, 2,
            MemoryConfig.Consolidation.defaults(), MemoryConfig.Maintenance.defaults());
    private final Config config = Config.defaults().withMemory(memory);
    private JsonSessionRepository repository;

    @BeforeEach void isolate() throws Exception {
        Files.createDirectory(temp.resolve(".git"));
        repository = new JsonSessionRepository(temp.resolve("sessions"));
    }

    private DefaultSessionService service(LLMClient model) {
        return new DefaultSessionService(config, model, repository, clock, DefaultSessionService.ClientOwnership.BORROWED,
                new MemoryHarness(memory, config.getMaxTokens(), temp.resolve("global")));
    }

    private SessionSnapshot stored(List<Message> raw) {
        var snapshot = new SessionSnapshot(UUID.randomUUID().toString(), "old session", temp.toAbsolutePath(), now, now,
                raw, SessionRun.started(now).finish(SessionRun.Status.COMPLETED, now, "old answer"), false);
        repository.save(snapshot);
        return repository.load(snapshot.id());
    }

    private List<Message> old(int length) {
        return List.of(Message.user("Do not modify public API"), Message.assistant("中".repeat(length)), Message.assistant("Tests remain pending"));
    }

    @Test void compactsMultipleBatchesThenFinalCheckpointsAndRestartsPreserveTheNewSummary() {
        List<Message> raw = new ArrayList<>();
        raw.add(Message.user("Do not modify public API"));
        for (int i = 0; i < 10; i++) raw.add(Message.assistant("中".repeat(3000)));
        raw.add(Message.assistant("Tests remain pending"));
        var original = stored(raw);
        var summaries = new AtomicInteger();
        var coding = new AtomicInteger();
        LLMClient model = SummaryFixtures.client((request, purpose) -> {
            if (purpose == RequestPurpose.COMPACTION) {
                summaries.incrementAndGet();
                return new LLMClient.LLMResponse(SummaryFixtures.json(SummaryFixtures.sources(request)), null);
            }
            coding.incrementAndGet();
            assertTrue(request.stream().anyMatch(message -> message.getContent().contains("Do not modify public API")));
            assertTrue(request.stream().anyMatch(message -> message.getContent().contains("Tests remain pending")));
            assertTrue(request.stream().noneMatch(message -> original.messages().get(1).getId().equals(message.getId())));
            return new LLMClient.LLMResponse("done", null);
        });
        SessionSnapshot completed;
        try (var service = service(model)) {
            var handle = service.submitTurn(original.id(), "continue");
            completed = handle.completion().toCompletableFuture().join();
            assertEquals(SessionRun.Status.COMPLETED, completed.lastRun().status());
            assertEquals(1, completed.lastRun().iterations());
            assertTrue(completed.memoryState().compression().version() > 1);
            assertEquals(original.messages().stream().map(Message::toJson).toList(),
                    completed.messages().subList(0, raw.size()).stream().map(Message::toJson).toList());
            assertTrue(repository.load(original.id()).samePersistedState(completed));
            assertTrue(handle.eventsAfter(0).stream().anyMatch(event -> event.type() == SessionEvent.Type.MEMORY_COMPACTION));
        }
        assertTrue(summaries.get() > 1);
        var beforeRestart = completed.memoryState();
        try (var service = service(model)) {
            var restored = service.runTurn(original.id(), "continue after restart");
            assertEquals(SessionRun.Status.COMPLETED, restored.lastRun().status());
            assertEquals(beforeRestart, restored.memoryState());
        }
        assertEquals(2, coding.get());
        assertEquals(beforeRestart.compression().version(), new HistoryRepository(temp.resolve(".coding-agent")).read().entries().size());
    }

    @Test void anOversizedOldMessageIsSummarizedInBoundedFragmentsBeforeOneAtomicArchiveCommit() {
        var original = stored(old(30000));
        var summaries = new AtomicInteger();
        var coding = new AtomicInteger();
        try (var service = service(SummaryFixtures.client((request, purpose) -> {
            if (purpose == RequestPurpose.COMPACTION) {
                summaries.incrementAndGet();
                return new LLMClient.LLMResponse(SummaryFixtures.json(SummaryFixtures.sources(request)), null);
            }
            coding.incrementAndGet();
            assertEquals("continue", request.get(request.size() - 1).getContent());
            return new LLMClient.LLMResponse("done", null);
        }))) {
            var result = service.runTurn(original.id(), "continue");
            assertEquals(SessionRun.Status.COMPLETED, result.lastRun().status());
            assertEquals(1, result.memoryState().compression().version());
            assertEquals(5, result.messages().size());
            assertEquals(30000, result.messages().get(1).getContent().length());
        }
        assertTrue(summaries.get() >= 3);
        assertEquals(1, coding.get());
        assertEquals(1, new HistoryRepository(temp.resolve(".coding-agent")).read().entries().size());
    }

    @Test void invalidSummaryFallsBackOnlyWhenTheCompleteInputStillFits() {
        for (int length : List.of(8500, 16000)) {
            var original = stored(old(length));
            var summaries = new AtomicInteger();
            var coding = new AtomicInteger();
            try (var service = service(SummaryFixtures.client((request, purpose) -> {
                if (purpose == RequestPurpose.COMPACTION) {
                    summaries.incrementAndGet();
                    return new LLMClient.LLMResponse("invalid JSON", null);
                }
                coding.incrementAndGet();
                assertTrue(request.stream().anyMatch(message -> "中".repeat(length).equals(message.getContent())));
                return new LLMClient.LLMResponse("done", null);
            }))) {
                var result = service.runTurn(original.id(), "continue");
                assertEquals(length == 8500 ? SessionRun.Status.COMPLETED : SessionRun.Status.CONTEXT_BLOCKED, result.lastRun().status());
                assertEquals(original.memoryState(), result.memoryState());
                assertEquals(original.contentRevision() + (length == 8500 ? 2 : 1), result.contentRevision());
                assertEquals(2, summaries.get());
                assertEquals(length == 8500 ? 1 : 0, coding.get());
                assertTrue(new HistoryRepository(temp.resolve(".coding-agent")).read().entries().isEmpty());
                if (length == 16000) assertTrue(result.lastRun().message().contains("COMPACTION_FAILED"));
            }
        }
    }

    @Test void aToolObservationCanTriggerCompactionInsideTheSameAgentTurn() throws Exception {
        var original = stored(old(6000));
        Files.writeString(temp.resolve("large.txt"), "中".repeat(4500));
        var coding = new AtomicInteger();
        var summaries = new AtomicInteger();
        try (var service = service(SummaryFixtures.client((request, purpose) -> {
            if (purpose == RequestPurpose.COMPACTION) {
                summaries.incrementAndGet();
                return new LLMClient.LLMResponse(SummaryFixtures.json(SummaryFixtures.sources(request)), null);
            }
            if (coding.incrementAndGet() == 1) {
                assertTrue(request.stream().anyMatch(message -> "中".repeat(6000).equals(message.getContent())));
                return new LLMClient.LLMResponse(null, List.of(new ToolCall("read", "read_file", "{\"path\":\"large.txt\"}")));
            }
            assertTrue(request.stream().anyMatch(message -> message.getContent() != null && message.getContent().contains("Do not modify public API")));
            assertTrue(request.stream().anyMatch(message -> "tool".equals(message.getRole()) && message.getToolCallId().equals("read")));
            assertTrue(request.stream().anyMatch(message -> "read new file".equals(message.getContent())));
            return new LLMClient.LLMResponse("done", null);
        }))) {
            var result = service.runTurn(original.id(), "read new file");
            assertEquals(SessionRun.Status.COMPLETED, result.lastRun().status());
            assertEquals(2, result.lastRun().iterations());
            assertEquals(2, coding.get());
            assertTrue(summaries.get() > 0);
            assertEquals(1, result.memoryState().compression().version());
            assertTrue(repository.load(result.id()).samePersistedState(result));
        }
    }

    @Test void storageWriteFailureBeforeCheckpointDoesNotAdvanceTheCursorAndCanFallBack() throws Exception {
        var original = stored(old(8500));
        Path directory = Files.createDirectories(temp.resolve(".coding-agent"));
        Files.writeString(directory.resolve("local"), "blocks creation of local storage");
        try (var service = service(SummaryFixtures.client((request, purpose) -> new LLMClient.LLMResponse(
                purpose == RequestPurpose.COMPACTION ? SummaryFixtures.json(SummaryFixtures.sources(request)) : "done", null)))) {
            var result = service.runTurn(original.id(), "continue");
            assertEquals(SessionRun.Status.COMPLETED, result.lastRun().status());
            assertEquals(original.memoryState(), result.memoryState());
            assertEquals(5, result.messages().size());
            assertTrue(repository.load(result.id()).samePersistedState(result));
        }
    }

    @Test void cancellationAfterSummaryResponseButBeforeCommitKeepsRawMessagesAndOldMarkers() throws Exception {
        var original = stored(old(16000));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        LLMClient model = SummaryFixtures.client((request, purpose) -> {
            assertEquals(RequestPurpose.COMPACTION, purpose);
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            return new LLMClient.LLMResponse(SummaryFixtures.json(SummaryFixtures.sources(request)), null);
        });
        try (var service = service(model)) {
            var handle = service.submitTurn(original.id(), "continue");
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                handle.cancel();
            } finally { release.countDown(); }
            var result = handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(SessionRun.Status.CANCELLED, result.lastRun().status());
            assertEquals(original.memoryState(), result.memoryState());
            assertEquals(4, result.messages().size());
            assertTrue(new HistoryRepository(temp.resolve(".coding-agent")).read().entries().isEmpty());
        }
    }
}
