package com.demo.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads immutable Config instances from YAML and environment overrides.
 */
public final class ConfigLoader {

    private static final Path DEFAULT_CONFIG_PATH = Path.of(System.getProperty("user.dir"), "config.yaml");

    private ConfigLoader() {
    }

    public static Config loadDefault() {
        return load(DEFAULT_CONFIG_PATH, System.getenv());
    }

    public static Config load(Path configPath) {
        return load(configPath, System.getenv());
    }

    static Config load(Path configPath, Map<String, String> environment) {
        Map<String, Object> yamlConfig = loadYamlConfig(configPath);
        return from(yamlConfig, environment);
    }

    static Config from(Map<String, Object> yamlConfig, Map<String, String> environment) {
        Map<String, Object> config = yamlConfig == null ? Map.of() : yamlConfig;
        Map<String, String> env = environment == null ? Map.of() : environment;

        return new Config(
            getEnvOrYaml(env, "LLM_API_URL", "llm.api_url", Config.DEFAULT_API_URL, config),
            getEnvOrYaml(env, "LLM_MODEL", "llm.model", Config.DEFAULT_MODEL, config),
            getEnvOrYaml(env, "LLM_API_KEY", "llm.api_key", "", config),
            parseInt("llm.max_tokens", getEnvOrYaml(env, "LLM_MAX_TOKENS", "llm.max_tokens", String.valueOf(Config.DEFAULT_MAX_TOKENS), config)),
            parseDouble("llm.temperature", getEnvOrYaml(env, "LLM_TEMPERATURE", "llm.temperature", String.valueOf(Config.DEFAULT_TEMPERATURE), config)),
            parseInt("agent.max_iterations", getEnvOrYaml(env, "AGENT_MAX_ITERATIONS", "agent.max_iterations", String.valueOf(Config.DEFAULT_MAX_ITERATIONS), config)),
            getEnvOrYaml(env, "WORKSPACE_DIR", "agent.workspace_dir", Config.DEFAULT_WORKSPACE_DIR, config),
            loadEnabledTools(config)
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYamlConfig(Path configPath) {
        Path normalizedPath = configPath == null ? DEFAULT_CONFIG_PATH : configPath.toAbsolutePath().normalize();

        if (Files.isRegularFile(normalizedPath)) {
            try (InputStream inputStream = Files.newInputStream(normalizedPath)) {
                Object loaded = new Yaml().load(inputStream);
                return loaded instanceof Map ? (Map<String, Object>) loaded : Map.of();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read config file: " + normalizedPath, e);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Failed to parse config file: " + normalizedPath + " (" + e.getMessage() + ")", e);
            }
        }

        try (InputStream inputStream = ConfigLoader.class.getClassLoader().getResourceAsStream("config.yaml")) {
            if (inputStream != null) {
                Object loaded = new Yaml().load(inputStream);
                return loaded instanceof Map ? (Map<String, Object>) loaded : Map.of();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read classpath config.yaml", e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Failed to parse classpath config.yaml (" + e.getMessage() + ")", e);
        }

        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static String getEnvOrYaml(
            Map<String, String> environment,
            String envVar,
            String yamlKey,
            String defaultValue,
            Map<String, Object> yamlConfig) {
        String envValue = environment.get(envVar);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }

        Object value = yamlConfig;
        for (String key : yamlKey.split("\\.")) {
            if (value instanceof Map) {
                value = ((Map<String, Object>) value).get(key);
            } else {
                return defaultValue;
            }
        }

        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> loadEnabledTools(Map<String, Object> yamlConfig) {
        Object tools = yamlConfig.get("tools");
        if (!(tools instanceof Map)) {
            return Config.DEFAULT_ENABLED_TOOLS;
        }

        Object enabled = ((Map<String, Object>) tools).get("enabled");
        if (!(enabled instanceof List)) {
            return Config.DEFAULT_ENABLED_TOOLS;
        }

        Set<String> result = new HashSet<>();
        for (Object item : (List<Object>) enabled) {
            if (item == null) {
                continue;
            }
            String toolName = item.toString().trim();
            if (!toolName.isEmpty()) {
                result.add(toolName);
            }
        }

        return result.isEmpty() ? Config.DEFAULT_ENABLED_TOOLS : Set.copyOf(result);
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": '" + value + "'", e);
        }
    }

    private static double parseDouble(String key, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid decimal for " + key + ": '" + value + "'", e);
        }
    }
}
