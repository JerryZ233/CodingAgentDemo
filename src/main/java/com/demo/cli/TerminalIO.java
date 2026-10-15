package com.demo.cli;

public interface TerminalIO extends AutoCloseable {
    enum Kind { TEXT, INTERRUPT, EOF }
    record Input(Kind kind, String text) {
        public static Input line(String text) { return new Input(Kind.TEXT, text); }
        public static Input interrupt() { return new Input(Kind.INTERRUPT, null); }
        public static Input eof() { return new Input(Kind.EOF, null); }
    }
    Input readLine(String prompt);
    void print(String text);
    void error(String text);
    void printAbove(String text);
    interface Registration extends AutoCloseable { @Override void close(); }
    default Registration onInterrupt(Runnable action) { return () -> {}; }
    @Override void close();

    /** Render untrusted text literally, without terminal escape/control sequences. */
    static String literal(String text) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\t' || (!Character.isISOControl(cp) && cp != 0x7f)) result.appendCodePoint(cp);
        });
        return result.toString();
    }
}
