package com.demo.llm.impl;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

/** OpenAI-compatible Chat Completions client; tool execution stays in AgentLoop. */
public class OpenAICompatibleLLMClient implements LLMClient {
    private final OpenAIClient client;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final OpenAIChatAdapter chatAdapter = new OpenAIChatAdapter();
    private final OpenAIResponseMapper responseMapper = new OpenAIResponseMapper();

    public OpenAICompatibleLLMClient() {
        this(Config.getInstance());
    }

    public OpenAICompatibleLLMClient(Config config) {
        this(config.getApiUrl(), config.getApiKey(), config.getModel(),
                config.getMaxTokens(), config.getTemperature());
    }

    public OpenAICompatibleLLMClient(String apiUrl, String apiKey, String model, int maxTokens, double temperature) {
        this(OpenAIOkHttpClient.builder()
                .baseUrl(toBaseUrl(apiUrl))
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(120))
                .maxRetries(0)
                .build(), model, maxTokens, temperature);
    }

    // Tests may inject a client with a shorter timeout.
    OpenAICompatibleLLMClient(OpenAIClient client, String model, int maxTokens, double temperature) {
        this.client = client;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    @Override
    public LLMResponse sendMessage(List<Message> messages, String toolsDescription) {
        try {
            var params = chatAdapter.buildRequest(messages, toolsDescription, model, maxTokens, temperature);
            return responseMapper.map(client.chat().completions().create(params));
        } catch (OpenAIServiceException e) {
            return LLMResponse.error("Failed to get response from LLM: HTTP " + e.statusCode());
        } catch (OpenAIException e) {
            // SDK exception messages can include response bodies; keep them out of the transcript.
            return LLMResponse.error("Failed to communicate with LLM or decode its response");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return LLMResponse.error("Invalid LLM request or response data");
        }
    }

    /** Accepts legacy complete endpoints as well as SDK base URLs. */
    static String toBaseUrl(String apiUrl) {
        try {
            URI uri = URI.create(apiUrl);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            String path = uri.getRawPath();
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String suffix = "/chat/completions";
            if (path.endsWith(suffix)) {
                path = path.substring(0, path.length() - suffix.length());
            }
            return uri.getScheme() + "://" + uri.getRawAuthority() + path;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "Invalid llm.api_url: expected an HTTP(S) endpoint or base URL without credentials, query or fragment");
        }
    }
}
