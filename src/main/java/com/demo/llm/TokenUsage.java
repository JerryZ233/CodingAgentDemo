package com.demo.llm;

/** Provider-reported usage. Input already includes cached tokens. Null means unavailable. */
public record TokenUsage(long inputTokens, long outputTokens, long totalTokens, Long cachedTokens) {
    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0
                || (cachedTokens != null && (cachedTokens < 0 || cachedTokens > inputTokens))) {
            throw new IllegalArgumentException("Invalid token usage");
        }
    }
}
