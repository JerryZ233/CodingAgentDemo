package com.demo.session;

import com.demo.agent.CodingAgent;
import com.demo.agent.Conversation;
import java.nio.file.Path;
import java.time.Instant;

/** Mutable state has one admitted writer; concurrent readers see published snapshots only. */
final class Session {
    final String id;
    final Path workspace;
    final Instant createdAt;
    String title;
    Instant updatedAt;
    Conversation context;
    SessionRun lastRun;
    CodingAgent agent;
    boolean unsaved;
    com.demo.memory.context.SessionMemoryState memoryState;
    volatile SessionSnapshot published;
    SessionSnapshot persisted;

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
        context = Conversation.fromMessages(snapshot.messages(), snapshot.contentRevision());
        unsaved = snapshot.unsaved();
        memoryState = snapshot.memoryState();
        publish();
    }

    SessionSnapshot publish() {
        published = new SessionSnapshot(id, title, workspace, createdAt, updatedAt,
                context.getMessages(), lastRun, unsaved, context.getContentRevision(), memoryState);
        return published;
    }
}
