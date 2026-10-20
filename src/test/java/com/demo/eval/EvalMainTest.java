package com.demo.eval;

import com.demo.config.Config;
import com.demo.execution.CancellationToken;
import com.demo.llm.*;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EvalMainTest {
    @TempDir Path root;

    private Config config(Set<String> tools) {
        return new Config("http://localhost/v1", "test-model", "test-key", 128, 0, 5,
                root.toString(), tools);
    }

    private EvalMain.Options options(int timeout) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path instruction = Files.writeString(root.resolve("instruction.txt"), "Complete this task");
        return new EvalMain.Options(workspace, instruction, root.resolve("out"), timeout);
    }

    @Test void executesOneTurnAndSavesMachineReadableResultWithoutTerminal() throws Exception {
        var options = options(5);
        var metrics = new EvalMetrics(options.output());
        AtomicInteger calls = new AtomicInteger();
        LLMClient client = (messages, tools) -> calls.getAndIncrement() == 0
                ? new LLMClient.LLMResponse("", List.of(new ToolCall("write", "write_file", "{\"path\":\"answer.txt\",\"content\":\"42\"}")))
                : new LLMClient.LLMResponse("done", List.of());
        Map<String, Object> result = new HashMap<>();
        assertEquals(0, EvalMain.execute(config(Set.of("write_file")), client, options, metrics, result));
        assertEquals("42", Files.readString(options.workspace().resolve("answer.txt")));
        assertEquals("COMPLETED", result.get("agent_status"));
        assertEquals(false, result.get("memory_enabled"));
        assertFalse(Files.exists(options.workspace().resolve(".coding-agent")));
        assertEquals(2, result.get("iterations"));
        assertTrue(Files.readString(options.output().resolve("events.jsonl")).contains("TOOL_RESULT"));
        try (var files = Files.list(options.output().resolve("sessions"))) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".json")).count());
        }
    }

    @Test void servicesApprovalRequestsForShellCommands() throws Exception {
        var options = options(10);
        var metrics = new EvalMetrics(options.output());
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String command = "{\"mode\":\"script\",\"shell\":\"" + (windows ? "cmd" : "sh") + "\",\"script\":\"echo evaluated\"}";
        AtomicInteger calls = new AtomicInteger();
        LLMClient client = (messages, tools) -> calls.getAndIncrement() == 0
                ? new LLMClient.LLMResponse("", List.of(new ToolCall("shell", "run_shell", command)))
                : new LLMClient.LLMResponse("done", List.of());
        Map<String, Object> result = new HashMap<>();
        assertEquals(0, EvalMain.execute(config(Set.of("run_shell")), client, options, metrics, result));
        String events = Files.readString(options.output().resolve("events.jsonl"));
        assertTrue(events.contains("EVAL_APPROVAL"));
        assertTrue(events.contains("evaluated"));
        assertFalse(events.contains("APPROVAL_CHANNEL_UNAVAILABLE"));
    }

    @Test void deadlineCancelsRequestAndStillReturnsOutcome() throws Exception {
        var options = options(1);
        var metrics = new EvalMetrics(options.output());
        LLMClient client = new LLMClient() {
            public LLMResponse sendMessage(List<Message> messages, String tools) { throw new AssertionError(); }
            public LLMResponse sendMessage(List<Message> messages, String tools, CancellationToken token) {
                while (true) {
                    token.throwIfCancellationRequested();
                    try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); token.throwIfCancellationRequested(); }
                }
            }
        };
        Map<String, Object> result = new HashMap<>();
        assertEquals(124, EvalMain.execute(config(Set.of()), client, options, metrics, result));
        assertEquals("TIMED_OUT", result.get("agent_status"));
        assertTrue(Files.readString(options.output().resolve("events.jsonl")).contains("CANCEL_REQUESTED"));
    }

    @Test void unknownUsageIsNotZeroAndKnownRequestsAccumulate() throws Exception {
        var metrics = new EvalMetrics(root.resolve("metrics"));
        assertNull(metrics.snapshot().get("input_tokens"));
        assertEquals(false, metrics.snapshot().get("usage_complete"));
        metrics.record(new RequestAttempt(1, 10, new TokenUsage(20, 5, 25, 3L), true));
        metrics.record(new RequestAttempt(1, 12, new TokenUsage(30, 7, 37, null), true));
        metrics.record(new RequestAttempt(2, 8, null, false));
        var snapshot = metrics.snapshot();
        assertEquals(50L, snapshot.get("input_tokens"));
        assertEquals(12L, snapshot.get("output_tokens"));
        assertEquals(62L, snapshot.get("total_tokens"));
        assertEquals(3L, snapshot.get("request_count"));
        assertEquals(1L, snapshot.get("retry_count"));
        assertEquals(30L, snapshot.get("model_request_ms"));
        assertEquals(false, snapshot.get("usage_complete"));
        assertEquals(false, snapshot.get("cache_usage_complete"));
    }

    @Test void rejectsDuplicateAndInvalidOptions() {
        assertThrows(IllegalArgumentException.class, () -> EvalMain.Options.parse(new String[]{"--workspace", "a", "--workspace", "b"}));
        assertThrows(IllegalArgumentException.class, () -> EvalMain.Options.parse(new String[]{"--workspace", "a", "--instruction-file", "b", "--output-dir", "c", "--timeout-seconds", "0"}));
    }
}
