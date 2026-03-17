package com.demo.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Represents a message in the conversation with the LLM.
 * 
 * Messages can be from the user (user role), from the AI assistant (assistant role),
 * or from the system (system role). This is used to maintain conversation history
 * during the agent loop.
 */
public class Message {
    
    private static final Gson GSON = new Gson();
    
    private final String role;      // "user", "assistant", or "system"
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
