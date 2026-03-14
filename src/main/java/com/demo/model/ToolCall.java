package com.demo.model;

/**
 * Represents a tool call requested by the LLM.
 * 
 * When the LLM decides to use a tool, it returns a ToolCall containing:
 * - The tool name (e.g., "read_file", "write_file")
 * - The arguments to pass to the tool (as a JSON-like string)
 */
public class ToolCall {
    
    private final String toolName;    // Name of the tool to execute
    private final String arguments;   // Arguments as JSON string
    
    public ToolCall(String toolName, String arguments) {
        this.toolName = toolName;
        this.arguments = arguments;
    }
    
    public String getToolName() {
        return toolName;
    }
    
    public String getArguments() {
        return arguments;
    }
    
    /**
     * Parses a JSON-formatted tool call string into a ToolCall object.
     * 
     * Implementation idea:
     * 1. Parse JSON using a library like Gson or Jackson
     * 2. Extract "name" and "arguments" fields
     * 3. Return new ToolCall instance
     */
    public static ToolCall fromJson(String json) {
        // TODO: implement JSON parsing
        // Example: {"name": "read_file", "arguments": {"path": "/path/to/file"}}
        return null;
    }
}
