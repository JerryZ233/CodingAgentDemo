package com.demo.model;

import com.google.gson.Gson;
import java.util.List;

/**
 * Represents a message in the conversation with the LLM.
 * 
 * Messages can be from the user, assistant, system, or a tool result.
 * This is used to maintain conversation history
 * during the agent loop.
 */
public class Message {
    
    private static final Gson GSON = new Gson();
    
    private final String role;      // "user", "assistant", "system", or "tool"
    private final String content;   // The actual message text
    private final List<ToolCall> toolCalls;
    private final String toolCallId;
    private final String toolName;
    
    public Message(String role, String content) {
        this(role, content, null, null, null);
    }

    private Message(String role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
    }
    
    public String getRole() {
        return role;
    }
    
    public String getContent() {
        return content;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }
    
    /**
     * Creates a user message.
     */
    public static Message user(String content) {
        return new Message("user", content);
    }
    
    /**
     * Creates an assistant message.
     */
    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    public static Message assistantToolCalls(String content, List<ToolCall> toolCalls) {
        return new Message("assistant", content, toolCalls, null, null);
    }

    public static Message tool(String toolCallId, String toolName, String content) {
        return new Message("tool", content, null, toolCallId, toolName);
    }
    
    /**
     * Creates a system message.
     */
    public static Message system(String content) {
        return new Message("system", content);
    }
    
    /**
     * Converts this message to a JSON string.
     * 
     * @return JSON representation of this message
     */
    public String toJson() {
        return GSON.toJson(this);
    }
    
    /**
     * Parses a JSON string and creates a Message object.
     * 
     * @param json The JSON string to parse
     * @return A new Message object, or null if parsing fails
     */
    public static Message fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        
        try {
            return GSON.fromJson(json, Message.class);
        } catch (Exception e) {
            return null;
        }
    }
}
