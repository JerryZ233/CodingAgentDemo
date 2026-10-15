package com.demo.cli;

import com.demo.session.*;
import java.nio.file.Path;

public final class CliController {
    public enum Phase { SELECTING_WORKSPACE, INTERACTIVE, EXITING }
    private final SessionService service;
    private final Path defaultWorkspace;
    private final CliRenderer renderer;
    private final RunEventPump pump;
    private final CliShutdownCoordinator shutdown;
    private final CliCommandParser parser = new CliCommandParser();
    private Phase phase = Phase.SELECTING_WORKSPACE;
    private String sessionId;
    public CliController(SessionService service, Path defaultWorkspace, CliRenderer renderer,
                         RunEventPump pump, CliShutdownCoordinator shutdown) {
        this.service = service; this.defaultWorkspace = defaultWorkspace;
        this.renderer = renderer; this.pump = pump; this.shutdown = shutdown;
    }
    public Phase phase() { return phase; }
    public String sessionId() { return sessionId; }
    public void initialize(CliOptions options) {
        if (options.sessionId() != null) select(service.getSession(options.sessionId()));
        else if (options.workspace() != null || options.mode() == CliOptions.Mode.SINGLE_TASK)
            select(service.createSession(options.workspace() == null ? defaultWorkspace : options.workspace()));
        else renderer.selecting(defaultWorkspace);
    }
    private void select(SessionSnapshot snapshot) {
        sessionId = snapshot.id(); phase = Phase.INTERACTIVE; renderer.session(snapshot);
    }
    public String prompt() { return phase == Phase.SELECTING_WORKSPACE ? "目录 > " : "agent > "; }
    public void accept(TerminalIO.Input input) {
        if (input.kind() == TerminalIO.Kind.EOF) {
            phase = Phase.EXITING;
            return;
        }
        try {
            if (input.kind() == TerminalIO.Kind.INTERRUPT) { cancel(); return; }
            String text = input.text();
            if (phase == Phase.SELECTING_WORKSPACE && !isSelectionCommand(text)) {
                select(service.createSession(text.isBlank() ? defaultWorkspace : Path.of(CliCommandParser.directory(text))));
                return;
            }
            execute(parser.parse(text));
        } catch (IllegalArgumentException e) { renderer.error(e.getMessage()); }
        catch (RuntimeException e) { renderer.failure(e); }
    }
    public void execute(CliCommand command) {
        RunHandle active = pump.active();
        if (active != null && (command instanceof CliCommand.Task || command instanceof CliCommand.NewSession
                || command instanceof CliCommand.Resume || command instanceof CliCommand.RetrySave)) {
            renderer.error("当前任务尚未结束；请等待完成或使用 /cancel。"); return;
        }
        if (command instanceof CliCommand.Empty) return;
        if (command instanceof CliCommand.Help) renderer.help(phase == Phase.SELECTING_WORKSPACE);
        else if (command instanceof CliCommand.Exit) {
            if (shutdown.close()) phase = Phase.EXITING;
        } else if (command instanceof CliCommand.Sessions) {
            renderer.catalog(service.listSessions());
        } else if (command instanceof CliCommand.NewSession create) {
            select(service.createSession(create.directory() == null ? defaultWorkspace : Path.of(create.directory())));
        } else if (command instanceof CliCommand.Resume resume) select(service.getSession(resume.id()));
        else if (command instanceof CliCommand.RetrySave save) {
            String id = save.id() == null ? requireSession() : save.id();
            renderer.session(service.retrySave(id));
        } else if (command instanceof CliCommand.Cancel) cancel();
        else if (command instanceof CliCommand.History) renderer.history(service.getSession(requireSession()));
        else if (command instanceof CliCommand.Status) renderer.status(service.getSession(requireSession()), active);
        else if (command instanceof CliCommand.Task task) pump.attach(service.submitTurn(requireSession(), task.text()));
    }
    private String requireSession() {
        if (sessionId == null) throw new IllegalArgumentException("请先选择工作目录或使用 /resume 恢复会话");
        return sessionId;
    }
    private boolean isSelectionCommand(String text) {
        String name = text.strip().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        return java.util.Set.of("/help", "/sessions", "/resume", "/new", "/history", "/status",
                "/cancel", "/retry-save", "/exit", "/quit").contains(name);
    }
    public void cancel() {
        RunHandle run = pump.active();
        if (run == null) renderer.info("当前没有正在执行的任务。");
        else renderer.cancellation(run.cancel());
    }
}
