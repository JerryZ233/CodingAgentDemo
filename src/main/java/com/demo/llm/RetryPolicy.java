package com.demo.llm;

/** One policy per model request; maxRetries excludes the first attempt. */
public record RetryPolicy(int maxRetries, long initialDelayMillis, long maxDelayMillis) {
    public static final RetryPolicy DEFAULT = new RetryPolicy(5, 2000, 30000);
    public RetryPolicy {
        if (maxRetries < 0 || maxRetries == Integer.MAX_VALUE)
            throw new IllegalArgumentException("llm.max_retries must be nonnegative and less than 2147483647");
        if (initialDelayMillis <= 0 || maxDelayMillis < initialDelayMillis)
            throw new IllegalArgumentException("llm.retry_max_delay_ms must be >= llm.retry_initial_delay_ms > 0");
    }

    public long delayMillis(int retryIndex, double random) {
        double base = Math.min(maxDelayMillis, initialDelayMillis * Math.pow(2, retryIndex));
        return (long) Math.min(maxDelayMillis, base * (1 + Math.max(0, Math.min(1, random)) * 0.2));
    }
}
