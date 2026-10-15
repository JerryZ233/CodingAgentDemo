package com.demo.llm.impl;

import com.demo.config.Config;
import com.demo.execution.CancellationToken;
import com.demo.execution.OperationCancelledException;
import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.http.HttpClient;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** OpenAI-compatible Chat Completions client; tool execution stays in AgentLoop. */
public class OpenAICompatibleLLMClient implements LLMClient {
    private final OpenAIClient client;
    private final HttpClient transport;
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
        this(apiUrl, apiKey, model, maxTokens, temperature, Duration.ofSeconds(120));
    }

    OpenAICompatibleLLMClient(String apiUrl, String apiKey, String model, int maxTokens, double temperature, Duration timeout) {
        String baseUrl = toBaseUrl(apiUrl);
        this.transport = new com.openai.client.okhttp.OkHttpClient.Builder().timeout(timeout).build();
        this.client = new OpenAIClientImpl(ClientOptions.builder().httpClient(transport)
                .baseUrl(baseUrl).apiKey(apiKey).timeout(timeout).maxRetries(0).build());
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    @Override
    public void close() {
        client.close();
    }

    @Override
    public LLMResponse sendMessage(List<Message> messages, String toolsDescription) {
        return sendMessage(messages, toolsDescription, CancellationToken.none());
    }

    @Override
    public LLMResponse sendMessage(List<Message> messages, String toolsDescription, CancellationToken token) {
        token.throwIfCancellationRequested();
        try {
            var params = chatAdapter.buildRequest(messages, toolsDescription, model, maxTokens, temperature);
            var scoped = client.withOptions(options -> options.httpClient(new CancellationAwareHttpClient(transport, token)));
            CompletableFuture<com.openai.models.chat.completions.ChatCompletion> response = scoped.async().chat().completions().create(params);
            try (var registration = token.onCancellation(() -> response.cancel(true))) {
                var completion = response.join();
                token.throwIfCancellationRequested();
                return responseMapper.map(completion);
            } catch (CompletionException e) {
                token.throwIfCancellationRequested();
                if (e.getCause() instanceof RuntimeException cause) throw cause;
                return LLMResponse.error("Failed to communicate with LLM or decode its response");
            } catch (CancellationException e) {
                token.throwIfCancellationRequested();
                return LLMResponse.error("Model request was interrupted");
            }
        } catch (OperationCancelledException e) {
            throw e;
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
