package com.demo.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a tool call requested by the LLM.
 * 
 * When the LLM decides to use a tool, it returns a ToolCall containing:
 * - The tool name (e.g., "read_file", "write_file")
 * - The arguments to pass to the tool (as a JSON-like string)
 */
public class ToolCall {
    
    private static final Gson GSON = new Gson();
    
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
     * Or nested: {"type": "function", "function": {"name": "read_file", "arguments": {...}}}
     */
    public static ToolCall fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            
            // Handle nested function format: {"type": "function", "function": {"name": ..., "arguments": ...}}
            if (root.has("function")) {
                JsonObject function = root.getAsJsonObject("function");
                String name = function.get("name").getAsString();
                JsonElement args = function.get("arguments");
                String argsStr = args.isJsonObject() ? args.getAsJsonObject().toString() : args.getAsString();
                return new ToolCall(name, argsStr);
            }
            
            // Handle flat format: {"name": ..., "arguments": ...}
            if (root.has("name")) {
                String name = root.get("name").getAsString();
                JsonElement args = root.get("arguments");
                String argsStr = args == null ? "{}" : (args.isJsonObject() ? args.getAsJsonObject().toString() : args.getAsString());
                return new ToolCall(name, argsStr);
            }
            
            return null;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Parses a JSON array of tool calls.
     */
    public static List<ToolCall> listFromJson(String json) {
        List<ToolCall> toolCalls = new ArrayList<>();
        
        if (json == null || json.isEmpty()) {
            return toolCalls;
        }
        
        try {
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : array) {
                ToolCall tc = fromJson(element.toString());
                if (tc != null) {
                    toolCalls.add(tc);
                }
            }
        } catch (Exception e) {
            // Return empty list
        }
        
        return toolCalls;
    }
}
