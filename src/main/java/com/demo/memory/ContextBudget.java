package com.demo.memory;

import com.demo.config.MemoryConfig;
import java.util.ArrayList;
import java.util.List;

/** Full-request limits; every section includes its actual rendered message overhead. */
public final class ContextBudget {
    public enum Limit { GLOBAL_MEMORY, PROJECT_MEMORY, CONTINUATION_SUMMARY, INPUT, ATOMIC_BLOCK }

    public record Report(long systemPromptTokens, long toolSchemaTokens, long globalMemoryTokens,
                         long projectMemoryTokens, long summaryTokens, long recentMessagesTokens,
                         long largestBlockTokens, int recentBlocks, int inputBudgetTokens,
                         int outputReservedTokens, int safetyMarginTokens, int contextWindowTokens,
                         int compactTriggerTokens, int compactTargetTokens, List<Limit> exceeded) {
        public Report { exceeded = List.copyOf(exceeded); }
        public long estimatedInputTokens() {
            return TokenEstimator.REQUEST_OVERHEAD + systemPromptTokens + toolSchemaTokens
                    + globalMemoryTokens + projectMemoryTokens + summaryTokens + recentMessagesTokens;
        }
        public boolean fits() { return exceeded.isEmpty(); }
        public boolean compactionRecommended() { return estimatedInputTokens() >= compactTriggerTokens; }
    }

    private final MemoryConfig config;
    private final int outputTokens;
    private final int inputTokens;

    public ContextBudget(MemoryConfig config, int outputTokens) {
        this.config = java.util.Objects.requireNonNull(config);
        this.outputTokens = outputTokens;
        inputTokens = config.inputBudgetTokens(outputTokens);
    }

    public Report inspect(long system, long tools, long global, long project, long summary,
                          long recent, long largestBlock, int blocks) {
        List<Limit> exceeded = new ArrayList<>();
        if (global > config.globalBudgetTokens()) exceeded.add(Limit.GLOBAL_MEMORY);
        if (project > config.projectBudgetTokens()) exceeded.add(Limit.PROJECT_MEMORY);
        if (summary > config.summaryBudgetTokens()) exceeded.add(Limit.CONTINUATION_SUMMARY);
        long fixed = TokenEstimator.REQUEST_OVERHEAD + system + tools + global + project + summary;
        if (fixed + recent > inputTokens) exceeded.add(Limit.INPUT);
        if (largestBlock > 0 && fixed + largestBlock > inputTokens) exceeded.add(Limit.ATOMIC_BLOCK);
        return new Report(system, tools, global, project, summary, recent, largestBlock, blocks,
                inputTokens, outputTokens, config.safetyMarginTokens(), config.contextWindowTokens(),
                (int) Math.floor(inputTokens * config.compactTriggerRatio()),
                (int) Math.floor(inputTokens * config.compactTargetRatio()), exceeded);
    }
}
