package com.demo.cli;

import com.demo.session.*;
import java.util.concurrent.*;

/** Serializes cursor consumption, including a synchronous final drain before a new command. */
public final class RunEventPump implements AutoCloseable {
    private static final class Display {
        final RunHandle handle;
        final CompletableFuture<SessionSnapshot> completion;
        long cursor;
        boolean terminal;
        Display(RunHandle handle) {
            this.handle = handle;
            completion = handle.completion().toCompletableFuture();
        }
    }
    public record Outcome(SessionSnapshot snapshot, Throwable failure) {}
    private final SessionService service;
    private final CliRenderer renderer;
    private final ScheduledExecutorService executor;
    private Display current;
    private Outcome latest;
    private boolean serviceClosed;
    private String memorySession;
    private long memoryCursor;
    public synchronized void watchMemory(String id) {
        memorySession=id;
        memoryCursor=service.memoryEventsAfter(id,Long.MAX_VALUE).nextSequence();
    }
    private void pollMemory() {
        if (serviceClosed || memorySession==null) return;
        var page=service.memoryEventsAfter(memorySession,memoryCursor);
        if(page.gap())renderer.info("部分记忆维护进度已归档到日志，可用 /memory status 查看当前用量。");
        for(var event:page.events())renderer.memoryEvent(event);
        memoryCursor=page.nextSequence();
    }
    public RunEventPump(SessionService service, CliRenderer renderer) {
        this.service = service;
        this.renderer = renderer;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "coding-agent-cli-events");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::safePoll, 100, 100, TimeUnit.MILLISECONDS);
    }
    public synchronized void attach(RunHandle handle) {
        poll();
        if (current != null) throw new IllegalStateException("前一任务尚未展示完成");
        latest = null;
        current = new Display(handle);
        poll();
    }
    public synchronized RunHandle active() { poll(); return current == null ? null : current.handle; }
    public synchronized Outcome outcome() { poll(); return latest; }
    public synchronized void poll() {
        Display display = current;
        if (display == null) { pollMemory(); return; }
        drain(display);
        pollMemory();
        if (!display.completion.isDone()) return;
        drain(display);
        if (!display.terminal) return;
        SessionSnapshot snapshot;
        Throwable failure = null;
        try { snapshot = display.completion.join(); }
        catch (CompletionException e) {
            failure = e.getCause();
            snapshot = serviceClosed ? null : service.getSession(display.handle.sessionId());
            renderer.failure(failure);
        }
        latest = new Outcome(snapshot, failure);
        current = null;
    }
    private void drain(Display display) {
        for (SessionEvent event : display.handle.eventsAfter(display.cursor)) {
            renderer.event(event);
            display.cursor = event.sequence();
            if (event.type() == SessionEvent.Type.RUN_FINISHED) display.terminal = true;
        }
    }
    private void safePoll() {
        try { poll(); }
        catch (RuntimeException e) {
            if (!(e instanceof SessionException) && !(e instanceof com.demo.session.storage.SessionStorageException))
                com.demo.logging.Logging.logger(RunEventPump.class).error("CLI event polling failed exception={}",
                        com.demo.logging.LogData.exception(e));
            renderer.failure(e);
        }
    }
    synchronized void closeService() {
        poll();
        service.close();
        serviceClosed = true;
        poll();
    }
    public Outcome await() {
        while (true) {
            synchronized (this) {
                poll();
                if (current == null) return latest;
            }
            try { Thread.sleep(25); }
            catch (InterruptedException e) {
                RunHandle handle = active();
                if (handle != null) handle.cancel();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待任务时线程被中断", e);
            }
        }
    }
    @Override public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) throw new IllegalStateException("事件线程未结束");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("事件线程关闭被中断", e);
        }
    }
}
