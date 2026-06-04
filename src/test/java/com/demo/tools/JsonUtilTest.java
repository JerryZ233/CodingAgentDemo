package com.demo.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonUtilTest {

    @Test
    @DisplayName("Reads escaped string values")
    void readsEscapedStrings() {
        String json = "{\"content\":\"Quote: \\\"hi\\\"\\nPath: C:\\\\tmp\"}";

        assertEquals("Quote: \"hi\"\nPath: C:\\tmp", JsonUtil.getString(json, "content"));
    }

    @Test
    @DisplayName("Serializes object values instead of truncating them")
    void readsObjectValues() {
        String json = "{\"arguments\":{\"path\":\"README.md\"}}";

        assertEquals("{\"path\":\"README.md\"}", JsonUtil.getString(json, "arguments"));
    }

    @Test
    @DisplayName("Returns null for invalid JSON")
    void invalidJsonReturnsNull() {
        assertNull(JsonUtil.getString("{not json", "path"));
    }
}
