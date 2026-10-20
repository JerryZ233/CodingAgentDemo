package com.demo.session;

import java.nio.file.Path;
import java.util.Optional;

/** UI-independent session backend. At most one admitted turn, no implicit task queue. */
public interface SessionService extends AutoCloseable {
    /** Creation succeeds only after the empty session is saved. */
    SessionSnapshot createSession(Path workspace);
    /** Sorted summaries and per-file errors; does not repair or rewrite sessions. */
    SessionCatalog listSessions();
    /** Lazily restores and checkpoints crash recovery; loaded sessions can be read during a turn. */
    SessionSnapshot getSession(String id);
    /** Errors in model execution are outcomes; storage failures throw and block further turns. */
    SessionSnapshot runTurn(String id, String message);
    RunHandle submitTurn(String id, String message);
    default RunHandle submitTurn(String id, String message, RunOptions options) { return submitTurn(id,message); }
    Optional<RunHandle> getActiveRun();
    default com.google.gson.JsonObject searchHistory(String id, String query) {
        throw new com.demo.memory.HistoryAccessException(com.demo.memory.HistoryAccessException.Reason.DISABLED);
    }
    default com.google.gson.JsonObject readHistory(String id, String historyId, boolean includeSources) {
        throw new com.demo.memory.HistoryAccessException(com.demo.memory.HistoryAccessException.Reason.DISABLED);
    }
    /** Repairs interrupted protocol blocks and saves without resuming the failed turn. */
    SessionSnapshot retrySave(String id);
    /** Cancels active work and waits up to five seconds. Failed saving remains retryable. */
    @Override void close();
}
