package com.demo.config;

import java.util.Objects;

/** Immutable memory settings. Explicit memory.enabled=false preserves the legacy context window. */
public record MemoryConfig(boolean enabled, int contextWindowTokens, int safetyMarginTokens,
                           double compactTriggerRatio, double compactTargetRatio,
                           int globalBudgetTokens, int projectBudgetTokens,
                           int summaryBudgetTokens, int retainRecentBlocks,
                           Consolidation consolidation, Maintenance maintenance) {
    public MemoryConfig {
        positive(contextWindowTokens, "memory.context_window_tokens");
        positive(safetyMarginTokens, "memory.safety_margin_tokens");
        positive(globalBudgetTokens, "memory.global_budget_tokens");
        positive(projectBudgetTokens, "memory.project_budget_tokens");
        positive(summaryBudgetTokens, "memory.summary_budget_tokens");
        positive(retainRecentBlocks, "memory.retain_recent_blocks");
        if (!Double.isFinite(compactTargetRatio) || !Double.isFinite(compactTriggerRatio)
                || compactTargetRatio <= 0 || compactTriggerRatio >= 1
                || compactTargetRatio >= compactTriggerRatio) {
            throw new IllegalArgumentException("memory ratios must satisfy 0 < target < trigger < 1");
        }
        if (safetyMarginTokens >= contextWindowTokens) {
            throw new IllegalArgumentException("memory.safety_margin_tokens must be smaller than context_window_tokens");
        }
        Objects.requireNonNull(consolidation, "memory.consolidation");
        Objects.requireNonNull(maintenance, "memory.maintenance");
    }

    public static MemoryConfig defaults() {
        return new MemoryConfig(true, 32_768, 2_048, 0.80, 0.60,
                1_024, 3_072, 1_200, 2, Consolidation.defaults(), Maintenance.defaults());
    }

    public MemoryConfig withEnabled(boolean value) {
        return new MemoryConfig(value, contextWindowTokens, safetyMarginTokens, compactTriggerRatio,
                compactTargetRatio, globalBudgetTokens, projectBudgetTokens, summaryBudgetTokens,
                retainRecentBlocks, consolidation, maintenance);
    }

    /** Also validates the output reservation; input includes prompt and tool schemas. */
    public int inputBudgetTokens(int outputTokens) {
        positive(outputTokens, "llm.max_tokens");
        long available = (long) contextWindowTokens - safetyMarginTokens - outputTokens;
        long fixed = (long) globalBudgetTokens + projectBudgetTokens + summaryBudgetTokens;
        if (available <= 0 || fixed >= Math.floor(available * compactTargetRatio)) {
            throw new IllegalArgumentException("memory input budget must leave room for prompts, tools and recent messages");
        }
        return Math.toIntExact(available);
    }

    public record Consolidation(boolean enabled, int newHistoryThreshold, int batchSize) {
        public Consolidation {
            positive(newHistoryThreshold, "memory.consolidation.new_history_threshold");
            positive(batchSize, "memory.consolidation.batch_size");
        }
        public static Consolidation defaults() { return new Consolidation(true, 5, 10); }
    }

    public record Maintenance(boolean enabled, int minIntervalHours, int scanIntervalSeconds) {
        public Maintenance {
            positive(minIntervalHours, "memory.maintenance.min_interval_hours");
            positive(scanIntervalSeconds, "memory.maintenance.scan_interval_seconds");
        }
        public static Maintenance defaults() { return new Maintenance(true, 24, 60); }
    }

    private static void positive(int value, String key) {
        if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
    }
}
