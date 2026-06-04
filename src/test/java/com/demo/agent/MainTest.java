package com.demo.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainTest {

    @Test
    @DisplayName("Command line arguments are joined into one task")
    void commandLineArgsBecomeSingleTask() {
        assertEquals("create hello world", Main.buildTaskFromArgs(new String[] {"create", "hello", "world"}));
    }
}
