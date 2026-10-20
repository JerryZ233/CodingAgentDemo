package com.demo.agent;

import com.demo.memory.context.ContextPreparation;
import com.demo.config.MemoryConfig;
import com.demo.execution.CancellationSource;
import com.demo.memory.context.CompressionState;
import com.demo.memory.context.ContextAssembler;
import com.demo.memory.context.ContextBlockedException;
import com.demo.memory.context.ContextBudget;
import com.demo.memory.context.TokenEstimator;
import com.demo.llm.LLMClient;
import com.demo.model.ToolCall;
import com.demo.model.ToolResult;
import com.demo.tools.Tool;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextPreparationTest {
    @Test void preparationRunsBeforeEveryRequestAndItsMessagesAndSchemaAreSentTogether() {
        Conversation context = new Conversation();
        var prompt = new PromptBuilder();
        prompt.setSystemPromptTemplate("system");
        prompt.setToolDescriptions("not the prepared schema");
        context.addUserMessage("work");
        var prepared = new AtomicInteger();
        var requested = new AtomicInteger();
        var assembler = new ContextAssembler(new TokenEstimator(), new ContextBudget(MemoryConfig.defaults(), 2048));
        ContextPreparation preparation = (state, token) -> {
            token.throwIfCancellationRequested();
            if (prepared.incrementAndGet() == 2) assertEquals("tool observation", state.messages().get(2).getContent());
            return assembler.assemble(state.systemPrompt(), "[]", "", "", CompressionState.empty(), state.messages());
        };
        LLMClient client = (messages, schema) -> {
            assertEquals("[]", schema);
            assertEquals(prepared.get(), requested.incrementAndGet());
            return requested.get() == 1
                    ? new LLMClient.LLMResponse(null, List.of(new ToolCall("echo", "echo", "{}")))
                    : new LLMClient.LLMResponse("done", null);
        };
        Tool tool = new Tool() {
            public String getName() { return "echo"; }
            public String getDescription() { return "echo"; }
            public ToolResult execute(String arguments) { return ToolResult.success("echo", "tool observation"); }
        };
        var events = new InMemoryAgentEventLog();
        var result = new AgentLoop(client, Map.of("echo", tool), 3, AgentObserver.noop(), events).run(context, AgentCheckpoint.noop(), com.demo.tools.ToolExecutionContext.unavailable(com.demo.execution.CancellationToken.none()), AgentEventLog.noop(), prompt, preparation);
        assertEquals(AgentRunResult.Status.COMPLETED, result.getStatus());
        assertEquals(2, prepared.get());
        assertEquals(2, events.getEvents().stream().filter(event -> event.getType() == AgentEvent.Type.CONTEXT_PREPARED).count());
        assertEquals(4, context.getContentRevision());
    }

    @Test void cancellationInsidePreparationPreventsRequestAdmission() {
        var source = new CancellationSource();
        Conversation context = new Conversation();
        context.addUserMessage("work");
        ContextPreparation preparation = (state, token) -> {
            assertSame(source.token(), token);
            source.cancel();
            return new ContextPreparation.Input(state.messages(), "[]", Optional.empty());
        };
        LLMClient never = (messages, schema) -> { fail("Cancelled preparation must not call model"); return null; };
        var result = new AgentLoop(never, Map.of()).run(context, AgentCheckpoint.noop(), com.demo.tools.ToolExecutionContext.unavailable(source.token()), AgentEventLog.noop(), new PromptBuilder(), preparation);
        assertEquals(AgentRunResult.Status.CANCELLED, result.getStatus());
        assertEquals(1, context.getMessages().size());
    }

    @Test void blockedPreparationNeverHidesCheckpointFailureOrAddsDiagnosticMessages() {
        Conversation context = new Conversation();
        context.addUserMessage("work");
        ContextPreparation preparation = (state, token) -> {
            throw new ContextBlockedException(ContextBlockedException.Reason.MEMORY_UNAVAILABLE, null);
        };
        var storageFailure = new IllegalStateException("storage unavailable");
        LLMClient never = (messages, schema) -> { fail("Blocked context must not call model"); return null; };
        assertSame(storageFailure, assertThrows(IllegalStateException.class, () -> new AgentLoop(never, Map.of())
                .run(context, (state, iteration) -> { throw storageFailure; },
                        com.demo.tools.ToolExecutionContext.unavailable(com.demo.execution.CancellationToken.none()),
                        AgentEventLog.noop(), new PromptBuilder(), preparation)));
        assertEquals(1, context.getMessages().size());
    }
}
