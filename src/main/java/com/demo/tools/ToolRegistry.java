package com.demo.tools;

import com.demo.config.Config;
import com.demo.tools.builtin.*;
import java.util.Set;
import java.util.function.Supplier;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the configured tool set and exposes both executable tools and their specs.
 */
public final class ToolRegistry {
    public static final Set<String> KNOWN_TOOLS = Set.of("read_file", "write_file", "list_files", "run_shell", "search_text");

    private final Map<String, Tool> tools;
    private final List<ToolSpec> toolSpecs;

    private ToolRegistry(Map<String, Tool> tools) {
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(tools));
        this.toolSpecs = ToolDescriptions.fromTools(this.tools.values());
    }

    public static ToolRegistry fromConfig(Config config) {
        return fromConfig(config, config.getWorkspacePath());
    }

    public static ToolRegistry fromConfig(Config config, Path workspaceRoot) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Map<String, Tool> registeredTools = new LinkedHashMap<>();

        if (!KNOWN_TOOLS.containsAll(config.getEnabledTools())) throw new IllegalArgumentException("Unknown enabled tool");
        registerIfEnabled(registeredTools, "read_file", () -> new FileReadTool(workspaceRoot), config);
        registerIfEnabled(registeredTools, "write_file", () -> new FileWriteTool(workspaceRoot), config);
        registerIfEnabled(registeredTools, "list_files", () -> new FileListTool(workspaceRoot), config);
        registerIfEnabled(registeredTools, "search_text", () -> new SearchTextTool(workspaceRoot), config);
        registerIfEnabled(registeredTools, "run_shell", () -> new ShellRunTool(workspaceRoot, config.getShellPolicy()), config);

        return new ToolRegistry(registeredTools);
    }

    public Map<String, Tool> getTools() {
        return tools;
    }

    /** Memory tools are host services, enabled by memory.enabled rather than workspace tool names. */
    public ToolRegistry withHistory(com.demo.memory.HistorySearchService history) {
        Map<String, Tool> combined = new LinkedHashMap<>(tools);
        if (combined.containsKey("memory_search") || combined.containsKey("memory_read")) {
            throw new IllegalStateException("History tools already installed");
        }
        combined.put("memory_search", new MemorySearchTool(history));
        combined.put("memory_read", new MemoryReadTool(history));
        return new ToolRegistry(combined);
    }

    public List<ToolSpec> getToolSpecs() {
        return toolSpecs;
    }

    private static void registerIfEnabled(Map<String, Tool> tools, String name, Supplier<Tool> factory, Config config) {
        if (config.getEnabledTools().contains(name)) {
            tools.put(name, factory.get());
        }
    }
}
