package com.demo.session;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.memory.CompressionState;
import com.demo.memory.SessionMemoryState;
import com.demo.model.Message;
import com.demo.session.storage.JsonSessionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SessionMemoryIntegrationTest {
    @TempDir Path temp;

    @Test void restoredTurnsPreserveIdentityAndOrganizingMarkersAndDirtyOnlyNewContent() throws Exception {
        Instant now = Instant.parse("2026-10-20T00:00:00Z");
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var repository = new JsonSessionRepository(temp.resolve("sessions"));
        var history = List.of(Message.user("Keep the public API unchanged"), Message.assistant("Understood"));
        var memory = new SessionMemoryState(20, now,
                new CompressionState(history.get(1).getId(), "history-integration", "Preserve public API.", 1));
        String id = UUID.randomUUID().toString();
        repository.save(new SessionSnapshot(id, "existing", temp.toRealPath(), now, now, history,
                SessionRun.started(now).finish(SessionRun.Status.COMPLETED, now, "Understood"), false, 20, memory));
        LLMClient model = (messages, tools) -> new LLMClient.LLMResponse("done", null);
        byte[] before = Files.readAllBytes(temp.resolve("sessions").resolve(id + ".json"));
        try (var service = new DefaultSessionService(Config.defaults(), model, repository, clock)) {
            assertFalse(service.getSession(id).dirty());
            service.listSessions();
            assertArrayEquals(before, Files.readAllBytes(temp.resolve("sessions").resolve(id + ".json")));
            var result = service.runTurn(id, "Continue");
            assertEquals(22, result.contentRevision());
            assertTrue(result.dirty());
            assertEquals(memory, result.memoryState());
            assertEquals(history.get(0).getId(), result.messages().get(0).getId());
            assertEquals(history.get(1).getId(), result.messages().get(1).getId());
            assertEquals(22, service.retrySave(id).contentRevision());
        }
        try (var restored = new DefaultSessionService(Config.defaults(), model, repository, clock)) {
            assertEquals(22, restored.getSession(id).contentRevision());
            assertEquals(memory, restored.getSession(id).memoryState());
            assertEquals(4, restored.getSession(id).messages().size());
        }
    }
}
