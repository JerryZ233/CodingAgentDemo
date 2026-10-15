package com.demo.cli;

import com.demo.llm.LLMClient;
import com.demo.session.*;
import com.demo.session.storage.*;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CliControllerTest {
    @TempDir Path temp;
    @Test void selectionFailureAndResumeFailureKeepCurrentState() {
        try (var h = new CliTestSupport.Harness(temp, CliTestSupport.ANSWER)) {
            h.line(temp.resolve("missing").toString());
            assertEquals(CliController.Phase.SELECTING_WORKSPACE, h.controller.phase());
            h.line("");
            String original = h.controller.sessionId();
            h.line("/resume " + java.util.UUID.randomUUID());
            assertEquals(original, h.controller.sessionId());
            h.line("/new");
            assertNotEquals(original, h.controller.sessionId());
            h.line("/resume " + original);
            assertEquals(original, h.controller.sessionId());
            h.line("/history"); h.line("/sessions"); h.line("/status");
            assertTrue(h.text().contains(original));
        }
    }
    @Test void inputStaysAvailableWhileBusyAndCancellationAllowsAnotherTurn() {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        LLMClient model = (messages, tools) -> {
            entered.countDown(); CliTestSupport.await(release); return new LLMClient.LLMResponse("answer", null);
        };
        try (var h = new CliTestSupport.Harness(temp, model)) {
            h.line(""); h.line("first"); CliTestSupport.await(entered);
            String id = h.controller.sessionId();
            h.line("must not queue"); h.line("/new"); h.line("/resume " + id); h.line("/retry-save");
            assertEquals(id, h.controller.sessionId());
            assertEquals(1, h.snapshot().messages().size());
            h.line("/status"); h.line("/history"); h.line("/sessions");
            h.controller.accept(TerminalIO.Input.interrupt());
            release.countDown();
            assertEquals(SessionRun.Status.CANCELLED, h.finish().snapshot().lastRun().status());
            h.line("second"); assertEquals(SessionRun.Status.COMPLETED, h.finish().snapshot().lastRun().status());
            h.controller.accept(TerminalIO.Input.interrupt());
            assertEquals(CliController.Phase.INTERACTIVE, h.controller.phase());
        } finally { release.countDown(); }
    }
    @Test void failedExitIsRetryableAndSaveRepairDoesNotReplayWork() {
        AtomicBoolean failing = new AtomicBoolean();
        JsonSessionRepository disk = new JsonSessionRepository(temp.resolve("store"));
        SessionRepository repository = new SessionRepository() {
            public void save(SessionSnapshot snapshot) {
                if (failing.get()) throw new SessionStorageException(SessionStorageException.Reason.IO_FAILURE, "disk unavailable");
                disk.save(snapshot);
            }
            public SessionSnapshot load(String id) { return disk.load(id); }
            public SessionCatalog list() { return disk.list(); }
        };
        try (var h = new CliTestSupport.Harness(temp, CliTestSupport.ANSWER, repository)) {
            h.line(""); failing.set(true);
            h.line("work"); assertNotNull(h.finish().failure());
            assertTrue(h.snapshot().unsaved());
            h.line("/exit"); assertEquals(CliController.Phase.INTERACTIVE, h.controller.phase());
            h.line("blocked"); assertEquals(1, h.snapshot().messages().size());
            failing.set(false); h.line("/retry-save");
            assertFalse(h.snapshot().unsaved());
            h.line("/exit"); assertEquals(CliController.Phase.EXITING, h.controller.phase());
        } finally { failing.set(false); }
    }
}
