package com.demo.session;

import java.nio.file.Path;

/** UI-independent, synchronous session backend. No implicit task queue. */
public interface SessionService extends AutoCloseable {
    /** Creation succeeds only after the empty session is saved. */
    SessionSnapshot createSession(Path workspace);
    /** Sorted summaries and per-file errors; does not repair or rewrite sessions. */
    SessionCatalog listSessions();
    /** Lazily restores and checkpoints crash recovery; loaded sessions can be read during a turn. */
    SessionSnapshot getSession(String id);
    /** Errors in model execution are outcomes; storage failures throw and block further turns. */
    SessionSnapshot runTurn(String id, String message);
    /** Repairs interrupted protocol blocks and saves without resuming the failed turn. */
    SessionSnapshot retrySave(String id);
    /** Busy close is rejected. A failed save leaves the service open for retry. */
    @Override void close();
}
