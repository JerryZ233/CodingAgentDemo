package com.demo.agent;

import com.demo.tools.ToolSpec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {

    @Test
    @DisplayName("Default prompt template is versioned")
    void defaultPromptTemplateIsVersioned() {
        PromptBuilder builder = new PromptBuilder();

        assertEquals(PromptTemplate.DEFAULT_VERSION, builder.getTemplateVersion());
        assertTrue(builder.buildSystemPrompt().contains("tool calling mechanism provided by the API"));
    }

    @Test
    @DisplayName("Prompt builder renders tool specs through the template")
    void rendersToolSpecsThroughTemplate() {
        PromptBuilder builder = new PromptBuilder(new PromptTemplate("test-v1", "Tools:\n{TOOLS}"));
        JsonObject schema = JsonParser.parseString("""
            {
              "type": "object",
              "properties": {
                "path": { "type": "string" }
              },
              "required": ["path"]
            }
            """).getAsJsonObject();
        builder.setToolSpecs(List.of(new ToolSpec("read_file", "Reads files.", schema)));

        assertEquals("test-v1", builder.getTemplateVersion());
        assertEquals("Tools:\n- read_file: Reads files. Required parameters: path.\n", builder.buildSystemPrompt());
    }

    @Test
    @DisplayName("Custom system prompts are marked as custom")
    void customSystemPromptsAreMarkedAsCustom() {
        PromptBuilder builder = new PromptBuilder();

        builder.setSystemPromptTemplate("Custom {TOOLS}");

        assertEquals(PromptTemplate.CUSTOM_VERSION, builder.getTemplateVersion());
        assertEquals("Custom (No tools available)", builder.buildSystemPrompt());
    }
}
