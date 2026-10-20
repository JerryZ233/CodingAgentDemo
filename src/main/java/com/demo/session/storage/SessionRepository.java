package com.demo.session.storage;

import com.demo.session.SessionCatalog;
import com.demo.session.SessionSnapshot;

/** One repository is owned by one application process. */
public interface SessionRepository {
    void save(SessionSnapshot snapshot);
    /** Storage-only CAS. Runtime writers must additionally coordinate their in-memory session. */
    default void compareAndSave(SessionSnapshot expected, SessionSnapshot replacement) {
        throw new UnsupportedOperationException("Repository does not support conditional checkpoints");
    }
    SessionSnapshot load(String id);
    SessionCatalog list();
}
