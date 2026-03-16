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
 */
public class ShellRunTool implements Tool {

    private static final Set<String> BLOCKED_COMMANDS = Set.of(
        "rm -rf", "del /f /s", "format", "mkfs", "dd if=",
        "shutdown", "reboot", "halt", "init 0", "kill -9",
        "curl | sh", "wget | sh", "eval", "exec "
    );

    private static final Set<String> BLOCKED_PATTERNS = Set.of(
        "..", "~", "Windows\\System32", "Windows\\SysWOW64",
        "/etc/", "/usr/", "/bin/", "/sbin/", "/var/", "/root/",
        ".ssh", ".git/config", "credentials", "secrets"
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
        String command = getValue(args, "command");
        String shell = getValue(args, "shell");

        if (command == null || command.trim().isEmpty()) {
            return ToolResult.error(getName(), "Missing 'command' in arguments");
        }

        // Security: Check for dangerous commands
        String lowerCmd = command.toLowerCase();
        for (String blocked : BLOCKED_COMMANDS) {
            if (lowerCmd.contains(blocked.toLowerCase())) {
                return ToolResult.error(getName(), "Security: Blocked command: " + blocked);
            }
        }

        // Security: Check for dangerous path patterns in command
        for (String blocked : BLOCKED_PATTERNS) {
            if (lowerCmd.contains(blocked.toLowerCase())) {
                return ToolResult.error(getName(), "Security: Blocked pattern in command: " + blocked);
            }
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

    private boolean isDangerousPath(String path) {
        String lowerPath = path.toLowerCase();
        for (String pattern : BLOCKED_PATTERNS) {
            if (lowerPath.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String getValue(String json, String key) {
        if (json == null) return null;
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        char c = json.charAt(i);
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            int end = i + 1;
            boolean escaped = false;
            while (end < json.length()) {
                char ch = json.charAt(end);
                if (escaped) {
                    switch (ch) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '\'': sb.append('\''); break;
                        default: sb.append(ch); break;
                    }
                    escaped = false;
                } else {
                    if (ch == '\\') {
                        escaped = true;
                    } else if (ch == '"') {
                        return sb.toString();
                    } else {
                        sb.append(ch);
                    }
                }
                end++;
            }
            return sb.toString();
        } else {
            int end = i;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(i, end).trim();
        }
    }
}
