package com.demo.tools.process;

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

    @Test void actualOutputHardLimitStopsFloodingProcess() throws Exception {
        try(var process=new ManagedProcess(builder("flood"),256*1024)) {
            var result=process.await(Duration.ofSeconds(10),CancellationToken.none());
            assertTrue(result.outputLimitExceeded());
            assertTrue(result.observedBytes()>=16L*1024*1024);
            assertTrue(result.output().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=256*1024);
            assertTrue(result.truncated());
        }
    }

    @Test void outputReadFailureIsReportedAndMalformedBytesAreReplaced() throws Exception {
        try (var process = new ManagedProcess(builder("output"), 128, java.nio.charset.StandardCharsets.UTF_8,
                input -> new java.io.FilterInputStream(input) {
                    @Override public int read(byte[] b, int off, int len) throws java.io.IOException { throw new java.io.IOException("simulated read failure"); }
                })) {
            assertTrue(process.await(Duration.ofSeconds(5), CancellationToken.none()).outputFailed());
        }
        try (var process = new ManagedProcess(builder("invalid"), 128)) {
            var result = process.await(Duration.ofSeconds(5), CancellationToken.none());
            assertFalse(result.outputFailed()); assertTrue(result.decodingError());
            assertTrue(result.output().contains("\uFFFD"));
        }
    }
    @Test void timeoutRetainsOutputAndStdinIsClosed() throws Exception {
        try (var process = new ManagedProcess(builder("output-wait"), 128)) {
            var result = process.await(Duration.ofSeconds(1), CancellationToken.none());
            assertTrue(result.timedOut()); assertTrue(result.output().contains("before-timeout"));
        }
        try (var process = new ManagedProcess(builder("stdin"), 128)) {
            var result = process.await(Duration.ofSeconds(5), CancellationToken.none());
            assertEquals(0, result.exitCode()); assertTrue(result.output().contains("EOF"));
        }
    }

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
            if (args[0].equals("flood")) {
                byte[] bytes=new byte[8192];java.util.Arrays.fill(bytes,(byte)'x');
                while(true)System.out.write(bytes);
            } else if (args[0].equals("invalid")) {
                System.out.write(new byte[]{65, (byte)0xc3, 40});
            } else if (args[0].equals("stdin")) {
                if (System.in.read() != -1) throw new AssertionError();
                System.out.println("EOF");
            } else if (args[0].equals("output-wait")) {
                System.out.println("before-timeout"); System.out.flush(); Thread.sleep(20_000);
            } else if (args[0].equals("output")) {
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
