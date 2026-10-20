package com.demo.memory.model;

import com.demo.model.Message;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Validated storage request. Scope/authorization and token budgets belong to the harness. */
public record MemoryUpdate(String operationId, String expectedVersion, List<MemoryEdit> edits,
                           List<MemorySource> sources, String reason, Instant createdAt) {
    public MemoryUpdate {
        Message.requireId(operationId);
        if (expectedVersion == null || !(expectedVersion.equals("missing") || expectedVersion.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Invalid memory version");
        }
        edits = List.copyOf(edits);
        sources = List.copyOf(sources);
        if (edits.isEmpty() || sources.isEmpty() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Memory update requires operations, sources and reason");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
