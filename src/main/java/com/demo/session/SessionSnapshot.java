package com.demo.session;

import com.demo.model.Message;
import com.demo.memory.SessionMemoryState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable published state. unsaved is runtime-only and is never serialized. */
public record SessionSnapshot(String id, String title, Path workspace, Instant createdAt,
                              Instant updatedAt, List<Message> messages, SessionRun lastRun,
                              boolean unsaved, long contentRevision, SessionMemoryState memoryState) {
    /** Existing callers create a history that has not yet been organized. */
    public SessionSnapshot(String id, String title, Path workspace, Instant createdAt, Instant updatedAt,
                           List<Message> messages, SessionRun lastRun, boolean unsaved) {
        this(id, title, workspace, createdAt, updatedAt, messages, lastRun, unsaved,
                messages.size(), SessionMemoryState.empty());
    }

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
        java.util.Set<String> messageIds = new java.util.HashSet<>();
        for (Message message : messages) {
            if (!messageIds.add(message.getId())) throw new IllegalArgumentException("Duplicate message ID");
        }
        if (contentRevision < messages.size()) throw new IllegalArgumentException("Invalid content revision");
        Objects.requireNonNull(memoryState, "memoryState");
        memoryState.validateRevision(contentRevision);
        if (memoryState.lastOrganizedAt() != null
                && (memoryState.lastOrganizedAt().isBefore(createdAt)
                || memoryState.lastOrganizedAt().isAfter(updatedAt))) {
            throw new IllegalArgumentException("Organization timestamp is outside session lifetime");
        }
        memoryState.compression().coveredCount(messages);
    }

    public boolean dirty() { return memoryState.dirty(contentRevision); }

    /** Runtime unsaved flags do not participate in a persisted-state comparison. */
    public boolean samePersistedState(SessionSnapshot other) {
        return other != null && id.equals(other.id) && title.equals(other.title) && workspace.equals(other.workspace)
                && createdAt.equals(other.createdAt) && updatedAt.equals(other.updatedAt)
                && Objects.equals(lastRun, other.lastRun) && contentRevision == other.contentRevision
                && memoryState.equals(other.memoryState)
                && messages.stream().map(Message::toJson).toList().equals(other.messages.stream().map(Message::toJson).toList());
    }

    public static void requireId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("Expected a canonical UUID");
        }
    }
}
