package com.demo.tools.support;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict bounded JSON parsing before any tool side effect. */
public final class ToolArguments {
    private final JsonObject values;
    private ToolArguments(JsonObject values) { this.values = values; }
    public static ToolArguments parse(String args, String... allowed) {
        if (args == null || args.length() > ToolLimits.ARGUMENT_BYTES
                || args.getBytes(StandardCharsets.UTF_8).length > ToolLimits.ARGUMENT_BYTES)
            throw new IllegalArgumentException("arguments must be a bounded JSON object");
        try (JsonReader reader = new JsonReader(new StringReader(args))) {
            reader.setLenient(false);
            JsonElement root = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("trailing JSON");
            if (!root.isJsonObject()) throw new IllegalArgumentException("arguments must be a JSON object");
            ToolArguments parsed = new ToolArguments(root.getAsJsonObject());
            if (allowed.length > 0) {
                Set<String> names = Set.of(allowed);
                for (String key : parsed.values.keySet())
                    if (!names.contains(key)) throw new IllegalArgumentException("unknown argument");
            }
            return parsed;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("arguments must be valid JSON", e);
        }
    }
    private static JsonElement read(JsonReader reader, int depth) throws java.io.IOException {
        if (depth > 32) throw new IllegalArgumentException("JSON nesting limit exceeded");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IllegalArgumentException("duplicate argument");
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("arguments must be valid JSON");
        };
    }
    public boolean has(String key) { return values.has(key); }
    public List<String> strings(String key) {
        if (!values.has(key)) return List.of();
        JsonElement value = values.get(key);
        if (!value.isJsonArray()) throw new IllegalArgumentException("Expected string array");
        List<String> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string array");
            result.add(item.getAsString());
        }
        return List.copyOf(result);
    }
    public String requiredString(String key) {
        String value = presentString(key);
        if (value.isBlank()) throw new IllegalArgumentException("missing required string argument '" + key + "'");
        return value;
    }
    public String presentString(String key) {
        String value = optionalString(key);
        if (value == null) throw new IllegalArgumentException("missing required string argument '" + key + "'");
        return value;
    }
    public String optionalString(String key) {
        JsonElement value = values.get(key);
        if (value == null) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("argument '" + key + "' must be a string");
        return value.getAsString();
    }
    public boolean bool(String key, boolean fallback) {
        JsonElement value = values.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("argument must be boolean");
        return value.getAsBoolean();
    }
    public int integer(String key, int fallback, int min, int max) {
        JsonElement value = values.get(key);
        if (value == null) return fallback;
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            int result = value.getAsBigDecimal().intValueExact();
            if (result < min || result > max) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException e) { throw new IllegalArgumentException("integer out of range"); }
    }
}
