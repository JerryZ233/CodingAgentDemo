package com.demo.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CliOptionsTest {
    @Test void defaultsAndTaskModes() {
        assertEquals(CliOptions.Mode.INTERACTIVE, CliOptions.parse(new String[0]).mode());
        var options = CliOptions.parse(new String[]{"--workspace", "a b", "--session-dir", "store", "do", "work"});
        assertTrue(options.workspace().isAbsolute());
        assertEquals("do work", options.task());
        assertTrue(options.sessionDirectory().isAbsolute());
        assertEquals(CliOptions.Mode.SINGLE_TASK, options.mode());
    }
    @Test void delimiterAndResume() {
        String id = java.util.UUID.randomUUID().toString();
        var options = CliOptions.parse(new String[]{"--session", id, "--", "--help", "-x"});
        assertEquals(id, options.sessionId());
        assertEquals("--help -x", options.task());
        assertFalse(options.help());
    }
    @Test void rejectsInvalidAndConflictingArguments() {
        for (String[] args : new String[][]{
                {"--bad"}, {"--workspace"}, {"--workspace", "--help"}, {"--session", "x"},
                {"--workspace", ".", "--session", java.util.UUID.randomUUID().toString()},
                {"--workspace", ".", "--workspace", "."}, {"--"}, {""}})
            assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(args));
    }
    @Test void helpSkipsAllApplicationInitializationAndBadArgsReturnTwo() {
        var app = new CliApplication(() -> { throw new AssertionError("configuration"); },
                (config, options) -> { throw new AssertionError("service"); },
                () -> { throw new AssertionError("terminal"); }, false);
        assertEquals(0, app.run(new String[]{"--help"}));
        assertEquals(2, app.run(new String[]{"--unknown"}));
    }
}
