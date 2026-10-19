package com.demo.llm;

import com.demo.agent.AgentObserver;
import com.demo.config.Config;
import com.demo.llm.impl.DummyLLMClientImpl;
import com.demo.llm.impl.OpenAICompatibleLLMClient;
import java.util.Objects;

/**
 * Creates the configured LLM client while keeping selection policy out of the agent.
 */
public final class LLMClientFactory {

    private LLMClientFactory() {
    }

    public static LLMClient create(Config config, AgentObserver observer) {
        Objects.requireNonNull(config, "config");
        com.demo.logging.LogData.registerSecret(config.getApiKey());
        var log = com.demo.logging.Logging.logger(LLMClientFactory.class);
        AgentObserver selectedObserver = Objects.requireNonNull(observer, "observer");

        if (config.isConfigured()) {
            log.info("Model client selected model={}", com.demo.logging.LogData.quote(config.getModel()));
            selectedObserver.onLlmClientSelected("Using LLM client with model: " + config.getModel());
            return new OpenAICompatibleLLMClient(config);
        }

        log.info("Dummy client selected; no model requests or file operations will be performed");
        selectedObserver.onLlmClientSelected("API key not configured. Using Dummy client.");
        selectedObserver.onLlmClientSelected(
                "To use real LLM, set LLM_API_KEY environment variable or configure in config.yaml");
        return new DummyLLMClientImpl();
    }
}
