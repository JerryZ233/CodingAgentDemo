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

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLoopTest {

    @Test
    @DisplayName("Tool calls and results are stored as structured messages")
    void storesStructuredToolMessages() {
        ToolCall toolCall = new ToolCall("call_1", "echo", "{\"text\":\"hello\"}");
        LLMClient llm = (messages, toolsDescription) -> new LLMClient.LLMResponse("", List.of(toolCall));
        Context context = new Context();
        context.addUserMessage("use a tool");

        new AgentLoop(llm, Map.of("echo", new EchoTool())).run(context);

        List<Message> messages = context.getMessages();
        assertEquals("user", messages.get(0).getRole());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("call_1", messages.get(1).getToolCalls().get(0).getId());
        assertEquals("tool", messages.get(2).getRole());
        assertEquals("call_1", messages.get(2).getToolCallId());
        assertEquals("echo", messages.get(2).getToolName());
        assertEquals("ok", messages.get(2).getContent());
    }

    @Test
    @DisplayName("Loop stops at configured max iterations")
    void stopsAtConfiguredMaxIterations() {
        ToolCall toolCall = new ToolCall("call_1", "echo", "{\"text\":\"hello\"}");
        LLMClient llm = (messages, toolsDescription) -> new LLMClient.LLMResponse("", List.of(toolCall));
        Context context = new Context();
        context.addUserMessage("keep using a tool");

        new AgentLoop(llm, Map.of("echo", new EchoTool()), 1).run(context);

        assertEquals(3, context.getMessages().size());
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
}
