package com.demo.session;

import com.demo.agent.CodingAgent;
import com.demo.agent.Context;
import java.nio.file.Path;
import java.time.Instant;

/** Mutable state is confined to the service's mutation gate; readers see published snapshots only. */
final class Session {
    final String id;
    final Path workspace;
    final Instant createdAt;
    String title;
    Instant updatedAt;
    Context context;
    SessionRun lastRun;
    CodingAgent agent;
    boolean unsaved;
    volatile SessionSnapshot published;

    Session(SessionSnapshot snapshot) {
        id = snapshot.id();
        workspace = snapshot.workspace();
        createdAt = snapshot.createdAt();
        apply(snapshot);
    }

    void apply(SessionSnapshot snapshot) {
        title = snapshot.title();
        updatedAt = snapshot.updatedAt();
        lastRun = snapshot.lastRun();
        context = Context.fromMessages(snapshot.messages());
        context.setWorkspace(workspace);
        unsaved = snapshot.unsaved();
        publish();
    }

    SessionSnapshot publish() {
        published = new SessionSnapshot(id, title, workspace, createdAt, updatedAt,
                context.getMessages(), lastRun, unsaved);
        return published;
    }
}
