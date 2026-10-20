package com.demo.memory.history;

import com.demo.memory.context.SessionMemoryState;
import com.demo.memory.context.TokenEstimator;
import com.demo.memory.model.HistoryEntry;
import com.demo.execution.*;
import com.demo.memory.storage.*;
import com.demo.model.Message;
import com.demo.session.*;
import com.demo.session.storage.JsonSessionRepository;
import com.demo.tools.support.ToolLimits;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HistorySearchServiceTest {
    @TempDir Path temp;
    private final Instant now = Instant.parse("2026-10-20T00:00:00Z");
    private Path project;
    private JsonSessionRepository sessions;
    private HistoryRepository history;
    private HistorySearchService service;
    private String id() { return UUID.randomUUID().toString(); }

    @BeforeEach void setup() throws Exception {
        project = Files.createDirectory(temp.resolve("project"));
        Files.createDirectory(project.resolve(".git"));
        sessions = new JsonSessionRepository(temp.resolve("sessions"));
        history = new HistoryRepository(project.resolve(".coding-agent"));
        service = new HistorySearchService(project, sessions);
    }

    private HistoryEntry archive(Path workspace, String summary, List<String> keywords, Instant when, String raw) {
        var messages = List.of(Message.user(raw), Message.assistant("original observation"));
        var snapshot = new SessionSnapshot(id(), "old", workspace.toAbsolutePath(), now, when, messages,
                SessionRun.started(now).finish(SessionRun.Status.COMPLETED, when, "done"), false);
        sessions.save(snapshot);
        var ids = messages.stream().map(Message::getId).toList();
        var entry = new HistoryEntry(id(), id(), snapshot.id(), snapshot.contentRevision(), ids, null,
                summary, "Continuation: " + summary, keywords, ids, when, "test-v1");
        new ArchiveCommitter(sessions, history).commit(snapshot, entry, when);
        return entry;
    }

    private HistoryEntry archive(String summary, List<String> keywords, int seconds) {
        return archive(project, summary, keywords, now.plusSeconds(seconds), "original user request");
    }

    @Test void ranksRelevanceBeforeTimeAndSupportsChineseAndCaseInsensitiveCodeIdentifiers() {
        var relevant = archive("实现会话恢复；工具配置丢失尚未解决", List.of("记忆功能", "ConfigLoader"), 1);
        var recent = archive("记忆功能的普通说明", List.of(), 2);
        var result = service.search("记忆功能", 5, CancellationToken.none());
        assertEquals(relevant.id(), result.getAsJsonArray("results").get(0).getAsJsonObject().get("history_id").getAsString());
        assertEquals(2, result.get("matched_count").getAsInt());
        assertTrue(result.get("complete").getAsBoolean());
        assertEquals(relevant.id(), service.search("configloader", 5, CancellationToken.none()).getAsJsonArray("results")
                .get(0).getAsJsonObject().get("history_id").getAsString());
        var sameRelevance = archive(recent.archiveSummary(), List.of(), 3);
        var tied = service.search("普通说明", 5, CancellationToken.none()).getAsJsonArray("results");
        assertEquals(sameRelevance.id(), tied.get(0).getAsJsonObject().get("history_id").getAsString());
    }

    @Test void englishWordsDoNotMatchIdentifierPrefixesAndChineseSingleCharactersStillMatch() {
        archive("Use JavaScript; 正在整理会话", List.of(), 1);
        assertEquals(0, service.search("Java", 5, CancellationToken.none()).get("matched_count").getAsInt());
        assertEquals(1, service.search("会", 5, CancellationToken.none()).get("matched_count").getAsInt());
    }

    @Test void readsCurrentCommitsWithoutCachingAndNeverCreatesMissingMemoryDirectories() {
        assertTrue(service.search("anything", 5, CancellationToken.none()).getAsJsonArray("results").isEmpty());
        assertFalse(Files.exists(project.resolve(".coding-agent")));
        archive("needle first", List.of(), 1);
        assertEquals(1, service.search("needle", 5, CancellationToken.none()).get("matched_count").getAsInt());
        archive("needle second", List.of(), 1);
        assertEquals(2, service.search("needle", 5, CancellationToken.none()).get("matched_count").getAsInt());
    }

    @Test void uncommittedEntriesAreNotSearchableOrReadable() {
        var messages = List.of(Message.user("raw"));
        var snapshot = new SessionSnapshot(id(), "old", project.toAbsolutePath(), now, now, messages,
                SessionRun.started(now).finish(SessionRun.Status.COMPLETED, now, "done"), false);
        sessions.save(snapshot);
        var ids = messages.stream().map(Message::getId).toList();
        var entry = new HistoryEntry(id(), id(), snapshot.id(), 1, ids, null, "needle", "summary", List.of(), ids, now, "test");
        var crashing = new ArchiveCommitter(sessions, history) {
            @Override protected void checkpoint(Step step) { if (step == Step.ARCHIVED) throw new IllegalStateException("crash"); }
        };
        assertThrows(IllegalStateException.class, () -> crashing.commit(snapshot, entry, now));
        assertEquals(0, service.search("needle", 5, CancellationToken.none()).get("matched_count").getAsInt());
        assertEquals(HistoryAccessException.Reason.NOT_FOUND, assertThrows(HistoryAccessException.class,
                () -> service.read(entry.id(), false, CancellationToken.none())).reason());
    }

    @Test void missingSourcesDoNotHideArchiveAndSourcesAreNeverLoadedFromAnotherStore() throws Exception {
        var entry = archive("needle summary", List.of("needle"), 1);
        Files.delete(sessions.directory().resolve(entry.sessionId() + ".json"));
        var result = service.read(entry.id(), true, CancellationToken.none());
        assertEquals("needle summary", result.get("archive_summary").getAsString());
        assertEquals("NOT_FOUND", result.get("source_status").getAsString());
        assertTrue(result.getAsJsonArray("sources").isEmpty());
        assertFalse(result.get("complete").getAsBoolean());
        assertEquals("NOT_FOUND", service.search("needle", 5, CancellationToken.none()).getAsJsonArray("results")
                .get(0).getAsJsonObject().get("source_status").getAsString());
    }

    @Test void sourceOwnershipAndCoveredIdentitiesAreVerifiedBeforeReturningRawMessages() throws Exception {
        Path other = Files.createDirectory(temp.resolve("other"));
        Files.createDirectory(other.resolve(".git"));
        var foreign = archive(other, "copied needle archive", List.of(), now, "secret-foreign-source");
        var rejected = service.read(foreign.id(), true, CancellationToken.none());
        assertEquals("PROJECT_MISMATCH", rejected.get("source_status").getAsString());
        assertFalse(rejected.toString().contains("secret-foreign-source"));
        var local = archive("local needle", List.of(), 1);
        var original = sessions.load(local.sessionId());
        sessions.save(new SessionSnapshot(original.id(), original.title(), original.workspace(), original.createdAt(), original.updatedAt(),
                List.of(Message.user("replacement-secret")), original.lastRun(), false, original.contentRevision() + 1, SessionMemoryState.empty()));
        var invalid = service.read(local.id(), true, CancellationToken.none());
        assertEquals("INVALID_SOURCE", invalid.get("source_status").getAsString());
        assertFalse(invalid.toString().contains("replacement-secret"));
    }

    @Test void siblingWorkspacesShareHistoryButAChangedGitBoundaryCannotRedirectAnExistingService() throws Exception {
        var entry = archive("needle", List.of(), 1);
        var child = Files.createDirectory(project.resolve("module"));
        var sibling = new HistorySearchService(child, sessions);
        assertEquals(entry.id(), sibling.search("needle", 5, CancellationToken.none()).getAsJsonArray("results")
                .get(0).getAsJsonObject().get("history_id").getAsString());
        Files.createDirectory(child.resolve(".git"));
        assertEquals(HistoryAccessException.Reason.PROJECT_CHANGED, assertThrows(HistoryAccessException.class,
                () -> sibling.search("needle", 5, CancellationToken.none())).reason());
        assertFalse(Files.exists(child.resolve(".coding-agent")));
    }

    @Test void resultCountAndSerializedOutputAreBoundedAndUnicodePreviewsRemainValid() {
        for (int i = 0; i < 12; i++) archive("😀".repeat(300) + "记忆功能" + "😀".repeat(300), List.of("记忆功能"), i);
        var limited = service.search("记忆功能", 1, CancellationToken.none());
        assertEquals(1, limited.get("returned_count").getAsInt());
        assertEquals(12, limited.get("matched_count").getAsInt());
        assertFalse(limited.get("complete").getAsBoolean());
        assertTrue(limited.get("limit_reasons").toString().contains("RESULT_LIMIT"));
        var maximum = service.search("记忆功能", 10, CancellationToken.none());
        assertTrue(maximum.get("returned_count").getAsInt() <= 10);
        assertBounded(maximum);
        String preview = limited.getAsJsonArray("results").get(0).getAsJsonObject().get("summary_preview").getAsString();
        assertTrue(preview.contains("记忆功能"));
        for (int i = 0; i < preview.length(); i++) {
            char value = preview.charAt(i);
            if (Character.isHighSurrogate(value)) assertTrue(i + 1 < preview.length() && Character.isLowSurrogate(preview.charAt(++i)));
            else assertFalse(Character.isLowSurrogate(value));
        }
    }

    @Test void longDetailsAndSourcesAreExplicitlyTruncatedToTheSharedOutputBudget() {
        var entry = archive(project, "中\"\\".repeat(10000), List.of("needle"), now, "source".repeat(10000));
        var result = service.read(entry.id(), true, CancellationToken.none());
        assertBounded(result);
        assertFalse(result.get("complete").getAsBoolean());
        assertTrue(result.get("archive_summary_truncated").getAsBoolean());
        assertEquals("AVAILABLE", result.get("source_status").getAsString());
        assertTrue(result.get("limit_reasons").toString().contains("OUTPUT_BUDGET"));
    }

    @Test void incompleteTailIsExplicitAndMalformedCompleteRecordsDoNotLookLikeEmptyHistory() throws Exception {
        var entry = archive("needle", List.of(), 1);
        Path journal = project.resolve(".coding-agent/local/history.jsonl");
        String good = Files.readString(journal);
        Files.writeString(journal, "{partial", StandardOpenOption.APPEND);
        var partial = service.search("needle", 5, CancellationToken.none());
        assertEquals(1, partial.get("matched_count").getAsInt());
        assertFalse(partial.get("complete").getAsBoolean());
        Files.writeString(journal, good + "{bad}\n");
        assertEquals(HistoryAccessException.Reason.HISTORY_UNAVAILABLE, assertThrows(HistoryAccessException.class,
                () -> service.read(entry.id(), false, CancellationToken.none())).reason());
    }

    @Test void invalidInputAndCancellationDoNotPerformWrites() {
        for (String query : List.of(" ", "???", "中".repeat(1025))) {
            assertThrows(IllegalArgumentException.class, () -> service.search(query, 5, CancellationToken.none()));
        }
        assertThrows(IllegalArgumentException.class, () -> service.search("query", 11, CancellationToken.none()));
        assertThrows(IllegalArgumentException.class, () -> service.read("../../elsewhere", true, CancellationToken.none()));
        var source = new CancellationSource();
        source.cancel();
        assertThrows(OperationCancelledException.class, () -> service.search("query", 5, source.token()));
        assertThrows(OperationCancelledException.class, () -> service.read(id(), true, source.token()));
        assertFalse(Files.exists(project.resolve(".coding-agent")));
    }

    @Test void cancellationDuringJournalScanClosesTheReadWithoutChangingStoredData() throws Exception {
        archive("needle " + "中".repeat(10000), List.of(), 1);
        Path journal = project.resolve(".coding-agent/local/history.jsonl");
        byte[] before = Files.readAllBytes(journal);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var token = new CancellationToken() {
            public boolean isCancellationRequested() { return checks.incrementAndGet() >= 4; }
            public Registration onCancellation(Runnable action) { return () -> {}; }
        };
        assertThrows(OperationCancelledException.class, () -> service.search("needle", 5, token));
        assertArrayEquals(before, Files.readAllBytes(journal));
    }

    private static void assertBounded(JsonObject result) {
        assertTrue(result.toString().length() <= ToolLimits.MESSAGE_CHARS);
        assertTrue(new TokenEstimator().text(result.toString()) <= HistorySearchService.OUTPUT_TOKENS);
    }
}
