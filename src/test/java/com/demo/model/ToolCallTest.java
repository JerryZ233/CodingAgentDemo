package com.demo.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolCallTest {

    @Test
    @DisplayName("fromJson() parses flat tool call")
    void fromJsonParsesFlatToolCall() {
        ToolCall toolCall = ToolCall.fromJson("{\"name\":\"read_file\",\"arguments\":{\"path\":\"README.md\"}}");

        assertEquals("read_file", toolCall.getToolName());
        assertEquals("{\"path\":\"README.md\"}", toolCall.getArguments());
    }

    @Test
    @DisplayName("fromJson() throws for invalid JSON")
    void fromJsonThrowsForInvalidJson() {
        assertThrows(IllegalArgumentException.class, () -> ToolCall.fromJson("not json"));
    }

    @Test
    @DisplayName("fromJson() throws when tool name is missing")
    void fromJsonThrowsWhenNameMissing() {
        assertThrows(IllegalArgumentException.class, () -> ToolCall.fromJson("{\"arguments\":{}}"));
    }

    @Test
    @DisplayName("listFromJson() throws when any tool call is invalid")
    void listFromJsonThrowsForInvalidElement() {
        String json = "[{\"name\":\"read_file\",\"arguments\":{}},{\"arguments\":{}}]";

        assertThrows(IllegalArgumentException.class, () -> ToolCall.listFromJson(json));
    }

    @Test
    @DisplayName("listFromJson() parses all valid tool calls")
    void listFromJsonParsesValidCalls() {
        String json = "[{\"name\":\"read_file\",\"arguments\":{}},{\"name\":\"write_file\",\"arguments\":\"{}\"}]";

        List<ToolCall> toolCalls = ToolCall.listFromJson(json);

        assertEquals(2, toolCalls.size());
        assertEquals("read_file", toolCalls.get(0).getToolName());
        assertEquals("write_file", toolCalls.get(1).getToolName());
    }
}
