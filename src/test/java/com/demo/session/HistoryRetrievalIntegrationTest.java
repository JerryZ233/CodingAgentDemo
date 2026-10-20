package com.demo.session;

import com.demo.config.*;
import com.demo.execution.CancellationToken;
import com.demo.llm.LLMClient;
import com.demo.memory.*;
import com.demo.memory.storage.*;
import com.demo.model.*;
import com.demo.session.storage.JsonSessionRepository;
import com.demo.tools.*;
import com.demo.tools.builtin.*;
import com.google.gson.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HistoryRetrievalIntegrationTest {
    @TempDir Path temp;
    private final Instant now = Instant.parse("2026-10-20T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final Config config = Config.defaults().withMemory(MemoryConfig.defaults().withEnabled(true));
    private JsonSessionRepository sessions;
    private String id() { return UUID.randomUUID().toString(); }

    @BeforeEach void setup() throws Exception {
        Files.createDirectory(temp.resolve(".git"));
        sessions = new JsonSessionRepository(temp.resolve("sessions"));
    }

    private HistoryEntry archive(Path project, String summary) {
        var raw = List.of(Message.user("原始需求：保留公共 API"), Message.assistant("old-raw-only"));
        var snapshot = new SessionSnapshot(id(), "old session", project.toAbsolutePath(), now, now, raw,
                SessionRun.started(now).finish(SessionRun.Status.COMPLETED, now, "done"), false);
        sessions.save(snapshot);
        var ids = raw.stream().map(Message::getId).toList();
        var entry = new HistoryEntry(id(), id(), snapshot.id(), snapshot.contentRevision(), ids, null,
                summary, "保留公共 API；工具配置恢复仍未解决", List.of("会话恢复", "工具配置"), ids, now, "test");
        new ArchiveCommitter(sessions, new HistoryRepository(project.resolve(".coding-agent"))).commit(snapshot, entry, now);
        return entry;
    }

    private DefaultSessionService service(Config config, LLMClient client) {
        return new DefaultSessionService(config, client, sessions, clock, DefaultSessionService.ClientOwnership.BORROWED,
                new MemoryHarness(config.getMemory(), config.getMaxTokens(), temp.resolve("global")));
    }

    @Test void newConversationRecallsHistoryThroughToolsAndNeverAutomaticallyLoadsOldRawMessages() {
        var entry = archive(temp, "上次修复会话恢复，独特的历史线索：工具配置仍丢失");
        var requests = new AtomicInteger();
        LLMClient client = (input, tools) -> {
            int request = requests.getAndIncrement();
            if (request == 0) {
                assertTrue(tools.contains("memory_search"));
                assertTrue(tools.contains("memory_read"));
                assertTrue(input.stream().noneMatch(message -> message.getContent() != null
                        && (message.getContent().contains("独特的历史线索") || message.getContent().contains("old-raw-only"))));
                return new LLMClient.LLMResponse(null, List.of(new ToolCall("search", "memory_search", "{\"query\":\"会话恢复\"}")));
            }
            if (request == 1) {
                var result = JsonParser.parseString(input.get(input.size() - 1).getContent()).getAsJsonObject();
                String historyId = result.getAsJsonArray("results").get(0).getAsJsonObject().get("history_id").getAsString();
                assertEquals(entry.id(), historyId);
                return new LLMClient.LLMResponse(null, List.of(new ToolCall("read", "memory_read",
                        "{\"history_id\":\"" + historyId + "\",\"include_sources\":true}")));
            }
            assertEquals(2, request);
            var result = JsonParser.parseString(input.get(input.size() - 1).getContent()).getAsJsonObject();
            assertEquals("AVAILABLE", result.get("source_status").getAsString());
            assertTrue(result.getAsJsonArray("sources").toString().contains("原始需求：保留公共 API"));
            return new LLMClient.LLMResponse("继续处理上次未解决的工具配置恢复。", null);
        };
        try (var service = service(config, client)) {
            String current = service.createSession(temp).id();
            var before = sessions.load(entry.sessionId());
            var result = service.runTurn(current, "继续上次的会话恢复功能");
            assertEquals(SessionRun.Status.COMPLETED, result.lastRun().status());
            assertEquals(3, requests.get());
            assertEquals(6, result.messages().size());
            assertEquals(List.of("memory_search", "memory_read"), result.messages().stream().filter(message -> "tool".equals(message.getRole()))
                    .map(Message::getToolName).toList());
            assertTrue(before.samePersistedState(sessions.load(entry.sessionId())));
            var unchanged = service.getSession(current);
            assertEquals(1, service.searchHistory(current, "工具配置").get("matched_count").getAsInt());
            assertEquals(entry.id(), service.readHistory(current, entry.id(), false).get("history_id").getAsString());
            assertTrue(unchanged.samePersistedState(service.getSession(current)));
        }
    }

    @Test void registeredToolsCannotSelectAnotherProjectOrAddArbitraryPaths() throws Exception {
        Path other = Files.createDirectory(temp.resolve("other"));
        Files.createDirectory(other.resolve(".git"));
        var foreign = archive(other, "foreign archive");
        var registry = ToolRegistry.fromConfig(config, temp).withHistory(new HistorySearchService(temp, sessions));
        var read = registry.getTools().get("memory_read");
        assertEquals(Set.of(ToolSpec.Capability.MEMORY_READ), read.getCapabilities());
        var missing = read.execute("{\"history_id\":\"" + foreign.id() + "\"}");
        assertFalse(missing.isSuccess());
        assertTrue(missing.getOutput().contains("NOT_FOUND"));
        for (String args : List.of("{\"query\":\"会话恢复\",\"path\":\"../other\"}",
                "{\"query\":\"会话恢复\",\"max_results\":11}", "{\"query\":\"a\",\"query\":\"b\"}")) {
            assertFalse(registry.getTools().get("memory_search").execute(args).isSuccess());
        }
        assertFalse(read.execute("{\"history_id\":\"../../other\"}").isSuccess());
        assertFalse(read.execute("{\"history_id\":\"" + foreign.id() + "\",\"include_sources\":null}").isSuccess());
    }

    @Test void disabledMemoryAddsNoToolsAndRejectsManualHistoryAccess() {
        archive(temp, "会话恢复");
        try (var service = service(Config.defaults(), (input, tools) -> {
            assertFalse(tools.contains("memory_search"));
            assertFalse(tools.contains("memory_read"));
            return new LLMClient.LLMResponse("done", null);
        })) {
            String current = service.createSession(temp).id();
            assertEquals(SessionRun.Status.COMPLETED, service.runTurn(current, "work").lastRun().status());
            assertEquals(HistoryAccessException.Reason.DISABLED, assertThrows(HistoryAccessException.class,
                    () -> service.searchHistory(current, "会话恢复")).reason());
        }
    }
}
