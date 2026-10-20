package com.demo.memory.context;

import com.demo.agent.Conversation;
import com.demo.config.MemoryConfig;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextAssemblerTest {
    @Test void sharedAnalysisPreservesBudgetAndSelectionWithoutEstimatingRawMessagesAgain() {
        var raw = List.of(Message.user("old request"), Message.assistant("old answer"),
                Message.assistantToolCalls(null, List.of(new com.demo.model.ToolCall("read", "read_file", "{}"))),
                Message.tool("read", "read_file", "result"), Message.assistant("pending"), Message.user("current"));
        var ids = raw.stream().map(Message::getId).collect(java.util.stream.Collectors.toSet());
        var estimates = new java.util.concurrent.atomic.AtomicInteger();
        var estimator = new TokenEstimator() {
            @Override public long message(Message message) {
                if (ids.contains(message.getId())) estimates.incrementAndGet();
                return super.message(message);
            }
        };
        var config = com.demo.config.MemoryConfig.defaults();
        var assembler = new ContextAssembler(estimator, new ContextBudget(config, 2048));
        var compression = new CompressionState(raw.get(1).getId(), "history", "summary", 1);
        var analysis = new ConversationBlockBuilder().analyze(raw, compression, estimator);
        var input = assembler.assemble("system", "[]", "", "", compression, raw, analysis);
        var report = input.budget().orElseThrow();
        var planner = new com.demo.memory.compaction.CompactionPlanner(config, estimator);
        var selected = planner.select(analysis, report);
        assertEquals(raw.size(), estimates.get());
        var ordinary = new ContextAssembler(new TokenEstimator(), new ContextBudget(config, 2048))
                .assemble("system", "[]", "", "", compression, raw);
        assertEquals(ordinary.budget(), input.budget());
        assertEquals(planner.select(raw, compression, report).stream().map(Message::getId).toList(),
                selected.stream().map(Message::getId).toList());
        var advanced = new CompressionState(raw.get(3).getId(), "next-history", "next summary", 2);
        var next = new ConversationBlockBuilder().analyze(raw, advanced, estimator);
        assertEquals(4, next.covered());
        assertEquals(new TokenEstimator().messages(raw.subList(4, raw.size())), next.recentTokens());
    }

    private final TokenEstimator estimator = new TokenEstimator();

    private MemoryConfig small() {
        return new MemoryConfig(true, 4000, 200, .8, .6, 100, 100, 100, 2,
                MemoryConfig.Consolidation.defaults(), MemoryConfig.Maintenance.defaults());
    }

    private ContextAssembler assembler(MemoryConfig config) {
        return new ContextAssembler(estimator, new ContextBudget(config, 1000));
    }

    @Test void includesAllUncompressedHistoryBeyondLegacyCountAndCharacterLimitsWithoutMutation() {
        List<Message> raw = new ArrayList<>();
        for (int i = 0; i < 120; i++) raw.add(Message.user("message " + i));
        raw.add(Message.assistant("x".repeat(9000)));
        var original = raw.stream().map(Message::toJson).toList();
        var input = assembler(MemoryConfig.defaults()).assemble("system", "[]", "global rule", "project rule",
                CompressionState.empty(), raw);
        assertEquals(124, input.messages().size());
        assertEquals("message 0", input.messages().get(3).getContent());
        assertEquals("x".repeat(9000), input.messages().get(123).getContent());
        assertEquals(original, raw.stream().map(Message::toJson).toList());
        var report = input.budget().orElseThrow();
        assertEquals(TokenEstimator.REQUEST_OVERHEAD + estimator.messages(input.messages()) + estimator.tools("[]"),
                report.estimatedInputTokens());
        assertEquals(121, report.recentBlocks());
        assertEquals(1000, report.outputReservedTokens());
        assertTrue(report.fits());
    }

    @Test void rendersLayersInOrderAndUsesSummaryOnlyForTheCoveredPrefix() {
        var raw = List.of(Message.user("old constraint"), Message.assistant("old answer"), Message.user("new request"));
        var compression = new CompressionState(raw.get(1).getId(), "history", "Preserve public API", 1);
        var input = assembler(MemoryConfig.defaults()).assemble("system", "schema", "global", "project", compression, raw);
        assertEquals(5, input.messages().size());
        assertEquals("system", input.messages().get(0).getRole());
        for (int i = 1; i <= 3; i++) assertEquals("user", input.messages().get(i).getRole());
        assertTrue(input.messages().get(1).getContent().contains("Global long-term memory"));
        assertTrue(input.messages().get(2).getContent().contains("Project long-term memory"));
        assertTrue(input.messages().get(3).getContent().contains("Preserve public API"));
        assertEquals(raw.get(2).getId(), input.messages().get(4).getId());
        assertFalse(input.messages().stream().anyMatch(message -> "old answer".equals(message.getContent())));
        var report = input.budget().orElseThrow();
        assertEquals(TokenEstimator.REQUEST_OVERHEAD + estimator.messages(input.messages()) + estimator.tools("schema"),
                report.estimatedInputTokens());
        assertTrue(report.summaryTokens() > 0);
    }

    @Test void triggerIsAdvisoryAndHardLimitBlocksWithoutDroppingMessages() {
        var assembler = assembler(small());
        var advisory = assembler.assemble("", "[]", "", "", CompressionState.empty(), List.of(Message.user("中".repeat(2200))));
        assertTrue(advisory.budget().orElseThrow().compactionRecommended());
        assertTrue(advisory.budget().orElseThrow().fits());
        var raw = List.of(Message.user("中".repeat(3000)));
        var blocked = assertThrows(ContextBlockedException.class,
                () -> assembler.assemble("", "[]", "", "", CompressionState.empty(), raw));
        assertEquals(ContextBlockedException.Reason.BUDGET_EXCEEDED, blocked.reason());
        assertTrue(blocked.report().orElseThrow().exceeded().contains(ContextBudget.Limit.ATOMIC_BLOCK));
        assertEquals(3000, raw.get(0).getContent().length());
    }

    @Test void checksEachLongTermLayerAndSummaryInsteadOfSilentlyClipping() {
        var assembler = assembler(small());
        var raw = List.of(Message.user("old"), Message.user("current"));
        String large = "中".repeat(150);
        var compression = new CompressionState(raw.get(0).getId(), "history", large, 1);
        var blocked = assertThrows(ContextBlockedException.class,
                () -> assembler.assemble("", "[]", large, large, compression, raw));
        assertEquals(List.of(ContextBudget.Limit.GLOBAL_MEMORY, ContextBudget.Limit.PROJECT_MEMORY,
                ContextBudget.Limit.CONTINUATION_SUMMARY), blocked.report().orElseThrow().exceeded());
    }

    @Test void toolsAndSystemPromptCanExhaustBudgetWithoutAnyHistory() {
        var blocked = assertThrows(ContextBlockedException.class, () -> assembler(small()).assemble("中".repeat(1500),
                "中".repeat(1500), "", "", CompressionState.empty(), List.of()));
        assertEquals(List.of(ContextBudget.Limit.INPUT), blocked.report().orElseThrow().exceeded());
    }

    @Test void reservesOutputAndSafetyAndAcceptsExactInputBoundary() {
        var budget = new ContextBudget(small(), 1000);
        var exact = budget.inspect(0, 0, 0, 0, 0, 2797, 0, 1);
        assertEquals(2800, exact.estimatedInputTokens());
        assertTrue(exact.fits());
        assertEquals(4000, exact.estimatedInputTokens() + exact.outputReservedTokens() + exact.safetyMarginTokens());
        assertFalse(budget.inspect(0, 0, 0, 0, 0, 2798, 0, 1).fits());
    }

    @Test void completeToolBlocksRemainIntactAndIncompleteRequestsAreBlocked() {
        var call = new ToolCall("read", "read_file", "{}");
        var assistant = Message.assistantToolCalls(null, List.of(call));
        var tool = Message.tool("read", "read_file", "result");
        var input = assembler(small()).assemble("", "[]", "", "", CompressionState.empty(), List.of(assistant, tool));
        assertEquals(3, input.messages().size());
        assertEquals(1, input.budget().orElseThrow().recentBlocks());
        var blocked = assertThrows(ContextBlockedException.class, () -> assembler(small()).assemble("", "[]", "", "",
                CompressionState.empty(), List.of(assistant)));
        assertEquals(ContextBlockedException.Reason.INVALID_HISTORY, blocked.reason());
    }

    @Test void rejectionCarriesBudgetAndDoesNotChangeContentRevision() {
        var context = new Conversation();
        context.addUserMessage("中".repeat(3000));
        ContextPreparation preparation = (request, token) -> assembler(small()).assemble("", "[]", "", "",
                CompressionState.empty(), request.messages());
        var request = new ContextPreparation.Request("", "[]", context.getMessages(), context.getContentRevision());
        var blocked = assertThrows(ContextBlockedException.class,
                () -> preparation.prepare(request, com.demo.execution.CancellationToken.none()));
        assertTrue(blocked.report().isPresent());
        assertEquals(1, context.getContentRevision());
        assertEquals(3000, context.getMessages().get(0).getContent().length());
    }
}
