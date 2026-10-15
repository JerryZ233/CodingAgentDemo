package com.demo.execution;

/** A resource may still be alive. Do not treat this as an ordinary tool error. */
public class ResourceCleanupException extends RuntimeException {
    private final Runnable retry;

    public ResourceCleanupException(String message, Runnable retry) {
        super(message);
        this.retry = retry;
    }

    public void retryCleanup() { retry.run(); }
}
