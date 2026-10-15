package com.demo.session;

import com.demo.agent.AgentRunResult;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable summary of the most recent turn; not an executable job. */
public record SessionRun(String id, Instant startedAt, Instant endedAt, Status status,
                         int iterations, String message) {
    public enum Status {
        RUNNING, COMPLETED, LLM_ERROR, MAX_ITERATIONS, TOOL_ERROR, FAILED, INTERRUPTED, STORAGE_ERROR
    }

    public SessionRun {
        SessionSnapshot.requireId(id);
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(status, "status");
        if (iterations < 0 || (status == Status.RUNNING) != (endedAt == null)
                || (endedAt != null && endedAt.isBefore(startedAt))) {
            throw new IllegalArgumentException("Invalid run timing, status or iteration count");
        }
    }

    public static SessionRun started(Instant now) {
        return new SessionRun(UUID.randomUUID().toString(), now, null, Status.RUNNING, 0, null);
    }

    public SessionRun atIteration(int iteration) {
        return new SessionRun(id, startedAt, endedAt, status, iteration, message);
    }

    public SessionRun finish(Status outcome, Instant now, String text) {
        return new SessionRun(id, startedAt, now.isBefore(startedAt) ? startedAt : now,
                outcome, iterations, text);
    }

    public SessionRun finish(AgentRunResult result, Instant now) {
        return atIteration(result.getIterations()).finish(Status.valueOf(result.getStatus().name()),
                now, result.getMessage());
    }
}
