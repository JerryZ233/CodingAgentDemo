package com.demo.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Narrow command policy for the optional run_shell tool.
 */
final class ShellPolicy {

    private static final Pattern HIGH_RISK_TOKENS = Pattern.compile(
        "(\\r|\\n|&&|\\|\\||[;&|<>`$(){}\\[\\]*?]|\\b2>\\b)"
    );

    private static final Map<String, ShellSpec> WINDOWS_SHELLS = Map.of(
        "cmd", new ShellSpec("cmd", "/c"),
        "cmd.exe", new ShellSpec("cmd", "/c"),
        "powershell", new ShellSpec("powershell", "-Command"),
        "pwsh", new ShellSpec("pwsh", "-Command")
    );

    private static final Map<String, ShellSpec> POSIX_SHELLS = Map.of(
        "sh", new ShellSpec("sh", "-c"),
        "bash", new ShellSpec("bash", "-c")
    );

    private static final Set<String> DEFAULT_ALLOWED_COMMANDS = Set.of(
        "cd",
        "pwd",
        "echo",
        "dir",
        "ls",
        "ping",
        "sleep"
    );

    private final Set<String> allowedCommands;
    private final boolean windows;

    private ShellPolicy(Set<String> allowedCommands, boolean windows) {
        this.allowedCommands = Set.copyOf(allowedCommands);
        this.windows = windows;
    }

    static ShellPolicy defaultPolicy() {
        return new ShellPolicy(DEFAULT_ALLOWED_COMMANDS, isWindows());
    }

    CommandPlan approve(String command, String shell) {
        String normalizedCommand = command == null ? "" : command.trim();
        if (normalizedCommand.isEmpty()) {
            return CommandPlan.rejected("Missing 'command' in arguments");
        }

        String requestedShell = shell == null || shell.isBlank()
            ? defaultShellName()
            : shell.trim().toLowerCase(Locale.ROOT);

        ShellSpec shellSpec = shellSpec(requestedShell);
        if (shellSpec == null) {
            return CommandPlan.rejected("Security: shell is not allowed: " + requestedShell);
        }

        if (containsHighRiskShellSyntax(normalizedCommand)) {
            return CommandPlan.rejected("Security: shell metacharacters, redirects, pipes, or background execution are not allowed");
        }

        String executable = firstToken(normalizedCommand).toLowerCase(Locale.ROOT);
        if (!allowedCommands.contains(executable)) {
            return CommandPlan.rejected("Security: command is not allowed: " + executable);
        }

        if (requiresWorkspaceScopedPaths(executable) && hasArguments(normalizedCommand)) {
            return CommandPlan.rejected(
                "Security: command does not accept arguments; use workspace file tools for path access");
        }

        return CommandPlan.allowed(List.of(shellSpec.executable(), shellSpec.flag(), normalizedCommand));
    }

    private ShellSpec shellSpec(String shell) {
        return windows ? WINDOWS_SHELLS.get(shell) : POSIX_SHELLS.get(shell);
    }

    private String defaultShellName() {
        return windows ? "cmd" : "sh";
    }

    private boolean containsHighRiskShellSyntax(String command) {
        return HIGH_RISK_TOKENS.matcher(command).find();
    }

    private String firstToken(String command) {
        String[] parts = command.trim().split("\\s+", 2);
        return parts[0];
    }

    private boolean hasArguments(String command) {
        return command.trim().split("\\s+", 2).length > 1;
    }

    private boolean requiresWorkspaceScopedPaths(String executable) {
        return Set.of("cd", "pwd", "dir", "ls").contains(executable);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    }

    static final class CommandPlan {
        private final boolean allowed;
        private final List<String> commandLine;
        private final String rejectionReason;

        private CommandPlan(boolean allowed, List<String> commandLine, String rejectionReason) {
            this.allowed = allowed;
            this.commandLine = commandLine == null ? List.of() : List.copyOf(commandLine);
            this.rejectionReason = rejectionReason;
        }

        static CommandPlan allowed(List<String> commandLine) {
            return new CommandPlan(true, new ArrayList<>(commandLine), null);
        }

        static CommandPlan rejected(String rejectionReason) {
            return new CommandPlan(false, List.of(), rejectionReason);
        }

        boolean isAllowed() {
            return allowed;
        }

        List<String> commandLine() {
            return commandLine;
        }

        String rejectionReason() {
            return rejectionReason;
        }
    }

    private record ShellSpec(String executable, String flag) {
    }
}
