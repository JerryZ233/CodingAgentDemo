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
 * - {"command": "echo hello", "shell": "cmd"} - specify shell (optional)
 * 
 * Supported shells: platform shells only (cmd/powershell on Windows, sh/bash on POSIX)
 * 
 * In-process guardrails:
 * - Explicit shell policy with command allowlist
 * - Shell metacharacter and redirection blocking
 * - Dangerous command and path pattern blocking
 *
 * This tool is not an OS sandbox or permission boundary.
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
    private final ShellPolicy shellPolicy;

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
        this(workspaceRoot, timeout, outputLimit, ShellPolicy.defaultPolicy());
    }

    ShellRunTool(Path workspaceRoot, Duration timeout, int outputLimit, ShellPolicy shellPolicy) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.timeout = timeout;
        this.outputLimit = outputLimit;
        this.shellPolicy = shellPolicy;
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
                  "description": "Optional shell: bash/sh on POSIX or cmd/powershell on Windows"
                }
              },
              "required": ["command"]
            }
            """).getAsJsonObject();
    }

    @Override
    public ToolResult execute(String args) {
        String command;
        String shell;
        try {
            ToolArguments arguments = ToolArguments.parse(args);
            command = arguments.requiredString("command");
            shell = arguments.optionalString("shell");
        } catch (IllegalArgumentException e) {
            return ToolResult.error(getName(), "Invalid arguments: " + e.getMessage());
        }

        // Security: Check for dangerous commands
        if (SecurityUtil.isDangerousCommand(command)) {
            return ToolResult.error(getName(), "Security: Blocked dangerous command detected");
        }

        // Security: Check for dangerous path patterns in command
        if (SecurityUtil.isDangerousPath(command)) {
            return ToolResult.error(getName(), "Security: Blocked dangerous pattern in command");
        }

        ShellPolicy.CommandPlan commandPlan = shellPolicy.approve(command, shell);
        if (!commandPlan.isAllowed()) {
            return ToolResult.error(getName(), commandPlan.rejectionReason());
        }

        ProcessBuilder pb = new ProcessBuilder(commandPlan.commandLine());
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error(getName(), "Execution interrupted: " + e.getMessage());
        } catch (Exception e) {
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

}
