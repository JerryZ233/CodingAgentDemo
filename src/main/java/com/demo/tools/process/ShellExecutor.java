package com.demo.tools.process;

import com.demo.execution.*;
import com.demo.model.ToolResult;
import com.demo.tools.fs.WorkspaceFileAccess;
import java.io.IOException;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Process hardening around the unchanged legacy command policy. Not an OS sandbox. */
public final class ShellExecutor {
    private final WorkspaceFileAccess workspace;
    private final Duration timeout;
    private final int outputLimit;
    public ShellExecutor(Path root, Duration timeout, int limit) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Timeout must be positive and at most one day");
        if (limit <= 0) throw new IllegalArgumentException("Output limit must be positive");
        workspace = new WorkspaceFileAccess(root); this.timeout = timeout; outputLimit = limit;
    }
    public ToolResult execute(String command, String shell, CancellationToken token) {
        token.throwIfCancellationRequested();
        if (LegacyShellChecks.isDangerousCommand(command) || LegacyShellChecks.isDangerousPath(command))
            return ToolResult.error("run_shell", "Security: Blocked dangerous command or path pattern");
        var plan = ShellPolicy.defaultPolicy().approve(command, shell);
        if (!plan.isAllowed()) return ToolResult.error("run_shell", plan.rejectionReason());
        try {
            workspace.verifyBinding();
            ProcessBuilder builder = hardenedBuilder(plan.commandLine());
            token.throwIfCancellationRequested();
            try (ManagedProcess managed = new ManagedProcess(builder, outputLimit, outputCharset(plan.commandLine().get(0)))) {
                var result = managed.await(timeout, token);
                if (result.timedOut()) return ToolResult.error("run_shell", "Command timed out after " + timeout.toMillis() + " ms. Output: " + result.output());
                if (result.outputFailed()) return ToolResult.error("run_shell", "OUTPUT_READ_FAILED. Output: " + result.output());
                if (result.exitCode() != 0) return ToolResult.error("run_shell", "Command exited with code " + result.exitCode() + ". Output: " + result.output());
                return ToolResult.success("run_shell", result.output());
            }
        } catch (OperationCancelledException | ResourceCleanupException e) {
            checkCleanupFailure(e); throw e;
        } catch (InterruptedException e) {
            checkCleanupFailure(e); Thread.currentThread().interrupt();
            return ToolResult.error("run_shell", "Execution interrupted");
        } catch (Exception e) {
            checkCleanupFailure(e); return ToolResult.error("run_shell", "Execution failed");
        }
    }
    ProcessBuilder hardenedBuilder(List<String> approved) throws IOException {
        boolean windows = isWindows();
        String name = approved.get(0);
        Path executable;
        List<String> command = new ArrayList<>();
        if (windows) {
            Path system = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows")).toAbsolutePath().normalize();
            executable = switch (name) {
                case "cmd" -> system.resolve("System32/cmd.exe");
                case "powershell" -> system.resolve("System32/WindowsPowerShell/v1.0/powershell.exe");
                case "pwsh" -> Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "PowerShell", "7", "pwsh.exe");
                default -> throw new IOException("Unknown shell");
            };
            command.add(executable.toString());
            if (name.equals("cmd")) command.addAll(List.of("/d", "/v:off"));
            else command.addAll(List.of("-NoLogo", "-NoProfile", "-NonInteractive"));
        } else {
            executable = Path.of("/bin", name);
            command.add(executable.toString());
            if (name.equals("bash")) command.addAll(List.of("--noprofile", "--norc"));
        }
        if (!Files.isRegularFile(executable)) throw new IOException("Trusted shell unavailable");
        command.addAll(approved.subList(1, approved.size()));
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.root().toFile()).redirectErrorStream(true);
        Map<String, String> env = builder.environment(); env.clear();
        if (windows) {
            String system = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows")).toAbsolutePath().normalize().toString();
            env.put("SystemRoot", system); env.put("WINDIR", system);
            env.put("ComSpec", Path.of(system, "System32", "cmd.exe").toString());
            env.put("PATH", Path.of(system, "System32") + ";" + system);
            env.put("TEMP", System.getProperty("java.io.tmpdir")); env.put("TMP", System.getProperty("java.io.tmpdir"));
        } else { env.put("PATH", "/usr/bin:/bin"); env.put("LANG", "C.UTF-8"); env.put("LC_ALL", "C.UTF-8"); }
        return builder;
    }
    private static Charset outputCharset(String shell) {
        if (!isWindows() || shell.equals("pwsh")) return StandardCharsets.UTF_8;
        // The console/native encoding is fixed at JVM startup; never read model-supplied encoding names.
        String encoding = System.getProperty("sun.stdout.encoding", System.getProperty("native.encoding", Charset.defaultCharset().name()));
        try { return Charset.forName(encoding); } catch (IllegalArgumentException e) { return Charset.defaultCharset(); }
    }
    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"); }
    public static void checkCleanupFailure(Throwable failure) {
        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof ResourceCleanupException resource) throw resource;
            checkCleanupFailure(suppressed);
        }
    }
}
