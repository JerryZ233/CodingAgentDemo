package com.demo.agent;

import com.demo.model.ToolCall;
import com.demo.model.ToolResult;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable event emitted by the agent loop for trace and replay diagnostics.
 */
public class AgentEvent {

    public enum Type {
        RUN_STARTED,
        ITERATION_STARTED,
        CONTEXT_PREPARED,
        LLM_REQUEST_STARTED,
        LLM_RETRY,
        LLM_RESPONSE,
        LLM_ERROR,
        TOOL_RESULT,
        TOOL_STARTED,
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
    private String toolCallId;
    private String arguments;
    private ToolResult toolResult;
    private boolean finalResponse;
    private com.demo.llm.LLMFailure failure;
    private com.demo.memory.ContextBudget.Report contextBudget;

    public com.demo.memory.ContextBudget.Report getContextBudget() { return contextBudget; }

    public static AgentEvent contextPrepared(int iteration, com.demo.memory.ContextBudget.Report report) {
        AgentEvent event = new AgentEvent(Type.CONTEXT_PREPARED, iteration, null, null, null);
        event.contextBudget = Objects.requireNonNull(report);
        return event;
    }

    public com.demo.llm.LLMFailure getFailure() { return failure; }

    public static AgentEvent llmRetry(int iteration, com.demo.llm.LLMFailure failure) {
        AgentEvent event = new AgentEvent(Type.LLM_RETRY, iteration, null, null, null);
        event.failure = failure;
        return event;
    }

    public static AgentEvent llmError(int iteration, String message, com.demo.llm.LLMFailure failure) {
        AgentEvent event = llmError(iteration, message);
        event.failure = failure;
        return event;
    }

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

    public static AgentEvent llmResponse(int iteration, String message, boolean finalResponse) {
        AgentEvent event = llmResponse(iteration, message);
        event.finalResponse = finalResponse;
        return event;
    }

    public static AgentEvent llmRequestStarted(int iteration) {
        return new AgentEvent(Type.LLM_REQUEST_STARTED, iteration, null, null, null);
    }

    public static AgentEvent toolStarted(int iteration, ToolCall call) {
        AgentEvent event = new AgentEvent(Type.TOOL_STARTED, iteration, null, call.getToolName(), null);
        event.toolCallId = call.getId();
        event.arguments = call.getArguments();
        return event;
    }

    public static AgentEvent toolResult(int iteration, ToolCall call, ToolResult result) {
        AgentEvent event = toolResult(iteration, call.getToolName(), result.getOutput());
        event.toolCallId = call.getId();
        event.toolResult = result;
        return event;
    }

    public String getToolCallId() { return toolCallId; }
    public String getArguments() { return arguments; }
    public ToolResult getToolResult() { return toolResult; }
    public boolean isFinalResponse() { return finalResponse; }

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
