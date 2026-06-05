package com.demo.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory event log useful for tests and diagnostics.
 */
public class InMemoryAgentEventLog implements AgentEventLog {

    private final List<AgentEvent> events = new ArrayList<>();

    @Override
    public void append(AgentEvent event) {
        events.add(event);
    }

    public List<AgentEvent> getEvents() {
        return List.copyOf(events);
    }
}
