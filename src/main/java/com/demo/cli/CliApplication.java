package com.demo.cli;

import com.demo.config.Config;
import com.demo.session.*;
import java.util.function.Supplier;

/** Composition root; injected terminals and services make the full CLI testable without a real API. */
public final class CliApplication {
    @FunctionalInterface public interface ServiceFactory {
        SessionService create(Config config, CliOptions options);
    }
    private final Supplier<Config> configs;
    private final ServiceFactory services;
    private final Supplier<TerminalIO> terminals;
    private final boolean hooks;
    public CliApplication() {
        this(Config::getInstance, (config, options) -> options.sessionDirectory() == null
                ? new DefaultSessionService(config) : new DefaultSessionService(config, options.sessionDirectory()),
                JLineTerminalIO::open, true);
    }
    public CliApplication(Supplier<Config> configs, ServiceFactory services, Supplier<TerminalIO> terminals, boolean hooks) {
        this.configs = configs; this.services = services; this.terminals = terminals; this.hooks = hooks;
    }
    public int run(String[] args) {
        CliOptions options;
        try { options = CliOptions.parse(args); }
        catch (java.nio.file.InvalidPathException e) {
            System.err.println("参数错误：目录格式无效，请重新输入。");
            return 2;
        }
        catch (IllegalArgumentException e) {
            System.err.println("参数错误：" + TerminalIO.literal(e.getMessage()));
            return 2;
        }
        if (options.help()) { System.out.println(CliRenderer.usage()); return 0; }
        try { com.demo.logging.Logging.initialize(); }
        catch (RuntimeException e) {
            System.err.println("无法创建日志文件，请检查日志目录权限。");
            return 1;
        }
        var log = com.demo.logging.Logging.logger(CliApplication.class);
        log.info("Application started mode={}", options.mode());
        TerminalIO terminal = null;
        CliShutdownCoordinator shutdown = null;
        try {
            Config config = configs.get();
            com.demo.logging.LogData.registerSecret(config.getApiKey());
            terminal = terminals.get();
            CliRenderer renderer = new CliRenderer(terminal);
            com.demo.logging.Logging.onFailure(() -> renderer.error("日志写入失败，请检查日志目录和磁盘空间；任务将继续执行。"));
            renderer.welcome(config);
            SessionService service = services.create(config, options);
            RunEventPump pump = new RunEventPump(service, renderer);
            shutdown = new CliShutdownCoordinator(service, pump, terminal, renderer);
            if (hooks) shutdown.registerHook();
            CliController controller = new CliController(service, config.getWorkspacePath(), renderer, pump, shutdown);
            controller.approvalChannel(terminal.supportsApprovals());
            controller.initialize(options);
            int code = 0;
            if (options.mode() == CliOptions.Mode.SINGLE_TASK) {
                // Signals can arrive before scheduling; remember them rather than losing cancellation.
                java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
                java.util.concurrent.atomic.AtomicReference<RunHandle> submitted = new java.util.concurrent.atomic.AtomicReference<>();
                try (var registration = terminal.onInterrupt(() -> {
                    interrupted.set(true);
                    RunHandle handle = submitted.get();
                    if (handle == null) handle = service.getActiveRun().orElse(null);
                    if (handle != null) handle.cancel();
                })) {
                    controller.execute(new CliCommand.Task(options.task()));
                    RunHandle handle = service.getActiveRun().orElse(null);
                    submitted.set(handle);
                    if (interrupted.get() && handle != null) handle.cancel();
                    while(pump.active()!=null) {
                        RunHandle foreground=pump.active();
                        if(foreground==null)break;
                        TerminalIO.Input input=terminal.readRunInput(foreground,true);
                        if(input.kind()==TerminalIO.Kind.EOF){foreground.cancel();break;}
                        controller.accept(input);
                    }
                    RunEventPump.Outcome outcome = pump.await();
                    code = outcome.failure() != null ? 1 : switch (outcome.snapshot().lastRun().status()) {
                        case COMPLETED -> 0;
                        case CANCELLED -> 130;
                        default -> 1;
                    };
                }
            } else {
                while (controller.phase() != CliController.Phase.EXITING)
                    {
                    RunHandle handle=pump.active();
                    controller.accept(handle!=null && terminal.supportsApprovals()?terminal.readRunInput(handle,false):terminal.readLine(controller.prompt()));
                }
            }
            if (!shutdown.close()) code = 1;
            return code;
        } catch (RuntimeException | java.io.IOError e) {
            // Service/storage failures have already been logged at their origin.
            if (!(e instanceof SessionException) && !(e instanceof com.demo.session.storage.SessionStorageException))
                log.error("Application failed exception={}", com.demo.logging.LogData.exception(e));
            if (terminal != null) new CliRenderer(terminal).failure(e);
            else System.err.println("启动失败，详细原因已记录到日志。");
            if (shutdown != null) shutdown.close();
            else if (terminal != null) {
                try { terminal.close(); }
                catch (RuntimeException close) {
                    log.error("Terminal close failed exception={}", com.demo.logging.LogData.exception(close));
                }
            }
            return 1;
        } finally {
            if (shutdown != null) shutdown.removeHook();
            com.demo.logging.Logging.onFailure(null);
        }
    }
}
