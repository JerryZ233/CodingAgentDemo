package com.demo.session;

public class SessionException extends RuntimeException {
    public enum Reason { NOT_FOUND, INVALID_WORKSPACE, BUSY, INVALID_INPUT, UNSAVED_SESSION, CLOSED, CLOSING, SHUTDOWN_TIMEOUT, RESOURCE_FAILURE }
    private final Reason reason;

    public SessionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SessionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
