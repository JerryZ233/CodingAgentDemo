package com.demo.tools;

import com.demo.model.ToolResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    private static final Set<String> BLOCKED_COMMANDS = Set.of(
        "rm -rf", "del /f /s", "format", "mkfs", "dd if=",
        "shutdown", "reboot", "halt", "init 0", "kill -9",
        "curl | sh", "wget | sh", "eval", "exec "
    );

    @Override
    public String getName() {
        return "run_shell";
    }

    @Override
    public String getDescription() {
        return "Runs a shell command. Input: {\"command\": \"ls -la\", \"shell\": \"bash\"}";
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

        StringBuilder output = new StringBuilder();
        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
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
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error(getName(), "Execution failed: " + e.getMessage());
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
