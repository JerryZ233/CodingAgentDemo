package com.demo.cli;

import com.demo.config.*;
import com.demo.llm.LLMClient;
import com.demo.memory.*;
import com.demo.memory.storage.*;
import com.demo.model.Message;
import com.demo.session.*;
import com.demo.session.storage.JsonSessionRepository;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CliMemoryIntegrationTest {
    @TempDir Path temp;
    private final Instant now = Instant.parse("2026-10-20T00:00:00Z");
    private String id() { return UUID.randomUUID().toString(); }

    @Test void searchAndReadUseLocalOrdinalsWithoutCallingModelOrChangingConversation() throws Exception {
        Files.createDirectory(temp.resolve(".git"));
        var repository = new JsonSessionRepository(temp.resolve("sessions"));
        var messages = List.of(Message.user("raw source"));
        var old = new SessionSnapshot(id(), "old", temp.toAbsolutePath(), now, now, messages,
                SessionRun.started(now).finish(SessionRun.Status.COMPLETED, now, "done"), false);
        repository.save(old);
        var entry = new HistoryEntry(id(), id(), old.id(), 1, List.of(messages.get(0).getId()), null,
                "配置覆盖规则已经确认", "环境变量优先于 YAML", List.of("配置"), List.of(messages.get(0).getId()), now, "test");
        new ArchiveCommitter(repository, new HistoryRepository(temp.resolve(".coding-agent"))).commit(old, entry, now);
        var config = Config.defaults().withMemory(MemoryConfig.defaults().withEnabled(true));
        LLMClient never = (input, tools) -> { fail("Manual history commands must not call a model"); return null; };
        var output = new StringWriter();
        try (var service = new DefaultSessionService(config, never, repository, Clock.fixed(now, ZoneOffset.UTC),
                    DefaultSessionService.ClientOwnership.BORROWED, new MemoryHarness(config.getMemory(), config.getMaxTokens(), temp.resolve("global")));
             var terminal = new PlainTerminalIO(new StringReader(""), new PrintWriter(output), new PrintWriter(output));
             var pump = new RunEventPump(service, new CliRenderer(terminal))) {
            var renderer = new CliRenderer(terminal);
            var shutdown = new CliShutdownCoordinator(service, pump, terminal, renderer);
            var controller = new CliController(service, temp, renderer, pump, shutdown);
            controller.initialize(CliOptions.parse(new String[]{"--workspace", temp.toString()}));
            var before = service.getSession(controller.sessionId());
            controller.accept(TerminalIO.Input.line("/memory search 配置"));
            controller.accept(TerminalIO.Input.line("/memory read 1"));
            assertTrue(output.toString().contains("配置覆盖规则已经确认"));
            assertTrue(output.toString().contains("环境变量优先于 YAML"));
            assertFalse(output.toString().contains(entry.id()));
            assertFalse(output.toString().contains(old.id()));
            assertTrue(before.samePersistedState(service.getSession(controller.sessionId())));
            controller.accept(TerminalIO.Input.line("/new " + temp));
            int mark = output.toString().length();
            controller.accept(TerminalIO.Input.line("/memory read 1"));
            assertTrue(output.toString().substring(mark).contains("请先使用 /memory search"));
        }
    }

    @Test void memoryHelpInWorkspaceSelectionIsNotTreatedAsADirectory() {
        var output = new StringWriter();
        try (var service = new DefaultSessionService(Config.defaults(), (input, tools) -> new LLMClient.LLMResponse("done", null),
                    new JsonSessionRepository(temp.resolve("sessions")), Clock.systemUTC());
             var terminal = new PlainTerminalIO(new StringReader(""), new PrintWriter(output), new PrintWriter(output));
             var pump = new RunEventPump(service, new CliRenderer(terminal))) {
            var renderer = new CliRenderer(terminal);
            var controller = new CliController(service, temp, renderer, pump, new CliShutdownCoordinator(service, pump, terminal, renderer));
            controller.initialize(CliOptions.parse(new String[0]));
            controller.accept(TerminalIO.Input.line("/memory"));
            assertEquals(CliController.Phase.SELECTING_WORKSPACE, controller.phase());
            assertTrue(output.toString().contains("/memory search"));
            assertTrue(service.listSessions().sessions().isEmpty());
        }
    }
}
