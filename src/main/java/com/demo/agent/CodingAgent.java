package com.demo.agent;

import com.demo.execution.CancellationToken;
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
    public AgentRunResult runTurn(Context context, AgentCheckpoint checkpoint) {
        context.setToolSpecs(tools.getToolSpecs());
        return loop.run(context, checkpoint);
    }
    public AgentRunResult runTurn(Context context, AgentCheckpoint checkpoint, CancellationToken token, AgentEventLog events) {
        context.setToolSpecs(tools.getToolSpecs());
        return loop.run(context, checkpoint, token, events);
    }
    public String getToolDescriptions() { return ToolDescriptions.toOpenAIToolsJson(tools.getToolSpecs()); }
}
