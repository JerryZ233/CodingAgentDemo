package com.demo.session;

import com.demo.model.ToolResult;
import java.time.Instant;

/** Live progress only; persisted conversation checkpoints remain the source of recovery. */
public record SessionEvent(String sessionId, String runId, long sequence, Instant timestamp,
                           Type type, Payload payload) {
    public enum Type {
        RUN_STARTED, ITERATION_STARTED, LLM_REQUEST_STARTED, ASSISTANT_MESSAGE,
        TOOL_STARTED, TOOL_RESULT, CANCEL_REQUESTED, RUN_FINISHED
    }
    public sealed interface Payload permits Progress, AssistantMessage, ToolStarted, ToolFinished, RunFinished { }
    public record Progress(int iteration) implements Payload { }
    public record AssistantMessage(int iteration, String text, boolean finalResponse) implements Payload { }
    public record ToolStarted(int iteration, String callId, String name, String arguments) implements Payload { }
    public record ToolFinished(int iteration, String callId, String name, ToolResult result) implements Payload { }
    public record RunFinished(SessionRun.Status status, int iterations, boolean unsaved, String diagnostic) implements Payload { }
}
