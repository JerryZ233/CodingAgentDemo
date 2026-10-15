package com.demo.llm.impl;

import com.demo.execution.CancellationToken;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Request-scoped view. Closing it never closes the shared SDK transport. */
final class CancellationAwareHttpClient implements HttpClient {
    private final HttpClient transport;
    private final CancellationToken token;

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
            if (token.isCancellationRequested() || !result.complete(bound)) {
                bound.close();
                result.cancel(true);
            }
        });
        return result;
    }

    @Override public void close() { }

    private static final class BoundResponse implements HttpResponse {
        private final HttpResponse delegate;
        private final CancellationToken.Registration registration;

        BoundResponse(HttpResponse delegate, CancellationToken token) {
            this.delegate = delegate;
            // Keep this registration until the SDK has finished parsing/closing the response body.
            registration = token.onCancellation(delegate::close);
        }
        public int statusCode() { return delegate.statusCode(); }
        public Headers headers() { return delegate.headers(); }
        public InputStream body() { return delegate.body(); }
        public void close() {
            registration.close();
            delegate.close();
        }
    }
}
