package com.demo.agent;

import com.demo.tools.ToolDescriptions;
import com.demo.tools.ToolSpec;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Builds the system prompt and renders tool descriptions for the model.
 */
public class PromptBuilder {

    private PromptTemplate promptTemplate;
    private String toolDescriptions;
    private List<ToolSpec> toolSpecs;

    public PromptBuilder() {
        this(PromptTemplate.defaultTemplate());
    }

    public PromptBuilder(PromptTemplate promptTemplate) {
        this.promptTemplate = Objects.requireNonNull(promptTemplate, "promptTemplate");
        this.toolDescriptions = "";
        this.toolSpecs = List.of();
    }

    public String buildSystemPrompt() {
        return promptTemplate.render(formatToolDescriptionsForPrompt());
    }

    public void setSystemPromptTemplate(String systemPromptTemplate) {
        this.promptTemplate = PromptTemplate.custom(systemPromptTemplate);
    }

    public void setToolDescriptions(String toolDescriptions) {
        this.toolDescriptions = toolDescriptions;
        this.toolSpecs = List.of();
    }

    public void setToolSpecs(Collection<ToolSpec> toolSpecs) {
        this.toolSpecs = toolSpecs == null ? List.of() : List.copyOf(toolSpecs);
        this.toolDescriptions = ToolDescriptions.toOpenAIToolsJson(this.toolSpecs);
    }

    public List<ToolSpec> getToolSpecs() {
        return toolSpecs;
    }

    public String getToolDescriptions() {
        return toolDescriptions;
    }

    public String getTemplateVersion() {
        return promptTemplate.getVersion();
    }

    private String formatToolDescriptionsForPrompt() {
        if (toolSpecs != null && !toolSpecs.isEmpty()) {
            return ToolDescriptions.toPromptText(toolSpecs);
        }

        if (toolDescriptions != null && !toolDescriptions.isEmpty()) {
            return toolDescriptions;
        }

        return "(No tools available)";
    }
}
