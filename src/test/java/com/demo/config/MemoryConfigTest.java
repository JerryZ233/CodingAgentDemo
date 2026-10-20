package com.demo.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MemoryConfigTest {
    @TempDir Path temp;

    @Test void defaultsKeepIncompleteHarnessOffAndReserveOutput() {
        var config = ConfigLoader.from(Map.of(), Map.of());
        assertFalse(config.getMemory().enabled());
        assertEquals(24, config.getMemory().maintenance().minIntervalHours());
        assertEquals(60, config.getMemory().maintenance().scanIntervalSeconds());
        assertEquals(28_672, config.getMemory().inputBudgetTokens(2_048));
        assertTrue(config.withMemory(config.getMemory().withEnabled(true)).getMemory().enabled());
        assertFalse(config.getMemory().enabled());
    }

    @Test void environmentOverridesNestedYamlAndBlankEnvironmentIsIgnored() {
        var yaml = Map.<String, Object>of("memory", Map.of("enabled", true, "summary_budget_tokens", 1_100,
                "maintenance", Map.of("enabled", false, "min_interval_hours", 48),
                "consolidation", Map.of("batch_size", 8)));
        var config = ConfigLoader.from(yaml, Map.of("MEMORY_ENABLED", "false",
                "MEMORY_MAINTENANCE_MIN_INTERVAL_HOURS", "12", "MEMORY_SUMMARY_BUDGET_TOKENS", " "));
        assertFalse(config.getMemory().enabled());
        assertEquals(1_100, config.getMemory().summaryBudgetTokens());
        assertEquals(12, config.getMemory().maintenance().minIntervalHours());
        assertFalse(config.getMemory().maintenance().enabled());
        assertEquals(8, config.getMemory().consolidation().batchSize());
    }

    @Test void localYamlMergesNestedSettingsAndEnvironmentStillWins() throws Exception {
        Path file = temp.resolve("config.yaml");
        Files.writeString(file, "memory:\n  project_budget_tokens: 2000\n  maintenance:\n    min_interval_hours: 48\n    scan_interval_seconds: 120\n");
        Files.writeString(temp.resolve("config.local.yaml"), "memory:\n  maintenance:\n    min_interval_hours: 12\n");
        var memory = ConfigLoader.load(file, Map.of("MEMORY_MAINTENANCE_SCAN_INTERVAL_SECONDS", "30")).getMemory();
        assertEquals(2_000, memory.projectBudgetTokens());
        assertEquals(12, memory.maintenance().minIntervalHours());
        assertEquals(30, memory.maintenance().scanIntervalSeconds());
    }

    @Test void invalidSettingsFailEarlyWithNoModelOrFilesystemWork() {
        for (var env : List.of(Map.of("MEMORY_ENABLED", "yes"),
                Map.of("MEMORY_COMPACT_TRIGGER_RATIO", "NaN"),
                Map.of("MEMORY_COMPACT_TARGET_RATIO", "Infinity"),
                Map.of("MEMORY_COMPACT_TARGET_RATIO", "0.9"),
                Map.of("MEMORY_SAFETY_MARGIN_TOKENS", "32768"),
                Map.of("MEMORY_RETAIN_RECENT_BLOCKS", "0"),
                Map.of("MEMORY_MAINTENANCE_MIN_INTERVAL_HOURS", "-1"),
                Map.of("MEMORY_CONSOLIDATION_BATCH_SIZE", "0"),
                Map.of("MEMORY_ENABLED", "true", "LLM_MAX_TOKENS", "32000"))) {
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.from(Map.of(), env), env.toString());
        }
        for (Object invalid : List.of("wrong", Map.of("unknown", 1), Map.of("maintenance", "wrong"),
                Map.of("maintenance", Map.of("parallelism", 8)))) {
            assertThrows(IllegalArgumentException.class,
                    () -> ConfigLoader.from(Map.of("memory", invalid), Map.of()));
        }
    }
}
