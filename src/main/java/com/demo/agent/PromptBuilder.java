package com.demo.agent;

import com.demo.tools.ToolDescriptions;
import com.demo.tools.ToolSpec;
import java.util.Collection;
import java.util.List;

/**
 * Builds the system prompt and renders tool descriptions for the model.
 */
public class PromptBuilder {

    private static final String TOOLS_PLACEHOLDER = "{TOOLS}";

    private String systemPromptTemplate;
    private String toolDescriptions;
    private List<ToolSpec> toolSpecs;

    public PromptBuilder() {
        this.systemPromptTemplate = defaultSystemPromptTemplate();
        this.toolDescriptions = "";
        this.toolSpecs = List.of();
    }

    public String buildSystemPrompt() {
        if (systemPromptTemplate == null) {
            return "";
        }
        return systemPromptTemplate.replace(TOOLS_PLACEHOLDER, formatToolDescriptionsForPrompt());
    }

    public void setSystemPromptTemplate(String systemPromptTemplate) {
        this.systemPromptTemplate = systemPromptTemplate;
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

    private String formatToolDescriptionsForPrompt() {
        if (toolSpecs != null && !toolSpecs.isEmpty()) {
            return ToolDescriptions.toPromptText(toolSpecs);
        }

        if (toolDescriptions != null && !toolDescriptions.isEmpty()) {
            return toolDescriptions;
        }

        return "(No tools available)";
    }

    private String defaultSystemPromptTemplate() {
        return "You are an AI coding assistant with the ability to execute tools to accomplish coding tasks.\n\n" +
               "## Your Role\n" +
               "You help users with programming tasks by understanding their requirements, writing code,\n" +
               "executing commands, and managing files using the available tools.\n\n" +
               "## Available Tools\n" +
               "You have access to the following tools:\n" +
               TOOLS_PLACEHOLDER + "\n\n" +
               "## Tool Use\n" +
               "When a task requires reading files, writing files, listing directories, or running another available tool,\n" +
               "use the tool calling mechanism provided by the API. Do not describe a tool call in prose.\n" +
               "After receiving the tool result, decide whether another tool call is needed or provide your final answer.\n\n" +
               "## Workflow\n" +
               "For each user request, follow this cycle:\n" +
               "1. THINK: Analyze the request and determine what needs to be done\n" +
               "2. DECIDE: Decide if you need to use tools or can answer directly\n" +
               "3. EXECUTE: If using tools, call the appropriate API tool\n" +
               "4. OBSERVE: Read the tool result returned to you\n" +
               "5. RESPOND: Provide a clear response to the user\n\n" +
               "## Tool Calling Rules\n" +
               "- Use tools ONLY when necessary to complete the user's request\n" +
               "- If the user asks a question you can answer directly (like general knowledge),\n" +
               "  respond without using tools\n" +
               "- If the user asks you to create files, run commands, read files, or perform\n" +
               "  any action that requires tool use, call the appropriate tool\n" +
               "- Always check tool results before providing final response\n\n" +
               "## Response Guidelines\n" +
               "- Be concise but informative\n" +
               "- Explain what you're going to do before doing it\n" +
               "- Show the user the results of tool executions\n" +
               "- If something goes wrong, explain the error and try to fix it\n\n" +
               "## Constraints\n" +
               "- All file operations must be within the designated workspace directory\n" +
               "- Do not execute destructive commands (rm -rf, format, etc.)\n" +
               "- Always confirm potentially dangerous operations with the user first";
    }
}
