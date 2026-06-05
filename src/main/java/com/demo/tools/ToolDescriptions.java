package com.demo.tools;

import com.google.gson.JsonArray;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders structured tool specs for the prompt and OpenAI-compatible APIs.
 */
public final class ToolDescriptions {

    private ToolDescriptions() {
    }

    public static List<ToolSpec> fromTools(Collection<Tool> tools) {
        return tools.stream()
                .map(Tool::getSpec)
                .collect(Collectors.toUnmodifiableList());
    }

    public static String toOpenAIToolsJson(Collection<ToolSpec> toolSpecs) {
        JsonArray toolsArray = new JsonArray();
        for (ToolSpec toolSpec : toolSpecs) {
            toolsArray.add(toolSpec.toOpenAIToolSchema());
        }
        return toolsArray.toString();
    }

    public static String toPromptText(Collection<ToolSpec> toolSpecs) {
        if (toolSpecs == null || toolSpecs.isEmpty()) {
            return "(No tools available)";
        }

        StringBuilder sb = new StringBuilder();
        for (ToolSpec toolSpec : toolSpecs) {
            sb.append("- ")
                    .append(toolSpec.getName())
                    .append(": ")
                    .append(toolSpec.getDescription());

            List<String> requiredParameters = toolSpec.getRequiredParameters();
            if (!requiredParameters.isEmpty()) {
                sb.append(" Required parameters: ")
                        .append(String.join(", ", requiredParameters))
                        .append(".");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
