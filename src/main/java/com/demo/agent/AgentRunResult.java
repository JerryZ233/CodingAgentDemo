package com.demo.agent;

/**
 * Structured outcome of a single agent loop execution.
 */
public class AgentRunResult {

    public enum Status {
        COMPLETED,
        LLM_ERROR,
        MAX_ITERATIONS,
        TOOL_ERROR
    }

    private final Status status;
    private final String message;
    private final int iterations;

    private AgentRunResult(Status status, String message, int iterations) {
        this.status = status;
        this.message = message;
        this.iterations = iterations;
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public int getIterations() {
        return iterations;
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    public static AgentRunResult completed(String message, int iterations) {
        return new AgentRunResult(Status.COMPLETED, message, iterations);
    }

    public static AgentRunResult llmError(String message, int iterations) {
        return new AgentRunResult(Status.LLM_ERROR, message, iterations);
    }

    public static AgentRunResult maxIterations(String message, int iterations) {
        return new AgentRunResult(Status.MAX_ITERATIONS, message, iterations);
    }

    public static AgentRunResult toolError(String message, int iterations) {
        return new AgentRunResult(Status.TOOL_ERROR, message, iterations);
    }
}
