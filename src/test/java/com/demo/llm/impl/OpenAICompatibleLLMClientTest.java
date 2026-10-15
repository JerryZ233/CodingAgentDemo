package com.demo.llm.impl;

import com.demo.agent.AgentLoop;
import com.demo.agent.Context;
import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.model.ToolResult;
import com.demo.tools.Tool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openai.client.okhttp.OpenAIOkHttpClient;
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
    void sendsConfiguredRequestAndReceivesText() throws Exception {
        replies.add(ok("{\"role\":\"assistant\",\"content\":\"hello\"}"));
        var response = client("/v1/chat/completions").sendMessage(
                List.of(Message.system("system"), Message.user("hi")), OpenAIChatAdapterTest.TOOLS);
        assertFalse(response.isError(), response.getText());
        assertEquals("hello", response.getText());
        assertFalse(response.hasToolCalls());
        var captured = takeRequest();
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
            assertTrue(response.getText().contains("HTTP " + status));
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
        var response = new OpenAICompatibleLLMClient("http://127.0.0.1:" + port + "/v1", "test-key", "model", 10, 0)
                .sendMessage(List.of(Message.user("hi")), null);
        assertTrue(response.isError());
    }

    @Test
    void timeoutBecomesErrorWithoutRetry() {
        replies.add(new Reply(200, completion("{\"role\":\"assistant\",\"content\":\"late\"}"), 2000));
        var sdk = OpenAIOkHttpClient.builder().baseUrl(origin + "/v1").apiKey("test-key")
                .timeout(Duration.ofMillis(500)).maxRetries(0).build();
        try {
            var response = new OpenAICompatibleLLMClient(sdk, "model", 10, 0)
                    .sendMessage(List.of(Message.user("hi")), null);
            assertTrue(response.isError());
        } finally {
            sdk.close();
        }
        assertEquals(1, requestCount.get());
    }

    private OpenAICompatibleLLMClient client(String path) {
        return new OpenAICompatibleLLMClient(origin + path, "test-key", "custom-model", 128, 0.25);
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

    private record Reply(int status, String body, long delayMillis) {}
    private record CapturedRequest(String path, String authorization, String method, JsonObject body) {}
}
