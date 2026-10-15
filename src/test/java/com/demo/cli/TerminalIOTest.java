package com.demo.cli;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerminalIOTest {
    @Test void asynchronousPrintAbovePreservesTheEditedInput() throws Exception {
        var prompt = new java.util.concurrent.CountDownLatch(1);
        var bytes = new ByteArrayOutputStream();
        var output = new OutputStream() {
            public synchronized void write(int b) {
                bytes.write(b);
                if (bytes.toString(StandardCharsets.UTF_8).contains("edit > ")) prompt.countDown();
            }
        };
        var input = new PipedInputStream();
        var writer = new PipedOutputStream(input);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var terminal = new JLineTerminalIO(TerminalBuilder.builder().system(false).type("xterm")
                .streams(input, output).build())) {
            var reading = executor.submit(() -> terminal.readLine("edit > "));
            assertTrue(prompt.await(3, java.util.concurrent.TimeUnit.SECONDS));
            writer.write("hel".getBytes(StandardCharsets.UTF_8)); writer.flush();
            terminal.printAbove("background progress");
            writer.write("lo\n".getBytes(StandardCharsets.UTF_8)); writer.flush();
            assertEquals(TerminalIO.Input.line("hello"), reading.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("background progress"));
        } finally { writer.close(); executor.shutdownNow(); }
    }
    @Test void jlineMapsInterruptAndEofWithoutExposingLibraryExceptions() throws Exception {
        var prompt = new java.util.concurrent.CountDownLatch(1);
        var bytes = new ByteArrayOutputStream();
        var output = new OutputStream() {
            public synchronized void write(int b) {
                bytes.write(b);
                if (bytes.toString(StandardCharsets.UTF_8).contains("test > ")) prompt.countDown();
            }
        };
        var input = new PipedInputStream();
        var writer = new PipedOutputStream(input);
        Terminal nativeTerminal = TerminalBuilder.builder().system(false).type("xterm")
                .streams(input, output).build();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var terminal = new JLineTerminalIO(nativeTerminal)) {
            var reading = executor.submit(() -> terminal.readLine("test > "));
            assertTrue(prompt.await(3, java.util.concurrent.TimeUnit.SECONDS));
            nativeTerminal.raise(Terminal.Signal.INT);
            assertEquals(TerminalIO.Kind.INTERRUPT, reading.get(3, java.util.concurrent.TimeUnit.SECONDS).kind());
            writer.close();
            assertEquals(TerminalIO.Kind.EOF, executor.submit(() -> terminal.readLine("> "))
                    .get(3, java.util.concurrent.TimeUnit.SECONDS).kind());
        } finally {
            writer.close(); executor.shutdownNow();
        }
    }
    @Test void plainInputAndOutputAreLiteralAndClosingDoesNotCloseBorrowedStreams() {
        var output = new StringWriter();
        var input = new StringReader("hello\n\"unfinished\n");
        var terminal = new PlainTerminalIO(input, new PrintWriter(output), new PrintWriter(output));
        assertEquals(TerminalIO.Input.line("hello"), terminal.readLine("> "));
        assertEquals(TerminalIO.Input.line("\"unfinished"), terminal.readLine("> "));
        assertEquals(TerminalIO.Kind.EOF, terminal.readLine("> ").kind());
        terminal.printAbove("\u001b[31mresult\u0007");
        terminal.error("error"); terminal.close(); terminal.print("still writable");
        assertFalse(output.toString().contains("\u001b"));
        assertFalse(output.toString().contains("\u0007"));
        assertTrue(output.toString().contains("still writable"));
    }
    @Test void jlineDoesNotRequireClosingTaskQuotesAndHasScopedInterruptHandler() throws Exception {
        var output = new ByteArrayOutputStream();
        Terminal nativeTerminal = TerminalBuilder.builder().system(false).dumb(true).type(Terminal.TYPE_DUMB)
                .streams(new ByteArrayInputStream("fix \"unfinished\n".getBytes(StandardCharsets.UTF_8)), output).build();
        try (var terminal = new JLineTerminalIO(nativeTerminal)) {
            assertEquals(TerminalIO.Input.line("fix \"unfinished"), terminal.readLine("> "));
            AtomicInteger signals = new AtomicInteger();
            try (var registration = terminal.onInterrupt(signals::incrementAndGet)) {
                nativeTerminal.raise(Terminal.Signal.INT);
                assertEquals(1, signals.get());
            }
            terminal.printAbove("progress");
        }
    }
}
