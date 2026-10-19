package com.demo.llm.impl;

import com.demo.llm.*;
import com.google.gson.*;
import java.time.*;
import java.util.*;
import java.net.*;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderErrorAdapterTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);

    private LLMError error(LLMProvider provider, int status, String code) {
        return adapter(provider, false).service(status,
                JsonParser.parseString("{\"error\":{\"code\":" + new Gson().toJson(code) + "}}").getAsJsonObject(), Map.of());
    }
    private ProviderErrorAdapter adapter(LLMProvider provider, boolean details) {
        return new ProviderErrorAdapter(provider, "configured-secret", details, CLOCK);
    }

    @Test void providerRulesOverrideHttpFallbackWithoutConfusingIdenticalCodes() {
        for (int status : new int[] {408, 429, 500, 502, 503, 504})
            assertTrue(error(LLMProvider.GENERIC, status, "unknown").retryable(), "status " + status);
        for (int status : new int[] {400, 401, 402, 403, 404, 405, 409, 413, 415, 422, 501, 505, 529})
            assertFalse(error(LLMProvider.GENERIC, status, "unknown").retryable(), "status " + status);
        for (String code : List.of("1113", "1001", "1214", "1261", "1301", "1308", "1309", "1310", "1311", "1313", "1321"))
            assertFalse(error(LLMProvider.ZHIPU, 429, code).retryable(), code);
        for (String code : List.of("1302", "1305")) assertTrue(error(LLMProvider.ZHIPU, 429, code).retryable());
        for (String code : List.of("credit_balance_exhausted", "organization_spend_limit_exceeded",
                "project_spend_limit_exceeded", "organization_usage_limit_exceeded", "insufficient_quota")) {
            assertFalse(error(LLMProvider.OPENAI, 429, code).retryable(), code);
        }
        for (String code : List.of("slow_down", "server_is_overloaded"))
            assertTrue(error(LLMProvider.OPENAI, 503, code).retryable());
        assertTrue(error(LLMProvider.DASHSCOPE, 429, "insufficient_quota").retryable());
        for (String code : List.of("Throttling.RateQuota", "Throttling.BurstRate", "Throttling.Concurrency",
                "Throttling.ServiceOverloaded", "Throttling.ResourceExhausted", "Throttling.AllocationQuota"))
            assertTrue(error(LLMProvider.DASHSCOPE, 429, code).retryable(), code);
        for (String code : List.of("AllocationQuota.FreeTierOnly", "Arrearage", "CommodityNotPurchased",
                "PrepaidBillOverdue", "InternalError.Algo.InvalidParameter", "InvokePluginFailed"))
            assertFalse(error(LLMProvider.DASHSCOPE, 500, code).retryable(), code);
        for (String code : List.of("quota_exceeded", "daily_quota_exceeded", "permission_denied", "invalid_argument"))
            assertFalse(error(LLMProvider.GEMINI, 429, code).retryable());
        assertTrue(error(LLMProvider.GEMINI, 429, "RESOURCE_EXHAUSTED").retryable());
        assertFalse(error(LLMProvider.DEEPSEEK, 402, "unknown_error").retryable());
        assertTrue(error(LLMProvider.DEEPSEEK, 503, "unknown_error").retryable());
    }

    @Test void ambiguousDashscopeQuotaUsesOnlyDocumentedMessageTemplates() {
        for (String message : List.of("Free allocated quota exceeded.", "Maximum voice-clone voice limit exceeded.")) {
            var body = new JsonObject();
            var detail = new JsonObject();
            detail.addProperty("code", "insufficient_quota"); detail.addProperty("message", message); body.add("error", detail);
            assertFalse(adapter(LLMProvider.DASHSCOPE, false).service(429, body, Map.of()).retryable());
        }
    }

    @Test void preservesOriginalJsonTypesNamesAndNestedBusinessCodes() {
        var numeric = adapter(LLMProvider.ZHIPU, false).service(429,
                JsonParser.parseString("{\"error\":{\"code\":1302,\"param\":null},\"request_id\":\"req_1\"}").getAsJsonObject(), Map.of());
        assertEquals("1302", numeric.fields().get("error.code"));
        assertEquals("\"1302\"", error(LLMProvider.ZHIPU, 429, "1302").fields().get("error.code"));
        assertEquals("null", numeric.fields().get("error.param"));
        assertFalse(numeric.fields().containsKey("error.type"));
        var nested = adapter(LLMProvider.GENERIC, true).service(429,
                JsonParser.parseString("{\"error\":{\"type\":\"rate_limit_error\",\"details\":{\"error_code\":\"enforced_spend_limit_reached\"}}}").getAsJsonObject(), Map.of());
        assertFalse(nested.retryable());
        assertEquals("\"enforced_spend_limit_reached\"", nested.fields().get("error.details.error_code"));
    }

    @Test void detailsAreOptInEscapedRedactedAndTruncatedWithoutInterpretation() {
        JsonObject body = new JsonObject();
        JsonObject detail = new JsonObject(); body.add("error", detail);
        detail.addProperty("code", "unexpected");
        detail.addProperty("message", "configured-secret Bearer another-secret password=private\u001b[31m\n" + "x".repeat(1300));
        body.addProperty("Authorization", "must-never-appear");
        var normal = adapter(LLMProvider.GENERIC, false).service(429, body, Map.of());
        assertFalse(normal.fields().containsKey("error.message"));
        var full = adapter(LLMProvider.GENERIC, true).service(429, body, Map.of());
        String output = LLMErrorFormatter.format(new LLMFailure(full, 1, 6, LLMFailure.State.RETRY_SCHEDULED, 2000L));
        for (String secret : List.of("configured-secret", "another-secret", "private", "must-never-appear", "\u001b"))
            assertFalse(output.contains(secret), secret);
        assertTrue(output.contains("[redacted]"));
        assertTrue(output.contains("[truncated]"));
        assertTrue(output.contains("unexpected"));
        assertTrue(output.contains("[server]"));
        assertTrue(output.contains("[client]"));
        assertFalse(output.contains("建议"));
        assertThrows(UnsupportedOperationException.class, () -> full.fields().put("new", "value"));
    }

    @Test void serverDelaysUseClockAndRetainOriginalFields() {
        var parser = adapter(LLMProvider.GEMINI, false);
        for (var header : Map.of("12", 12000L, "Mon, 19 Oct 2026 00:00:15 GMT", 15000L,
                "invalid", 0L, "-3", 0L, "NaN", 0L).entrySet()) {
            assertEquals(header.getValue(), parser.service(429, new JsonObject(), Map.of("retry-after", header.getKey())).serverWaitMillis());
        }
        var body = JsonParser.parseString("{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"7.5s\"}]}}").getAsJsonObject();
        var delayed = parser.service(429, body, Map.of("retry-after", "3"));
        assertEquals(7500, delayed.serverWaitMillis());
        assertEquals("\"7.5s\"", delayed.fields().get("error.details[0].retryDelay"));
    }

    @Test void networkClassificationDistinguishesPermanentFailuresFromTimeouts() {
        var parser = adapter(LLMProvider.GENERIC, false);
        assertTrue(parser.local(new RuntimeException(new SocketTimeoutException()), LLMError.Source.RESPONSE).retryable());
        assertTrue(parser.local(new RuntimeException(new SocketException("reset")), LLMError.Source.RESPONSE).retryable());
        assertTrue(parser.local(new ConnectException(), LLMError.Source.RESPONSE).retryable());
        assertFalse(parser.local(new UnknownHostException(), LLMError.Source.RESPONSE).retryable());
        assertFalse(parser.local(new RuntimeException(new SSLHandshakeException("private")), LLMError.Source.RESPONSE).retryable());
        assertFalse(parser.local(new IllegalArgumentException("private"), LLMError.Source.RESPONSE).retryable());
    }

    @Test void providerAutoDetectionOnlyUsesExactRegisteredHosts() {
        assertEquals(LLMProvider.ZHIPU, LLMProvider.AUTO.resolve("https://open.bigmodel.cn/api/paas/v4"));
        assertEquals(LLMProvider.OPENAI, LLMProvider.AUTO.resolve("https://api.openai.com/v1"));
        assertEquals(LLMProvider.DASHSCOPE, LLMProvider.AUTO.resolve("https://dashscope-intl.aliyuncs.com/compatible-mode/v1"));
        assertEquals(LLMProvider.GENERIC, LLMProvider.AUTO.resolve("https://api.openai.com.evil.example/v1"));
        assertEquals(LLMProvider.GENERIC, LLMProvider.AUTO.resolve("http://localhost/glm-4.7-flash"));
        assertEquals(LLMProvider.DASHSCOPE, LLMProvider.DASHSCOPE.resolve("https://gateway.example/v1"));
    }

    @Test void backoffAndValidationAreDeterministic() {
        var policy = RetryPolicy.DEFAULT;
        assertEquals(List.of(2000L, 4000L, 8000L, 16000L, 30000L),
                java.util.stream.IntStream.range(0, 5).mapToObj(i -> policy.delayMillis(i, 0)).toList());
        assertEquals(2400, policy.delayMillis(0, 1));
        assertEquals(30000, policy.delayMillis(10, 1));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(-1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 2, 1));
    }

    @Test void waitingWithInterruptedThreadPropagatesCancellationAndRestoresInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(com.demo.execution.OperationCancelledException.class,
                    () -> RetryWaiter.cancellable().await(30000, com.demo.execution.CancellationToken.none()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
