package com.demo.tools;

import com.demo.execution.CancellationToken;
import com.demo.execution.OperationCancelledException;
import com.demo.execution.ResourceCleanupException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Owns the process tree and its output reader on every exit path. */
final class ManagedProcess implements AutoCloseable {
    private final Process process;
    private final Map<Long, ProcessHandle> descendants = new ConcurrentHashMap<>();
    private final ExecutorService reader;
    private final Future<?> reading;
    private final StringBuilder output = new StringBuilder();
    private final int limit;
    private boolean truncated;

    ManagedProcess(ProcessBuilder builder, int limit) throws IOException {
        this.limit = limit;
        process = builder.start();
        reader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "coding-agent-output-" + process.pid());
            thread.setDaemon(true);
            return thread;
        });
        reading = reader.submit(() -> {
            try (var input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                int count;
                while ((count = input.read(buffer)) != -1) append(buffer, count);
            } catch (IOException ignored) { /* Killing a process can close its pipe. */ }
        });
    }

    Result await(Duration timeout, CancellationToken token) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        try (var registration = token.onCancellation(this::requestStop)) {
            while (true) {
                observeDescendants();
                token.throwIfCancellationRequested();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return new Result(-1, "", true);
                if (process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS)) break;
            }
            token.throwIfCancellationRequested();
            try { reading.get(1, TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                throw new ResourceCleanupException("Command output reader did not finish", this::close);
            }
            token.throwIfCancellationRequested();
            return new Result(process.exitValue(), output(), false);
        }
    }

    private void observeDescendants() {
        process.toHandle().descendants().forEach(handle -> descendants.put(handle.pid(), handle));
    }

    void requestStop() {
        observeDescendants();
        descendants.values().forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
        if (process.isAlive()) process.destroyForcibly();
    }

    @Override public void close() {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            requestStop();
            for (ProcessHandle handle : descendants.values()) awaitExit(handle, deadline);
            awaitExit(process.toHandle(), deadline);
            process.getOutputStream().close();
            process.getInputStream().close();
            process.getErrorStream().close();
            reading.cancel(true);
            reader.shutdownNow();
            if (!reader.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                    || process.isAlive() || descendants.values().stream().anyMatch(ProcessHandle::isAlive)) {
                throw new ResourceCleanupException("Command resources could not be stopped", this::close);
            }
        } catch (InterruptedException e) {
            interrupted = true;
            throw new ResourceCleanupException("Command cleanup was interrupted", this::close);
        } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new ResourceCleanupException("Command resources could not be released", this::close);
        } catch (SecurityException | UnsupportedOperationException e) {
            throw new ResourceCleanupException("Command resources could not be stopped", this::close);
        } finally {
            reader.shutdownNow();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void awaitExit(ProcessHandle handle, long deadline)
            throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        if (handle.isAlive()) handle.onExit().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    private synchronized void append(char[] buffer, int count) {
        int accepted = Math.min(count, Math.max(0, limit - output.length()));
        output.append(buffer, 0, accepted);
        if (accepted < count) truncated = true;
    }
    private synchronized String output() { return output + (truncated ? System.lineSeparator() + "[output truncated]" : ""); }
    long pid() { return process.pid(); }
    record Result(int exitCode, String output, boolean timedOut) { }
}
