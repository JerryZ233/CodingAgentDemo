package com.demo.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDescriptionsTest {

    @Test void validatesSchemaAndReturnsDefensiveCopies() {
        var schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}").getAsJsonObject();
        var spec = new ToolSpec("read_file", "Reads", schema, java.util.Set.of(ToolSpec.Capability.FILE_READ));
        schema.addProperty("type", "array"); spec.getParametersSchema().addProperty("type", "array");
        assertEquals("object", spec.getParametersSchema().get("type").getAsString());
        assertTrue(ToolDescriptions.toPromptText(List.of(spec)).contains("Capabilities: FILE_READ"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new ToolSpec("bad name", "", null));
        for (String invalid : List.of("{}", "{\"type\":\"array\",\"properties\":{}}",
                "{\"type\":\"object\",\"properties\":{\"a\":null}}",
                "{\"type\":\"object\",\"properties\":{},\"required\":[\"missing\"]}"))
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new ToolSpec("test", "", JsonParser.parseString(invalid).getAsJsonObject()));
    }

    @Test
    @DisplayName("Prompt text and API schema are rendered from the same ToolSpec")
    void promptTextAndApiSchemaUseSameToolSpec() {
        JsonObject parametersSchema = JsonParser.parseString("""
            {
              "type": "object",
              "properties": {
                "path": { "type": "string" },
                "content": { "type": "string" }
              },
              "required": ["path", "content"]
            }
            """).getAsJsonObject();
        ToolSpec spec = new ToolSpec("write_file", "Writes content to a file.", parametersSchema);

        String promptText = ToolDescriptions.toPromptText(List.of(spec));
        String toolsJson = ToolDescriptions.toOpenAIToolsJson(List.of(spec));
        JsonObject apiTool = JsonParser.parseString(toolsJson)
                .getAsJsonArray()
                .get(0)
                .getAsJsonObject();

        assertTrue(promptText.contains("write_file"));
        assertTrue(promptText.contains("Writes content to a file."));
        assertTrue(promptText.contains("Required parameters: path, content."));

        JsonObject function = apiTool.getAsJsonObject("function");
        assertEquals("write_file", function.get("name").getAsString());
        assertEquals("Writes content to a file.", function.get("description").getAsString());
        assertEquals("path", function.getAsJsonObject("parameters")
                .getAsJsonArray("required")
                .get(0)
                .getAsString());
        assertEquals("content", function.getAsJsonObject("parameters")
                .getAsJsonArray("required")
                .get(1)
                .getAsString());
    }
}
