package com.demo.agent;

import com.demo.model.ToolResult;

/**
 * Receives agent lifecycle events without coupling core logic to a concrete IO sink.
 */
public interface AgentObserver {

    static AgentObserver noop() {
        return NoOpAgentObserver.INSTANCE;
    }

    default void onLlmClientSelected(String message) {
    }

    default void onAgentStarted(String message) {
    }

    default void onAgentCompleted(String message) {
    }

    default void onIterationStarted(int iteration) {
    }

    default void onLlmError(String message) {
    }

    default void onFinalResponse(String response) {
    }

    default void onToolResult(String toolName, ToolResult result) {
    }

    default void onMaxIterations(String message) {
    }

    enum NoOpAgentObserver implements AgentObserver {
        INSTANCE
    }
}
