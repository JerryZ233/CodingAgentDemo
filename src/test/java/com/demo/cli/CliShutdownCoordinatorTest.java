package com.demo.cli;

import com.demo.session.SessionException;
import java.io.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CliShutdownCoordinatorTest {
    @Test void concurrentCloseRequestsAndHookRegistrationCloseServiceOnlyOnce() throws Exception {
        var service = new RunEventPumpTest.StubService();
        var output = new StringWriter();
        var terminal = new PlainTerminalIO(new StringReader(""), new PrintWriter(output), new PrintWriter(output));
        var renderer = new CliRenderer(terminal);
        var pump = new RunEventPump(service, renderer);
        var shutdown = new CliShutdownCoordinator(service, pump, terminal, renderer);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            shutdown.registerHook(); shutdown.registerHook();
            var first = executor.submit(shutdown::close);
            var second = executor.submit(shutdown::close);
            assertTrue(first.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(second.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, service.closes);
        } finally {
            shutdown.removeHook(); shutdown.close(); executor.shutdownNow();
        }
    }
    @Test void failedCloseKeepsTerminalAvailableAndSuccessfulCloseIsIdempotent() {
        var service = new RunEventPumpTest.StubService();
        var output = new StringWriter();
        var terminal = new PlainTerminalIO(new StringReader(""), new PrintWriter(output), new PrintWriter(output));
        var renderer = new CliRenderer(terminal);
        try (var pump = new RunEventPump(service, renderer)) {
            var shutdown = new CliShutdownCoordinator(service, pump, terminal, renderer);
            for (SessionException.Reason reason : new SessionException.Reason[]{
                    SessionException.Reason.SHUTDOWN_TIMEOUT, SessionException.Reason.RESOURCE_FAILURE}) {
                service.closeFailure = new SessionException(reason, "not closed");
                assertFalse(shutdown.close());
                assertFalse(output.toString().contains("已关闭会话服务"));
            }
            service.closeFailure = null;
            assertTrue(shutdown.close()); assertTrue(shutdown.close());
            assertEquals(3, service.closes);
            assertEquals(1, output.toString().split("已关闭会话服务", -1).length - 1);
        }
    }
    @Test void terminalEventsAreDrainedEvenIfCompletionIsPublishedAfterServiceCloses() {
        var handle = new RunEventPumpTest.Handle();
        var service = new RunEventPumpTest.StubService() {
            public void close() {
                handle.event(com.demo.session.SessionEvent.Type.RUN_FINISHED,
                        new com.demo.session.SessionEvent.RunFinished(com.demo.session.SessionRun.Status.CANCELLED, 0, false, "stopped"));
                handle.future.complete(handle.snapshot());
                super.close();
            }
            public com.demo.session.SessionSnapshot getSession(String id) { throw new AssertionError("closed snapshot access"); }
        };
        StringWriter output = new StringWriter();
        var terminal = new PlainTerminalIO(new StringReader(""), new PrintWriter(output), new PrintWriter(output));
        var renderer = new CliRenderer(terminal);
        var pump = new RunEventPump(service, renderer);
        pump.attach(handle);
        var shutdown = new CliShutdownCoordinator(service, pump, terminal, renderer);
        assertTrue(shutdown.close());
        assertTrue(output.toString().contains("CANCELLED"));
    }
}
