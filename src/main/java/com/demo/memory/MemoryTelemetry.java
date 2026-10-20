package com.demo.memory;

import com.demo.llm.*;
import java.time.*;
import java.util.*;

/** Bounded metadata only. Usage is provider-reported; absent values are never treated as zero. */
public final class MemoryTelemetry {
    public enum Origin { FOREGROUND, BACKGROUND, MANUAL }
    public enum Operation { COMPACTION, CONSOLIDATION }
    public enum Phase { STARTED, SUCCEEDED, FAILED, CANCELLED, SKIPPED }
    public record Event(long sequence, Instant at, String projectKey, String sessionId,
                        Origin origin, Operation operation, Phase phase) { }
    public record Page(long nextSequence, List<Event> events, boolean gap) {
        public Page { events = List.copyOf(events); }
        public static Page empty() { return new Page(0, List.of(), false); }
    }
    public record Usage(long attempts, long retries, long responses, long withUsage, Long inputTokens,
                        Long outputTokens, Long totalTokens, Long cachedTokens, long withCacheUsage,
                        long elapsedMillis, boolean complete, boolean cacheComplete) { }
    private static final class Counter {
        long attempts, retries, responses, known, input, output, total, cached, cacheKnown, millis;
        void record(RequestAttempt a) {
            attempts++; if (a.attempt() > 1) retries++; if (a.responseReceived()) responses++; millis += a.elapsedMillis();
            if (a.usage() != null) {
                known++; input += a.usage().inputTokens(); output += a.usage().outputTokens(); total += a.usage().totalTokens();
                if (a.usage().cachedTokens() != null) { cacheKnown++; cached += a.usage().cachedTokens(); }
            }
        }
        Usage snapshot() { return new Usage(attempts,retries,responses,known,known == 0 ? null : input,
                known == 0 ? null : output,known == 0 ? null : total,cacheKnown == 0 ? null : cached,cacheKnown,millis,
                attempts > 0 && known == attempts,attempts > 0 && cacheKnown == attempts); }
    }
    private final Clock clock;
    private long sequence;
    private final Deque<Event> events = new ArrayDeque<>();
    private final EnumMap<RequestPurpose, Counter> usage = new EnumMap<>(RequestPurpose.class);
    public MemoryTelemetry(Clock clock) { this.clock = clock; }
    public synchronized void record(RequestAttempt attempt) {
        usage.computeIfAbsent(attempt.purpose(), ignored -> new Counter()).record(attempt);
        com.demo.logging.Logging.logger(MemoryTelemetry.class).info(
                "Model usage purpose={} attempt={} elapsed_ms={} response_received={} usage_present={} input_tokens={} output_tokens={} cached_tokens={}",
                attempt.purpose(),attempt.attempt(),attempt.elapsedMillis(),attempt.responseReceived(),attempt.usage()!=null,
                attempt.usage()==null?null:attempt.usage().inputTokens(),attempt.usage()==null?null:attempt.usage().outputTokens(),
                attempt.usage()==null?null:attempt.usage().cachedTokens());
    }
    public synchronized Map<RequestPurpose, Usage> usage() {
        var result = new EnumMap<RequestPurpose,Usage>(RequestPurpose.class);
        for (var purpose : RequestPurpose.values()) result.put(purpose,usage.getOrDefault(purpose,new Counter()).snapshot());
        return Collections.unmodifiableMap(result);
    }
    public synchronized void event(String projectKey, String sessionId, Origin origin, Operation operation, Phase phase) {
        var event = new Event(++sequence,clock.instant(),projectKey,sessionId,origin,operation,phase);
        events.addLast(event); if (events.size()>256) events.removeFirst();
        com.demo.logging.Logging.logger(MemoryTelemetry.class).info("Memory activity project_id={} session_id={} origin={} operation={} phase={}",
                projectKey,sessionId,origin,operation,phase);
    }
    public synchronized Page eventsAfter(String projectKey, long cursor) {
        return new Page(sequence,events.stream().filter(e -> e.sequence()>cursor && e.projectKey().equals(projectKey)).toList(),
                !events.isEmpty() && cursor < events.getFirst().sequence()-1);
    }
    public static String projectKey(java.nio.file.Path root) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public String usageText() {
        StringBuilder text = new StringBuilder("本服务进程模型用量（含前台、后台及重试；估算预算不计入用量）：");
        usage().forEach((purpose,u) -> text.append("\n").append(purpose).append(": 尝试 ").append(u.attempts())
                .append("，重试 ").append(u.retries()).append("，usage ").append(u.withUsage()).append('/').append(u.attempts())
                .append("，输入 ").append(Objects.toString(u.inputTokens(),"未知")).append("，输出 ").append(Objects.toString(u.outputTokens(),"未知"))
                .append("，缓存 ").append(Objects.toString(u.cachedTokens(),"未知"))
                .append(u.complete()?"（usage 完整":"（用量不完整")
                .append(u.cacheComplete()?"，缓存完整）":"，缓存统计不完整）"));
        return text.toString();
    }
}
