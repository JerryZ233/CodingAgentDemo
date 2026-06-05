package com.demo.llm;

import com.demo.agent.AgentObserver;
import com.demo.config.Config;
import com.demo.llm.impl.DummyLLMClientImpl;
import com.demo.llm.impl.LLMClientImpl;
import java.util.Objects;

/**
 * Creates the configured LLM client while keeping selection policy out of the agent.
 */
public final class LLMClientFactory {

    private LLMClientFactory() {
    }

    public static LLMClient create(Config config, AgentObserver observer) {
        Objects.requireNonNull(config, "config");
        AgentObserver selectedObserver = Objects.requireNonNull(observer, "observer");

        if (config.isConfigured()) {
            selectedObserver.onLlmClientSelected("Using LLM client with model: " + config.getModel());
            return new LLMClientImpl(config);
        }

        selectedObserver.onLlmClientSelected("API key not configured. Using Dummy client.");
        selectedObserver.onLlmClientSelected(
                "To use real LLM, set LLM_API_KEY environment variable or configure in config.yaml");
        return new DummyLLMClientImpl();
    }
}
