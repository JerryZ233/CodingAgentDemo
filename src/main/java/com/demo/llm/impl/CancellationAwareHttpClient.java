package com.demo.llm.impl;

import com.demo.execution.CancellationToken;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Request-scoped view. Closing it never closes the shared SDK transport. */
final class CancellationAwareHttpClient implements HttpClient {
    private final HttpClient transport;
    private final CancellationToken token;
    private final AtomicReference<CompletableFuture<HttpResponse>> active = new AtomicReference<>();
    private final AtomicReference<HttpResponse> activeResponse = new AtomicReference<>();
    private volatile boolean cancelled;
    private final ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
    private boolean errorBodyExceeded;

    synchronized Optional<JsonObject> errorBody() {
        if (errorBodyExceeded || errorBytes.size() == 0) return Optional.empty();
        try {
            var body = JsonParser.parseString(errorBytes.toString(StandardCharsets.UTF_8));
            return body.isJsonObject() ? Optional.of(body.getAsJsonObject()) : Optional.empty();
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }

    private synchronized void capture(byte[] bytes, int offset, int length) {
        if (errorBodyExceeded) return;
        if (length > 65536 - errorBytes.size()) {
            errorBodyExceeded = true;
            errorBytes.reset();
        } else errorBytes.write(bytes, offset, length);
    }

    void cancel() {
        cancelled = true;
        var network = active.get();
        if (network != null) network.cancel(true);
        var response = activeResponse.getAndSet(null);
        if (response != null) response.close();
    }

    CancellationAwareHttpClient(HttpClient transport, CancellationToken token) {
        this.transport = transport;
        this.token = token;
    }

    @Override public HttpResponse execute(HttpRequest request, RequestOptions options) {
        return executeAsync(request, options).join();
    }

    @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
        AtomicReference<CompletableFuture<HttpResponse>> started = new AtomicReference<>();
        token.runIfActive(() -> started.set(transport.executeAsync(request, options)));
        CompletableFuture<HttpResponse> network = started.get();
        active.set(network);
        if (cancelled) network.cancel(true);
        CompletableFuture<HttpResponse> result = new CompletableFuture<>();
        CancellationToken.Registration registration = token.onCancellation(() -> {
            network.cancel(true); // The SDK's raw transport future cancels the actual HTTP call.
            result.cancel(true);
        });
        network.whenComplete((response, failure) -> {
            registration.close();
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            BoundResponse bound = new BoundResponse(response, token);
            activeResponse.set(bound);
            if (cancelled || token.isCancellationRequested() || !result.complete(bound)) {
                bound.close();
                result.cancel(true);
            }
        });
        return result;
    }

    @Override public void close() { }

    private final class BoundResponse implements HttpResponse {
        private final HttpResponse delegate;
        private final CancellationToken.Registration registration;
        private InputStream body;

        BoundResponse(HttpResponse delegate, CancellationToken token) {
            this.delegate = delegate;
            // Keep this registration until the SDK has finished parsing/closing the response body.
            registration = token.onCancellation(delegate::close);
        }
        public int statusCode() { return delegate.statusCode(); }
        public Headers headers() { return delegate.headers(); }
        public synchronized InputStream body() {
            if (body != null) return body;
            InputStream original = delegate.body();
            body = delegate.statusCode() >= 400 ? new FilterInputStream(original) {
                @Override public int read() throws IOException {
                    int value = in.read();
                    if (value >= 0) capture(new byte[] {(byte) value}, 0, 1);
                    return value;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = in.read(bytes, offset, length);
                    if (count > 0) capture(bytes, offset, count);
                    return count;
                }
            } : original;
            return body;
        }
        public void close() {
            registration.close();
            delegate.close();
        }
    }
}
