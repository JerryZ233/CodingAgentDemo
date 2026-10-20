package com.demo.agent;

/** Persistence failures must propagate; a checkpoint is outside the tool error boundary. */
@FunctionalInterface
public interface AgentCheckpoint {
    void save(Conversation context, int iteration);

    static AgentCheckpoint noop() { return (context, iteration) -> { }; }
}
