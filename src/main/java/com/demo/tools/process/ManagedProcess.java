package com.demo.tools.process;

import com.demo.execution.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.Channels;
import java.nio.charset.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

/** Owns a process, observed descendants, pipes, and its bounded output reader. */
final class ManagedProcess implements AutoCloseable {
    private final Process process;
    private final Map<Long, ProcessHandle> descendants = new ConcurrentHashMap<>();
    private final ExecutorService reader;
    private final Future<?> reading;
    private final StringBuilder output = new StringBuilder();
    private final int limit;
    private volatile boolean stopping;
    private volatile boolean outputFailed;
    private volatile boolean decodingError;
    private boolean truncated;
    private volatile long observedBytes;
    private volatile boolean outputLimitExceeded;
    private final StringBuilder tail = new StringBuilder();
    private int headBytes, tailBytes;
    private boolean headFull;
    private final java.util.function.Consumer<String> liveOutput;
    private int liveBytes;


    ManagedProcess(ProcessBuilder builder, int limit) throws IOException { this(builder, limit, StandardCharsets.UTF_8); }
    ManagedProcess(ProcessBuilder builder, int limit, Charset charset) throws IOException {
        this(builder, limit, charset, java.util.function.UnaryOperator.identity());
    }
    ManagedProcess(ProcessBuilder builder, int limit, Charset charset, java.util.function.UnaryOperator<InputStream> input) throws IOException {
        this(builder,limit,charset,input,text -> {});
    }
    ManagedProcess(ProcessBuilder builder, int limit, Charset charset, java.util.function.UnaryOperator<InputStream> input,
                   java.util.function.Consumer<String> outputEvents) throws IOException {
        if (limit <= 0) throw new IllegalArgumentException("Output limit must be positive");
        this.limit = limit;
        this.liveOutput=outputEvents;
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "coding-agent-output"); thread.setDaemon(true); return thread;
        });
        Process started = null;
        try {
            started = builder.start();
            process = started;
            process.getOutputStream().close(); // No interactive input is supported.
            reader = executor;
            reading = reader.submit(() -> consume(charset, input.apply(process.getInputStream())));
        } catch (IOException | RuntimeException | Error failure) {
            executor.shutdownNow();
            if (started != null) {
                try { cleanupInitialization(started); }
                catch (ResourceCleanupException e) { failure.addSuppressed(e); }
            }
            if (started != null && !(failure instanceof Error)) {
                InitializationFailure observed=new InitializationFailure(failure);
                for(Throwable suppressed:failure.getSuppressed())observed.addSuppressed(suppressed);
                throw observed;
            }
            throw failure;
        }
    }
    static final class InitializationFailure extends IOException {
        InitializationFailure(Throwable cause) { super("Process started but initialization failed",cause); }
    }
    Result stopAndCollect() throws InterruptedException {
        requestStop(); finishReading(); return result(-1,false);
    }
    private static void cleanupInitialization(Process process) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            process.toHandle().descendants().forEach(h -> { if (h.isAlive()) h.destroyForcibly(); });
            process.destroyForcibly();
            IOException failure = null;
            for (Closeable stream : new Closeable[]{process.getInputStream(), process.getErrorStream(), process.getOutputStream()})
                try { stream.close(); } catch (IOException e) { failure = e; }
            while (process.isAlive() && System.nanoTime() < deadline) {
                try { process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (process.isAlive() || failure != null)
                throw new ResourceCleanupException("Process initialization cleanup failed", () -> cleanupInitialization(process));
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private void consume(Charset charset, InputStream input) {
        CharsetDecoder decoder = charset.newDecoder();
        ByteBuffer bytes = ByteBuffer.allocate(8192);
        CharBuffer chars = CharBuffer.allocate(8192);
        try (var channel = Channels.newChannel(input)) {
            boolean ended = false;
            while (!ended) {
                int count = channel.read(bytes);
                ended = count == -1;
                if (count > 0) {
                    observedBytes += count;
                    if (observedBytes >= 16L * 1024 * 1024) { outputLimitExceeded = true; requestStop(); }
                }
                bytes.flip();
                while (true) {
                    CoderResult result = decoder.decode(bytes, chars, ended);
                    chars.flip(); append(chars); chars.clear();
                    if (result.isError()) {
                        decodingError = true;
                        bytes.position(bytes.position() + result.length());
                        append(CharBuffer.wrap("\uFFFD"));
                    } else if (result.isUnderflow()) break;
                }
                bytes.compact();
            }
            while (true) {
                CoderResult result = decoder.flush(chars);
                chars.flip(); append(chars); chars.clear();
                if (result.isUnderflow()) break;
            }
        } catch (IOException | RuntimeException e) { if (!stopping) { outputFailed = true; requestStop(); } }
    }
    Result await(Duration timeout, CancellationToken token) throws InterruptedException {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Timeout must be positive");
        long deadline = System.nanoTime() + timeout.toNanos();
        try (var registration = token.onCancellation(this::requestStop)) {
            while (true) {
                observeDescendants(); token.throwIfCancellationRequested();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    requestStop();
                    finishReading();
                    return result(-1, true);
                }
                if (process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS)) break;
            }
            token.throwIfCancellationRequested();
            finishReading();
            token.throwIfCancellationRequested();
            return result(process.exitValue(), false);
        }
    }
    private void finishReading() throws InterruptedException {
        try { reading.get(1, TimeUnit.SECONDS); }
        catch (ExecutionException e) { outputFailed = true; }
        catch (TimeoutException e) { throw new ResourceCleanupException("Command output reader did not finish", this::close); }
    }
    private Result result(int exit, boolean timedOut) {
        return new Result(exit, output(), timedOut, outputFailed, decodingError, truncated, observedBytes, outputLimitExceeded);
    }
    private void observeDescendants() {
        process.toHandle().descendants().forEach(handle -> descendants.put(handle.pid(), handle));
    }
    void requestStop() {
        stopping = true;
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
            IOException failure = null;
            for (Closeable stream : new Closeable[]{process.getOutputStream(), process.getInputStream(), process.getErrorStream()}) {
                try { stream.close(); } catch (IOException e) { failure = e; }
            }
            reading.cancel(true); reader.shutdownNow();
            if (!reader.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                    || process.isAlive() || descendants.values().stream().anyMatch(ProcessHandle::isAlive) || failure != null)
                throw new ResourceCleanupException("Command resources could not be stopped", this::close);
        } catch (InterruptedException e) {
            interrupted = true; throw new ResourceCleanupException("Command cleanup was interrupted", this::close);
        } catch (ExecutionException | TimeoutException | SecurityException | UnsupportedOperationException e) {
            throw new ResourceCleanupException("Command resources could not be released", this::close);
        } finally {
            reader.shutdownNow(); if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private void awaitExit(ProcessHandle handle, long deadline) throws InterruptedException, ExecutionException, TimeoutException {
        if (handle.isAlive()) handle.onExit().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }
    private synchronized void append(CharBuffer chars) {
        String chunk = chars.toString();
        if (liveBytes < 32768) {
            String event = chunk.substring(0, Math.min(chunk.length(), (32768-liveBytes)/4));
            if (!event.isEmpty()) { liveBytes += event.getBytes(StandardCharsets.UTF_8).length; liveOutput.accept(event); }
        }
        // Count encoded bytes, and never split a surrogate pair at either truncation boundary.
        int half = Math.max(0,(limit-32)/2), accepted=0;
        while(!headFull && accepted<chunk.length()) {
            int cp=chunk.codePointAt(accepted), size=utf8Size(cp);
            if(headBytes+size>half){headFull=true;break;}
            headBytes+=size;accepted+=Character.charCount(cp);
        }
        output.append(chunk,0,accepted);
        if (accepted < chunk.length()) {
            String rest=chunk.substring(accepted);tail.append(rest);tailBytes+=rest.getBytes(StandardCharsets.UTF_8).length;
            int removed=0;
            while(tailBytes>half && removed<tail.length()) {
                int cp=tail.codePointAt(removed);tailBytes-=utf8Size(cp);removed+=Character.charCount(cp);
            }
            if(removed>0){tail.delete(0,removed);truncated=true;}
        }
    }
    private static int utf8Size(int cp){return cp<0x80?1:cp<0x800?2:cp<0x10000?3:4;}
    private synchronized String output() {
        return output + (truncated && limit>=32 ? "\n[output truncated]\n" : "") + tail;
    }
    long pid() { return process.pid(); }
    record Result(int exitCode, String output, boolean timedOut, boolean outputFailed, boolean decodingError, boolean truncated, long observedBytes, boolean outputLimitExceeded) { }
}
