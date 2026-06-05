package com.demo.agent;

/**
 * Indicates that conversation persistence failed for a known storage reason.
 */
public class AgentStorageException extends RuntimeException {

    public enum Reason {
        MALFORMED_DATA,
        IO_FAILURE
    }

    private final Reason reason;

    public AgentStorageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AgentStorageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
