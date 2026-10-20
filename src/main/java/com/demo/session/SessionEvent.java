package com.demo.session;

import com.demo.model.ToolResult;
import java.time.Instant;

/** Live progress only; persisted conversation checkpoints remain the source of recovery. */
public record SessionEvent(String sessionId, String runId, long sequence, Instant timestamp,
                           Type type, Payload payload) {
    public enum Type {
        RUN_STARTED, ITERATION_STARTED, CONTEXT_PREPARED, MEMORY_COMPACTION, LLM_REQUEST_STARTED, LLM_RETRY, LLM_FAILED, ASSISTANT_MESSAGE,
        TOOL_STARTED, TOOL_RESULT, APPROVAL, COMMAND_OUTPUT, CANCEL_REQUESTED, RUN_FINISHED
    }
    public sealed interface Payload permits Approval, CommandOutput, Progress, ContextPrepared, MemoryCompaction, ModelFailure, AssistantMessage, ToolStarted, ToolFinished, RunFinished { }
    public record MemoryCompaction(com.demo.memory.MemoryHarness.CompactionPhase phase) implements Payload { }
    public record ContextPrepared(com.demo.memory.ContextBudget.Report budget) implements Payload { }
    public record Approval(String kind) implements Payload { }
    public record CommandOutput(String text) implements Payload { }
    public record ModelFailure(int iteration, com.demo.llm.LLMFailure failure) implements Payload { }
    public record Progress(int iteration) implements Payload { }
    public record AssistantMessage(int iteration, String text, boolean finalResponse) implements Payload { }
    public record ToolStarted(int iteration, String callId, String name, String arguments) implements Payload { }
    public record ToolFinished(int iteration, String callId, String name, ToolResult result) implements Payload { }
    public record RunFinished(SessionRun.Status status, int iterations, boolean unsaved, String diagnostic) implements Payload { }
}
