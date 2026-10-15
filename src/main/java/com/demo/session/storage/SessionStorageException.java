package com.demo.session.storage;

public class SessionStorageException extends RuntimeException {
    public enum Reason { NOT_FOUND, MALFORMED_DATA, UNSUPPORTED_VERSION, IO_FAILURE }
    private final Reason reason;

    public SessionStorageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SessionStorageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
