package com.demo.agent;

import com.demo.config.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingAgentTest {

    @Test
    void injectedAgentRunsOnExternalContextWithoutAddingOrResettingUserHistory() {
        com.demo.llm.LLMClient model = (messages, tools) -> {
            org.junit.jupiter.api.Assertions.assertEquals(2,
                    messages.stream().filter(message -> "user".equals(message.getRole())).count());
            return new com.demo.llm.LLMClient.LLMResponse("done", null);
        };
        CodingAgent agent = new CodingAgent(model, com.demo.tools.ToolRegistry.fromConfig(Config.defaults()), 3);
        Context context = Context.fromMessages(java.util.List.of(
                com.demo.model.Message.user("first"), com.demo.model.Message.assistant("previous answer"),
                com.demo.model.Message.user("second")));
        var checkpoints = new java.util.ArrayList<Integer>();
        assertTrue(agent.runTurn(context, (state, iteration) -> checkpoints.add(iteration)).isCompleted());
        org.junit.jupiter.api.Assertions.assertEquals(4, context.getMessages().size());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(1), checkpoints);
        assertTrue(context.getToolDescriptions().contains("read_file"));
    }

    @Test
    @DisplayName("Default configuration exposes file tools but not shell")
    void defaultToolsExcludeShell() {
        CodingAgent agent = agent(Config.defaults());
        String toolDescriptions = agent.getToolDescriptions();

        assertTrue(toolDescriptions.contains("read_file"));
        assertTrue(toolDescriptions.contains("write_file"));
        assertTrue(toolDescriptions.contains("list_files"));
        assertFalse(toolDescriptions.contains("run_shell"));
    }

    @Test
    @DisplayName("Tool descriptions include argument schemas")
    void toolDescriptionsIncludeSchemas() {
        CodingAgent agent = agent(Config.defaults());
        String toolDescriptions = agent.getToolDescriptions();

        assertTrue(toolDescriptions.contains("\"required\":[\"path\"]"));
        assertTrue(toolDescriptions.contains("\"content\""));
    }

    @Test
    @DisplayName("Conversation prompt includes required parameters from tool specs")
    void conversationPromptIncludesRequiredParametersFromToolSpecs() {
        CodingAgent agent = agent(Config.defaults());
        Context external = new Context();
        external.addUserMessage("inspect tools");
        agent.runTurn(external, AgentCheckpoint.noop());
        String prompt = external.buildSystemPrompt();

        assertTrue(prompt.contains("read_file"));
        assertTrue(prompt.contains("Required parameters: path."));
        assertTrue(prompt.contains("Required parameters: text."));
        assertTrue(prompt.contains("mode=replace"));
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

        CodingAgent agent = agent(config);
        String toolDescriptions = agent.getToolDescriptions();

        assertTrue(toolDescriptions.contains("run_shell"));
        assertFalse(toolDescriptions.contains("read_file"));
        assertFalse(toolDescriptions.contains("write_file"));
        assertFalse(toolDescriptions.contains("list_files"));
    }

    private CodingAgent agent(Config config) {
        return new CodingAgent((messages, tools) -> new com.demo.llm.LLMClient.LLMResponse("done", null),
                com.demo.tools.ToolRegistry.fromConfig(config), config.getMaxIterations());
    }
}
