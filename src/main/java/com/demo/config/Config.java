package com.demo.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration values for the AI coding agent.
 */
public final class Config {

    public static final String DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions";
    public static final String DEFAULT_MODEL = "gpt-4";
    public static final int DEFAULT_MAX_TOKENS = 2048;
    public static final double DEFAULT_TEMPERATURE = 0.7;
    public static final int DEFAULT_MAX_ITERATIONS = 10;
    public static final String DEFAULT_WORKSPACE_DIR = ".";
    public static final Set<String> DEFAULT_ENABLED_TOOLS = Set.of(
        "read_file",
        "write_file",
        "list_files"
    );

    private static volatile Config instance;

    private final String apiUrl;
    private final String model;
    private final String apiKey;
    private final int maxTokens;
    private final double temperature;
    private final int maxIterations;
    private final String workspaceDir;
    private final Set<String> enabledTools;

    public Config(
            String apiUrl,
            String model,
            String apiKey,
            int maxTokens,
            double temperature,
            int maxIterations,
            String workspaceDir,
            Set<String> enabledTools) {
        this.apiUrl = requireText(apiUrl, "apiUrl");
        this.model = requireText(model, "model");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.maxIterations = maxIterations;
        this.workspaceDir = requireText(workspaceDir, "workspaceDir");
        this.enabledTools = Set.copyOf(Objects.requireNonNull(enabledTools, "enabledTools"));
    }

    public static Config defaults() {
        return new Config(
            DEFAULT_API_URL,
            DEFAULT_MODEL,
            "",
            DEFAULT_MAX_TOKENS,
            DEFAULT_TEMPERATURE,
            DEFAULT_MAX_ITERATIONS,
            DEFAULT_WORKSPACE_DIR,
            DEFAULT_ENABLED_TOOLS
        );
    }

    /**
     * Compatibility entry point for existing callers.
     */
    public static Config getInstance() {
        Config current = instance;
        if (current == null) {
            synchronized (Config.class) {
                current = instance;
                if (current == null) {
                    current = ConfigLoader.loadDefault();
                    instance = current;
                }
            }
        }
        return current;
    }

    public static Config load(Path configPath) {
        return ConfigLoader.load(configPath);
    }

    public static void setInstanceForTests(Config config) {
        instance = config;
    }

    public static void resetForTests() {
        instance = null;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String getModel() {
        return model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public String getWorkspaceDir() {
        return workspaceDir;
    }

    public Path getWorkspacePath() {
        return Path.of(workspaceDir).toAbsolutePath().normalize();
    }

    public Set<String> getEnabledTools() {
        return enabledTools;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
