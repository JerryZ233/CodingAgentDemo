package com.demo.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parsed JSON object arguments for a tool invocation.
 */
final class ToolArguments {

    private final JsonObject values;

    private ToolArguments(JsonObject values) {
        this.values = values;
    }

    static ToolArguments parse(String args) {
        if (args == null) {
            throw new IllegalArgumentException("arguments must be a JSON object, got null");
        }

        JsonElement root;
        try {
            root = JsonParser.parseString(args);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("arguments must be valid JSON: " + e.getMessage(), e);
        }

        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("arguments must be a JSON object");
        }

        return new ToolArguments(root.getAsJsonObject());
    }

    String requiredString(String key) {
        String value = optionalString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required string argument '" + key + "'");
        }
        return value;
    }

    String optionalString(String key) {
        JsonElement value = values.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("argument '" + key + "' must be a string");
        }
        return value.getAsString();
    }
}
