package com.demo.memory.model;

import com.demo.model.Message;
import java.util.Objects;

public record MemoryItem(String id, Kind kind, boolean active, String body) {
    public enum Kind { FACT, RULE, DECISION, PREFERENCE }
    public MemoryItem {
        Message.requireId(id);
        Objects.requireNonNull(kind, "kind");
        if (body == null || body.isBlank() || body.contains("<!-- memory:") || body.contains("<!-- /memory:")) {
            throw new IllegalArgumentException("Invalid memory item body");
        }
        body = body.strip();
    }
}
