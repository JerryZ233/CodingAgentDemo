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

    private final String name;
    private final String description;
    private final JsonObject parametersSchema;

    public ToolSpec(String name, String description, JsonObject parametersSchema) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.parametersSchema = parametersSchema == null ? emptyParametersSchema() : parametersSchema.deepCopy();
    }

    public static ToolSpec from(Tool tool) {
        return new ToolSpec(tool.getName(), tool.getDescription(), tool.getParametersSchema());
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
