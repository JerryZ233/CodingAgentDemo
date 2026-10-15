package com.demo.tools;

import com.demo.execution.CancellationSource;
import com.demo.execution.CancellationToken;
import com.demo.execution.OperationCancelledException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ManagedProcessTest {
    @TempDir Path temp;

    @Test void cancellationStopsParentAndObservedChildAndReader() throws Exception {
        Path pidFile = temp.resolve("child.pid");
        var pool = Executors.newSingleThreadExecutor();
        CancellationSource source = new CancellationSource();
        long parent;
        long child;
        try (var process = new ManagedProcess(builder("tree", pidFile.toString()), 4096)) {
            parent = process.pid();
            var waiting = pool.submit(() -> process.await(Duration.ofSeconds(20), source.token()));
            child = waitForPid(pidFile);
            source.cancel();
            assertInstanceOf(OperationCancelledException.class, assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> waiting.get(3, TimeUnit.SECONDS)).getCause());
        } finally { pool.shutdownNow(); }
        assertFalse(ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false));
        assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void timeoutClosesProcessAndOutputLimitHandlesLongLines() throws Exception {
        long pid;
        try (var process = new ManagedProcess(builder("wait"), 128)) {
            pid = process.pid();
            assertTrue(process.await(Duration.ofMillis(100), CancellationToken.none()).timedOut());
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        try (var process = new ManagedProcess(builder("output"), 128)) {
            var result = process.await(Duration.ofSeconds(5), CancellationToken.none());
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("[output truncated]"));
            assertTrue(result.output().length() < 200);
        }
    }

    private ProcessBuilder builder(String... args) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var command = new java.util.ArrayList<>(java.util.List.of(javaExecutable, "-cp", classes, Child.class.getName()));
        command.addAll(java.util.List.of(args));
        return new ProcessBuilder(command).redirectErrorStream(true).directory(temp.toFile());
    }

    private long waitForPid(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path)) {
                String text = Files.readString(path);
                if (!text.isBlank()) return Long.parseLong(text);
            }
            Thread.sleep(10); // Poll actual OS readiness, not a guessed process startup delay.
        }
        throw new AssertionError("Child did not report readiness");
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("output")) {
                System.out.print("x".repeat(100_000));
            } else if (args[0].equals("tree")) {
                String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                        Child.class.getName(), "leaf", args[1]).start();
                child.waitFor();
            } else {
                if (args[0].equals("leaf")) Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                new CountDownLatch(1).await();
            }
        }
    }
}
