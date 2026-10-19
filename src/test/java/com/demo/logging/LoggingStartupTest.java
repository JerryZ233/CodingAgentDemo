package com.demo.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Fresh JVMs verify bootstrap ordering independently of the test runner's logging singleton. */
class LoggingStartupTest {
    @TempDir Path temp;
    private record Result(int code, String text) { }
    private Result run(Path logs, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-Dcoding.agent.log.dir=" + logs,
                "-cp", System.getProperty("coding.agent.test.classpath"), "com.demo.cli.Main"));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).directory(temp.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.startsWith("LLM_"));
        Process process = builder.start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "CLI did not terminate");
            return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally { process.destroyForcibly(); }
    }
    @Test void helpDoesNotInitializeLoggingOrReadInvalidModelConfiguration() throws Exception {
        Path blocked = Files.writeString(temp.resolve("not-a-directory"), "file");
        Files.writeString(temp.resolve("config.yaml"), "llm: [");
        var result = run(blocked, "--help");
        assertEquals(0, result.code());
        assertTrue(result.text().contains("用法："));
        assertFalse(result.text().contains("Exception"));
    }
    @Test void inaccessibleLogDirectoryShowsOnlyChineseFailureAndReturnsOne() throws Exception {
        Path blocked = Files.writeString(temp.resolve("not-a-directory"), "file");
        var result = run(blocked, "--workspace", temp.toString(), "task");
        assertEquals(1, result.code());
        assertEquals("无法创建日志文件，请检查日志目录权限。", result.text().strip());
    }
    @Test void configurationFailureIsLoggedBeforeTerminalInitialization() throws Exception {
        Files.writeString(temp.resolve("config.yaml"), "llm: [");
        Path logs = temp.resolve("logs");
        var result = run(logs, "--workspace", temp.toString(), "task");
        assertEquals(1, result.code());
        assertEquals("启动失败，详细原因已记录到日志。", result.text().strip());
        try (var files = Files.list(logs)) {
            String data = Files.readString(files.findFirst().orElseThrow());
            assertTrue(data.contains("Application failed"));
            assertTrue(data.contains("Failed to parse config file"));
        }
    }
}
