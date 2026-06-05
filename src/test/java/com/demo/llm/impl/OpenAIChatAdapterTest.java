package com.demo.llm.impl;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAIChatAdapterTest {

    private final OpenAIChatAdapter adapter = new OpenAIChatAdapter();

    @Test
    @DisplayName("Request body formats tool calls, tool messages, and tools schema")
    void requestBodyFormatsToolFieldsAndToolsSchema() {
        String toolsDescription = """
            [
              {
                "type": "function",
                "function": {
                  "name": "read_file",
                  "description": "Read a file",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "path": { "type": "string" }
                    },
                    "required": ["path"]
                  }
                }
              }
            ]
            """;
        List<Message> messages = List.of(
                Message.user("Please read the README"),
                Message.assistantToolCalls(null, List.of(
                        new ToolCall("call_1", "read_file", "{\"path\":\"README.md\"}")
                )),
                Message.tool("call_1", "read_file", "contents")
        );

        String requestBody = adapter.buildRequestBody(messages, toolsDescription, "demo-model", 128, 0.25);
        JsonObject root = JsonParser.parseString(requestBody).getAsJsonObject();

        assertEquals("demo-model", root.get("model").getAsString());
        assertEquals(128, root.get("max_tokens").getAsInt());
        assertEquals(0.25, root.get("temperature").getAsDouble());

        JsonArray requestMessages = root.getAsJsonArray("messages");
        JsonObject assistantMessage = requestMessages.get(1).getAsJsonObject();
        assertTrue(assistantMessage.get("content").isJsonNull());

        JsonObject formattedToolCall = assistantMessage.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call_1", formattedToolCall.get("id").getAsString());
        assertEquals("function", formattedToolCall.get("type").getAsString());
        JsonObject function = formattedToolCall.getAsJsonObject("function");
        assertEquals("read_file", function.get("name").getAsString());
        assertEquals("{\"path\":\"README.md\"}", function.get("arguments").getAsString());

        JsonObject toolMessage = requestMessages.get(2).getAsJsonObject();
        assertEquals("tool", toolMessage.get("role").getAsString());
        assertEquals("call_1", toolMessage.get("tool_call_id").getAsString());
        assertEquals("read_file", toolMessage.get("name").getAsString());
        assertEquals("contents", toolMessage.get("content").getAsString());

        JsonObject toolSchema = root.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("function", toolSchema.get("type").getAsString());
        JsonObject parameters = toolSchema.getAsJsonObject("function").getAsJsonObject("parameters");
        assertEquals("object", parameters.get("type").getAsString());
        assertEquals("string", parameters.getAsJsonObject("properties")
                .getAsJsonObject("path")
                .get("type")
                .getAsString());
    }
}
