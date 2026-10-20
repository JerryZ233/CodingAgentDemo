package com.demo.memory;

import com.demo.config.MemoryConfig;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompactionPlannerTest {
    private final MemoryConfig config = MemoryConfig.defaults();
    private final CompactionPlanner planner = new CompactionPlanner(config, new TokenEstimator());
    private ContextBudget.Report report() { return new ContextBudget(config, 2048).inspect(100, 0, 0, 0, 0, 50000, 30000, 10); }

    @Test void keepsLatestUserAndRecentBlocksAndNeverSplitsAnOversizedToolBlock() {
        var raw = List.of(Message.user("old constraint"),
                Message.assistantToolCalls(null, List.of(new ToolCall("read", "read_file", "{}"))),
                Message.tool("read", "read_file", "中".repeat(40000)),
                Message.assistant("still pending"), Message.user("current user"), Message.assistant("current tool work"));
        var selected = planner.select(raw, CompressionState.empty(), report());
        assertEquals(raw.subList(0, 3).stream().map(Message::getId).toList(), selected.stream().map(Message::getId).toList());
        assertTrue(new ConversationBlockBuilder().build(selected).stream().allMatch(ConversationBlockBuilder.Block::complete));
    }

    @Test void currentUnansweredUserCannotBeCoveredEvenWhenFollowedByManyToolBlocks() {
        var raw = List.of(Message.user("current user"), Message.assistant("1"), Message.assistant("2"), Message.assistant("3"));
        assertTrue(planner.select(raw, CompressionState.empty(), report()).isEmpty());
    }
}
