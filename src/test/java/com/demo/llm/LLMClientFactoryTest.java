package com.demo.llm;

import com.demo.agent.AgentObserver;
import com.demo.config.Config;
import com.demo.llm.impl.DummyLLMClientImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMClientFactoryTest {

    @Test
    @DisplayName("Factory falls back to dummy client without API key")
    void factoryFallsBackToDummyClientWithoutApiKey() {
        CapturingObserver observer = new CapturingObserver();

        LLMClient client = LLMClientFactory.create(Config.defaults(), observer);

        assertInstanceOf(DummyLLMClientImpl.class, client);
        assertTrue(observer.messages.stream()
                .anyMatch(message -> message.contains("API key not configured")));
        assertTrue(observer.messages.stream()
                .anyMatch(message -> message.contains("LLM_API_KEY")));
    }

    private static final class CapturingObserver implements AgentObserver {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void onLlmClientSelected(String message) {
            messages.add(message);
        }
    }
}
