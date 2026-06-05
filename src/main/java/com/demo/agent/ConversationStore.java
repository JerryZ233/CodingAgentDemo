package com.demo.agent;

import com.demo.model.Message;
import java.util.List;

/**
 * Persistence boundary for conversation history.
 */
public interface ConversationStore {

    /**
     * Saves the conversation history.
     *
     * @param messages The messages to save
     * @param path The persistence target
     */
    void save(List<Message> messages, String path);

    /**
     * Loads conversation history.
     *
     * @param path The persistence source
     * @return The loaded messages
     */
    List<Message> load(String path);
}
