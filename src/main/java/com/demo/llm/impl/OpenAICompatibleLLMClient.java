package com.demo.llm.impl;

import com.demo.config.Config;
import com.demo.execution.CancellationToken;
import com.demo.execution.OperationCancelledException;
import com.demo.llm.LLMClient;
import com.demo.llm.LLMError;
import com.demo.llm.LLMFailure;
import com.demo.llm.RetryPolicy;
import com.demo.model.Message;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.http.HttpClient;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import java.net.URI;
import java.time.Duration;
import java.time.Clock;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.concurrent.ExecutionException;
import java.util.List;
import java.util.concurrent.CancellationException;
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
    private final RetryPolicy retryPolicy;
    private final ProviderErrorAdapter errors;
    private final RetryWaiter waiter;
    private final DoubleSupplier random;

    public OpenAICompatibleLLMClient() {
        this(Config.getInstance());
    }

    public OpenAICompatibleLLMClient(Config config) {
        this(config.getApiUrl(), config.getApiKey(), config.getModel(),
                config.getMaxTokens(), config.getTemperature(), Duration.ofSeconds(120),
                LLMProvider.parse(config.getProvider()), config.getRetryPolicy(), config.isErrorDetails(),
                Clock.systemUTC(), Math::random, RetryWaiter.cancellable());
    }

    public OpenAICompatibleLLMClient(String apiUrl, String apiKey, String model, int maxTokens, double temperature) {
        this(apiUrl, apiKey, model, maxTokens, temperature, Duration.ofSeconds(120));
    }

    OpenAICompatibleLLMClient(String apiUrl, String apiKey, String model, int maxTokens, double temperature, Duration timeout) {
        this(apiUrl, apiKey, model, maxTokens, temperature, timeout, LLMProvider.AUTO, RetryPolicy.DEFAULT,
                false, Clock.systemUTC(), Math::random, RetryWaiter.cancellable());
    }

    OpenAICompatibleLLMClient(String apiUrl, String apiKey, String model, int maxTokens, double temperature,
            Duration timeout, LLMProvider provider, RetryPolicy retryPolicy, boolean details,
            Clock clock, DoubleSupplier random, RetryWaiter waiter) {
        String baseUrl = toBaseUrl(apiUrl);
        this.transport = new com.openai.client.okhttp.OkHttpClient.Builder().timeout(timeout).build();
        this.client = new OpenAIClientImpl(ClientOptions.builder().httpClient(transport)
                .baseUrl(baseUrl).apiKey(apiKey).timeout(timeout).maxRetries(0).build());
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.retryPolicy = retryPolicy;
        this.errors = new ProviderErrorAdapter(provider.resolve(apiUrl), apiKey, details, clock);
        this.random = random;
        this.waiter = waiter;
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
        return sendMessage(messages, toolsDescription, token, ignored -> { });
    }

    @Override
    public LLMResponse sendMessage(List<Message> messages, String toolsDescription, CancellationToken token,
                                  Consumer<LLMFailure> onRetry) {
        checkActive(token);
        final com.openai.models.chat.completions.ChatCompletionCreateParams params;
        try {
            params = chatAdapter.buildRequest(messages, toolsDescription, model, maxTokens, temperature);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return failure(errors.local(invalid, LLMError.Source.REQUEST), 1, LLMFailure.State.NON_RETRYABLE);
        }
        int maxAttempts = retryPolicy.maxRetries() + 1;
        for (int attempt = 1; ; attempt++) {
            checkActive(token);
            LLMError error;
            var scopedTransport = new CancellationAwareHttpClient(transport, token);
            try {
                var scoped = client.withOptions(options -> options.httpClient(scopedTransport));
                var response = scoped.async().chat().completions().create(params);
                try (var registration = token.onCancellation(() -> response.cancel(true))) {
                    var completion = response.get();
                    checkActive(token);
                    var mapped = responseMapper.map(completion);
                    if (!mapped.isError()) return mapped;
                    error = errors.local(new IllegalStateException(), LLMError.Source.RESPONSE);
                } catch (InterruptedException interrupted) {
                    scopedTransport.cancel();
                    response.cancel(true);
                    Thread.currentThread().interrupt();
                    throw new OperationCancelledException();
                } catch (ExecutionException e) {
                    checkActive(token);
                    Throwable cause = e.getCause();
                    while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    error = errors.local(cause, LLMError.Source.RESPONSE);
                }
            } catch (OperationCancelledException cancelled) {
                throw cancelled;
            } catch (CancellationException cancelled) {
                scopedTransport.cancel();
                throw new OperationCancelledException();
            } catch (OpenAIServiceException service) {
                checkActive(token);
                error = errors.service(service, scopedTransport.errorBody());
            } catch (OpenAIException | IllegalArgumentException | IllegalStateException invalid) {
                checkActive(token);
                error = errors.local(invalid, LLMError.Source.RESPONSE);
            }
            checkActive(token);
            if (!error.retryable()) return failure(error, attempt, LLMFailure.State.NON_RETRYABLE);
            if (error.serverWaitMillis() > 120000) return failure(error, attempt, LLMFailure.State.SERVER_WAIT_LIMIT);
            if (attempt >= maxAttempts) return failure(error, attempt, LLMFailure.State.RETRIES_EXHAUSTED);
            long delay = Math.max(error.serverWaitMillis(), retryPolicy.delayMillis(attempt - 1, random.getAsDouble()));
            var progress = new LLMFailure(error, attempt, maxAttempts, LLMFailure.State.RETRY_SCHEDULED, delay);
            token.runIfActive(() -> onRetry.accept(progress));
            checkActive(token);
            waiter.await(delay, token);
            checkActive(token);
        }
    }

    private LLMResponse failure(LLMError error, int attempt, LLMFailure.State state) {
        return LLMResponse.error(new LLMFailure(error, attempt, retryPolicy.maxRetries() + 1, state, null));
    }

    private static void checkActive(CancellationToken token) {
        token.throwIfCancellationRequested();
        if (Thread.currentThread().isInterrupted()) throw new OperationCancelledException();
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
