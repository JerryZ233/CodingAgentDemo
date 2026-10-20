package com.demo.memory.history;

/** Safe public diagnostics; never include stored transcripts or filesystem paths. */
public final class HistoryAccessException extends RuntimeException {
    public enum Reason { DISABLED, PROJECT_UNAVAILABLE, PROJECT_CHANGED, HISTORY_UNAVAILABLE, NOT_FOUND }
    private final Reason reason;
    public HistoryAccessException(Reason reason) { this(reason, null); }
    public HistoryAccessException(Reason reason, Throwable cause) {
        super("Project history unavailable: " + reason, cause);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
