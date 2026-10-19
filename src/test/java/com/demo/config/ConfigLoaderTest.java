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

    @Test void retryOptionsSupportDefaultsOverridesAndValidation() {
        var defaults = ConfigLoader.from(Map.of(), Map.of());
        assertEquals("auto", defaults.getProvider());
        assertEquals(5, defaults.getRetryPolicy().maxRetries());
        assertEquals(2000, defaults.getRetryPolicy().initialDelayMillis());
        assertEquals(30000, defaults.getRetryPolicy().maxDelayMillis());
        assertTrue(!defaults.isErrorDetails());
        var yaml = Map.<String, Object>of("llm", Map.of("provider", "zhipu", "max_retries", 3,
                "retry_initial_delay_ms", 10, "retry_max_delay_ms", 100, "error_details", true));
        var configured = ConfigLoader.from(yaml, Map.of("LLM_PROVIDER", "dashscope", "LLM_MAX_RETRIES", "0",
                "LLM_RETRY_INITIAL_DELAY_MS", "20", "LLM_RETRY_MAX_DELAY_MS", "200", "LLM_ERROR_DETAILS", "false"));
        assertEquals("dashscope", configured.getProvider());
        assertEquals(0, configured.getRetryPolicy().maxRetries());
        assertEquals(20, configured.getRetryPolicy().initialDelayMillis());
        assertEquals(200, configured.getRetryPolicy().maxDelayMillis());
        assertTrue(!configured.isErrorDetails());
        assertTrue(ConfigLoader.from(yaml, Map.of()).isErrorDetails());
        for (var invalid : java.util.List.of(Map.of("LLM_PROVIDER", "anthropic"), Map.of("LLM_MAX_RETRIES", "-1"),
                Map.of("LLM_RETRY_INITIAL_DELAY_MS", "0"), Map.of("LLM_RETRY_MAX_DELAY_MS", "1"),
                Map.of("LLM_ERROR_DETAILS", "yes")))
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.from(Map.of(), invalid));
    }

    @Test void localRetryOptionsMergeWithoutLosingBaseConfiguration() throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "llm:\n  provider: zhipu\n  max_retries: 2\n  model: base\n");
        Files.writeString(tempDir.resolve("config.local.yaml"), "llm:\n  max_retries: 4\n  error_details: true\n");
        Config config = ConfigLoader.load(configFile, Map.of("LLM_MAX_RETRIES", "0"));
        assertEquals("zhipu", config.getProvider());
        assertEquals("base", config.getModel());
        assertEquals(0, config.getRetryPolicy().maxRetries());
        assertTrue(config.isErrorDetails());
    }

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
    void localSecretsOverrideYamlAndEnvironmentStillWins() throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "llm:\n  model: base-model\n  api_key: base-key\n  max_tokens: 123\n");
        Files.writeString(tempDir.resolve("config.local.yaml"), "llm:\n  api_key: local-test-key\n");

        Config local = ConfigLoader.load(configFile, Map.of());
        assertEquals("local-test-key", local.getApiKey());
        assertEquals("base-model", local.getModel());
        assertEquals(123, local.getMaxTokens());
        assertEquals("env-test-key", ConfigLoader.load(configFile,
            Map.of("LLM_API_KEY", "env-test-key")).getApiKey());
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
