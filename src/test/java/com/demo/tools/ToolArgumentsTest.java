package com.demo.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolArgumentsTest {

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
