package com.demo.eval;

import com.demo.llm.RequestAttempt;
import com.demo.logging.LogData;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Incremental attempt journal survives a process timeout; final totals never invent missing usage. */
final class EvalMetrics {
    static final Gson JSON = new GsonBuilder().serializeNulls()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
    private final Path directory;
    private long requests, retries, known, input, output, total, cache, cacheKnown, modelMillis;

    EvalMetrics(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        Files.createFile(directory.resolve("attempts.jsonl"));
        Files.createFile(directory.resolve("events.jsonl"));
    }

    synchronized void record(RequestAttempt attempt) {
        append("attempts.jsonl", attempt);
        requests++;
        if (attempt.attempt() > 1) retries++;
        modelMillis += attempt.elapsedMillis();
        if (attempt.usage() != null) {
            known++;
            input += attempt.usage().inputTokens();
            output += attempt.usage().outputTokens();
            total += attempt.usage().totalTokens();
            if (attempt.usage().cachedTokens() != null) {
                cacheKnown++;
                cache += attempt.usage().cachedTokens();
            }
        }
    }

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request_count", requests);
        result.put("retry_count", retries);
        result.put("requests_with_usage", known);
        result.put("usage_complete", requests > 0 && known == requests);
        result.put("input_tokens", known == 0 ? null : input);
        result.put("output_tokens", known == 0 ? null : output);
        result.put("total_tokens", known == 0 ? null : total);
        result.put("cached_tokens", cacheKnown == 0 ? null : cache);
        result.put("cache_usage_complete", requests > 0 && cacheKnown == requests);
        result.put("model_request_ms", modelMillis);
        return result;
    }

    void append(String name, Object value) {
        try {
            Files.writeString(directory.resolve(name), LogData.redact(JSON.toJson(value)) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    void write(String name, Object value) throws IOException {
        Path target = directory.resolve(name);
        Path temp = Files.createTempFile(directory, name, ".tmp");
        try {
            Files.writeString(temp, LogData.redact(JSON.toJson(value)), StandardCharsets.UTF_8);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
