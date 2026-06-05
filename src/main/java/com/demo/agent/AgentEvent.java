package com.demo.agent;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable event emitted by the agent loop for trace and replay diagnostics.
 */
public class AgentEvent {

    public enum Type {
        RUN_STARTED,
        ITERATION_STARTED,
        LLM_RESPONSE,
        LLM_ERROR,
        TOOL_RESULT,
        FINAL_RESPONSE,
        MAX_ITERATIONS,
        RUN_COMPLETED
    }

    private final Type type;
    private final Instant timestamp;
    private final int iteration;
    private final String message;
    private final String toolName;
    private final AgentRunResult.Status status;

    private AgentEvent(Type type, int iteration, String message, String toolName, AgentRunResult.Status status) {
        this.type = Objects.requireNonNull(type, "type");
        this.timestamp = Instant.now();
        this.iteration = iteration;
        this.message = message;
        this.toolName = toolName;
        this.status = status;
    }

    public static AgentEvent runStarted() {
        return new AgentEvent(Type.RUN_STARTED, 0, null, null, null);
    }

    public static AgentEvent iterationStarted(int iteration) {
        return new AgentEvent(Type.ITERATION_STARTED, iteration, null, null, null);
    }

    public static AgentEvent llmResponse(int iteration, String message) {
        return new AgentEvent(Type.LLM_RESPONSE, iteration, message, null, null);
    }

    public static AgentEvent llmError(int iteration, String message) {
        return new AgentEvent(Type.LLM_ERROR, iteration, message, null, AgentRunResult.Status.LLM_ERROR);
    }

    public static AgentEvent toolResult(int iteration, String toolName, String message) {
        return new AgentEvent(Type.TOOL_RESULT, iteration, message, toolName, null);
    }

    public static AgentEvent finalResponse(int iteration, String message) {
        return new AgentEvent(Type.FINAL_RESPONSE, iteration, message, null, AgentRunResult.Status.COMPLETED);
    }

    public static AgentEvent maxIterations(int iteration, String message) {
        return new AgentEvent(Type.MAX_ITERATIONS, iteration, message, null, AgentRunResult.Status.MAX_ITERATIONS);
    }

    public static AgentEvent runCompleted(AgentRunResult result) {
        return new AgentEvent(Type.RUN_COMPLETED, result.getIterations(), result.getMessage(), null, result.getStatus());
    }

    public Type getType() {
        return type;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public int getIteration() {
        return iteration;
    }

    public String getMessage() {
        return message;
    }

    public String getToolName() {
        return toolName;
    }

    public AgentRunResult.Status getStatus() {
        return status;
    }
}
