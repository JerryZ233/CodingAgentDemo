package com.demo.tools;

import com.demo.config.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    @DisplayName("Registry only exposes tools enabled in config")
    void registryOnlyExposesEnabledTools() {
        Config config = new Config(
            Config.DEFAULT_API_URL,
            Config.DEFAULT_MODEL,
            "",
            Config.DEFAULT_MAX_TOKENS,
            Config.DEFAULT_TEMPERATURE,
            Config.DEFAULT_MAX_ITERATIONS,
            Config.DEFAULT_WORKSPACE_DIR,
            Set.of("read_file", "run_shell")
        );

        ToolRegistry registry = ToolRegistry.fromConfig(config);

        assertTrue(registry.getTools().containsKey("read_file"));
        assertTrue(registry.getTools().containsKey("run_shell"));
        assertFalse(registry.getTools().containsKey("write_file"));
        assertFalse(registry.getTools().containsKey("list_files"));
    }

    @Test
    @DisplayName("Registry exposes ToolSpec descriptions for enabled tools")
    void registryExposesToolSpecs() {
        ToolRegistry registry = ToolRegistry.fromConfig(Config.defaults());

        assertEquals(3, registry.getToolSpecs().size());
        assertTrue(registry.getToolSpecs().stream()
                .anyMatch(spec -> spec.getName().equals("read_file")
                        && spec.getDescription().contains("Reads content")));
        assertTrue(registry.getToolSpecs().stream()
                .anyMatch(spec -> spec.getName().equals("write_file")
                        && spec.getRequiredParameters().contains("content")));
    }
}
