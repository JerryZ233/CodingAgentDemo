package com.demo.tools;

import com.demo.model.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Tool for running shell commands.
 * 
 * Input format:
 * - {"command": "ls -la"} - run shell command
 * - {"command": "python script.py", "shell": "bash"} - specify shell (optional)
 * 
 * Supported shells: bash, sh, cmd, powershell, python, node
 * 
 * Security features:
 * - Dangerous command blocking
 * - Dangerous path pattern blocking
 */
public class ShellRunTool implements Tool {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_OUTPUT_LIMIT = 64 * 1024;

    private static final Set<String> BLOCKED_COMMANDS = Set.of(
        "rm -rf", "del /f /s", "format", "mkfs", "dd if=",
        "shutdown", "reboot", "halt", "init 0", "kill -9",
        "curl | sh", "wget | sh", "eval", "exec "
    );

    private final Duration timeout;
    private final int outputLimit;
    private final Path workspaceRoot;

    public ShellRunTool() {
        this(SecurityUtil.getWorkspaceRoot(), DEFAULT_TIMEOUT, DEFAULT_OUTPUT_LIMIT);
    }

    ShellRunTool(Duration timeout, int outputLimit) {
        this(SecurityUtil.getWorkspaceRoot(), timeout, outputLimit);
    }

    public ShellRunTool(Path workspaceRoot) {
        this(workspaceRoot, DEFAULT_TIMEOUT, DEFAULT_OUTPUT_LIMIT);
    }

    ShellRunTool(Path workspaceRoot, Duration timeout, int outputLimit) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.timeout = timeout;
        this.outputLimit = outputLimit;
    }

    @Override
    public String getName() {
        return "run_shell";
    }

    @Override
    public String getDescription() {
        return "Runs a shell command. Input: {\"command\": \"ls -la\", \"shell\": \"bash\"}";
    }

    @Override
    public JsonObject getParametersSchema() {
        return JsonParser.parseString("""
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "Command to run inside the workspace"
                },
                "shell": {
                  "type": "string",
                  "description": "Optional shell: bash, sh, cmd, powershell, python, or node"
                }
              },
              "required": ["command"]
            }
            """).getAsJsonObject();
    }

    @Override
    public ToolResult execute(String args) {
        String command = JsonUtil.getString(args, "command");
        String shell = JsonUtil.getString(args, "shell");

        if (command == null || command.trim().isEmpty()) {
            return ToolResult.error(getName(), "Missing 'command' in arguments");
        }

        // Security: Check for dangerous commands
        if (SecurityUtil.isDangerousCommand(command)) {
            return ToolResult.error(getName(), "Security: Blocked dangerous command detected");
        }

        // Security: Check for dangerous path patterns in command
        if (SecurityUtil.isDangerousPath(command)) {
            return ToolResult.error(getName(), "Security: Blocked dangerous pattern in command");
        }

        // Build command based on shell
        List<String> cmdList = buildCommand(command, shell);

        ProcessBuilder pb = new ProcessBuilder(cmdList);
        pb.redirectErrorStream(true);
        pb.directory(workspaceRoot.toFile());

        StringBuilder output = new StringBuilder();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Process process = pb.start();

            Future<?> outputReader = executor.submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        appendLimited(output, line + System.lineSeparator());
                    }
                } catch (IOException ignored) {
                    appendLimited(output, "Error reading command output" + System.lineSeparator());
                }
            });

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                outputReader.cancel(true);
                return ToolResult.error(getName(), "Command timed out after " + timeout.toSeconds() + " seconds");
            }

            outputReader.get(1, TimeUnit.SECONDS);
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return ToolResult.success(getName(), output.toString());
            } else {
                String errorMessage = "Command exited with code " + exitCode;
                if (output.length() > 0) {
                    errorMessage += ". Output: " + output.toString();
                }
                return ToolResult.error(getName(), errorMessage);
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return ToolResult.error(getName(), "Execution failed: " + e.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    private void appendLimited(StringBuilder output, String text) {
        synchronized (output) {
            int remaining = outputLimit - output.length();
            if (remaining <= 0) {
                return;
            }
            if (text.length() <= remaining) {
                output.append(text);
            } else {
                output.append(text, 0, remaining);
                output.append(System.lineSeparator()).append("[output truncated]");
            }
        }
    }

    private List<String> buildCommand(String command, String shellType) {
        List<String> cmd = new ArrayList<>();

        if (shellType != null) {
            switch (shellType.toLowerCase()) {
                case "bash":
                case "sh":
                    cmd.add("bash");
                    cmd.add("-c");
                    cmd.add(command);
                    break;
                case "cmd":
                case "windows":
                case "cmd.exe":
                    cmd.add("cmd");
                    cmd.add("/c");
                    cmd.add(command);
                    break;
                case "powershell":
                case "pwsh":
                    cmd.add("powershell");
                    cmd.add("-Command");
                    cmd.add(command);
                    break;
                case "python":
                    cmd.add("python");
                    cmd.add("-c");
                    cmd.add(command);
                    break;
                case "node":
                    cmd.add("node");
                    cmd.add("-e");
                    cmd.add(command);
                    break;
                default:
                    // Default shell
                    cmd.add("sh");
                    cmd.add("-c");
                    cmd.add(command);
            }
        } else {
            // Auto-detect: use platform default
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("windows")) {
                cmd.add("cmd");
                cmd.add("/c");
                cmd.add(command);
            } else {
                cmd.add("sh");
                cmd.add("-c");
                cmd.add(command);
            }
        }

        return cmd;
    }
}
