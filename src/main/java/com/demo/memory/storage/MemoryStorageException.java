package com.demo.memory.storage;

import com.demo.storage.StorageFiles;

public class MemoryStorageException extends RuntimeException {
    public enum Reason { IO_FAILURE, LOCKED, CONFLICT, MALFORMED_DATA, UNSUPPORTED_VERSION, INCONSISTENT_STATE }
    private final Reason reason;
    public MemoryStorageException(Reason reason, String message) { super(message); this.reason = reason; }
    public MemoryStorageException(Reason reason, String message, Throwable cause) {
        super(message, cause); this.reason = reason;
    }
    public Reason getReason() { return reason; }
    static MemoryStorageException io(Exception cause) {
        return new MemoryStorageException(cause instanceof StorageFiles.BusyException ? Reason.LOCKED : Reason.IO_FAILURE,
                "Memory storage operation failed", cause);
    }
    static MemoryStorageException malformed(Exception cause) {
        return new MemoryStorageException(Reason.MALFORMED_DATA, "Malformed memory data", cause);
    }
    static MemoryStorageException conflict() {
        return new MemoryStorageException(Reason.CONFLICT, "Memory changed; reload before retrying");
    }
}
