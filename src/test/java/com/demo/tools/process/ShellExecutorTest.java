package com.demo.tools.process;

import com.demo.execution.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShellExecutorTest {
    @TempDir Path root;
    @Test void hardeningDoesNotChangePolicyAndEnvironmentIsMinimal() throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
        var executor = new ShellExecutor(root, Duration.ofSeconds(5), 4096);
        var plan = ShellPolicy.defaultPolicy().approve("echo hello", null);
        var builder = executor.hardenedBuilder(plan.commandLine());
        assertTrue(Path.of(builder.command().get(0)).isAbsolute());
        assertFalse(builder.environment().containsKey("LLM_API_KEY"));
        assertFalse(builder.environment().containsKey("BASH_ENV"));
        assertFalse(builder.environment().containsKey("ENV"));
        assertFalse(builder.environment().containsKey("HOME"));
        assertEquals("echo hello", builder.command().get(builder.command().size() - 1));
        assertTrue(builder.environment().keySet().stream().noneMatch(k -> k.toLowerCase(Locale.ROOT).matches(".*(key|token|secret).*")));
        if (windows) {
            assertTrue(builder.command().contains("/d"));
            var powershell = executor.hardenedBuilder(ShellPolicy.defaultPolicy().approve("echo hello", "powershell").commandLine());
            assertTrue(powershell.command().containsAll(List.of("-NoLogo", "-NoProfile", "-NonInteractive")));
        }
        assertFalse(ShellPolicy.defaultPolicy().approve("ls -la", null).isAllowed());
        assertFalse(ShellPolicy.defaultPolicy().approve("echo hello && echo no", null).isAllowed());
    }
    @Test void rejectsBadLimitsBeforeStartingAnything() {
        assertThrows(IllegalArgumentException.class, () -> new ShellExecutor(root, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new ShellExecutor(root, Duration.ofSeconds(1), 0));
    }
    @Test void simulatedSecretInParentEnvironmentIsNotInherited() throws Exception {
        String classes = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String classpath = classes + java.io.File.pathSeparator + System.getProperty("coding.agent.test.classpath");
        ProcessBuilder child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, Child.class.getName(), root.toString()).redirectErrorStream(true);
        child.environment().put("CODING_AGENT_SIMULATED_SECRET", "FAKE_SECRET_MUST_NOT_ESCAPE");
        Process process = child.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertFalse(output.contains("FAKE_SECRET_MUST_NOT_ESCAPE"), output);
        assertTrue(output.contains("checked"), output);
    }
    public static class Child {
        public static void main(String[] args) throws Exception {
            if (!"FAKE_SECRET_MUST_NOT_ESCAPE".equals(System.getenv("CODING_AGENT_SIMULATED_SECRET"))) throw new AssertionError();
            var executor = new ShellExecutor(Path.of(args[0]), Duration.ofSeconds(5), 4096);
            var builder = executor.hardenedBuilder(ShellPolicy.defaultPolicy().approve("echo hello", null).commandLine());
            if (builder.environment().containsKey("CODING_AGENT_SIMULATED_SECRET")) throw new AssertionError();
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
            if (windows) {
                var result = executor.execute("echo %CODING_AGENT_SIMULATED_SECRET%", null, CancellationToken.none());
                if (!result.isSuccess() || result.getOutput().contains("FAKE_SECRET_MUST_NOT_ESCAPE")) throw new AssertionError();
            }
            System.out.println("checked");
        }
    }
}
