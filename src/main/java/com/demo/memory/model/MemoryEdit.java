package com.demo.memory.model;

import com.demo.model.Message;
import java.util.Objects;

/** A bounded item operation, never a filesystem path or arbitrary textual patch. */
public record MemoryEdit(Action action, String itemId, MemoryItem.Kind kind, String body) {
    public enum Action { ADD, UPDATE, INVALIDATE, IGNORE }
    public MemoryEdit {
        Objects.requireNonNull(action, "action");
        if (action == Action.IGNORE) {
            if (itemId != null || kind != null || body != null) throw new IllegalArgumentException("Ignore has no item payload");
        } else {
            Message.requireId(itemId);
            if (action == Action.ADD || action == Action.UPDATE) {
                body = new MemoryItem(itemId, kind, true, body).body();
            } else if (kind != null || body != null) {
                throw new IllegalArgumentException("Invalidation preserves the original item");
            }
        }
    }
    public static MemoryEdit add(String id, MemoryItem.Kind kind, String body) { return new MemoryEdit(Action.ADD, id, kind, body); }
    public static MemoryEdit update(String id, MemoryItem.Kind kind, String body) { return new MemoryEdit(Action.UPDATE, id, kind, body); }
    public static MemoryEdit invalidate(String id) { return new MemoryEdit(Action.INVALIDATE, id, null, null); }
    public static MemoryEdit ignore() { return new MemoryEdit(Action.IGNORE, null, null, null); }
}
