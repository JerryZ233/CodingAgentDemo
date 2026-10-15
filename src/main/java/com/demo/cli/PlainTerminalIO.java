package com.demo.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;

/** Does not own the supplied streams (notably System.in/out/err). */
public final class PlainTerminalIO implements TerminalIO {
    private final BufferedReader input;
    private final PrintWriter output;
    private final PrintWriter errors;
    private final Object outputLock = new Object();

    public PlainTerminalIO(Reader input, PrintWriter output, PrintWriter errors) {
        this.input = new BufferedReader(input);
        this.output = output;
        this.errors = errors;
    }
    public Input readLine(String prompt) {
        synchronized (outputLock) { output.print(TerminalIO.literal(prompt)); output.flush(); }
        try {
            String line = input.readLine();
            return line == null ? Input.eof() : Input.line(line);
        } catch (IOException e) { throw new UncheckedIOException("终端输入读取失败", e); }
    }
    public void print(String text) {
        synchronized (outputLock) { output.println(TerminalIO.literal(text)); output.flush(); }
    }
    public void error(String text) {
        synchronized (outputLock) { errors.println(TerminalIO.literal(text)); errors.flush(); }
    }
    public void printAbove(String text) { print(text); }
    public void close() {
        synchronized (outputLock) { output.flush(); errors.flush(); }
    }
}
