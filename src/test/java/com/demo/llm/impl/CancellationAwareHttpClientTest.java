package com.demo.llm.impl;

import com.demo.execution.CancellationSource;
import com.demo.execution.OperationCancelledException;
import com.demo.model.Message;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CancellationAwareHttpClientTest {
    @Test void tokenCancelsRawTransportFutureNotOnlySdkMappingFuture() {
        CancellationSource source = new CancellationSource();
        FakeTransport transport = new FakeTransport();
        var view = new CancellationAwareHttpClient(transport, source.token());
        var response = view.executeAsync(null, RequestOptions.none());
        source.cancel();
        assertTrue(transport.request.isCancelled());
        assertTrue(response.isDone());
        view.close();
        assertEquals(0, transport.closed.get());
    }

    @Test void lateResponseAndResponseBodyAreClosedWithoutClosingSharedTransport() {
        CancellationSource source = new CancellationSource();
        FakeTransport transport = new FakeTransport();
        var response = new CancellationAwareHttpClient(transport, source.token()).executeAsync(null, RequestOptions.none());
        AtomicInteger closed = new AtomicInteger();
        transport.request.complete(response(closed));
        assertTrue(response.isDone());
        source.cancel();
        assertTrue(closed.get() > 0);
        assertEquals(0, transport.closed.get());

        source = new CancellationSource();
        transport = new FakeTransport();
        transport.request = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) { return false; } // Simulate an uncooperative late transport.
        };
        var late = new CancellationAwareHttpClient(transport, source.token()).executeAsync(null, RequestOptions.none());
        source.cancel();
        AtomicInteger lateClosed = new AtomicInteger();
        transport.request.complete(response(lateClosed));
        assertTrue(late.isCancelled());
        assertTrue(lateClosed.get() > 0);
    }

    @Test void sdkCancellationWorksBeforeHeadersAndDuringBodyReadAndClientCanBeReused() throws Exception {
        for (boolean sendHeaders : List.of(false, true)) {
            CountDownLatch arrived = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var handlers = Executors.newCachedThreadPool();
            var worker = Executors.newSingleThreadExecutor();
            server.setExecutor(handlers);
            byte[] body = ("{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test\","
                    + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                boolean first = calls.incrementAndGet() == 2; // First request warms SDK/class loading before timing cancellation.
                try {
                    if (!first || sendHeaders) exchange.sendResponseHeaders(200, body.length);
                    if (first) { arrived.countDown(); release.await(15, TimeUnit.SECONDS); }
                    if (first && !sendHeaders) exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (java.io.IOException ignored) { /* Client cancellation closes the socket. */ }
                finally { exchange.close(); }
            });
            server.start();
            try (var client = new OpenAICompatibleLLMClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-key", "test", 100, 0)) {
                assertEquals("done",client.sendMessage(List.of(Message.user("warmup")),null).getText());
                CancellationSource source = new CancellationSource();
                var request = worker.submit(() -> client.sendMessage(List.of(Message.user("work")), null, source.token()));
                assertTrue(arrived.await(10, TimeUnit.SECONDS), () -> "Local HTTP fixture did not receive the request; completed=" + request.isDone() + "; calls=" + calls.get());
                source.cancel();
                var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> request.get(3, TimeUnit.SECONDS));
                assertInstanceOf(OperationCancelledException.class, error.getCause());
                assertEquals("done", client.sendMessage(List.of(Message.user("next")), null).getText());
                assertEquals(3, calls.get());
                CancellationSource alreadyCancelled = new CancellationSource();
                alreadyCancelled.cancel();
                assertThrows(OperationCancelledException.class,
                        () -> client.sendMessage(List.of(Message.user("not sent")), null, alreadyCancelled.token()));
                assertEquals(3, calls.get());
            } finally {
                release.countDown(); server.stop(0); handlers.shutdownNow(); worker.shutdownNow();
            }
        }
    }

    private HttpResponse response(AtomicInteger closed) {
        return new HttpResponse() {
            public int statusCode() { return 200; }
            public Headers headers() { return Headers.builder().build(); }
            public InputStream body() { return InputStream.nullInputStream(); }
            public void close() { closed.incrementAndGet(); }
        };
    }
    private static class FakeTransport implements HttpClient {
        CompletableFuture<HttpResponse> request = new CompletableFuture<>();
        AtomicInteger closed = new AtomicInteger();
        public HttpResponse execute(HttpRequest request, RequestOptions options) { throw new UnsupportedOperationException(); }
        public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) { return this.request; }
        public void close() { closed.incrementAndGet(); }
    }
}
