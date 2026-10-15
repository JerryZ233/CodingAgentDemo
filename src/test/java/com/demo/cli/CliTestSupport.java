package com.demo.cli;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.session.*;
import com.demo.session.storage.*;
import java.io.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

final class CliTestSupport {
    static final LLMClient ANSWER = (messages, tools) -> new LLMClient.LLMResponse("answer", null);
    static final class Harness implements AutoCloseable {
        final StringWriter output = new StringWriter();
        final TerminalIO terminal = new PlainTerminalIO(new StringReader(""), new PrintWriter(output), new PrintWriter(output));
        final SessionService service;
        final CliRenderer renderer = new CliRenderer(terminal);
        final RunEventPump pump;
        final CliShutdownCoordinator shutdown;
        final CliController controller;
        Harness(Path workspace, LLMClient model) { this(workspace, model, new JsonSessionRepository(workspace.resolve("sessions"))); }
        Harness(Path workspace, LLMClient model, SessionRepository repository) {
            service = new DefaultSessionService(Config.defaults(), model, repository, Clock.systemUTC());
            pump = new RunEventPump(service, renderer);
            shutdown = new CliShutdownCoordinator(service, pump, terminal, renderer);
            controller = new CliController(service, workspace, renderer, pump, shutdown);
            controller.initialize(CliOptions.parse(new String[0]));
        }
        void line(String line) { controller.accept(TerminalIO.Input.line(line)); }
        SessionSnapshot snapshot() { return service.getSession(controller.sessionId()); }
        RunEventPump.Outcome finish() { return pump.await(); }
        String text() { synchronized (output.getBuffer()) { return output.toString(); } }
        public void close() { if (!shutdown.close()) throw new AssertionError(text()); }
    }
    static void await(java.util.concurrent.CountDownLatch latch) {
        try { org.junit.jupiter.api.Assertions.assertTrue(latch.await(4, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
