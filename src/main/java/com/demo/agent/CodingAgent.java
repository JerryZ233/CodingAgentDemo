package com.demo.agent;

import com.demo.execution.CancellationToken;
import com.demo.memory.context.ContextPreparation;
import com.demo.memory.context.ContextWindowStrategy;
import com.demo.llm.LLMClient;
import com.demo.tools.ToolDescriptions;
import com.demo.tools.ToolRegistry;
import java.util.Objects;

/** Executes one turn on caller-owned context; SessionService owns history and client lifetime. */
public final class CodingAgent {
    private final ToolRegistry tools;
    private final AgentLoop loop;

    public CodingAgent(LLMClient client, ToolRegistry tools, int maxIterations) {
        this.tools = Objects.requireNonNull(tools, "tools");
        if (maxIterations <= 0) throw new IllegalArgumentException("maxIterations must be positive");
        loop = new AgentLoop(Objects.requireNonNull(client, "client"), tools.getTools(), maxIterations, AgentObserver.noop());
    }
    public AgentRunResult runTurn(Conversation context, AgentCheckpoint checkpoint) {
        return runTurn(context, checkpoint, CancellationToken.none(), AgentEventLog.noop());
    }
    public AgentRunResult runTurn(Conversation context, AgentCheckpoint checkpoint, CancellationToken token, AgentEventLog events) {
        return runTurn(context, checkpoint, com.demo.tools.ToolExecutionContext.unavailable(token), events);
    }
    public AgentRunResult runTurn(Conversation context, AgentCheckpoint checkpoint, com.demo.tools.ToolExecutionContext execution, AgentEventLog events) {
        return runTurn(context, checkpoint, execution, events, new PromptBuilder(), new ContextWindowStrategy());
    }
    public AgentRunResult runTurn(Conversation context, AgentCheckpoint checkpoint, com.demo.tools.ToolExecutionContext execution,
                                  AgentEventLog events, PromptBuilder prompt, ContextPreparation preparation) {
        prompt.setToolSpecs(tools.getToolSpecs());
        return loop.run(context, checkpoint, execution, events, prompt, preparation);
    }
    public String getToolDescriptions() { return ToolDescriptions.toOpenAIToolsJson(tools.getToolSpecs()); }
}
