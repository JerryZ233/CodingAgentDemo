package com.demo.cli;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.model.ToolCall;
import com.demo.session.*;
import com.demo.session.storage.JsonSessionRepository;
import java.io.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CliSessionIntegrationTest {
    @TempDir Path temp;
    private LLMClient filesModel() {
        return (messages, tools) -> {
            var last = messages.get(messages.size() - 1);
            if ("tool".equals(last.getRole())) return new LLMClient.LLMResponse("saved", null);
            String content = last.getContent();
            if (content.equals("modify")) {
                assertTrue(messages.stream().anyMatch(m -> "first".equals(m.getContent())));
                return new LLMClient.LLMResponse("", List.of(new ToolCall("write_file",
                        "{\"path\":\"note.txt\",\"content\":\"modified\",\"expected_sha256\":\"" +
                        com.google.gson.JsonParser.parseString(messages.stream().filter(m -> "tool".equals(m.getRole())).reduce((a, b) -> b).orElseThrow().getContent())
                                .getAsJsonObject().get("sha256").getAsString() + "\"}")));
            }
            return new LLMClient.LLMResponse("", List.of(new ToolCall("write_file",
                    "{\"path\":\"note.txt\",\"content\":\"first\"}")));
        };
    }
    @Test void repeatedTasksIsolatedDirectoriesAndRestartContinueSameSession() throws Exception {
        Path first = Files.createDirectory(temp.resolve("first"));
        Path second = Files.createDirectory(temp.resolve("second"));
        Path store = temp.resolve("store");
        String firstId;
        try (var h = new CliTestSupport.Harness(first, filesModel(), new JsonSessionRepository(store))) {
            h.line(""); firstId = h.controller.sessionId();
            h.line("first"); assertNull(h.finish().failure());
            h.line("modify"); assertNull(h.finish().failure());
            assertEquals("modified", Files.readString(first.resolve("note.txt")));
            h.line("/new \"" + second + "\"");
            h.line("first"); h.finish();
            assertEquals("first", Files.readString(second.resolve("note.txt")));
            assertEquals("modified", Files.readString(first.resolve("note.txt")));
            assertEquals(4, h.snapshot().messages().size());
        }
        try (var h = new CliTestSupport.Harness(first, filesModel(), new JsonSessionRepository(store))) {
            h.line("/resume " + firstId);
            assertEquals(8, h.snapshot().messages().size());
            h.line("modify"); h.finish();
            assertEquals(12, h.snapshot().messages().size());
            assertEquals(firstId, h.controller.sessionId());
        }
    }
    @Test void applicationProcessesSelectionTaskThenEofAndSavesBeforeExit() {
        var output = new StringWriter();
        AtomicReference<SessionService> serviceRef = new AtomicReference<>();
        TerminalIO terminal = new TerminalIO() {
            int step;
            public Input readLine(String prompt) {
                return switch (step++) {
                    case 0 -> Input.line("");
                    case 1 -> Input.line("first");
                    default -> {
                        serviceRef.get().getActiveRun().ifPresent(run -> run.completion().toCompletableFuture().join());
                        yield Input.eof();
                    }
                };
            }
            public void print(String text) { output.append(text).append('\n'); }
            public void printAbove(String text) { print(text); }
            public void error(String text) { print(text); }
            public void close() {}
        };
        Config defaults = Config.defaults();
        Config config = new Config(defaults.getApiUrl(), defaults.getModel(), "", defaults.getMaxTokens(),
                defaults.getTemperature(), defaults.getMaxIterations(), temp.toString(), defaults.getEnabledTools());
        var app = new CliApplication(() -> config, (cfg, options) -> {
            var service = new DefaultSessionService(cfg, filesModel(), new JsonSessionRepository(temp.resolve("store")), Clock.systemUTC());
            serviceRef.set(service); return service;
        }, () -> terminal, false);
        assertEquals(0, app.run(new String[0]));
        var catalog = new JsonSessionRepository(temp.resolve("store")).list();
        assertEquals(1, catalog.sessions().size());
        assertEquals(SessionRun.Status.COMPLETED, catalog.sessions().get(0).lastRunStatus());
        assertTrue(output.toString().contains("已关闭会话服务"));
    }
    @Test void singleTaskSavesAndReturnsOutcomeExitCodes() {
        for (boolean fail : new boolean[]{false, true}) {
            var output = new StringWriter();
            var app = new CliApplication(Config::defaults, (cfg, options) -> new DefaultSessionService(cfg,
                    (messages, tools) -> fail ? LLMClient.LLMResponse.error("model unavailable") : new LLMClient.LLMResponse("done", null),
                    new JsonSessionRepository(temp.resolve(fail ? "fail" : "success")), Clock.systemUTC()),
                    () -> new PlainTerminalIO(new StringReader(""), new PrintWriter(output), new PrintWriter(output)), false);
            assertEquals(fail ? 1 : 0, app.run(new String[]{"--workspace", temp.toString(), "task"}));
            var summary = new JsonSessionRepository(temp.resolve(fail ? "fail" : "success")).list().sessions().get(0);
            assertEquals(fail ? SessionRun.Status.LLM_ERROR : SessionRun.Status.COMPLETED, summary.lastRunStatus());
        }
    }
    @Test void eofCancelsAnActiveTaskAndReportsTerminalEventBeforeClosing() {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var output = new StringWriter();
        LLMClient model = new LLMClient() {
            public LLMResponse sendMessage(List<com.demo.model.Message> messages, String tools) { throw new AssertionError(); }
            public LLMResponse sendMessage(List<com.demo.model.Message> messages, String tools,
                                           com.demo.execution.CancellationToken token) {
                try (var registration = token.onCancellation(release::countDown)) {
                    entered.countDown(); CliTestSupport.await(release);
                    token.throwIfCancellationRequested();
                    return new LLMResponse("late", null);
                }
            }
        };
        TerminalIO terminal = new TerminalIO() {
            int step;
            public Input readLine(String prompt) {
                if (step++ == 0) return Input.line("task");
                CliTestSupport.await(entered); return Input.eof();
            }
            public void print(String text) { output.append(text).append('\n'); }
            public void printAbove(String text) { print(text); }
            public void error(String text) { print(text); }
            public void close() {}
        };
        var app = new CliApplication(Config::defaults, (cfg, options) -> new DefaultSessionService(cfg, model,
                new JsonSessionRepository(temp.resolve("store")), Clock.systemUTC()), () -> terminal, false);
        assertEquals(0, app.run(new String[]{"--workspace", temp.toString()}));
        var summary = new JsonSessionRepository(temp.resolve("store")).list().sessions().get(0);
        assertEquals(SessionRun.Status.CANCELLED, summary.lastRunStatus());
        String rendered = output.toString();
        assertTrue(rendered.contains("任务已取消"));
        assertTrue(rendered.indexOf("任务已取消") < rendered.indexOf("已关闭会话服务"));
    }
    @Test void singleTaskSignalCancelsThroughHandleAndReturns130() {
        AtomicReference<Runnable> signal = new AtomicReference<>();
        var output = new StringWriter();
        TerminalIO terminal = new TerminalIO() {
            public Input readLine(String prompt) { throw new AssertionError("single task must not read stdin"); }
            public void print(String text) { output.append(text); }
            public void printAbove(String text) { print(text); }
            public void error(String text) { print(text); }
            public Registration onInterrupt(Runnable action) { signal.set(action); return () -> signal.set(null); }
            public void close() {}
        };
        var app = new CliApplication(Config::defaults, (cfg, options) -> new DefaultSessionService(cfg, (messages, tools) -> {
            signal.get().run(); return new LLMClient.LLMResponse("late", null);
        }, new JsonSessionRepository(temp.resolve("store")), Clock.systemUTC()), () -> terminal, false);
        assertEquals(130, app.run(new String[]{"--workspace", temp.toString(), "task"}));
        assertTrue(output.toString().contains("任务已取消"));
        assertNull(signal.get());
    }
}
