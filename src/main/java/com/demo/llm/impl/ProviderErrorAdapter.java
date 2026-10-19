package com.demo.llm.impl;

import com.demo.llm.LLMError;
import com.google.gson.*;
import com.openai.errors.OpenAIServiceException;
import java.io.*;
import java.net.*;
import java.net.http.HttpTimeoutException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.net.ssl.SSLException;

/** Provider semantics are internal; display values retain their original names and JSON types. */
final class ProviderErrorAdapter {
    private static final Gson JSON = new Gson();
    private static final Set<Integer> TRANSIENT = Set.of(408, 429, 500, 502, 503, 504);
    private static final Set<String> PERMANENT = Set.of(
            "invalid_api_key", "authentication_error", "permission_error", "permission_denied",
            "invalid_request_error", "invalid_request", "invalid_parameter", "invalid_parameters",
            "context_length_exceeded", "model_not_found", "content_policy_violation", "billing_error",
            "payment_required", "quota_exceeded", "enforced_spend_limit_reached");
    private static final Set<String> OPENAI_PERMANENT = Set.of("insufficient_quota", "credit_balance_exhausted",
            "organization_spend_limit_exceeded", "project_spend_limit_exceeded",
            "organization_usage_limit_exceeded", "billing_hard_limit_reached");
    private final LLMProvider provider;
    private final String apiKey;
    private final boolean details;
    private final Clock clock;

    ProviderErrorAdapter(LLMProvider provider, String apiKey, boolean details, Clock clock) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.details = details;
        this.clock = clock;
    }

    LLMError service(OpenAIServiceException exception) {
        return service(exception, Optional.empty());
    }

    LLMError service(OpenAIServiceException exception, Optional<JsonObject> originalBody) {
        JsonObject body = new JsonObject();
        try {
            JsonElement value = JSON.toJsonTree(exception.body().convert(Object.class));
            // SDK typed service exceptions retain the inner error object, not the full envelope.
            if (value.isJsonObject()) body.add("error", value);
        } catch (RuntimeException ignored) { /* Status/headers survive non-JSON and malformed errors. */ }
        if (originalBody.isPresent()) body = originalBody.get();
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : List.of("retry-after", "retry-after-ms", "x-request-id", "request-id", "x-dashscope-request-id")) {
            var values = exception.headers().values(name);
            if (!values.isEmpty()) headers.put(name, values.get(0));
        }
        return service(exception.statusCode(), body, headers);
    }

    // Also serves as a fixture boundary for provider response samples.
    LLMError service(int status, JsonObject body, Map<String, String> headers) {
        Map<String, JsonElement> raw = new LinkedHashMap<>();
        flatten(body, "", 0, raw);
        Map<String, String> display = new LinkedHashMap<>();
        raw.forEach((path, value) -> {
            if (isDisplayField(path, details)) display.put(path, safeValue(value));
        });
        long wait = 0;
        for (var entry : headers.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            display.put("headers." + name, safeValue(new JsonPrimitive(entry.getValue())));
            if (name.equals("retry-after")) wait = Math.max(wait, retryAfter(entry.getValue()));
            if (name.equals("retry-after-ms") && provider == LLMProvider.OPENAI)
                wait = Math.max(wait, milliseconds(entry.getValue(), 1));
        }
        for (var entry : raw.entrySet()) {
            String infoPath = entry.getKey().replaceFirst("\\.retryDelay$", ".@type");
            if (provider == LLMProvider.GEMINI && entry.getKey().endsWith(".retryDelay")
                    && scalar(raw.get(infoPath)).equals("type.googleapis.com/google.rpc.RetryInfo"))
                wait = Math.max(wait, secondsDuration(scalar(entry.getValue())));
            if (entry.getKey().equals("error.retry_after") || entry.getKey().equals("retry_after"))
                wait = Math.max(wait, milliseconds(scalar(entry.getValue()), 1000));
        }
        return new LLMError(LLMError.Source.SERVER, status, display, retryable(status, raw), wait,
                raw.keySet().stream().anyMatch(path -> !isDisplayField(path, details)) || raw.size() >= 128);
    }

    private boolean retryable(int status, Map<String, JsonElement> raw) {
        List<String> codes = new ArrayList<>();
        raw.forEach((path, value) -> {
            if (path.equals("code") || path.equals("type") || path.equals("status")
                    || path.equals("error.code") || path.equals("error.type") || path.equals("error.status")
                    || path.endsWith(".error_code") || path.endsWith(".reason"))
                codes.add(scalar(value).toLowerCase(Locale.ROOT));
        });
        if (provider == LLMProvider.ZHIPU) {
            for (String code : codes) {
                if (Set.of("1000", "1001", "1003", "1005", "1113", "1210", "1211", "1212", "1213",
                        "1214", "1215", "1220", "1221", "1222", "1261", "1301", "1307").contains(code)) return false;
                try { int n = Integer.parseInt(code); if (n >= 1308 && n <= 1321 && n != 1312) return false; }
                catch (NumberFormatException ignored) { }
            }
        }
        if (provider == LLMProvider.DASHSCOPE) {
            for (String code : codes) {
                if (code.startsWith("invalidparameter") || code.startsWith("internalerror.algo.invalidparameter")
                        || code.startsWith("accessdenied") || code.equals("allocationquota.freetieronly")
                        || Set.of("arrearage", "commoditynotpurchased", "prepaidbilloverdue", "invalidapikey",
                        "datainspectionfailed", "inappropriatecontent", "invokepluginfailed",
                        "appprocessfailed", "retrivalfailed").contains(code)) return false;
            }
            String message = scalar(raw.get("error.message"));
            if (message.isEmpty()) message = scalar(raw.get("message"));
            String normalized = message.toLowerCase(Locale.ROOT).trim();
            if (normalized.startsWith("free allocated quota exceeded")
                    || normalized.startsWith("maximum voice-clone voice limit exceeded")) return false;
        }
        for (String code : codes) {
            if (PERMANENT.contains(code)) return false;
            if ((provider == LLMProvider.OPENAI || provider == LLMProvider.GENERIC)
                    && OPENAI_PERMANENT.contains(code)) return false;
            if (provider == LLMProvider.GEMINI && Set.of("daily_limit_exceeded", "daily_quota_exceeded",
                    "quota_exceeded", "failed_precondition", "invalid_argument", "not_found", "unauthenticated").contains(code)) return false;
        }
        // Business errors may veto a transient HTTP status; they never turn arbitrary 4xx into retries.
        return TRANSIENT.contains(status);
    }

    LLMError local(Throwable failure, LLMError.Source fallback) {
        boolean transientNetwork = false;
        Throwable current = failure;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        String kind = fallback == LLMError.Source.RESPONSE ? "response_decode" : "request_data";
        while (current != null && visited.add(current)) {
            if (current instanceof SSLException || current instanceof UnknownHostException)
                return new LLMError(LLMError.Source.TRANSPORT, null,
                        Map.of("exception", safeValue(new JsonPrimitive(current.getClass().getSimpleName()))), false, 0);
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException
                    || current instanceof ConnectException || current instanceof SocketException
                    || current instanceof EOFException) {
                transientNetwork = true;
                kind = current.getClass().getSimpleName();
            }
            if (current instanceof java.io.InterruptedIOException && !(current instanceof SocketTimeoutException)) {
                // OkHttp also uses InterruptedIOException for its overall call timeout.
                if (!Thread.currentThread().isInterrupted()) { transientNetwork = true; kind = "InterruptedIOException"; }
            }
            current = current.getCause();
        }
        return new LLMError(transientNetwork ? LLMError.Source.TRANSPORT : fallback, null,
                Map.of("exception", safeValue(new JsonPrimitive(kind))), transientNetwork, 0);
    }

    private long retryAfter(String value) {
        long seconds = milliseconds(value, 1000);
        if (seconds > 0) return seconds;
        try { return Math.max(0, Duration.between(clock.instant(),
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()); }
        catch (RuntimeException ignored) { return 0; }
    }
    private static long milliseconds(String value, int scale) {
        try {
            double n = Double.parseDouble(value);
            return Double.isFinite(n) && n > 0 ? (long) Math.ceil(Math.min(Long.MAX_VALUE, n * scale)) : 0;
        } catch (RuntimeException ignored) { return 0; }
    }
    private static long secondsDuration(String value) {
        return value.endsWith("s") ? milliseconds(value.substring(0, value.length() - 1), 1000) : 0;
    }

    private static void flatten(JsonElement value, String path, int depth, Map<String, JsonElement> result) {
        if (depth > 8 || result.size() >= 128) { result.put("_omitted", JsonNull.INSTANCE); return; }
        if (value == null) return;
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                // Never put provider-controlled field names into terminal output.
                if (entry.getKey().matches("[A-Za-z_@][A-Za-z0-9_@.-]{0,63}"))
                    flatten(entry.getValue(), path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), depth + 1, result);
                else result.put("_omitted", JsonNull.INSTANCE);
            }
        } else if (value.isJsonArray()) {
            if (value.getAsJsonArray().size() > 16) result.put("_omitted", JsonNull.INSTANCE);
            for (int i = 0; i < Math.min(16, value.getAsJsonArray().size()); i++)
                flatten(value.getAsJsonArray().get(i), path + "[" + i + "]", depth + 1, result);
        } else result.put(path, value);
    }
    private static boolean isDisplayField(String path, boolean details) {
        if (Set.of("code", "type", "status", "param", "request_id", "requestId", "error.code", "error.type",
                "error.status", "error.param", "error.request_id", "error.retry_after", "retry_after").contains(path)) return true;
        if (path.endsWith(".retryDelay")) return true;
        return details && (Set.of("message", "error.message").contains(path)
                || path.matches("error\\.details(?:\\[\\d+\\])?\\.(?:error_code|reason|domain|@type|retryDelay|quota_metric|quota_id|limit)"));
    }
    private static String scalar(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private String safeValue(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        if (!value.isJsonPrimitive()) return JSON.toJson("[omitted]");
        if (!value.getAsJsonPrimitive().isString()) return value.toString();
        String text = value.getAsString();
        if (apiKey != null && !apiKey.isEmpty()) text = text.replace(apiKey, "[redacted]");
        text = text.replaceAll("(?i)Bearer\\s+[^\\s\"']+", "Bearer [redacted]")
                .replaceAll("(?i)((?:api[_-]?key|authorization|password|secret|access[_-]?token)[\"']?\\s*[=:]\\s*[\"']?)[^\\s,;\"'}]+", "$1[redacted]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]+", "[redacted]");
        if (text.codePointCount(0, text.length()) > 1000)
            text = text.substring(0, text.offsetByCodePoints(0, 1000)) + "[truncated]";
        StringBuilder escaped = new StringBuilder();
        text.codePoints().forEach(c -> {
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                escaped.append(String.format("\\u%04x", c));
            else escaped.appendCodePoint(c);
        });
        return JSON.toJson(escaped.toString());
    }
}
