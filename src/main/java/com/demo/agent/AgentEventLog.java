package com.demo.agent;

/**
 * Append-only sink for agent execution events.
 */
public interface AgentEventLog {

    static AgentEventLog noop() {
        return NoOpAgentEventLog.INSTANCE;
    }

    void append(AgentEvent event);

    enum NoOpAgentEventLog implements AgentEventLog {
        INSTANCE;

        @Override
        public void append(AgentEvent event) {
        }
    }
}
