package com.demo.agent;

import com.demo.model.Message;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages conversation history for the AI agent.
 * 
 * This class maintains a list of messages exchanged between the user and the AI,
 * and uses a Memory instance for persistent storage.
 */
public class Context {
    
    private final List<Message> messages;
    private final Memory memory;
    
    /**
     * Creates a new Context with a new Memory instance.
     */
    public Context() {
        this.messages = new ArrayList<>();
        this.memory = new Memory();
    }
    
    /**
     * Creates a new Context with a custom Memory instance.
     * 
     * @param memory The Memory instance to use for persistence
     */
    public Context(Memory memory) {
        this.messages = new ArrayList<>();
        this.memory = memory;
    }
    
    /**
     * Adds a user message to the conversation history.
     * 
     * @param content The content of the user message
     */
    public void addUserMessage(String content) {
        messages.add(Message.user(content));
    }
    
    /**
     * Adds an assistant message to the conversation history.
     * 
     * @param content The content of the assistant message
     */
    public void addAssistantMessage(String content) {
        messages.add(Message.assistant(content));
    }
    
    /**
     * Returns the list of messages in the conversation history.
     * 
     * @return The message list
     */
    public List<Message> getMessages() {
        return messages;
    }
    
    /**
     * Clears all messages from the conversation history.
     */
    public void clear() {
        messages.clear();
    }
    
    /**
     * Saves the conversation history to a JSON file.
     * 
     * @param path The file path to save to
     */
    public void saveToFile(String path) {
        memory.save(messages, path);
    }
    
    /**
     * Loads the conversation history from a JSON file.
     * 
     * @param path The file path to load from
     */
    public void loadFromFile(String path) {
        List<Message> loaded = memory.load(path);
        messages.clear();
        messages.addAll(loaded);
    }
    
    /**
     * Returns the Memory instance used for persistence.
     * 
     * @return The Memory instance
     */
    public Memory getMemory() {
        return memory;
    }
}
