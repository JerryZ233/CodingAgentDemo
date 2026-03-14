package com.demo.model;

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
}
