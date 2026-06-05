package com.demo.tools;

import com.demo.model.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellRunToolTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Shell commands run from workspace root")
    void commandRunsFromWorkspaceRoot() {
        ShellRunTool tool = new ShellRunTool(Duration.ofSeconds(5), 4096);
        String command = isWindows() ? "cd" : "pwd";

        ToolResult result = tool.execute("{\"command\":\"" + command + "\"}");

        assertTrue(result.isSuccess(), result.getOutput());
        String expected = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString();
        assertTrue(result.getOutput().trim().contains(expected), result.getOutput());
    }

    @Test
    @DisplayName("Shell commands honor injected workspace root")
    void commandRunsFromInjectedWorkspaceRoot() {
        ShellRunTool tool = new ShellRunTool(tempDir, Duration.ofSeconds(5), 4096);
        String command = isWindows() ? "cd" : "pwd";

        ToolResult result = tool.execute("{\"command\":\"" + command + "\"}");

        assertTrue(result.isSuccess(), result.getOutput());
        String expected = tempDir.toAbsolutePath().normalize().toString();
        assertTrue(result.getOutput().trim().contains(expected), result.getOutput());
    }

    @Test
    @DisplayName("Shell commands time out")
    void commandTimesOut() {
        ShellRunTool tool = new ShellRunTool(Duration.ofMillis(100), 4096);
        String command = isWindows() ? "ping -n 5 127.0.0.1 > nul" : "sleep 5";

        ToolResult result = tool.execute("{\"command\":\"" + command + "\"}");

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("timed out"), result.getOutput());
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }
}
