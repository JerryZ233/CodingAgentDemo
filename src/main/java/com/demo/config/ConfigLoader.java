package com.demo.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
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
        Path basePath = configPath == null ? DEFAULT_CONFIG_PATH : configPath.toAbsolutePath().normalize();
        Path localPath = basePath.resolveSibling("config.local.yaml");
        if (!basePath.equals(localPath) && Files.isRegularFile(localPath)) {
            yamlConfig = merge(yamlConfig, loadYamlConfig(localPath));
        }
        return from(yamlConfig, environment);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overrides) {
        Map<String, Object> result = new HashMap<>(base);
        overrides.forEach((key, value) -> {
            Object previous = result.get(key);
            if (previous instanceof Map && value instanceof Map) {
                result.put(key, merge((Map<String, Object>) previous, (Map<String, Object>) value));
            } else {
                result.put(key, value);
            }
        });
        return result;
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
            loadEnabledTools(config),
            getEnvOrYaml(env, "LLM_PROVIDER", "llm.provider", "auto", config),
            new com.demo.llm.RetryPolicy(
                parseInt("llm.max_retries", getEnvOrYaml(env, "LLM_MAX_RETRIES", "llm.max_retries", "10", config)),
                parseInt("llm.retry_initial_delay_ms", getEnvOrYaml(env, "LLM_RETRY_INITIAL_DELAY_MS", "llm.retry_initial_delay_ms", "2000", config)),
                parseInt("llm.retry_max_delay_ms", getEnvOrYaml(env, "LLM_RETRY_MAX_DELAY_MS", "llm.retry_max_delay_ms", "30000", config))),
            parseBoolean("llm.error_details", getEnvOrYaml(env, "LLM_ERROR_DETAILS", "llm.error_details", "false", config)),
            loadShellPolicy(config)
        );
    }

    private static com.demo.tools.process.ShellPolicyConfig loadShellPolicy(Map<String,Object> config) {
        var defaults=com.demo.tools.process.ShellPolicyConfig.defaults();
        Object tools=config.get("tools");
        if (!(tools instanceof Map<?,?> tm) || !tm.containsKey("shell")) return defaults;
        Map<?,?> shell=objectMap(tm.get("shell"));
        for(Object key:shell.keySet()) if(!Set.of("aliases","search_directories","profiles","shell_paths").contains(key))throw new IllegalArgumentException("Unknown tools.shell field");
        Map<String,Path> aliases=pathMap(shell.get("aliases"),Map.of());
        Map<String,Path> shells=pathMap(shell.get("shell_paths"),defaults.shells());
        List<Path> dirs=defaults.searchDirectories();
        if(shell.containsKey("search_directories")) {
            if(!(shell.get("search_directories") instanceof List<?> list))throw new IllegalArgumentException("search_directories must be a list");
            dirs=list.stream().map(v->Path.of(text(v))).toList();
        }
        Map<String,com.demo.tools.process.ShellPolicyConfig.EnvironmentProfile> profiles=new HashMap<>(defaults.profiles());
        if(shell.containsKey("profiles")) for(var entry:objectMap(shell.get("profiles")).entrySet()) {
            String name=text(entry.getKey());Map<?,?> value=objectMap(entry.getValue());
            for(Object key:value.keySet()) if(!Set.of("environment","credentials").contains(key))throw new IllegalArgumentException("Unknown profile field");
            Map<String,String> variables=new HashMap<>(defaults.profiles().get("default").values());
            for(var variable:objectMap(value.get("environment")).entrySet())variables.put(text(variable.getKey()),text(variable.getValue()));
            Object credentialValue=value.containsKey("credentials")?value.get("credentials"):Boolean.FALSE;
            if(!(credentialValue instanceof Boolean))throw new IllegalArgumentException("credentials must be boolean");
            boolean credentials=(Boolean)credentialValue;
            if(credentials)objectMap(value.get("environment")).values().forEach(v->com.demo.logging.LogData.registerSecret(text(v)));
            // Startup injection is never inherited, even through a development profile.
            for(String key:variables.keySet())if(Set.of("BASH_ENV","ENV","SHELLOPTS","BASHOPTS","CDPATH","PSModulePath").contains(key))throw new IllegalArgumentException("Shell startup injection variable is not supported");
            profiles.put(name,new com.demo.tools.process.ShellPolicyConfig.EnvironmentProfile(variables,credentials));
        }
        return new com.demo.tools.process.ShellPolicyConfig(aliases,dirs,profiles,shells,"shell-policy-v1");
    }
    private static Map<?,?> objectMap(Object value) {
        if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException("Expected configuration object");return map;
    }
    private static String text(Object value) {
        if(!(value instanceof String text)||text.isBlank())throw new IllegalArgumentException("Expected nonempty configuration string");return text;
    }
    private static Map<String,Path> pathMap(Object value,Map<String,Path> fallback) {
        if(value==null)return fallback;
        Map<String,Path> result=new HashMap<>(fallback);
        for(var e:objectMap(value).entrySet())result.put(text(e.getKey()),Path.of(text(e.getValue())));
        return result;
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
        if (!yamlConfig.containsKey("tools")) return Config.DEFAULT_ENABLED_TOOLS;
        if (!(tools instanceof Map)) throw new IllegalArgumentException("tools must be a mapping");

        Object enabled = ((Map<String, Object>) tools).get("enabled");
        if (!((Map<?, ?>) tools).containsKey("enabled")) return Config.DEFAULT_ENABLED_TOOLS;
        if (!(enabled instanceof List)) throw new IllegalArgumentException("tools.enabled must be a list");

        Set<String> result = new HashSet<>();
        for (Object item : (List<Object>) enabled) {
            if (!(item instanceof String)) throw new IllegalArgumentException("Tool names must be strings");
            String toolName = ((String) item).trim();
            if (!com.demo.tools.ToolRegistry.KNOWN_TOOLS.contains(toolName))
                throw new IllegalArgumentException("Unknown or empty tool name: " + toolName);
            result.add(toolName);
        }

        return Set.copyOf(result);
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": '" + value + "'", e);
        }
    }

    private static boolean parseBoolean(String key, String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("Invalid boolean for " + key);
    }

    private static double parseDouble(String key, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid decimal for " + key + ": '" + value + "'", e);
        }
    }
}
