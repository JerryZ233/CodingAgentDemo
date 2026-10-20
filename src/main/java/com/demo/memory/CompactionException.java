package com.demo.memory;

/** Failed optional work. Caller may continue only with a fully budgeted, verified input. */
public final class CompactionException extends RuntimeException {
    public enum Reason { MODEL_FAILED, INVALID_OUTPUT, INPUT_TOO_LARGE, PERSISTENCE_FAILED }
    private final Reason reason;
    public CompactionException(Reason reason) { this(reason, null); }
    public CompactionException(Reason reason, Throwable cause) {
        super("Compaction failed: " + reason, cause);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
