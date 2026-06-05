package com.demo.agent;

import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.model.ToolResult;
import com.demo.tools.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {

    @Test
    @DisplayName("Tool calls and results are stored as structured messages")
    void storesStructuredToolMessages() {
        ToolCall toolCall = new ToolCall("call_1", "echo", "{\"text\":\"hello\"}");
        AtomicInteger calls = new AtomicInteger();
        LLMClient llm = (messages, toolsDescription) -> calls.getAndIncrement() == 0
                ? new LLMClient.LLMResponse("", List.of(toolCall))
                : new LLMClient.LLMResponse("done", null);
        Context context = new Context();
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
        Context context = new Context();
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
        Context context = new Context();
        context.addUserMessage("do work");

        AgentRunResult result = new AgentLoop(llm, Map.of()).run(context);

        assertEquals(AgentRunResult.Status.LLM_ERROR, result.getStatus());
        assertEquals("Error: transport failed", result.getMessage());
        assertEquals(2, context.getMessages().size());
        assertEquals("assistant", context.getMessages().get(1).getRole());
        assertEquals("Error: transport failed", context.getMessages().get(1).getContent());
    }

    @Test
    @DisplayName("Unknown tools are recorded as failed tool results")
    void stopsOnUnknownTool() {
        ToolCall toolCall = new ToolCall("call_missing", "missing", "{}");
        LLMClient llm = (messages, toolsDescription) -> new LLMClient.LLMResponse("", List.of(toolCall));
        Context context = new Context();
        context.addUserMessage("use a missing tool");

        AgentRunResult result = new AgentLoop(llm, Map.of()).run(context);

        assertEquals(AgentRunResult.Status.TOOL_ERROR, result.getStatus());
        assertEquals("Error: Tool 'missing' not found.", result.getMessage());
        Message toolMessage = context.getMessages().get(2);
        assertEquals("tool", toolMessage.getRole());
        assertFalse(toolMessage.getToolSuccess());
        assertEquals("Error: Tool 'missing' not found.", toolMessage.getToolError());
        assertEquals(toolMessage.getToolError(), toolMessage.getContent());
    }

    @Test
    @DisplayName("Failed tools are recorded as failed tool results")
    void stopsOnFailedToolResult() {
        ToolCall toolCall = new ToolCall("call_fail", "fail", "{}");
        LLMClient llm = (messages, toolsDescription) -> new LLMClient.LLMResponse("", List.of(toolCall));
        Context context = new Context();
        context.addUserMessage("use a failing tool");

        AgentRunResult result = new AgentLoop(llm, Map.of("fail", new FailingTool())).run(context);

        assertEquals(AgentRunResult.Status.TOOL_ERROR, result.getStatus());
        assertEquals("boom", result.getMessage());
        Message toolMessage = context.getMessages().get(2);
        assertEquals("tool", toolMessage.getRole());
        assertFalse(toolMessage.getToolSuccess());
        assertEquals("boom", toolMessage.getContent());
        assertEquals("boom", toolMessage.getToolError());
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
}
