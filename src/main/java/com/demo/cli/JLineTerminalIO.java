package com.demo.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/** All output uses printAbove; readLine is called only on the application's input thread. */
public final class JLineTerminalIO implements TerminalIO {
    private final Terminal terminal;
    private final LineReader reader;
    private final Object outputLock = new Object();

    public JLineTerminalIO(Terminal terminal) {
        this.terminal = terminal;
        reader = LineReaderBuilder.builder().terminal(terminal)
                .parser((line, cursor, context) -> new org.jline.reader.impl.DefaultParser()
                        .eofOnUnclosedQuote(false).eofOnEscapedNewLine(false).parse(line, cursor, context))
                .build();
        reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
        reader.unsetOpt(LineReader.Option.HISTORY_BEEP);
    }
    public static TerminalIO open() {
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).nativeSignals(true).dumb(true).build();
            if (!Terminal.TYPE_DUMB.equals(terminal.getType()) && !Terminal.TYPE_DUMB_COLOR.equals(terminal.getType()))
                return new JLineTerminalIO(terminal);
            terminal.close();
        } catch (IOException | IllegalStateException e) {
            // No full terminal is available; use the process streams without ANSI editing.
        }
        return new PlainTerminalIO(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8),
                new java.io.PrintWriter(System.out, true), new java.io.PrintWriter(System.err, true));
    }
    public Input readLine(String prompt) {
        try { return Input.line(reader.readLine(prompt)); }
        catch (UserInterruptException e) { return Input.interrupt(); }
        catch (EndOfFileException e) { return Input.eof(); }
    }
    public void print(String text) { printAbove(text); }
    public Registration onInterrupt(Runnable action) {
        Terminal.SignalHandler previous = terminal.handle(Terminal.Signal.INT, signal -> action.run());
        return () -> terminal.handle(Terminal.Signal.INT, previous);
    }
    public void error(String text) { printAbove(text); }
    public void printAbove(String text) {
        synchronized (outputLock) { reader.printAbove(TerminalIO.literal(text)); terminal.flush(); }
    }
    public void close() {
        try { terminal.close(); }
        catch (IOException e) { throw new UncheckedIOException("终端关闭失败", e); }
    }
}
