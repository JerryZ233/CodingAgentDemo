package com.demo.agent;

import com.demo.execution.CancellationToken;
import com.demo.execution.OperationCancelledException;
import com.demo.execution.ResourceCleanupException;
import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.model.ToolResult;
import com.demo.tools.Tool;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One bounded think/execute/observe turn. Session lifetime belongs to SessionService. */
public class AgentLoop {
    public static final String CANCELLED_BEFORE_EXECUTION = "Cancelled before execution. This tool was not run.";
    public static final String CANCELLED_DURING_EXECUTION = "Cancelled during execution; partial effects may have occurred.";
    private final LLMClient llmClient;
    private final Map<String, Tool> tools;
    private final AgentObserver observer;
    private final AgentEventLog eventLog;
    private final int maxIterations;

    public AgentLoop(LLMClient client, Map<String, Tool> tools) { this(client, tools, 10); }
    public AgentLoop(LLMClient client, Map<String, Tool> tools, int maxIterations) {
        this(client, tools, maxIterations, AgentObserver.noop());
    }
    public AgentLoop(LLMClient client, Map<String, Tool> tools, AgentObserver observer) { this(client, tools, 10, observer); }
    public AgentLoop(LLMClient client, Map<String, Tool> tools, int maxIterations, AgentObserver observer) {
        this(client, tools, maxIterations, observer, AgentEventLog.noop());
    }
    public AgentLoop(LLMClient client, Map<String, Tool> tools, int maxIterations, AgentObserver observer, AgentEventLog log) {
        this.llmClient = Objects.requireNonNull(client);
        this.tools = Map.copyOf(tools);
        this.observer = Objects.requireNonNull(observer);
        AgentEventLog supplied = Objects.requireNonNull(log);
        AgentEventLog diagnostics = new LoggingAgentEventLog();
        this.eventLog = event -> { diagnostics.append(event); supplied.append(event); };
        this.maxIterations = maxIterations;
    }

    public AgentRunResult run(Context context) { return run(context, AgentCheckpoint.noop()); }
    public AgentRunResult run(Context context, AgentCheckpoint checkpoint) {
        return run(context, checkpoint, CancellationToken.none(), AgentEventLog.noop());
    }

    public AgentRunResult run(Context context, AgentCheckpoint checkpoint, CancellationToken token, AgentEventLog progress) {
        Objects.requireNonNull(checkpoint);
        AgentEventLog events = event -> { eventLog.append(event); progress.append(event); };
        events.append(AgentEvent.runStarted());
        int iteration = 0;
        try {
            for (int next = 1; next <= maxIterations; next++) {
                token.throwIfCancellationRequested();
                iteration = next;
                final int current = iteration;
                try (var iterationScope = org.apache.logging.log4j.CloseableThreadContext.put("iteration", String.valueOf(current))) {
                    observer.onIterationStarted(iteration);
                    events.append(AgentEvent.iterationStarted(iteration));
                    var messages = context.buildMessagesForLLM();
                    token.runIfActive(() -> events.append(AgentEvent.llmRequestStarted(current)));
                    LLMClient.LLMResponse response = llmClient.sendMessage(messages, context.getToolDescriptions(), token,
                            failure -> token.runIfActive(() -> events.append(AgentEvent.llmRetry(current, failure))));
                    token.throwIfCancellationRequested();
                    if (response == null || response.isError()) {
                        String error = response == null ? "Failed to get response from LLM."
                                : response.getFailure().isPresent() ? response.getText() : "Error: " + response.getText();
                        if (response != null) token.runIfActive(() -> context.addAssistantMessage(error));
                        checkpoint.save(context, iteration);
                        observer.onLlmError(error);
                        events.append(AgentEvent.llmError(iteration, error,
                                response == null ? null : response.getFailure().orElse(null)));
                        return complete(AgentRunResult.llmError(error, iteration), events);
                    }
                    String text = response.getText() == null ? "" : response.getText();
                    boolean finalResponse = !response.hasToolCalls();
                    token.runIfActive(() -> {
                        if (finalResponse) context.addAssistantMessage(text);
                        else context.addAssistantToolCalls(response.getText(), response.getToolCalls());
                    });
                    checkpoint.save(context, iteration);
                    events.append(AgentEvent.llmResponse(iteration, text, finalResponse));
                    if (finalResponse) {
                        token.throwIfCancellationRequested();
                        observer.onFinalResponse(text);
                        events.append(AgentEvent.finalResponse(iteration, text));
                        return complete(AgentRunResult.completed(text, iteration), events);
                    }
                    for (ToolCall call : response.getToolCalls()) {
                        try (var toolScope = org.apache.logging.log4j.CloseableThreadContext.put("tool_call_id", com.demo.logging.LogData.quote(call.getId()))) {
                            token.runIfActive(() -> events.append(AgentEvent.toolStarted(current, call)));
                            execute(call, context, iteration, checkpoint, token, events);
                            token.throwIfCancellationRequested();
                        }
                    }
                }
            }
            token.throwIfCancellationRequested();
            String error = "Maximum iterations reached. Task may not be complete.";
            observer.onMaxIterations(error);
            events.append(AgentEvent.maxIterations(maxIterations, error));
            return complete(AgentRunResult.maxIterations(error, maxIterations), events);
        } catch (OperationCancelledException cancelled) {
            int current = iteration;
            // Repair is deliberately not cancellable. The next request needs complete tool blocks.
            Map<String, ToolCall> pending = new LinkedHashMap<>();
            for (Message message : context.getMessages()) {
                if (message.getToolCalls() != null) message.getToolCalls().forEach(call -> pending.put(call.getId(), call));
                if ("tool".equals(message.getRole())) pending.remove(message.getToolCallId());
            }
            for (ToolCall call : pending.values()) {
                record(call, ToolResult.error(call.getToolName(), CANCELLED_BEFORE_EXECUTION), context,
                        current, checkpoint, events);
            }
            return complete(AgentRunResult.cancelled(current), events);
        }
    }

    private void execute(ToolCall call, Context context, int iteration, AgentCheckpoint checkpoint,
                         CancellationToken token, AgentEventLog events) {
        Tool tool = tools.get(call.getToolName());
        ToolResult result;
        if (tool == null) {
            result = ToolResult.error(call.getToolName(), "Error: Tool '" + call.getToolName() + "' not found.");
        } else {
            try {
                result = tool.execute(call.getArguments(), token);
                if (result == null) result = ToolResult.error(call.getToolName(), "Error: Tool '" + call.getToolName() + "' returned no result.");
            } catch (OperationCancelledException e) {
                record(call, ToolResult.error(call.getToolName(), CANCELLED_DURING_EXECUTION), context,
                        iteration, checkpoint, events);
                throw e;
            } catch (ResourceCleanupException e) {
                throw e; // Resource failure is not an ordinary tool error.
            } catch (Exception e) {
                com.demo.logging.Logging.logger(AgentLoop.class).warn("Tool execution raised exception name={} exception={}",
                        com.demo.logging.LogData.quote(call.getToolName()), com.demo.logging.LogData.exception(e));
                result = ToolResult.error(call.getToolName(), "Error: Tool '" + call.getToolName() + "' failed: " + e.getMessage());
            }
        }
        record(call, result, context, iteration, checkpoint, events);
    }

    private void record(ToolCall call, ToolResult result, Context context, int iteration,
                        AgentCheckpoint checkpoint, AgentEventLog events) {
        context.addToolResult(call, result);
        checkpoint.save(context, iteration);
        observer.onToolResult(call.getToolName(), result);
        events.append(AgentEvent.toolResult(iteration, call, result));
    }

    private AgentRunResult complete(AgentRunResult result, AgentEventLog events) {
        events.append(AgentEvent.runCompleted(result));
        return result;
    }
}
