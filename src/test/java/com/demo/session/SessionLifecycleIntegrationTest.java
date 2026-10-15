package com.demo.session;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.session.storage.JsonSessionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SessionLifecycleIntegrationTest {
    @TempDir Path temp;

    @Test
    void writesThenReadsAndModifiesAndContinuesAfterRestart() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        var repository = new JsonSessionRepository(temp.resolve("sessions"));
        AtomicInteger requests = new AtomicInteger();
        LLMClient model = (messages, tools) -> {
            int index = requests.getAndIncrement();
            Message last = messages.get(messages.size() - 1);
            return switch (index) {
                case 0 -> call("write_file", "{\"path\":\"hello.txt\",\"content\":\"hello\"}");
                case 1 -> new LLMClient.LLMResponse("created", null);
                case 2 -> {
                    assertEquals(2, messages.stream().filter(message -> "user".equals(message.getRole())).count());
                    yield call("read_file", "{\"path\":\"hello.txt\"}");
                }
                case 3 -> {
                    assertEquals("hello", last.getContent());
                    yield call("write_file", "{\"path\":\"hello.txt\",\"content\":\"hello world\"}");
                }
                case 4 -> new LLMClient.LLMResponse("modified", null);
                case 5 -> {
                    assertEquals(3, messages.stream().filter(message -> "user".equals(message.getRole())).count());
                    assertTrue(messages.stream().anyMatch(message -> "modified".equals(message.getContent())));
                    yield call("read_file", "{\"path\":\"hello.txt\"}");
                }
                case 6 -> {
                    assertEquals("hello world", last.getContent());
                    yield new LLMClient.LLMResponse("verified after restart", null);
                }
                default -> throw new AssertionError("Unexpected model request");
            };
        };
        String id;
        try (var service = service(model, repository)) {
            var session = service.createSession(workspace);
            id = session.id();
            assertEquals(SessionRun.Status.COMPLETED, service.runTurn(id, "create greeting").lastRun().status());
            assertEquals("hello", Files.readString(workspace.resolve("hello.txt")));
            service.runTurn(id, "append world");
            assertEquals("hello world", Files.readString(workspace.resolve("hello.txt")));
        }
        try (var service = service(model, repository)) {
            assertEquals("create greeting", service.listSessions().sessions().get(0).title());
            var restored = service.getSession(id);
            assertEquals(workspace.toRealPath(), restored.workspace());
            var result = service.runTurn(id, "verify file");
            assertEquals("verified after restart", result.lastRun().message());
            assertEquals(3, result.messages().stream().filter(message -> "user".equals(message.getRole())).count());
        }
        assertEquals(7, requests.get());
    }

    @Test
    void twoSessionsHaveSeparateHistoriesAndSameNamedFilesResolveToTheirOwnRoots() throws Exception {
        Path a = Files.createDirectory(temp.resolve("a"));
        Path b = Files.createDirectory(temp.resolve("b"));
        String cwd = System.getProperty("user.dir");
        LLMClient model = (messages, tools) -> {
            Message last = messages.get(messages.size() - 1);
            if ("user".equals(last.getRole())) {
                long users = messages.stream().filter(message -> "user".equals(message.getRole())).count();
                if (last.getContent().startsWith("write")) {
                    assertEquals(1, users);
                    String value = last.getContent().substring(6);
                    return call("write_file", "{\"path\":\"same.txt\",\"content\":\"" + value + "\"}");
                }
                assertEquals(2, users);
                assertTrue(messages.stream().noneMatch(message -> "write B".equals(message.getContent())));
                return call("read_file", "{\"path\":\"same.txt\"}");
            }
            return new LLMClient.LLMResponse(last.getContent(), null);
        };
        try (var service = service(model, new JsonSessionRepository(temp.resolve("sessions")))) {
            String idA = service.createSession(a).id();
            String idB = service.createSession(b).id();
            service.runTurn(idA, "write A");
            service.runTurn(idB, "write B");
            var result = service.runTurn(idA, "read again");
            assertEquals("A", result.lastRun().message());
            assertEquals("A", Files.readString(a.resolve("same.txt")));
            assertEquals("B", Files.readString(b.resolve("same.txt")));
            assertEquals(4, service.getSession(idB).messages().size());
            assertEquals(2, service.listSessions().sessions().size());
        }
        assertEquals(cwd, System.getProperty("user.dir"));
    }

    @Test
    void restartRepairsPartialToolBatchWithoutAnyModelOrToolReplay() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        var repository = new JsonSessionRepository(temp.resolve("sessions"));
        Instant now = Instant.parse("2026-10-15T10:00:00Z");
        String id = java.util.UUID.randomUUID().toString();
        Files.writeString(workspace.resolve("a.txt"), "already done");
        repository.save(new SessionSnapshot(id, "interrupted", workspace.toRealPath(), now, now,
                List.of(Message.user("write files"), Message.assistantToolCalls(null, List.of(
                        new ToolCall("a", "write_file", "{\"path\":\"a.txt\",\"content\":\"overwrite\"}"),
                        new ToolCall("b", "write_file", "{\"path\":\"b.txt\",\"content\":\"new\"}"))),
                        Message.tool("a", "write_file", "written", true, null)), SessionRun.started(now), false));
        AtomicInteger calls = new AtomicInteger();
        try (var service = service((messages, tools) -> {
            calls.incrementAndGet();
            return new LLMClient.LLMResponse("continuing", null);
        }, repository)) {
            var restored = service.getSession(id);
            assertEquals(SessionRun.Status.INTERRUPTED, restored.lastRun().status());
            assertEquals(4, restored.messages().size());
            assertEquals("b", restored.messages().get(3).getToolCallId());
            assertFalse(Files.exists(workspace.resolve("b.txt")));
            assertEquals("already done", Files.readString(workspace.resolve("a.txt")));
            assertEquals(0, calls.get());
            service.runTurn(id, "continue with a new request");
            assertEquals(1, calls.get());
        }
        assertEquals(SessionRun.Status.COMPLETED, repository.load(id).lastRun().status());
    }

    private DefaultSessionService service(LLMClient model, JsonSessionRepository repository) {
        return new DefaultSessionService(Config.defaults(), model, repository, Clock.systemUTC());
    }

    private LLMClient.LLMResponse call(String tool, String arguments) {
        return new LLMClient.LLMResponse(null, List.of(new ToolCall(tool, arguments)));
    }
}
