package com.demo.llm;

/** Client metadata is deliberately separate from the server's original fields. */
public record LLMFailure(LLMError error, int attempt, int maxAttempts, State state, Long retryInMillis) {
    public enum State { RETRY_SCHEDULED, NON_RETRYABLE, RETRIES_EXHAUSTED, SERVER_WAIT_LIMIT }
}
