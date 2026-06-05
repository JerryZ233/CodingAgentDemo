package com.demo.tools;

import com.demo.config.Config;
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

    private final Map<String, Tool> tools;
    private final List<ToolSpec> toolSpecs;

    private ToolRegistry(Map<String, Tool> tools) {
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(tools));
        this.toolSpecs = ToolDescriptions.fromTools(this.tools.values());
    }

    public static ToolRegistry fromConfig(Config config) {
        Objects.requireNonNull(config, "config");
        Path workspaceRoot = config.getWorkspacePath();
        Map<String, Tool> registeredTools = new LinkedHashMap<>();

        registerIfEnabled(registeredTools, new FileReadTool(workspaceRoot), config);
        registerIfEnabled(registeredTools, new FileWriteTool(workspaceRoot), config);
        registerIfEnabled(registeredTools, new FileListTool(workspaceRoot), config);
        registerIfEnabled(registeredTools, new ShellRunTool(workspaceRoot), config);

        return new ToolRegistry(registeredTools);
    }

    public Map<String, Tool> getTools() {
        return tools;
    }

    public List<ToolSpec> getToolSpecs() {
        return toolSpecs;
    }

    private static void registerIfEnabled(Map<String, Tool> tools, Tool tool, Config config) {
        if (config.getEnabledTools().contains(tool.getName())) {
            tools.put(tool.getName(), tool);
        }
    }
}
