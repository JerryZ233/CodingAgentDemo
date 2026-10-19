package com.demo.tools.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolArgumentsTest {

    @Test void rejectsUnknownDuplicateAndNonStandardJsonAndAllowsExplicitEmpty() {
        assertEquals("", ToolArguments.parse("{\"content\":\"\"}", "content").presentString("content"));
        for (String json : java.util.List.of("{\"extra\":1}", "{\"content\":null}", "{\"content\":\"a\",\"content\":\"b\"}",
                "{content:'a'}", "{/*comment*/\"content\":\"a\"}", "{} {}"))
            assertThrows(IllegalArgumentException.class, () -> ToolArguments.parse(json, "content").presentString("content"));
        assertThrows(IllegalArgumentException.class, () -> ToolArguments.parse(" ".repeat(ToolLimits.ARGUMENT_BYTES + 1)));
    }

    @Test
    @DisplayName("Parses required and optional string arguments")
    void parsesStringArguments() {
        ToolArguments arguments = ToolArguments.parse("{\"path\":\"README.md\",\"content\":\"hello\"}");

        assertEquals("README.md", arguments.requiredString("path"));
        assertEquals("hello", arguments.optionalString("content"));
    }

    @Test
    @DisplayName("Rejects invalid JSON")
    void rejectsInvalidJson() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ToolArguments.parse("{not json")
        );

        assertTrue(error.getMessage().contains("valid JSON"));
    }

    @Test
    @DisplayName("Rejects non-object JSON")
    void rejectsNonObjectJson() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ToolArguments.parse("[]")
        );

        assertEquals("arguments must be a JSON object", error.getMessage());
    }

    @Test
    @DisplayName("Rejects non-string fields")
    void rejectsNonStringFields() {
        ToolArguments arguments = ToolArguments.parse("{\"path\":123}");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> arguments.requiredString("path")
        );

        assertEquals("argument 'path' must be a string", error.getMessage());
    }
}
