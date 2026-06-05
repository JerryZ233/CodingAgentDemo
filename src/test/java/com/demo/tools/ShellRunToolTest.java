package com.demo.tools;

import com.demo.model.ToolResult;
import com.google.gson.JsonObject;
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
        String command = isWindows() ? "ping -n 5 127.0.0.1" : "sleep 5";

        ToolResult result = tool.execute("{\"command\":\"" + command + "\"}");

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("timed out"), result.getOutput());
    }

    @Test
    @DisplayName("Unknown shells are rejected instead of falling back")
    void unknownShellIsRejected() {
        ShellRunTool tool = new ShellRunTool(tempDir, Duration.ofSeconds(5), 4096);

        ToolResult result = tool.execute("{\"command\":\"echo hello\",\"shell\":\"not-a-real-shell\"}");

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("shell is not allowed"), result.getOutput());
    }

    @Test
    @DisplayName("Shell metacharacters are rejected")
    void shellMetacharactersAreRejected() {
        ShellRunTool tool = new ShellRunTool(tempDir, Duration.ofSeconds(5), 4096);

        ToolResult result = tool.execute("{\"command\":\"echo hello && echo blocked\"}");

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("metacharacters"), result.getOutput());
    }

    @Test
    @DisplayName("Path-capable shell commands reject arguments")
    void pathCapableCommandsRejectArguments() {
        ShellRunTool tool = new ShellRunTool(tempDir, Duration.ofSeconds(5), 4096);
        String command = isWindows() ? "dir C:\\" : "ls /";

        ToolResult result = tool.execute(jsonCommand(command));

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("does not accept arguments"), result.getOutput());
    }

    @Test
    @DisplayName("Simple allowlisted commands are allowed")
    void allowlistedSimpleCommandRuns() {
        ShellRunTool tool = new ShellRunTool(tempDir, Duration.ofSeconds(5), 4096);

        ToolResult result = tool.execute("{\"command\":\"echo hello\"}");

        assertTrue(result.isSuccess(), result.getOutput());
        assertTrue(result.getOutput().contains("hello"), result.getOutput());
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    private String jsonCommand(String command) {
        JsonObject object = new JsonObject();
        object.addProperty("command", command);
        return object.toString();
    }
}
