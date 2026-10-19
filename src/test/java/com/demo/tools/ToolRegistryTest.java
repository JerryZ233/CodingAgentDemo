package com.demo.tools;

import com.demo.config.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test void emptyRegistryDoesNotInstantiateToolsOrResolveWorkspace() {
        var base = Config.defaults();
        var config = new Config(base.getApiUrl(), base.getModel(), "", base.getMaxTokens(), base.getTemperature(),
                base.getMaxIterations(), base.getWorkspaceDir(), Set.of());
        var registry = ToolRegistry.fromConfig(config, java.nio.file.Path.of("does-not-exist-for-empty-registry"));
        assertTrue(registry.getTools().isEmpty()); assertTrue(registry.getToolSpecs().isEmpty());
    }
    @Test void capabilitiesAreImmutableAndMatchExecution() {
        var registry = ToolRegistry.fromConfig(Config.defaults());
        var read = registry.getTools().get("read_file").getSpec();
        assertEquals(Set.of(ToolSpec.Capability.FILE_READ), read.getCapabilities());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> read.getCapabilities().clear());
    }

    @Test
    void explicitWorkspaceOverridesConfigDirectoryForActualToolExecution(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)
            throws java.io.IOException {
        ToolRegistry registry = ToolRegistry.fromConfig(Config.defaults(), root);
        var write = registry.getTools().get("write_file").execute("{\"path\":\"a.txt\",\"content\":\"in session\"}");
        assertTrue(write.isSuccess(), write.getOutput());
        assertEquals("in session", java.nio.file.Files.readString(root.resolve("a.txt")));
        assertEquals("in session", com.google.gson.JsonParser.parseString(registry.getTools().get("read_file").execute("{\"path\":\"a.txt\"}").getOutput()).getAsJsonObject().get("content").getAsString());
        assertTrue(registry.getTools().get("list_files").execute("{\"path\":\".\"}").getOutput().contains("a.txt"));
    }

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

        assertEquals(4, registry.getToolSpecs().size());
        assertEquals(Set.of(ToolSpec.Capability.FILE_READ, ToolSpec.Capability.DIRECTORY_LIST),
                registry.getTools().get("search_text").getCapabilities());
        assertTrue(registry.getToolSpecs().stream()
                .anyMatch(spec -> spec.getName().equals("read_file")
                        && spec.getDescription().contains("Reads complete")));
        assertTrue(registry.getToolSpecs().stream()
                .anyMatch(spec -> spec.getName().equals("write_file")
                        && spec.getRequiredParameters().equals(java.util.List.of("path"))));
    }
}
