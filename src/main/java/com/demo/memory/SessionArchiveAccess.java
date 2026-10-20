package com.demo.memory;

import com.demo.session.SessionSnapshot;
import java.nio.file.Path;
import java.time.Instant;

/** Host boundary: a disk commit must update live metadata without replacing the running Context. */
public interface SessionArchiveAccess {
    SessionSnapshot snapshot();
    void recover(Path memoryDirectory);
    void commit(Path memoryDirectory, SessionSnapshot expected, HistoryEntry entry, Instant organizedAt);
}
