package com.demo.llm.impl;

import com.demo.agent.AgentLoop;
import com.demo.agent.Context;
import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.llm.RetryPolicy;
import com.demo.llm.LLMFailure;
import com.demo.execution.CancellationSource;
import com.demo.execution.CancellationToken;
import com.demo.execution.OperationCancelledException;
import com.demo.model.Message;
import com.demo.model.ToolResult;
import com.demo.tools.Tool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OpenAICompatibleLLMClientTest {
    private HttpServer server;
    private ExecutorService executor;
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final BlockingQueue<CapturedRequest> requests = new LinkedBlockingQueue<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private String origin;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            requests.add(new CapturedRequest(exchange.getRequestURI().getRawPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestMethod(),
                    JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8)).getAsJsonObject()));
            Reply reply = replies.poll();
            if (reply == null) {
                reply = new Reply(500, "{\"error\":{\"message\":\"unexpected retry\"}}", 0);
            }
            try {
                if (reply.delayMillis() > 0) Thread.sleep(reply.delayMillis());
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void disablesDeepSeekThinkingOnTheWire() throws Exception {
        replies.add(ok("{\"role\":\"assistant\",\"content\":\"hello\"}"));
        try (var client = new OpenAICompatibleLLMClient(origin + "/v1", "test-key", "deepseek-flash",
                128, 0.25, Duration.ofSeconds(5), LLMProvider.DEEPSEEK,
                new RetryPolicy(0, 1, 1), false, Clock.systemUTC(), () -> 0.5, RetryWaiter.cancellable())) {
            assertFalse(client.sendMessage(List.of(Message.user("hi")), OpenAIChatAdapterTest.TOOLS).isError());
            var body = takeRequest().body();
            assertEquals("deepseek-flash", body.get("model").getAsString());
            assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
        }
    }

    @Test
    void sendsConfiguredRequestAndReceivesText() throws Exception {
        replies.add(ok("{\"role\":\"assistant\",\"content\":\"hello\"}"));
        var response = client("/v1/chat/completions").sendMessage(
                List.of(Message.system("system"), Message.user("hi")), OpenAIChatAdapterTest.TOOLS);
        assertFalse(response.isError(), response.getText());
        assertEquals("hello", response.getText());
        assertFalse(response.hasToolCalls());
        var captured = takeRequest();
        assertFalse(captured.body().has("thinking"));
        assertEquals("/v1/chat/completions", captured.path());
        assertEquals("POST", captured.method());
        assertEquals("Bearer test-key", captured.authorization());
        assertEquals("custom-model", captured.body().get("model").getAsString());
        assertEquals(128, captured.body().get("max_tokens").getAsInt());
        assertEquals(0.25, captured.body().get("temperature").getAsDouble());
        assertEquals("system", captured.body().getAsJsonArray("messages").get(0)
                .getAsJsonObject().get("role").getAsString());
        var parameters = captured.body().getAsJsonArray("tools").get(0).getAsJsonObject()
                .getAsJsonObject("function").getAsJsonObject("parameters");
        assertEquals("string", parameters.getAsJsonObject("properties").getAsJsonObject("path")
                .get("type").getAsString());
        assertEquals("path", parameters.getAsJsonArray("required").get(0).getAsString());
    }

    @Test
    void acceptsFullEndpointsAndBaseUrlsWithCustomPrefixes() throws Exception {
        for (String suffix : List.of("/v1", "/v1/", "/custom/api/v3",
                "/custom/api/v3/chat/completions", "/custom/api/v3/chat/completions/", "")) {
            replies.add(ok("{\"role\":\"assistant\",\"content\":\"ok\"}"));
            assertFalse(client(suffix).sendMessage(List.of(Message.user("hi")), "[]").isError());
            var captured = takeRequest();
            assertEquals(suffix.startsWith("/custom") ? "/custom/api/v3/chat/completions"
                    : suffix.isEmpty() ? "/chat/completions" : "/v1/chat/completions", captured.path());
            assertFalse(captured.body().has("tools"));
        }
    }

    @Test
    void normalizesDefaultAndEncodedEndpointsWithoutChangingConfig() {
        assertEquals("https://api.openai.com/v1", OpenAICompatibleLLMClient.toBaseUrl(Config.DEFAULT_API_URL));
        assertEquals("https://example.com/prefix%20name/v1",
                OpenAICompatibleLLMClient.toBaseUrl("https://example.com/prefix%20name/v1/chat/completions/"));
        assertEquals("https://example.com", OpenAICompatibleLLMClient.toBaseUrl("https://example.com/"));
    }

    @Test
    void rejectsInvalidEndpointConfiguration() {
        for (String url : new String[] {null, "", "relative/path", "file:///tmp/model",
                "https://example.com/v1?key=x", "https://example.com/v1#fragment",
                "https://user:pass@example.com/v1"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> OpenAICompatibleLLMClient.toBaseUrl(url));
            assertTrue(error.getMessage().contains("llm.api_url"));
        }
    }

    @Test
    void preservesMultipleCallsAndNullableText() {
        for (String content : List.of("null", "\"using tools\"")) {
            replies.add(ok("{\"role\":\"assistant\",\"content\":" + content + ",\"tool_calls\":["
                    + functionCall("call_1", "read_file") + "," + functionCall("call_2", "list_files") + "]}"));
            var response = client("/v1").sendMessage(List.of(Message.user("work")), "[]");
            assertFalse(response.isError(), response.getText());
            assertEquals(content.equals("null") ? "" : "using tools", response.getText());
            assertEquals(2, response.getToolCalls().size());
            assertEquals("call_1", response.getToolCalls().get(0).getId());
            assertEquals("read_file", response.getToolCalls().get(0).getToolName());
            assertEquals("{\"path\":\"README.md\"}", response.getToolCalls().get(0).getArguments());
        }
    }

    @Test
    void agentLoopSendsToolResultBackToSdk() throws Exception {
        replies.add(ok("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                + functionCall("call_read", "read_file") + "]}"));
        replies.add(ok("{\"role\":\"assistant\",\"content\":\"done\"}"));
        Tool tool = new Tool() {
            public String getName() { return "read_file"; }
            public String getDescription() { return "fixture"; }
            public ToolResult execute(String args) { return ToolResult.success(getName(), "fixture content"); }
        };
        Context context = new Context();
        context.setToolDescriptions(OpenAIChatAdapterTest.TOOLS);
        context.addUserMessage("read fixture");
        var result = new AgentLoop(client("/v1"), Map.of("read_file", tool)).run(context);
        assertTrue(result.isCompleted());
        assertEquals("done", result.getMessage());
        takeRequest();
        var messages = takeRequest().body().getAsJsonArray("messages");
        var assistant = messages.get(messages.size() - 2).getAsJsonObject();
        assertEquals("call_read", assistant.getAsJsonArray("tool_calls").get(0)
                .getAsJsonObject().get("id").getAsString());
        var feedback = messages.get(messages.size() - 1).getAsJsonObject();
        assertEquals("tool", feedback.get("role").getAsString());
        assertEquals("call_read", feedback.get("tool_call_id").getAsString());
        assertEquals("fixture content", feedback.get("content").getAsString());
    }

    @Test
    void httpFailuresBecomeErrorsWithoutRetriesOrRawBody() {
        for (int status : new int[] {401, 429, 500}) {
            replies.add(new Reply(status, "{\"error\":{\"message\":\"private-response-body\"}}", 0));
            var response = client("/v1").sendMessage(List.of(Message.user("hi")), null);
            assertTrue(response.isError());
            assertTrue(response.getText().contains("http_status: " + status));
            assertFalse(response.getText().contains("private-response-body"));
        }
        assertEquals(3, requestCount.get());
    }

    @Test
    void invalidResponsesBecomeErrorsWithoutConsoleOutput() {
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream sink = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(sink);
            System.setErr(sink);
            for (String body : List.of("not json", "", "{\"choices\":[]}",
                    "{\"choices\":[{}]}",
                    completion("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"bad\",\"type\":\"function\"}]}"),
                    completion("{\"role\":\"assistant\",\"tool_calls\":[" + functionCall("", "read_file") + "]}"),
                    completion("{\"role\":\"assistant\",\"tool_calls\":[" + functionCall("id", "") + "]}"))) {
                replies.add(new Reply(200, body, 0));
                var response = client("/v1").sendMessage(List.of(Message.user("hi")), null);
                assertTrue(response.isError(), "Expected error for " + body);
                assertFalse(response.hasToolCalls());
            }
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertEquals(7, requestCount.get());
    }

    @Test
    void connectionFailureBecomesError() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        try (var model = configuredClient("http://127.0.0.1:" + port + "/v1", new RetryPolicy(0, 1, 1),
                RetryWaiter.cancellable())) {
            assertTrue(model.sendMessage(List.of(Message.user("hi")), null).isError());
        }
    }

    @Test
    void timeoutBecomesErrorWithoutRetry() {
        replies.add(new Reply(200, completion("{\"role\":\"assistant\",\"content\":\"late\"}"), 2000));
        try (var client = configuredClient(origin + "/v1", new RetryPolicy(0, 1, 1), RetryWaiter.cancellable())) {
            var response = client
                    .sendMessage(List.of(Message.user("hi")), null);
            assertTrue(response.isError());
        }
        assertEquals(1, requestCount.get());
    }

    private OpenAICompatibleLLMClient client(String path) {
        return configuredClient(origin + path, new RetryPolicy(0, 1, 1), RetryWaiter.cancellable());
    }

    @Test void retriesTransientErrorsWithIdenticalRequestsAndBoundedAttempts() throws Exception {
        var logFile = com.demo.logging.Logging.file();
        int logStart = java.nio.file.Files.readString(logFile).length();
        List<Long> waits = new ArrayList<>();
        List<LLMFailure> progress = new ArrayList<>();
        for (int status : new int[] {429, 500, 503})
            replies.add(new Reply(status, "{\"error\":{\"code\":\"transient\"}}", 0));
        replies.add(ok("{\"role\":\"assistant\",\"content\":\"recovered\"}"));
        try (var model = configuredClient(origin + "/v1", RetryPolicy.DEFAULT, (delay, token) -> waits.add(delay))) {
            var response = model.sendMessage(List.of(Message.user("hi")), null, CancellationToken.none(), progress::add);
            assertEquals("recovered", response.getText());
        }
        assertEquals(List.of(2000L, 4000L, 8000L), waits);
        assertEquals(3, progress.size());
        assertEquals("\"transient\"", progress.get(0).error().fields().get("error.code"));
        var first = takeRequest().body();
        for (int i = 0; i < 3; i++) assertEquals(first, takeRequest().body());

        for (int i = 0; i < 6; i++) replies.add(new Reply(429, "{}", 0));
        try (var model = configuredClient(origin + "/v1", RetryPolicy.DEFAULT, (delay, token) -> { })) {
            var result = model.sendMessage(List.of(Message.user("hi")), null);
            assertEquals(LLMFailure.State.RETRIES_EXHAUSTED, result.getFailure().orElseThrow().state());
            assertEquals(6, result.getFailure().orElseThrow().attempt());
        }
        assertEquals(10, requestCount.get());
        String logged = java.nio.file.Files.readString(logFile).substring(logStart);
        assertEquals(8, logged.lines().filter(line -> line.contains("WARN") && line.contains("Model request retry scheduled")).count());
        assertEquals(1, logged.lines().filter(line -> line.contains("ERROR") && line.contains("Model request failed")).count());
        assertTrue(logged.contains("retry_in_ms=2000"));
        assertTrue(logged.contains("RETRIES_EXHAUSTED"));
        assertFalse(logged.contains("Bearer test-key"));
    }

    @Test void honorsRetryAfterAndStopsBeforeExcessiveServerDelay() throws Exception {
        var logFile = com.demo.logging.Logging.file();
        int logStart = java.nio.file.Files.readString(logFile).length();
        List<Long> waits = new ArrayList<>();
        replies.add(new Reply(429, "{}", 0, Map.of("Retry-After", "12", "X-Request-Id", "req_123")));
        replies.add(ok("{\"role\":\"assistant\",\"content\":\"done\"}"));
        try (var model = configuredClient(origin + "/v1", RetryPolicy.DEFAULT, (delay, token) -> waits.add(delay))) {
            assertFalse(model.sendMessage(List.of(Message.user("hi")), null).isError());
        }
        assertEquals(List.of(12000L), waits);
        replies.add(new Reply(429, "{}", 0, Map.of("Retry-After", "121")));
        try (var model = configuredClient(origin + "/v1", RetryPolicy.DEFAULT, (delay, token) -> fail("must not wait"))) {
            var response = model.sendMessage(List.of(Message.user("hi")), null);
            assertEquals(LLMFailure.State.SERVER_WAIT_LIMIT, response.getFailure().orElseThrow().state());
            assertTrue(response.getText().contains("121"));
        }
        assertEquals(3, requestCount.get());
        String logged = java.nio.file.Files.readString(logFile).substring(logStart);
        assertTrue(logged.contains("retry_in_ms=12000"));
        assertTrue(logged.contains("SERVER_WAIT_LIMIT"));
    }

    @Test void cancellationDuringBackoffDoesNotSendAnotherRequest() throws Exception {
        replies.add(new Reply(429, "{}", 0));
        CancellationSource cancellation = new CancellationSource();
        CountDownLatch waiting = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try (var model = configuredClient(origin + "/v1", RetryPolicy.DEFAULT, RetryWaiter.cancellable())) {
            var task = worker.submit(() -> model.sendMessage(List.of(Message.user("hi")), null,
                    cancellation.token(), failure -> waiting.countDown()));
            assertTrue(waiting.await(3, TimeUnit.SECONDS));
            cancellation.cancel();
            assertInstanceOf(OperationCancelledException.class,
                    assertThrows(ExecutionException.class, () -> task.get(2, TimeUnit.SECONDS)).getCause());
            assertEquals(1, requestCount.get());
        } finally { worker.shutdownNow(); }
    }

    @Test void retryAfterToolExecutionDoesNotReplayToolOrAdvanceIteration(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        verifyToolRetry(directory, true);
    }

    @Test void exhaustedRetryPreservesWrittenFileAndToolRecord(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        verifyToolRetry(directory, false);
    }

    private void verifyToolRetry(java.nio.file.Path directory, boolean recover) throws Exception {
        replies.add(ok("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" + functionCall("once", "write_file") + "]}"));
        for (int i = 0; i < (recover ? 1 : 6); i++) replies.add(new Reply(429, "{}", 0));
        if (recover) replies.add(ok("{\"role\":\"assistant\",\"content\":\"done\"}"));
        AtomicInteger executed = new AtomicInteger();
        Tool tool = new Tool() {
            public String getName() { return "write_file"; }
            public String getDescription() { return "test"; }
            public ToolResult execute(String args) {
                executed.incrementAndGet();
                try { java.nio.file.Files.writeString(directory.resolve("1.txt"), "35"); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                return ToolResult.success(getName(), "35");
            }
        };
        Context context = new Context();
        context.addUserMessage("write answer");
        context.setToolDescriptions(OpenAIChatAdapterTest.TOOLS);
        List<com.demo.agent.AgentEvent> events = new ArrayList<>();
        try (var model = configuredClient(origin + "/v1", RetryPolicy.DEFAULT, (delay, token) -> { })) {
            var result = new AgentLoop(model, Map.of("write_file", tool)).run(context,
                    com.demo.agent.AgentCheckpoint.noop(), CancellationToken.none(), events::add);
            assertEquals(recover, result.isCompleted());
            assertEquals(2, result.getIterations());
        }
        assertEquals(1, executed.get());
        assertEquals("35", java.nio.file.Files.readString(directory.resolve("1.txt")));
        assertEquals(recover ? 1 : 5, events.stream().filter(e -> e.getType() == com.demo.agent.AgentEvent.Type.LLM_RETRY).count());
        assertEquals(recover ? 0 : 1, context.getMessages().stream().filter(m -> m.getContent() != null && m.getContent().contains("[server]")).count());
        assertEquals(1, context.getMessages().stream().filter(m -> "tool".equals(m.getRole())).count());
    }

    @Test void providerSpecificSdkErrorBodyPreservesNumericCodeAndDetails() {
        replies.add(new Reply(429, "{\"error\":{\"code\":1113,\"message\":\"original message test-key\",\"param\":null},\"request_id\":\"body_req\"}", 0,
                Map.of("X-Request-ID", "header_req")));
        try (var model = new OpenAICompatibleLLMClient(origin + "/v1", "test-key", "model", 128, 0.25,
                Duration.ofSeconds(2), LLMProvider.ZHIPU, RetryPolicy.DEFAULT, true, Clock.systemUTC(), () -> 0,
                (delay, token) -> fail("permanent business code must not wait"))) {
            var result = model.sendMessage(List.of(Message.user("hi")), null);
            var failure = result.getFailure().orElseThrow();
            assertEquals(LLMFailure.State.NON_RETRYABLE, failure.state());
            assertEquals("1113", failure.error().fields().get("error.code"));
            assertEquals("null", failure.error().fields().get("error.param"));
            assertEquals("\"original message [redacted]\"", failure.error().fields().get("error.message"));
            assertEquals("\"body_req\"", failure.error().fields().get("request_id"));
            assertEquals("\"header_req\"", failure.error().fields().get("headers.x-request-id"));
        }
        assertEquals(1, requestCount.get());
    }

    @Test void timeoutCanRecoverWithoutWaitingInTest() {
        replies.add(new Reply(200, completion("{\"role\":\"assistant\",\"content\":\"late\"}"), 1200));
        replies.add(ok("{\"role\":\"assistant\",\"content\":\"recovered\"}"));
        try (var model = configuredClient(origin + "/v1", RetryPolicy.DEFAULT, (delay, token) -> { })) {
            assertEquals("recovered", model.sendMessage(List.of(Message.user("hi")), null).getText());
        }
        assertEquals(2, requestCount.get());
    }

    private OpenAICompatibleLLMClient configuredClient(String url, RetryPolicy policy, RetryWaiter waiter) {
        return new OpenAICompatibleLLMClient(url, "test-key", "custom-model", 128, 0.25, Duration.ofMillis(500),
                LLMProvider.GENERIC, policy, false, Clock.systemUTC(), () -> 0, waiter);
    }

    private CapturedRequest takeRequest() throws InterruptedException {
        var request = requests.poll(5, TimeUnit.SECONDS);
        assertNotNull(request, "SDK did not reach local server");
        return request;
    }

    private static Reply ok(String message) { return new Reply(200, completion(message), 0); }

    private static String completion(String message) {
        return "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"custom-model\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":" + message + "}]}";
    }

    private static String functionCall(String id, String name) {
        return "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name
                + "\",\"arguments\":\"{\\\"path\\\":\\\"README.md\\\"}\"}}";
    }

    private record Reply(int status, String body, long delayMillis, Map<String, String> headers) {
        Reply(int status, String body, long delayMillis) { this(status, body, delayMillis, Map.of()); }
    }
    private record CapturedRequest(String path, String authorization, String method, JsonObject body) {}
}
