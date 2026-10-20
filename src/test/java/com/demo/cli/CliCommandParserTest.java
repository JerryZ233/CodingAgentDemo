package com.demo.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CliCommandParserTest {
    private final CliCommandParser parser = new CliCommandParser();
    @Test void textAndEscapePreserveTaskContent() {
        assertEquals(new CliCommand.Task("help"), parser.parse("help"));
        assertEquals(new CliCommand.Task("  fix 'this"), parser.parse("  fix 'this"));
        assertEquals(new CliCommand.Task("/fix something"), parser.parse("//fix something"));
        assertInstanceOf(CliCommand.Empty.class, parser.parse(" "));
    }
    @Test void commandsPreserveArgumentsAndSupportSpaces() {
        assertEquals(new CliCommand.NewSession("G:/My Project"), parser.parse("/NEW \"G:/My Project\""));
        assertEquals(new CliCommand.NewSession("G:/My Project"), parser.parse("/new G:/My Project"));
        String id = java.util.UUID.randomUUID().toString();
        assertEquals(new CliCommand.Resume(id), parser.parse("/resume " + id));
        assertEquals(new CliCommand.Resume("2"), parser.parse("/resume 2"));
        assertEquals(new CliCommand.RetrySave("2"), parser.parse("/retry-save 2"));
        assertEquals(new CliCommand.RetrySave(null), parser.parse("/retry-save"));
        assertInstanceOf(CliCommand.Exit.class, parser.parse("/quit"));
    }
    @Test void invalidCommandsNeverBecomeTasks() {
        for (String line : new String[]{"/unknown", "/status extra", "/resume", "/resume broken", "/new \"open", "/new ''"})
            assertThrows(IllegalArgumentException.class, () -> parser.parse(line));
    }

    @Test void memoryCommandsPreserveQueryAndValidateLocalResultOrdinals() {
        assertEquals(new CliCommand.MemorySearch("配置 ConfigLoader"), parser.parse("/MEMORY SEARCH 配置 ConfigLoader"));
        assertEquals(new CliCommand.MemoryRead(2), parser.parse("/memory read 2"));
        assertInstanceOf(CliCommand.MemoryHelp.class, parser.parse("/memory"));
        for (String input : new String[]{"/memory read 0", "/memory read 11", "/memory read bad", "/memory search", "/memory unknown"}) {
            assertThrows(IllegalArgumentException.class, () -> parser.parse(input));
        }
    }
}
