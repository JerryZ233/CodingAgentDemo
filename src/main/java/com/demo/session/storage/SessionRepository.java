package com.demo.session.storage;

import com.demo.session.SessionCatalog;
import com.demo.session.SessionSnapshot;

/** One repository is owned by one application process. */
public interface SessionRepository {
    void save(SessionSnapshot snapshot);
    SessionSnapshot load(String id);
    SessionCatalog list();
}
