package com.demo.memory.storage;

import com.demo.memory.MemoryItem;
import java.util.List;

public record MemoryDocument(boolean exists, String version, String text, List<MemoryItem> items) {
    public MemoryDocument { items = List.copyOf(items); }
}
