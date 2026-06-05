package com.demo.agent;

import com.demo.config.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingAgentTest {

    @Test
    @DisplayName("Default configuration exposes file tools but not shell")
    void defaultToolsExcludeShell() {
        CodingAgent agent = new CodingAgent(Config.defaults(), AgentObserver.noop());
        String toolDescriptions = agent.getToolDescriptions();

        assertTrue(toolDescriptions.contains("read_file"));
        assertTrue(toolDescriptions.contains("write_file"));
        assertTrue(toolDescriptions.contains("list_files"));
        assertFalse(toolDescriptions.contains("run_shell"));
    }

    @Test
    @DisplayName("Tool descriptions include argument schemas")
    void toolDescriptionsIncludeSchemas() {
        CodingAgent agent = new CodingAgent(Config.defaults(), AgentObserver.noop());
        String toolDescriptions = agent.getToolDescriptions();

        assertTrue(toolDescriptions.contains("\"required\":[\"path\"]"));
        assertTrue(toolDescriptions.contains("\"content\""));
    }

    @Test
    @DisplayName("Conversation prompt includes required parameters from tool specs")
    void conversationPromptIncludesRequiredParametersFromToolSpecs() {
        CodingAgent agent = new CodingAgent(Config.defaults(), AgentObserver.noop());
        String prompt = agent.getConversation().buildSystemPrompt();

        assertTrue(prompt.contains("read_file"));
        assertTrue(prompt.contains("Required parameters: path."));
        assertTrue(prompt.contains("Required parameters: path, content."));
    }

    @Test
    @DisplayName("Explicit agent config controls enabled tools")
    void explicitConfigControlsEnabledTools() {
        Config config = new Config(
            Config.DEFAULT_API_URL,
            Config.DEFAULT_MODEL,
            "",
            Config.DEFAULT_MAX_TOKENS,
            Config.DEFAULT_TEMPERATURE,
            Config.DEFAULT_MAX_ITERATIONS,
            Config.DEFAULT_WORKSPACE_DIR,
            Set.of("run_shell")
        );

        CodingAgent agent = new CodingAgent(config, AgentObserver.noop());
        String toolDescriptions = agent.getToolDescriptions();

        assertTrue(toolDescriptions.contains("run_shell"));
        assertFalse(toolDescriptions.contains("read_file"));
        assertFalse(toolDescriptions.contains("write_file"));
        assertFalse(toolDescriptions.contains("list_files"));
    }

    @Test
    @DisplayName("setConversation injects current tool specs")
    void setConversationInjectsToolSpecs() {
        CodingAgent agent = new CodingAgent(Config.defaults(), AgentObserver.noop());
        Context restored = new Context();

        agent.setConversation(restored);

        String prompt = restored.buildSystemPrompt();
        assertTrue(prompt.contains("read_file"));
        assertTrue(restored.getToolDescriptions().contains("\"read_file\""));
    }
}
