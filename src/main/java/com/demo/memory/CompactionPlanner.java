package com.demo.memory;

import com.demo.config.MemoryConfig;
import com.demo.model.Message;
import java.util.ArrayList;
import java.util.List;

/** Selects a contiguous old prefix; protects the current user request and recent complete blocks. */
public final class CompactionPlanner {
    private final MemoryConfig config;
    private final TokenEstimator estimator;
    public CompactionPlanner(MemoryConfig config, TokenEstimator estimator) {
        this.config = config;
        this.estimator = estimator;
    }

    public List<Message> select(List<Message> raw, CompressionState compression, ContextBudget.Report report) {
        int covered = compression.coveredCount(raw);
        var blocks = new ConversationBlockBuilder().build(raw.subList(covered, raw.size()));
        int available = Math.max(0, blocks.size() - config.retainRecentBlocks());
        int latestUser = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).messages().stream().anyMatch(message -> "user".equals(message.getRole()))) latestUser = i;
        }
        if (latestUser >= 0) available = Math.min(available, latestUser);
        List<Message> selected = new ArrayList<>();
        long removed = 0;
        // Bound each transaction; an oversized first block is processed in source fragments.
        long batchBudget = report.inputBudgetTokens() / 2L;
        for (int i = 0; i < available; i++) {
            var block = blocks.get(i);
            if (!block.complete()) break;
            long size = estimator.messages(block.messages());
            if (removed >= config.summaryBudgetTokens() && removed + size > batchBudget) break;
            selected.addAll(block.messages());
            removed += size;
            long projected = report.estimatedInputTokens() - report.summaryTokens() - removed + config.summaryBudgetTokens();
            if (projected <= report.compactTargetTokens()) break;
        }
        return List.copyOf(selected);
    }
}
