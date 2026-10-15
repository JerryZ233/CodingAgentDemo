package com.demo.tools;

import com.demo.model.ToolResult;
import com.demo.execution.CancellationToken;
import com.demo.execution.OperationCancelledException;
import com.demo.execution.ResourceCleanupException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.time.Duration;

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
        return execute(args, CancellationToken.none());
    }

    @Override
    public ToolResult execute(String args, CancellationToken token) {
        token.throwIfCancellationRequested();
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

        token.throwIfCancellationRequested();
        try (ManagedProcess managed = new ManagedProcess(pb, outputLimit)) {
            ManagedProcess.Result result = managed.await(timeout, token);
            if (result.timedOut()) {
                return ToolResult.error(getName(), "Command timed out after " + timeout.toSeconds() + " seconds");
            }
            if (result.exitCode() == 0) return ToolResult.success(getName(), result.output());
            return ToolResult.error(getName(), "Command exited with code " + result.exitCode()
                    + (result.output().isEmpty() ? "" : ". Output: " + result.output()));
        } catch (OperationCancelledException | ResourceCleanupException e) {
            checkCleanupFailure(e);
            throw e;
        } catch (InterruptedException e) {
            checkCleanupFailure(e);
            Thread.currentThread().interrupt();
            return ToolResult.error(getName(), "Execution interrupted");
        } catch (Exception e) {
            checkCleanupFailure(e);
            return ToolResult.error(getName(), "Execution failed: " + e.getMessage());
        }
    }

    /** try-with-resources suppresses close errors behind cancellation; these must take precedence. */
    static void checkCleanupFailure(Throwable failure) {
        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof ResourceCleanupException resource) throw resource;
            checkCleanupFailure(suppressed);
        }
    }
}
