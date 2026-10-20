package com.demo.config;

import com.demo.tools.ToolDescriptions;
import com.demo.tools.ToolRegistry;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import static org.junit.jupiter.api.Assertions.*;

class EvalToolProfilesTest {
    @TempDir Path workspace;

    @SuppressWarnings("unchecked")
    private Config profile(String filename) throws Exception {
        try (var reader = Files.newBufferedReader(Path.of("eval", filename))) {
            Map<String, Object> yaml = new Yaml().load(reader);
            if (System.getProperty("os.name").startsWith("Windows")) {
                // These profiles target Linux containers. Test the actual tool list
                // on Windows with native default executable directories instead.
                var tools = new HashMap<>((Map<String, Object>) yaml.get("tools"));
                tools.remove("shell");
                yaml.put("tools", tools);
            }
            return ConfigLoader.from(yaml, Map.of());
        }
    }

    @Test void fileOnlyEvalProfileDoesNotExposeShellToModelOrExecution() throws Exception {
        Config config = profile("config-files.yaml");
        ToolRegistry registry = ToolRegistry.fromConfig(config, workspace);
        assertEquals(Set.of("read_file", "write_file", "list_files", "search_text"), registry.getTools().keySet());
        String schema = ToolDescriptions.toOpenAIToolsJson(registry.getToolSpecs());
        assertFalse(schema.contains("run_shell"));
        assertTrue(schema.contains("search_text"));
        assertEquals(30, config.getMaxIterations());
    }

    @Test void fullEvalProfileAlsoExposesShellAndKeepsModelDefaults() throws Exception {
        Config full = profile("config.yaml");
        Config files = profile("config-files.yaml");
        ToolRegistry registry = ToolRegistry.fromConfig(full, workspace);
        assertEquals(Set.of("read_file", "write_file", "list_files", "search_text", "run_shell"),
                registry.getTools().keySet());
        assertTrue(ToolDescriptions.toOpenAIToolsJson(registry.getToolSpecs()).contains("run_shell"));
        assertEquals(full.getModel(), files.getModel());
        assertEquals(full.getMaxTokens(), files.getMaxTokens());
        assertEquals(full.getMaxIterations(), files.getMaxIterations());
    }
}
