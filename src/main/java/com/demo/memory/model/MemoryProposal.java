package com.demo.memory.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** An immutable proposal is also the stable identity of its eventual storage operation. */
public record MemoryProposal(String id, Scope scope, String expectedVersion, MemoryEdit edit,
                             List<MemorySource> sources, String reason, Instant createdAt, boolean automatic) {
    public enum Scope { PROJECT, GLOBAL }
    public MemoryProposal {
        Objects.requireNonNull(scope);
        new MemoryUpdate(id, expectedVersion, List.of(edit), sources, reason, createdAt);
        sources = List.copyOf(sources);
        if (scope == Scope.GLOBAL && automatic) throw new IllegalArgumentException("Global changes require approval");
    }
    public MemoryUpdate update() { return new MemoryUpdate(id, expectedVersion, List.of(edit), sources, reason, createdAt); }
}
