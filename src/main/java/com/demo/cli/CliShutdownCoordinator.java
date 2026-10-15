package com.demo.cli;

import com.demo.session.SessionService;

/** The service is closed before the event pump and terminal. Failed interactive closes remain retryable. */
public final class CliShutdownCoordinator {
    private final SessionService service;
    private final RunEventPump pump;
    private final TerminalIO terminal;
    private final CliRenderer renderer;
    private final Thread hook;
    private boolean serviceClosed, pumpClosed, terminalClosed, hookRegistered;
    public CliShutdownCoordinator(SessionService service, RunEventPump pump, TerminalIO terminal, CliRenderer renderer) {
        this.service = service; this.pump = pump; this.terminal = terminal; this.renderer = renderer;
        hook = new Thread(this::close, "coding-agent-cli-shutdown");
    }
    public synchronized void registerHook() {
        if (!hookRegistered) { Runtime.getRuntime().addShutdownHook(hook); hookRegistered = true; }
    }
    public synchronized boolean close() {
        try {
            // Drain before closing the service so exceptional completion can still read snapshots.
            pump.poll();
            if (!serviceClosed) {
                pump.closeService();
                serviceClosed = true;
            }
            // Closing the service may finish a running task. Its terminal events are still readable.
            pump.poll();
            if (!pumpClosed) { pump.close(); pumpClosed = true; }
            if (!terminalClosed) {
                terminal.print("已关闭会话服务。");
                terminal.close(); terminalClosed = true;
            }
            return true;
        } catch (RuntimeException e) {
            renderer.failure(e);
            renderer.error("尚未完成保存或资源释放；可查看状态并重试 /exit。");
            return false;
        }
    }
    public synchronized void removeHook() {
        if (!hookRegistered || Thread.currentThread() == hook) return;
        try { Runtime.getRuntime().removeShutdownHook(hook); }
        catch (IllegalStateException ignored) { /* JVM shutdown is already in progress. */ }
        hookRegistered = false;
    }
}
