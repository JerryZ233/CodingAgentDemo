package com.demo.llm;

/** One actual HTTP attempt, including unsuccessful and cancelled attempts; excludes retry waits. */
public record RequestAttempt(int attempt, long elapsedMillis, TokenUsage usage, boolean responseReceived) { }
