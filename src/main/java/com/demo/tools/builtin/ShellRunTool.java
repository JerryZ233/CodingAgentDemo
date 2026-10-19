package com.demo.tools.builtin;

import com.demo.tools.Tool;
import com.demo.tools.ToolSpec.Capability;
import com.demo.tools.process.ShellExecutor;
import com.demo.tools.support.*;
import com.demo.model.ToolResult;
import com.demo.execution.CancellationToken;
import com.google.gson.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

public final class ShellRunTool implements Tool {
    private final ShellExecutor executor;
    public ShellRunTool(Path workspace) { this(workspace, Duration.ofSeconds(30), 64 * 1024); }
    public ShellRunTool(Path workspace, Duration timeout, int limit) { executor = new ShellExecutor(workspace, timeout, limit); }
    public String getName() { return "run_shell"; }
    public String getDescription() { return "Runs a narrowly allowlisted command, e.g. echo hello. Directory commands accept no arguments. Does not support builds or tests; not an OS sandbox."; }
    public Set<Capability> getCapabilities() { return Set.of(Capability.PROCESS_EXECUTE, Capability.NETWORK); }
    public JsonObject getParametersSchema() {
        return JsonParser.parseString("""
            {"type":"object","properties":{"command":{"type":"string","maxLength":4096},
             "shell":{"type":"string","description":"cmd/powershell/pwsh on Windows or sh/bash on POSIX"}},
             "required":["command"],"additionalProperties":false}
            """).getAsJsonObject();
    }
    public ToolResult execute(String args) { return execute(args, CancellationToken.none()); }
    public ToolResult execute(String args, CancellationToken token) {
        token.throwIfCancellationRequested();
        String command, shell;
        try {
            var a = ToolArguments.parse(args, "command", "shell");
            command = a.requiredString("command"); shell = a.optionalString("shell");
            if (command.length() > ToolLimits.COMMAND_CHARS || (shell != null && shell.length() > 64))
                throw new IllegalArgumentException("Command too long");
        } catch (IllegalArgumentException e) { return ToolOutputs.error(getName(), "INVALID_ARGUMENTS", "Invalid command arguments"); }
        return executor.execute(command, shell, token);
    }
}
