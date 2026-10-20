package com.demo.agent;

import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.model.ToolResult;
import com.demo.tools.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentLoopTest {

    @Test
    void admittedShortFileOperationKeepsTrueResultWhenCancellationArrivesDuringExecution(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace)
            throws java.io.IOException {
        var source = new com.demo.execution.CancellationSource();
        Tool writer = new EchoTool() {
            @Override public ToolResult execute(String args) {
                source.cancel(); // The short operation has already started; it is allowed to finish.
                try { java.nio.file.Files.writeString(workspace.resolve("a.txt"), "completed"); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                return ToolResult.success("echo", "written");
            }
        };
        LLMClient model = (messages, tools) -> new LLMClient.LLMResponse(null,
                List.of(new ToolCall("a", "echo", "{}"), new ToolCall("b", "echo", "{}")));
        Conversation context = Conversation.fromMessages(List.of(Message.user("write")));
        var result = new AgentLoop(model, Map.of("echo", writer)).run(context, AgentCheckpoint.noop(), source.token(), AgentEventLog.noop());
        assertEquals(AgentRunResult.Status.CANCELLED, result.getStatus());
        assertEquals("completed", java.nio.file.Files.readString(workspace.resolve("a.txt")));
        assertTrue(context.getMessages().get(2).getToolSuccess());
        assertFalse(context.getMessages().get(3).getToolSuccess());
        assertTrue(context.getMessages().get(3).getContent().contains("before execution"));
    }

    @Test
    void checkpointFailureBeforeToolsPropagatesAndExecutesNothing() {
        AtomicInteger executed = new AtomicInteger();
        Tool tool = new EchoTool() {
            @Override public ToolResult execute(String args) {
                executed.incrementAndGet();
                return super.execute(args);
            }
        };
        LLMClient model = (messages, tools) -> new LLMClient.LLMResponse(null,
                List.of(new ToolCall("a", "echo", "{}"), new ToolCall("b", "echo", "{}")));
        Conversation context = Conversation.fromMessages(List.of(Message.user("work")));
        IllegalStateException failure = new IllegalStateException("checkpoint unavailable");
        assertEquals(failure, assertThrows(IllegalStateException.class,
                () -> new AgentLoop(model, Map.of("echo", tool)).run(context, (state, iteration) -> {
                    assertEquals(2, state.getMessages().size());
                    assertEquals(1, iteration);
                    throw failure;
                })));
        assertEquals(0, executed.get());
    }

    @Test
    void resultCheckpointFailureStopsRemainingToolsAndDoesNotBecomeToolError() {
        AtomicInteger executed = new AtomicInteger();
        Tool tool = new EchoTool() {
            @Override public ToolResult execute(String args) {
                executed.incrementAndGet();
                return super.execute(args);
            }
        };
        LLMClient model = (messages, tools) -> new LLMClient.LLMResponse(null,
                List.of(new ToolCall("a", "echo", "{}"), new ToolCall("b", "echo", "{}")));
        Conversation context = Conversation.fromMessages(List.of(Message.user("work")));
        assertThrows(IllegalStateException.class,
                () -> new AgentLoop(model, Map.of("echo", tool)).run(context, (state, iteration) -> {
                    if (state.getMessages().size() == 3) throw new IllegalStateException("cannot save result");
                }));
        assertEquals(1, executed.get());
        assertEquals(3, context.getMessages().size());
        assertTrue(context.getMessages().get(2).getToolSuccess());
    }

    @Test
    @DisplayName("Tool calls and results are stored as structured messages")
    void storesStructuredToolMessages() {
        ToolCall toolCall = new ToolCall("call_1", "echo", "{\"text\":\"hello\"}");
        AtomicInteger calls = new AtomicInteger();
        LLMClient llm = (messages, toolsDescription) -> calls.getAndIncrement() == 0
                ? new LLMClient.LLMResponse("", List.of(toolCall))
                : new LLMClient.LLMResponse("done", null);
        Conversation context = new Conversation();
        context.addUserMessage("use a tool");

        AgentRunResult result = new AgentLoop(llm, Map.of("echo", new EchoTool())).run(context);

        assertEquals(AgentRunResult.Status.COMPLETED, result.getStatus());
        assertEquals("done", result.getMessage());
        List<Message> messages = context.getMessages();
        assertEquals("user", messages.get(0).getRole());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("call_1", messages.get(1).getToolCalls().get(0).getId());
        assertEquals("tool", messages.get(2).getRole());
        assertEquals("call_1", messages.get(2).getToolCallId());
        assertEquals("echo", messages.get(2).getToolName());
        assertEquals("ok", messages.get(2).getContent());
        assertTrue(messages.get(2).getToolSuccess());
        assertNull(messages.get(2).getToolError());
        assertEquals("assistant", messages.get(3).getRole());
        assertEquals("done", messages.get(3).getContent());
    }

    @Test
    @DisplayName("Loop stops at configured max iterations")
    void stopsAtConfiguredMaxIterations() {
        ToolCall toolCall = new ToolCall("call_1", "echo", "{\"text\":\"hello\"}");
        LLMClient llm = (messages, toolsDescription) -> new LLMClient.LLMResponse("", List.of(toolCall));
        Conversation context = new Conversation();
        context.addUserMessage("keep using a tool");

        AgentRunResult result = new AgentLoop(llm, Map.of("echo", new EchoTool()), 1).run(context);

        assertEquals(AgentRunResult.Status.MAX_ITERATIONS, result.getStatus());
        assertEquals(1, result.getIterations());
        assertEquals("Maximum iterations reached. Task may not be complete.", result.getMessage());
        assertEquals(3, context.getMessages().size());
    }

    @Test
    @DisplayName("LLM errors are recorded as errors and stop the loop")
    void stopsOnLlmError() {
        LLMClient llm = (messages, toolsDescription) -> LLMClient.LLMResponse.error("transport failed");
        Conversation context = new Conversation();
        context.addUserMessage("do work");

        AgentRunResult result = new AgentLoop(llm, Map.of()).run(context);

        assertEquals(AgentRunResult.Status.LLM_ERROR, result.getStatus());
        assertEquals("Error: transport failed", result.getMessage());
        assertEquals(2, context.getMessages().size());
        assertEquals("assistant", context.getMessages().get(1).getRole());
        assertEquals("Error: transport failed", context.getMessages().get(1).getContent());
    }

    @Test
    @DisplayName("Unknown tools are recorded and returned to the model")
    void continuesAfterUnknownTool() {
        ToolCall toolCall = new ToolCall("call_missing", "missing", "{}");
        AtomicInteger calls = new AtomicInteger();
        LLMClient llm = (messages, toolsDescription) -> calls.getAndIncrement() == 0
                ? new LLMClient.LLMResponse("", List.of(toolCall))
                : new LLMClient.LLMResponse("recovered from missing tool", null);
        Conversation context = new Conversation();
        context.addUserMessage("use a missing tool");

        AgentRunResult result = new AgentLoop(llm, Map.of()).run(context);

        assertEquals(AgentRunResult.Status.COMPLETED, result.getStatus());
        assertEquals("recovered from missing tool", result.getMessage());
        Message toolMessage = context.getMessages().get(2);
        assertEquals("tool", toolMessage.getRole());
        assertFalse(toolMessage.getToolSuccess());
        assertEquals("Error: Tool 'missing' not found.", toolMessage.getToolError());
        assertEquals(toolMessage.getToolError(), toolMessage.getContent());
        assertEquals("assistant", context.getMessages().get(3).getRole());
        assertEquals("recovered from missing tool", context.getMessages().get(3).getContent());
    }

    @Test
    @DisplayName("Failed tools are recorded and returned to the model")
    void continuesAfterFailedToolResult() {
        ToolCall toolCall = new ToolCall("call_fail", "fail", "{}");
        AtomicInteger calls = new AtomicInteger();
        LLMClient llm = (messages, toolsDescription) -> calls.getAndIncrement() == 0
                ? new LLMClient.LLMResponse("", List.of(toolCall))
                : new LLMClient.LLMResponse("handled failure", null);
        Conversation context = new Conversation();
        context.addUserMessage("use a failing tool");

        AgentRunResult result = new AgentLoop(llm, Map.of("fail", new FailingTool())).run(context);

        assertEquals(AgentRunResult.Status.COMPLETED, result.getStatus());
        assertEquals("handled failure", result.getMessage());
        Message toolMessage = context.getMessages().get(2);
        assertEquals("tool", toolMessage.getRole());
        assertFalse(toolMessage.getToolSuccess());
        assertEquals("boom", toolMessage.getContent());
        assertEquals("boom", toolMessage.getToolError());
        assertEquals("assistant", context.getMessages().get(3).getRole());
        assertEquals("handled failure", context.getMessages().get(3).getContent());
    }

    @Test
    @DisplayName("All tool calls are recorded even when one fails")
    void recordsAllToolCallsWhenOneFails() {
        ToolCall failingCall = new ToolCall("call_fail", "fail", "{}");
        ToolCall echoCall = new ToolCall("call_echo", "echo", "{}");
        AtomicInteger calls = new AtomicInteger();
        LLMClient llm = (messages, toolsDescription) -> calls.getAndIncrement() == 0
                ? new LLMClient.LLMResponse("", List.of(failingCall, echoCall))
                : new LLMClient.LLMResponse("saw both results", null);
        Conversation context = new Conversation();
        context.addUserMessage("use two tools");

        AgentRunResult result = new AgentLoop(
                llm,
                Map.of("fail", new FailingTool(), "echo", new EchoTool())
        ).run(context);

        assertEquals(AgentRunResult.Status.COMPLETED, result.getStatus());
        assertEquals("tool", context.getMessages().get(2).getRole());
        assertFalse(context.getMessages().get(2).getToolSuccess());
        assertEquals("tool", context.getMessages().get(3).getRole());
        assertTrue(context.getMessages().get(3).getToolSuccess());
        assertEquals("assistant", context.getMessages().get(4).getRole());
        assertEquals("saw both results", context.getMessages().get(4).getContent());
    }

    @Test
    @DisplayName("Agent loop appends structured execution events")
    void appendsStructuredExecutionEvents() {
        ToolCall toolCall = new ToolCall("call_1", "echo", "{}");
        AtomicInteger calls = new AtomicInteger();
        LLMClient llm = (messages, toolsDescription) -> calls.getAndIncrement() == 0
                ? new LLMClient.LLMResponse("", List.of(toolCall))
                : new LLMClient.LLMResponse("done", null);
        InMemoryAgentEventLog eventLog = new InMemoryAgentEventLog();
        Conversation context = new Conversation();
        context.addUserMessage("trace this");

        AgentRunResult result = new AgentLoop(
                llm,
                Map.of("echo", new EchoTool()),
                5,
                AgentObserver.noop(),
                eventLog
        ).run(context);

        assertEquals(AgentRunResult.Status.COMPLETED, result.getStatus());
        assertEquals(List.of(
                AgentEvent.Type.RUN_STARTED,
                AgentEvent.Type.ITERATION_STARTED,
                AgentEvent.Type.LLM_REQUEST_STARTED,
                AgentEvent.Type.LLM_RESPONSE,
                AgentEvent.Type.TOOL_STARTED,
                AgentEvent.Type.TOOL_RESULT,
                AgentEvent.Type.ITERATION_STARTED,
                AgentEvent.Type.LLM_REQUEST_STARTED,
                AgentEvent.Type.LLM_RESPONSE,
                AgentEvent.Type.FINAL_RESPONSE,
                AgentEvent.Type.RUN_COMPLETED
        ), eventLog.getEvents().stream().map(AgentEvent::getType).toList());
        assertEquals("echo", eventLog.getEvents().get(5).getToolName());
        assertEquals(AgentRunResult.Status.COMPLETED, eventLog.getEvents().get(10).getStatus());
    }

    @Test
    @DisplayName("Loop can run without console output while observer records events")
    void canRunWithoutConsoleOutputAndRecordEvents() {
        ToolCall toolCall = new ToolCall("call_1", "echo", "{}");
        AtomicInteger calls = new AtomicInteger();
        LLMClient llm = (messages, toolsDescription) -> calls.getAndIncrement() == 0
                ? new LLMClient.LLMResponse("", List.of(toolCall))
                : new LLMClient.LLMResponse("done", null);
        RecordingObserver observer = new RecordingObserver();
        Conversation context = new Conversation();
        context.addUserMessage("observe this");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));

            AgentRunResult result = new AgentLoop(llm, Map.of("echo", new EchoTool()), observer).run(context);

            assertEquals(AgentRunResult.Status.COMPLETED, result.getStatus());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        assertEquals(List.of("iteration:1", "tool:echo:ok", "iteration:2", "final:done"), observer.events);
    }

    private static class EchoTool implements Tool {
        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public String getDescription() {
            return "Echo test tool";
        }

        @Override
        public ToolResult execute(String args) {
            return ToolResult.success(getName(), "ok");
        }
    }

    private static class FailingTool implements Tool {
        @Override
        public String getName() {
            return "fail";
        }

        @Override
        public String getDescription() {
            return "Fail test tool";
        }

        @Override
        public ToolResult execute(String args) {
            return ToolResult.error(getName(), "boom");
        }
    }

    private static class RecordingObserver implements AgentObserver {
        private final List<String> events = new ArrayList<>();

        @Override
        public void onIterationStarted(int iteration) {
            events.add("iteration:" + iteration);
        }

        @Override
        public void onFinalResponse(String response) {
            events.add("final:" + response);
        }

        @Override
        public void onToolResult(String toolName, ToolResult result) {
            events.add("tool:" + toolName + ":" + result.getOutput());
        }
    }
}
