package com.demo.session;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

final class RunEventBuffer {
    private final String sessionId;
    private final String runId;
    private final Clock clock;
    private final List<SessionEvent> events = new ArrayList<>();
    private boolean finished;

    RunEventBuffer(String sessionId, String runId, Clock clock) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.clock = clock;
    }

    synchronized void append(SessionEvent.Type type, SessionEvent.Payload payload) {
        if (finished) return;
        events.add(new SessionEvent(sessionId, runId, events.size() + 1L, clock.instant(), type, payload));
        if (type == SessionEvent.Type.RUN_FINISHED) finished = true;
    }

    synchronized List<SessionEvent> after(long sequence) {
        if (sequence < 0) throw new IllegalArgumentException("Event sequence must not be negative");
        return List.copyOf(events.subList((int) Math.min(sequence, events.size()), events.size()));
    }
}
