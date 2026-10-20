package com.demo.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Structured description of a tool used by both prompts and LLM API schemas.
 */
public final class ToolSpec {
    public enum Capability { FILE_READ, FILE_WRITE, DIRECTORY_LIST, PROCESS_EXECUTE, NETWORK, MEMORY_READ, MEMORY_WRITE }
    private final java.util.Set<Capability> capabilities;
    public java.util.Set<Capability> getCapabilities() { return capabilities; }


    private final String name;
    private final String description;
    private final JsonObject parametersSchema;

    public ToolSpec(String name, String description, JsonObject parametersSchema) {
        this(name, description, parametersSchema, java.util.Set.of());
    }

    public ToolSpec(String name, String description, JsonObject parametersSchema, java.util.Set<Capability> capabilities) {
        this.name = Objects.requireNonNull(name, "name");
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid tool name");
        this.capabilities = java.util.Set.copyOf(capabilities);
        this.description = description == null ? "" : description;
        this.parametersSchema = parametersSchema == null ? emptyParametersSchema() : parametersSchema.deepCopy();
        if (!this.parametersSchema.has("type") || !this.parametersSchema.get("type").isJsonPrimitive()
                || !"object".equals(this.parametersSchema.get("type").getAsString())
                || !this.parametersSchema.has("properties") || !this.parametersSchema.get("properties").isJsonObject())
            throw new IllegalArgumentException("Tool schema must describe an object with properties");
        for (var property : this.parametersSchema.getAsJsonObject("properties").entrySet())
            if (!property.getValue().isJsonObject()) throw new IllegalArgumentException("Invalid property schema");
        if (this.parametersSchema.has("additionalProperties")
                && !(this.parametersSchema.get("additionalProperties").isJsonObject()
                || this.parametersSchema.get("additionalProperties").isJsonPrimitive()
                && this.parametersSchema.get("additionalProperties").getAsJsonPrimitive().isBoolean()))
            throw new IllegalArgumentException("Invalid additionalProperties schema");
        if (this.parametersSchema.has("required")) {
            if (!this.parametersSchema.get("required").isJsonArray()) throw new IllegalArgumentException("Invalid required fields");
            java.util.Set<String> names = new java.util.HashSet<>();
            for (var field : this.parametersSchema.getAsJsonArray("required")) {
                if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()
                        || !this.parametersSchema.getAsJsonObject("properties").has(field.getAsString())
                        || !names.add(field.getAsString())) throw new IllegalArgumentException("Invalid required field");
            }
        }
    }

    public static ToolSpec from(Tool tool) {
        return new ToolSpec(tool.getName(), tool.getDescription(), tool.getParametersSchema(), tool.getCapabilities());
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public JsonObject getParametersSchema() {
        return parametersSchema.deepCopy();
    }

    public List<String> getRequiredParameters() {
        List<String> required = new ArrayList<>();
        if (!parametersSchema.has("required") || !parametersSchema.get("required").isJsonArray()) {
            return required;
        }

        JsonArray requiredArray = parametersSchema.getAsJsonArray("required");
        requiredArray.forEach(item -> required.add(item.getAsString()));
        return required;
    }

    public JsonObject toOpenAIToolSchema() {
        JsonObject toolObject = new JsonObject();
        toolObject.addProperty("type", "function");

        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", getParametersSchema());

        toolObject.add("function", function);
        return toolObject;
    }

    private static JsonObject emptyParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }
}
