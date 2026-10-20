package com.demo.logging;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.model.ToolCall;
import com.demo.session.*;
import com.demo.session.storage.JsonSessionRepository;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LoggingTest {
    @TempDir Path temp;
    @AfterEach void restore() {
        Logging.onFailure(null);
        Logging.configure(Path.of(System.getProperty("coding.agent.log.dir")), Level.DEBUG, "10 MB");
    }
    @Test void unicodeIsQuotedAndSecretsAndControlCharactersAreRemoved() {
        LogData.registerSecret("test-configured-key-中文");
        String value = LogData.quote("中文😀\nforged INFO\u001b password=private Bearer secret-token test-configured-key-中文");
        assertTrue(value.contains("中文😀"));
        assertFalse(value.contains("\n"));
        assertFalse(value.contains("\u001b"));
        assertFalse(value.contains("private"));
        assertFalse(value.contains("secret-token"));
        assertFalse(value.contains("test-configured-key-中文"));
        assertTrue(JsonParser.parseString(value).getAsString().contains("中文😀\nforged INFO"));
        String nested = LogData.quote("{\"nested\":{\"api_key\":\"private-value\"}}");
        assertFalse(nested.contains("private-value"));
    }
    @Test void fileIsUtf8WithContextAndLevelFiltering() throws Exception {
        Logging.configure(temp, Level.INFO, "10 MB");
        var log = Logging.logger(LoggingTest.class);
        try (var scope = org.apache.logging.log4j.CloseableThreadContext.put("session_id", "session-marker")) {
            log.debug("hidden-debug");
            log.info("Visible message text={}", LogData.quote("中文😀"));
        }
        String content = Files.readString(Logging.file());
        assertTrue(content.contains("中文😀"));
        assertTrue(content.contains("session_id=session-marker"));
        assertFalse(content.contains("hidden-debug"));
        assertNull(ThreadContext.get("session_id"));
        var context = (LoggerContext) LogManager.getContext(false);
        assertEquals(Set.of("file"), context.getConfiguration().getAppenders().keySet());
    }
    @Test void serviceWithoutCliRecordsRepliesToolsAndDistinctRunContexts() throws Exception {
        Logging.configure(temp.resolve("logs"), Level.DEBUG, "10 MB");
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        String firstId, secondId;
        LLMClient client = (messages, tools) -> "tool".equals(messages.get(messages.size() - 1).getRole())
                ? new LLMClient.LLMResponse("自然语言回复😀", null)
                : new LLMClient.LLMResponse("中间说明", List.of(new ToolCall("read_file", "{\"path\":\"example.txt\"}")));
        Files.writeString(workspace.resolve("example.txt"), "private-tool-marker中文");
        try (var service = new DefaultSessionService(com.demo.testing.TestConfigs.defaults(), client,
                new JsonSessionRepository(temp.resolve("sessions")), Clock.systemUTC())) {
            firstId = service.createSession(workspace).id();
            service.runTurn(firstId, "读取文件");
            secondId = service.createSession(workspace).id();
            service.runTurn(secondId, "再次读取");
        }
        String content = Files.readString(Logging.file());
        assertTrue(content.contains("Model response received"));
        assertTrue(content.contains("自然语言回复😀"));
        assertTrue(content.contains("private-tool-marker中文"));
        assertTrue(content.contains("Iteration started"));
        assertTrue(content.contains("session_id=" + firstId));
        assertTrue(content.contains("session_id=" + secondId));
        var replies = content.lines().filter(line -> line.contains("text=\"自然语言回复😀\"")).toList();
        assertEquals(2, replies.size());
        assertTrue(replies.get(0).contains("session_id=" + firstId));
        assertFalse(replies.get(0).contains(secondId));
        assertTrue(replies.get(1).contains("session_id=" + secondId));
    }
    @Test void rotationIsBoundedAndOnlyOldApplicationArchivesAreDeleted() throws Exception {
        Path old = temp.resolve("agent-old-123-1.log.gz");
        Path unrelated = temp.resolve("notes.log.gz");
        Files.writeString(old, "old"); Files.writeString(unrelated, "keep");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(20))));
        Files.setLastModifiedTime(unrelated, FileTime.from(Instant.now().minus(Duration.ofDays(20))));
        Logging.configure(temp, Level.DEBUG, "1 KB");
        var log = Logging.logger(LoggingTest.class);
        for (int i = 0; i < 20; i++) log.debug("Rotation data index={} text={}", i, "x".repeat(1200));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (Files.exists(old) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(unrelated));
        try (var files = Files.list(temp)) {
            long archives = files.filter(path -> path.getFileName().toString().startsWith("agent-"))
                    .filter(path -> path.toString().endsWith(".gz")).count();
            assertTrue(archives > 0 && archives <= 10, "archive count=" + archives);
        }
    }
    @Test void invalidDirectoryFailsAndRuntimeAppenderErrorsNotifyOnlyOnce() throws Exception {
        Path blocked = Files.writeString(temp.resolve("blocked"), "file");
        assertThrows(IllegalStateException.class, () -> Logging.configure(blocked, Level.DEBUG, "10 MB"));
        Logging.configure(temp.resolve("valid"), Level.DEBUG, "10 MB");
        AtomicInteger notices = new AtomicInteger();
        var context = (LoggerContext) LogManager.getContext(false);
        var handler = context.getConfiguration().getAppender("file").getHandler();
        handler.error("simulated write failure"); // Failure before a frontend registers is retained.
        Logging.onFailure(notices::incrementAndGet);
        handler.error("second write failure");
        assertEquals(1, notices.get());
        assertDoesNotThrow(() -> Logging.logger(LoggingTest.class).info("Task continues"));
    }
    @Test void settingsUsePropertyThenNonblankEnvironmentThenDefault() {
        assertEquals("property", Logging.choose("property", "env", "default"));
        assertEquals("env", Logging.choose(" ", "env", "default"));
        assertEquals("default", Logging.choose(null, " ", "default"));
    }
}
