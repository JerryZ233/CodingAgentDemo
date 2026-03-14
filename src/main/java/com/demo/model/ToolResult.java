package com.demo.model;

/**
 * Represents the result of executing a tool.
 * 
 * After a tool is executed, it returns a ToolResult containing:
 * - Whether the execution was successful
 * - The output content (or error message)
 */
public class ToolResult {
    
    private final boolean success;    // Whether the tool executed successfully
    private final String output;      // The result content or error message
    private final String toolName;    // Name of the tool that was executed
    
    public ToolResult(String toolName, boolean success, String output) {
        this.toolName = toolName;
        this.success = success;
        this.output = output;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public String getOutput() {
        return output;
    }
    
    public String getToolName() {
        return toolName;
    }
    
    /**
     * Creates a successful result.
     */
    public static ToolResult success(String toolName, String output) {
        return new ToolResult(toolName, true, output);
    }
    
    /**
     * Creates a failed result.
     */
    public static ToolResult error(String toolName, String errorMessage) {
        return new ToolResult(toolName, false, errorMessage);
    }
}
