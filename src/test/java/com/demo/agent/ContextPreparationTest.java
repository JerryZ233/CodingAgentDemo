package com.demo.agent;

import com.demo.config.MemoryConfig;
import com.demo.execution.CancellationSource;
import com.demo.memory.CompressionState;
import com.demo.memory.ContextAssembler;
import com.demo.memory.ContextBlockedException;
import com.demo.memory.ContextBudget;
import com.demo.memory.TokenEstimator;
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
        Context context = new Context();
        context.setSystemPrompt("system");
        context.setToolDescriptions("not the prepared schema");
        context.addUserMessage("work");
        var prepared = new AtomicInteger();
        var requested = new AtomicInteger();
        var assembler = new ContextAssembler(new TokenEstimator(), new ContextBudget(MemoryConfig.defaults(), 2048));
        context.setPreparation((state, token) -> {
            token.throwIfCancellationRequested();
            if (prepared.incrementAndGet() == 2) assertEquals("tool observation", state.getMessages().get(2).getContent());
            return assembler.assemble(state.buildSystemPrompt(), "[]", "", "", CompressionState.empty(), state.getMessages());
        });
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
        var result = new AgentLoop(client, Map.of("echo", tool), 3, AgentObserver.noop(), events).run(context);
        assertEquals(AgentRunResult.Status.COMPLETED, result.getStatus());
        assertEquals(2, prepared.get());
        assertEquals(2, events.getEvents().stream().filter(event -> event.getType() == AgentEvent.Type.CONTEXT_PREPARED).count());
        assertTrue(context.getLastBudgetReport().orElseThrow().fits());
    }

    @Test void cancellationInsidePreparationPreventsRequestAdmission() {
        var source = new CancellationSource();
        Context context = new Context();
        context.addUserMessage("work");
        context.setPreparation((state, token) -> {
            assertSame(source.token(), token);
            source.cancel();
            return new ContextPreparation.Input(state.getMessages(), "[]", Optional.empty());
        });
        LLMClient never = (messages, schema) -> { fail("Cancelled preparation must not call model"); return null; };
        var result = new AgentLoop(never, Map.of()).run(context, AgentCheckpoint.noop(), source.token(), AgentEventLog.noop());
        assertEquals(AgentRunResult.Status.CANCELLED, result.getStatus());
        assertEquals(1, context.getMessages().size());
    }

    @Test void blockedPreparationNeverHidesCheckpointFailureOrAddsDiagnosticMessages() {
        Context context = new Context();
        context.addUserMessage("work");
        context.setPreparation((state, token) -> {
            throw new ContextBlockedException(ContextBlockedException.Reason.MEMORY_UNAVAILABLE, null);
        });
        var storageFailure = new IllegalStateException("storage unavailable");
        LLMClient never = (messages, schema) -> { fail("Blocked context must not call model"); return null; };
        assertSame(storageFailure, assertThrows(IllegalStateException.class, () -> new AgentLoop(never, Map.of())
                .run(context, (state, iteration) -> { throw storageFailure; })));
        assertEquals(1, context.getMessages().size());
    }
}
