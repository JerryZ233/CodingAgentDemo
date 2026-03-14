package com.demo.model;

/**
 * Represents a tool call requested by the LLM.
 * 
 * When the LLM decides to use a tool, it returns a ToolCall containing:
 * - The tool name (e.g., "read_file", "write_file")
 * - The arguments to pass to the tool (as a JSON-like string)
 */
public class ToolCall {
    
    private final String toolName;
    private final String arguments;
    
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
     * Example input: {"name": "read_file", "arguments": {"path": "/path/to/file"}}
     */
    public static ToolCall fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        
        String toolName = extractJsonField(json, "name");
        String arguments = extractJsonField(json, "arguments");
        
        if (toolName == null) {
            return null;
        }
        
        return new ToolCall(toolName, arguments);
    }
    
    private static String extractJsonField(String json, String fieldName) {
        String searchPattern = "\"" + fieldName + "\"";
        int fieldIndex = json.indexOf(searchPattern);
        if (fieldIndex == -1) {
            return null;
        }
        
        int colonIndex = json.indexOf(":", fieldIndex);
        if (colonIndex == -1) {
            return null;
        }
        
        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        
        if (valueStart >= json.length()) {
            return null;
        }
        
        char firstChar = json.charAt(valueStart);
        if (firstChar == '"') {
            int valueEnd = json.indexOf('"', valueStart + 1);
            if (valueEnd == -1) {
                return null;
            }
            return json.substring(valueStart + 1, valueEnd);
        } else if (firstChar == '{' || firstChar == '[') {
            int braceCount = 1;
            int valueEnd = valueStart + 1;
            while (valueEnd < json.length() && braceCount > 0) {
                char c = json.charAt(valueEnd);
                if (c == '{' || c == '[') {
                    braceCount++;
                } else if (c == '}' || c == ']') {
                    braceCount--;
                }
                valueEnd++;
            }
            return json.substring(valueStart, valueEnd);
        } else {
            int valueEnd = valueStart;
            while (valueEnd < json.length() && !Character.isWhitespace(json.charAt(valueEnd)) && json.charAt(valueEnd) != ',' && json.charAt(valueEnd) != '}') {
                valueEnd++;
            }
            return json.substring(valueStart, valueEnd).trim();
        }
    }
}
