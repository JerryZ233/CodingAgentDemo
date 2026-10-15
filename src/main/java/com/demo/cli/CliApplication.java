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
        catch (IllegalArgumentException e) {
            System.err.println("参数错误：" + TerminalIO.literal(e.getMessage()));
            return 2;
        }
        if (options.help()) { System.out.println(CliRenderer.usage()); return 0; }
        TerminalIO terminal = null;
        CliShutdownCoordinator shutdown = null;
        try {
            Config config = configs.get();
            terminal = terminals.get();
            CliRenderer renderer = new CliRenderer(terminal);
            renderer.welcome(config);
            SessionService service = services.create(config, options);
            RunEventPump pump = new RunEventPump(service, renderer);
            shutdown = new CliShutdownCoordinator(service, pump, terminal, renderer);
            if (hooks) shutdown.registerHook();
            CliController controller = new CliController(service, config.getWorkspacePath(), renderer, pump, shutdown);
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
                    RunEventPump.Outcome outcome = pump.await();
                    code = outcome.failure() != null ? 1 : switch (outcome.snapshot().lastRun().status()) {
                        case COMPLETED -> 0;
                        case CANCELLED -> 130;
                        default -> 1;
                    };
                }
            } else {
                while (controller.phase() != CliController.Phase.EXITING)
                    controller.accept(terminal.readLine(controller.prompt()));
            }
            if (!shutdown.close()) code = 1;
            return code;
        } catch (RuntimeException | java.io.IOError e) {
            if (terminal != null) new CliRenderer(terminal).failure(e);
            else System.err.println("启动失败：" + e.getClass().getSimpleName());
            if (shutdown != null) shutdown.close();
            else if (terminal != null) terminal.close();
            return 1;
        } finally {
            if (shutdown != null) shutdown.removeHook();
        }
    }
}
