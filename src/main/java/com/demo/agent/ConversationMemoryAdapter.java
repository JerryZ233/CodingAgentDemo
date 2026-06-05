package com.demo.agent;

import com.demo.model.Message;
import java.util.List;
import java.util.Objects;

/**
 * ConversationStore implementation backed by the existing Memory file store.
 */
public class ConversationMemoryAdapter implements ConversationStore {

    private final Memory memory;

    public ConversationMemoryAdapter() {
        this(new Memory());
    }

    public ConversationMemoryAdapter(Memory memory) {
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    @Override
    public void save(List<Message> messages, String path) {
        memory.save(messages, path);
    }

    @Override
    public List<Message> load(String path) {
        return memory.load(path);
    }

    public Memory getMemory() {
        return memory;
    }
}
