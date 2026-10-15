package com.demo.session;

import java.nio.file.Path;
import java.time.Instant;

public record SessionSummary(String id, String title, Path workspace, Instant createdAt,
                             Instant updatedAt, int messageCount, SessionRun.Status lastRunStatus,
                             boolean unsaved) {
    public static SessionSummary from(SessionSnapshot snapshot) {
        return new SessionSummary(snapshot.id(), snapshot.title(), snapshot.workspace(),
                snapshot.createdAt(), snapshot.updatedAt(), snapshot.messages().size(),
                snapshot.lastRun() == null ? null : snapshot.lastRun().status(), snapshot.unsaved());
    }
}
