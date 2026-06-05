package com.demo.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Loads explicit config file without parent-directory search")
    void loadsExplicitConfigFile() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            llm:
              api_url: "http://local-llm"
              model: "demo-model"
              api_key: "yaml-key"
              max_tokens: 123
              temperature: 0.25
            agent:
              max_iterations: 3
              workspace_dir: "%s"
            tools:
              enabled:
                - run_shell
                - read_file
            """.formatted(workspace.toString().replace("\\", "\\\\")));

        Config config = Config.load(configFile);

        assertEquals("http://local-llm", config.getApiUrl());
        assertEquals("demo-model", config.getModel());
        assertEquals("yaml-key", config.getApiKey());
        assertEquals(123, config.getMaxTokens());
        assertEquals(0.25, config.getTemperature());
        assertEquals(3, config.getMaxIterations());
        assertEquals(workspace.toAbsolutePath().normalize(), config.getWorkspacePath());
        assertEquals(Set.of("run_shell", "read_file"), config.getEnabledTools());
    }

    @Test
    @DisplayName("Environment variables override YAML values")
    void environmentOverridesYamlValues() {
        Config config = ConfigLoader.from(
            Map.of("llm", Map.of("model", "yaml-model", "max_tokens", 100)),
            Map.of("LLM_MODEL", "env-model", "LLM_MAX_TOKENS", "321")
        );

        assertEquals("env-model", config.getModel());
        assertEquals(321, config.getMaxTokens());
    }

    @Test
    @DisplayName("Invalid numeric values include the config key")
    void invalidNumericValueIncludesKey() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            ConfigLoader.from(
                Map.of("agent", Map.of("max_iterations", "many")),
                Map.of()
            )
        );

        assertTrue(error.getMessage().contains("agent.max_iterations"), error.getMessage());
        assertTrue(error.getMessage().contains("many"), error.getMessage());
    }

    @Test
    @DisplayName("Blank enabled tool entries fall back to defaults only when none remain")
    void enabledToolsIgnoreBlankEntries() {
        Config config = ConfigLoader.from(
            Map.of("tools", Map.of("enabled", java.util.List.of("read_file", " ", "run_shell"))),
            Map.of()
        );

        assertEquals(Set.of("read_file", "run_shell"), config.getEnabledTools());
    }
}
