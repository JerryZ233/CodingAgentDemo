package com.demo.llm.impl;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OpenAIChatAdapterTest {
    private final OpenAIChatAdapter adapter = new OpenAIChatAdapter();

    static final String TOOLS = """
        [{"type":"function","function":{"name":"read_file","description":"Read a file",
          "parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}}]
        """;

    @Test
    void buildsTypedMessagesAndToolSchemas() {
        var params = adapter.buildRequest(List.of(
                Message.system("system prompt"),
                Message.user("read files"),
                Message.assistantToolCalls(null, List.of(
                        new ToolCall("call_1", "read_file", "{\"path\":\"one.txt\"}"),
                        new ToolCall("call_2", "read_file", "{\"path\":\"two.txt\"}"))),
                Message.tool("call_1", "read_file", "first result"),
                Message.tool("call_2", "read_file", "second result"),
                Message.assistant("done")), TOOLS, "custom-model", 128, 0.25);

        assertEquals(6, params.messages().size());
        assertEquals("system prompt", params.messages().get(0).asSystem().content().asText());
        assertEquals("read files", params.messages().get(1).asUser().content().asText());
        var assistant = params.messages().get(2).asAssistant();
        assertTrue(assistant.content().isEmpty());
        assertEquals(2, assistant.toolCalls().orElseThrow().size());
        var call = assistant.toolCalls().orElseThrow().get(0).asFunction();
        assertEquals("call_1", call.id());
        assertEquals("read_file", call.function().name());
        assertEquals("{\"path\":\"one.txt\"}", call.function().arguments());
        assertEquals("call_1", params.messages().get(3).asTool().toolCallId());
        assertEquals("first result", params.messages().get(3).asTool().content().asText());
        assertEquals("done", params.messages().get(5).asAssistant().content().orElseThrow().asText());
        var function = params.tools().orElseThrow().get(0).asFunction().function();
        assertEquals("read_file", function.name());
        assertEquals("Read a file", function.description().orElseThrow());
        assertTrue(function.parameters().orElseThrow()._additionalProperties().containsKey("properties"));
        assertTrue(function.parameters().orElseThrow()._additionalProperties().containsKey("required"));
    }

    @Test
    void omitsToolsWhenNoneAreAvailable() {
        for (String tools : new String[] {null, "", " ", "[]"}) {
            var params = adapter.buildRequest(List.of(Message.user("hello")), tools, "model", 10, 0);
            assertTrue(params.tools().isEmpty());
        }
    }

    @Test
    void preservesEmptyAssistantContent() {
        var params = adapter.buildRequest(List.of(Message.assistant("")), null, "model", 10, 0);
        assertEquals("", params.messages().get(0).asAssistant().content().orElseThrow().asText());
    }

    @Test
    void rejectsUnknownMessageRoles() {
        assertThrows(IllegalArgumentException.class, () -> adapter.buildRequest(
                List.of(new Message("unknown", "text")), null, "model", 10, 0));
    }
}
