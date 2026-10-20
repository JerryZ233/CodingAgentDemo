package com.demo.memory;

import com.demo.model.Message;

/** Local provenance; at least one message or history identifier is required. */
public record MemorySource(String sessionId, String messageId, String historyId) {
    public MemorySource {
        if (messageId == null && historyId == null) throw new IllegalArgumentException("Memory needs a source");
        if (sessionId != null) Message.requireId(sessionId);
        if (messageId != null) {
            Message.requireId(messageId);
            Message.requireId(sessionId);
        }
        if (historyId != null) Message.requireId(historyId);
    }
}
