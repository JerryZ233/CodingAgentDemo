package com.demo.agent;

import com.demo.logging.LogData;
import com.demo.logging.Logging;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.CloseableThreadContext;

/** Diagnostic sink independent of frontend event consumption and transcript replay. */
public final class LoggingAgentEventLog implements AgentEventLog {
    private final Logger log = Logging.logger(LoggingAgentEventLog.class);
    @Override public void append(AgentEvent event) {
        try (var scope = CloseableThreadContext.put("iteration", String.valueOf(event.getIteration()))
                .put("tool_call_id", event.getToolCallId() == null ? "" : LogData.quote(event.getToolCallId()))) {
            switch (event.getType()) {
                case ITERATION_STARTED -> log.debug("Iteration started");
                case CONTEXT_PREPARED -> log.debug("Conversation budget report={}", event.getContextBudget());
                case LLM_REQUEST_STARTED -> log.debug("Model request started");
                case LLM_RESPONSE -> {
                    if (log.isDebugEnabled()) log.debug("Model response received final={} text={}",
                            event.isFinalResponse(), LogData.quote(event.getMessage()));
                }
                case TOOL_STARTED -> {
                    if (log.isDebugEnabled()) log.debug("Tool started name={} arguments={}",
                            LogData.quote(event.getToolName()), LogData.quote(event.getArguments()));
                }
                case TOOL_RESULT -> {
                    var result = event.getToolResult();
                    if (result != null && !result.isSuccess())
                        log.warn("Tool failed name={} output={}", LogData.quote(event.getToolName()), LogData.quote(result.getOutput()));
                    else if (log.isDebugEnabled()) log.debug("Tool completed name={} output={}",
                            LogData.quote(event.getToolName()), LogData.quote(event.getMessage()));
                }
                case MAX_ITERATIONS -> log.warn("Iteration limit reached");
                case LLM_ERROR -> {
                    // Structured provider failures are logged once by the client.
                    if (event.getFailure() == null) log.error("Model failed diagnostic={}", LogData.quote(event.getMessage()));
                }
                case RUN_STARTED -> log.debug("Agent run started");
                case RUN_COMPLETED -> log.debug("Agent run finished status={}", event.getStatus());
                default -> { } // Reply/retry/lifecycle records already have a single owner.
            }
        }
    }
}
