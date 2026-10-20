package com.demo.memory.compaction;

import com.demo.memory.model.HistoryEntry;
import com.demo.session.SessionSnapshot;
import java.nio.file.Path;
import java.time.Instant;

/** Host boundary: a disk commit must update live metadata without replacing the running Conversation. */
public interface SessionArchiveAccess {
    SessionSnapshot snapshot();
    void recover(Path memoryDirectory);
    void commit(Path memoryDirectory, SessionSnapshot expected, HistoryEntry entry, Instant organizedAt);
}
