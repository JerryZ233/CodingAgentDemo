package com.demo.session;

import com.demo.model.Message;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable published state. unsaved is runtime-only and is never serialized. */
public record SessionSnapshot(String id, String title, Path workspace, Instant createdAt,
                              Instant updatedAt, List<Message> messages, SessionRun lastRun,
                              boolean unsaved) {
    public SessionSnapshot {
        requireId(id);
        if (title == null || title.isBlank()) throw new IllegalArgumentException("Missing session title");
        Objects.requireNonNull(workspace, "workspace");
        if (!workspace.isAbsolute()) throw new IllegalArgumentException("Workspace must be absolute");
        workspace = workspace.normalize();
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("Invalid session timestamps");
        messages = messages.stream().map(Message::copyOf).toList();
    }

    public static void requireId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("Expected a canonical UUID");
        }
    }
}
