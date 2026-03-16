package com.demo.model;

import com.demo.tools.JsonUtil;

/**
 * Represents a message in the conversation with the LLM.
 * 
 * Messages can be from the user (user role) or from the AI assistant (assistant role).
 * This is used to maintain conversation history during the agent loop.
 */
public class Message {
    
    private final String role;      // "user" or "assistant"
    private final String content;   // The actual message text
    
    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }
    
    public String getRole() {
        return role;
    }
    
    public String getContent() {
        return content;
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
    
    /**
     * Converts this message to a JSON string.
     * 
     * @return JSON representation of this message
     */
    public String toJson() {
        return "{\"role\": \"" + escapeJson(role) + "\", \"content\": \"" + escapeJson(content) + "\"}";
    }
    
    /**
     * Parses a JSON string and creates a Message object.
     * 
     * @param json The JSON string to parse
     * @return A new Message object, or null if parsing fails
     */
    public static Message fromJson(String json) {
        if (json == null) return null;
        
        String role = JsonUtil.getString(json, "role");
        String content = JsonUtil.getString(json, "content");
        
        if (role == null || content == null) return null;
        
        return new Message(role, content);
    }
    
    /**
     * Escapes special characters for JSON strings.
     * 
     * @param s The string to escape
     * @return The escaped string
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
