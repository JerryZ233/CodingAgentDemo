package com.demo.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingAgentTest {

    @Test
    @DisplayName("Default configuration exposes file tools but not shell")
    void defaultToolsExcludeShell() {
        CodingAgent agent = new CodingAgent();
        String toolDescriptions = agent.getToolDescriptions();

        assertTrue(toolDescriptions.contains("read_file"));
        assertTrue(toolDescriptions.contains("write_file"));
        assertTrue(toolDescriptions.contains("list_files"));
        assertFalse(toolDescriptions.contains("run_shell"));
    }

    @Test
    @DisplayName("Tool descriptions include argument schemas")
    void toolDescriptionsIncludeSchemas() {
        CodingAgent agent = new CodingAgent();
        String toolDescriptions = agent.getToolDescriptions();

        assertTrue(toolDescriptions.contains("\"required\":[\"path\"]"));
        assertTrue(toolDescriptions.contains("\"content\""));
    }
}
