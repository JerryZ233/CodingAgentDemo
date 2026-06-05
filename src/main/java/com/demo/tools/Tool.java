package com.demo.tools;

import com.demo.model.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Interface for all tools that the agent can use.
 * 
 * Each tool must implement:
 * - getName(): returns the tool's identifier
 * - getDescription(): returns a description for the LLM
 * - execute(): performs the tool's action
 */
public interface Tool {
    
    /**
     * Returns the unique name of this tool.
     * This is used to identify which tool to call.
     */
    String getName();
    
    /**
     * Returns a description of what this tool does.
     * This is provided to the LLM so it knows when to use the tool.
     */
    String getDescription();

    /**
     * Returns the JSON schema for this tool's arguments.
     */
    default JsonObject getParametersSchema() {
        return JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}").getAsJsonObject();
    }

    /**
     * Returns the structured spec used for both prompts and API tool schemas.
     */
    default ToolSpec getSpec() {
        return ToolSpec.from(this);
    }
    
    /**
     * Executes the tool with the given arguments.
     * 
     * @param args Arguments as a JSON string
     * @return ToolResult containing success status and output
     */
    ToolResult execute(String args);
}
