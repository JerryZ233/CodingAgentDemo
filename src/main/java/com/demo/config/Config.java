package com.demo.config;

import org.yaml.snakeyaml.Yaml;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration manager for the AI coding agent.
 * 
 * Reads settings from config.yaml and allows environment variables to override them.
 * Environment variables take precedence over YAML configuration.
 * 
 * Supported environment variables:
 * - LLM_API_URL: Override the API endpoint
 * - LLM_MODEL: Override the model name
 * - LLM_API_KEY: Override the API key
 * - LLM_MAX_TOKENS: Override max tokens
 * - LLM_TEMPERATURE: Override temperature
 * - AGENT_MAX_ITERATIONS: Override max iterations
 * - WORKSPACE_DIR: Override workspace directory
 */
public class Config {
    
    private static Config instance;
    
    private final String apiUrl;
    private final String model;
    private final String apiKey;
    private final int maxTokens;
    private final double temperature;
    private final int maxIterations;
    private final String workspaceDir;
    private final Set<String> enabledTools;
    
    // Default values
    private static final String DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4";
    private static final int DEFAULT_MAX_TOKENS = 2048;
    private static final double DEFAULT_TEMPERATURE = 0.7;
    private static final int DEFAULT_MAX_ITERATIONS = 10;
    private static final String DEFAULT_WORKSPACE_DIR = ".";
    private static final Set<String> DEFAULT_ENABLED_TOOLS = Set.of(
        "read_file",
        "write_file",
        "list_files"
    );
    
    private Config() {
        // Load from YAML first
        Map<String, Object> yamlConfig = loadYamlConfig();
        
        // LLM settings (env vars override YAML)
        this.apiUrl = getEnvOrYaml("LLM_API_URL", "llm.api_url", DEFAULT_API_URL, yamlConfig);
        this.model = getEnvOrYaml("LLM_MODEL", "llm.model", DEFAULT_MODEL, yamlConfig);
        this.apiKey = getEnvOrYaml("LLM_API_KEY", "llm.api_key", "", yamlConfig);
        this.maxTokens = Integer.parseInt(getEnvOrYaml("LLM_MAX_TOKENS", "llm.max_tokens", String.valueOf(DEFAULT_MAX_TOKENS), yamlConfig));
        this.temperature = Double.parseDouble(getEnvOrYaml("LLM_TEMPERATURE", "llm.temperature", String.valueOf(DEFAULT_TEMPERATURE), yamlConfig));
        
        // Agent settings
        this.maxIterations = Integer.parseInt(getEnvOrYaml("AGENT_MAX_ITERATIONS", "agent.max_iterations", String.valueOf(DEFAULT_MAX_ITERATIONS), yamlConfig));
        this.workspaceDir = getEnvOrYaml("WORKSPACE_DIR", "agent.workspace_dir", DEFAULT_WORKSPACE_DIR, yamlConfig);
        this.enabledTools = loadEnabledTools(yamlConfig);
    }
    
    /**
     * Gets the singleton instance of Config.
     */
    public static Config getInstance() {
        if (instance == null) {
            instance = new Config();
        }
        return instance;
    }
    
    /**
     * Loads configuration from config.yaml.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYamlConfig() {
        String[] possiblePaths = {
            "config.yaml",
            "src/main/resources/config.yaml",
            "../config.yaml",
            "../../config.yaml"
        };
        
        for (String path : possiblePaths) {
            try {
                InputStream inputStream = new FileInputStream(path);
                Yaml yaml = new Yaml();
                return yaml.load(inputStream);
            } catch (FileNotFoundException e) {
                // Try next path
            }
        }
        
        // Try loading from classpath
        try {
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("config.yaml");
            if (inputStream != null) {
                Yaml yaml = new Yaml();
                return yaml.load(inputStream);
            }
        } catch (Exception e) {
            // Ignore
        }
        
        return Map.of();
    }
    
    /**
     * Gets value from environment variable or YAML config.
     * Environment variable takes precedence.
     */
    @SuppressWarnings("unchecked")
    private String getEnvOrYaml(String envVar, String yamlKey, String defaultValue, Map<String, Object> yamlConfig) {
        // Check environment variable first
        String envValue = System.getenv(envVar);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }
        
        // Fall back to YAML
        if (yamlConfig != null && !yamlConfig.isEmpty()) {
            String[] keys = yamlKey.split("\\.");
            Object value = yamlConfig;
            for (String key : keys) {
                if (value instanceof Map) {
                    value = ((Map<String, Object>) value).get(key);
                } else {
                    break;
                }
            }
            if (value != null && !value.toString().isEmpty()) {
                return value.toString();
            }
        }
        
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Set<String> loadEnabledTools(Map<String, Object> yamlConfig) {
        if (yamlConfig == null || yamlConfig.isEmpty()) {
            return DEFAULT_ENABLED_TOOLS;
        }

        Object tools = yamlConfig.get("tools");
        if (!(tools instanceof Map)) {
            return DEFAULT_ENABLED_TOOLS;
        }

        Object enabled = ((Map<String, Object>) tools).get("enabled");
        if (!(enabled instanceof List)) {
            return DEFAULT_ENABLED_TOOLS;
        }

        Set<String> result = new java.util.HashSet<>();
        for (Object item : (List<Object>) enabled) {
            if (item != null && !item.toString().isBlank()) {
                result.add(item.toString());
            }
        }

        return result.isEmpty() ? DEFAULT_ENABLED_TOOLS : Set.copyOf(result);
    }
    
    // Getters
    
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

    public Set<String> getEnabledTools() {
        return enabledTools;
    }
    
    /**
     * Checks if the configuration is valid (has API key).
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty();
    }
}
