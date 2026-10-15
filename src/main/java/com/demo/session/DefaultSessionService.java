package com.demo.session;

import com.demo.agent.AgentObserver;
import com.demo.agent.AgentRunResult;
import com.demo.agent.CodingAgent;
import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.llm.LLMClientFactory;
import com.demo.session.storage.JsonSessionRepository;
import com.demo.session.storage.SessionRepository;
import com.demo.session.storage.SessionStorageException;
import com.demo.tools.ToolRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Synchronous backend with a non-queuing single-writer gate and lock-free published reads. */
public final class DefaultSessionService implements SessionService {
    public enum ClientOwnership { OWNED, BORROWED }

    private final Config config;
    private final LLMClient client;
    private final ClientOwnership ownership;
    private final SessionRepository repository;
    private final Clock clock;
    private final WorkspaceResolver workspaces = new WorkspaceResolver();
    private final SessionRecovery recovery = new SessionRecovery();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ReentrantLock mutations = new ReentrantLock();
    private volatile boolean closed;

    public DefaultSessionService(Config config) {
        this(config, JsonSessionRepository.defaultDirectory());
    }

    public DefaultSessionService(Config config, Path directory) {
        this(config, new JsonSessionRepository(directory));
    }

    private DefaultSessionService(Config config, SessionRepository repository) {
        this(config, createOwnedClient(config), repository, Clock.systemUTC(), ClientOwnership.OWNED);
    }

    private static LLMClient createOwnedClient(Config config) {
        Objects.requireNonNull(config, "config");
        if (config.getMaxIterations() <= 0) throw new IllegalArgumentException("maxIterations must be positive");
        return LLMClientFactory.create(config, AgentObserver.noop());
    }

    /** Injected clients are borrowed unless ownership is explicitly transferred. */
    public DefaultSessionService(Config config, LLMClient client, SessionRepository repository, Clock clock) {
        this(config, client, repository, clock, ClientOwnership.BORROWED);
    }

    public DefaultSessionService(Config config, LLMClient client, SessionRepository repository, Clock clock,
                                 ClientOwnership ownership) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        if (config.getMaxIterations() <= 0) throw new IllegalArgumentException("maxIterations must be positive");
    }

    @Override
    public SessionSnapshot createSession(Path workspace) {
        enterMutation();
        try {
            Path real = workspaces.resolve(workspace);
            Instant now = clock.instant();
            Session session = new Session(new SessionSnapshot(UUID.randomUUID().toString(), "新会话", real,
                    now, now, List.of(), null, true));
            persist(session); // Failed creation is not published as an existing session.
            sessions.put(session.id, session);
            return session.published;
        } finally {
            mutations.unlock();
        }
    }

    @Override
    public SessionCatalog listSessions() {
        ensureOpen();
        SessionCatalog disk = repository.list();
        Map<String, SessionSummary> summaries = new HashMap<>();
        disk.sessions().forEach(summary -> summaries.put(summary.id(), summary));
        sessions.values().forEach(session -> summaries.put(session.id, SessionSummary.from(session.published)));
        return new SessionCatalog(new ArrayList<>(summaries.values()), disk.issues());
    }

    @Override
    public SessionSnapshot getSession(String id) {
        ensureOpen();
        validateId(id);
        Session loaded = sessions.get(id);
        if (loaded != null) return loaded.published;
        enterMutation();
        try {
            return loadSession(id).published;
        } finally {
            mutations.unlock();
        }
    }

    @Override
    public SessionSnapshot runTurn(String id, String message) {
        enterMutation();
        try {
            if (message == null || message.isBlank()) {
                throw new SessionException(SessionException.Reason.INVALID_INPUT, "Message must not be blank");
            }
            Session session = loadSession(id);
            // A failed save in any session blocks further model/tool side effects until it is resolved.
            if (sessions.values().stream().anyMatch(item -> item.unsaved)) {
                throw new SessionException(SessionException.Reason.UNSAVED_SESSION,
                        "Retry saving unsaved sessions before starting another turn");
            }
            workspaces.validateStored(session.workspace);
            if (session.agent == null) {
                session.agent = new CodingAgent(client, ToolRegistry.fromConfig(config, session.workspace),
                        config.getMaxIterations());
            }
            if (session.context.getMessages().isEmpty()) session.title = titleFor(message);
            session.lastRun = SessionRun.started(now(session));
            session.context.addUserMessage(message);
            touch(session);
            try {
                persist(session);
                AgentRunResult result = session.agent.runTurn(session.context, (context, iteration) -> {
                    session.lastRun = session.lastRun.atIteration(iteration);
                    touch(session);
                    persist(session);
                });
                session.lastRun = session.lastRun.finish(result, now(session));
                touch(session);
                persist(session);
                return session.published;
            } catch (SessionStorageException e) {
                markStorageFailure(session);
                throw e;
            } catch (RuntimeException e) {
                // Unknown exceptions must not leak raw model/provider data into durable history.
                session.apply(recovery.recover(session.publish(), now(session)).snapshot());
                session.lastRun = session.lastRun.finish(SessionRun.Status.FAILED, now(session),
                        "Turn failed unexpectedly; no operations were replayed.");
                touch(session);
                try {
                    persist(session);
                } catch (SessionStorageException storageFailure) {
                    markStorageFailure(session);
                    throw storageFailure;
                }
                return session.published;
            }
        } finally {
            mutations.unlock();
        }
    }

    @Override
    public SessionSnapshot retrySave(String id) {
        enterMutation();
        try {
            Session session = loadSession(id);
            savePending(session);
            return session.published;
        } finally {
            mutations.unlock();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        enterMutation();
        try {
            SessionStorageException failure = null;
            for (Session session : sessions.values()) {
                try {
                    savePending(session);
                } catch (SessionStorageException e) {
                    if (failure == null) failure = e; else failure.addSuppressed(e);
                }
            }
            // Keep the service and client alive so the caller can repair storage and retry close.
            if (failure != null) throw failure;
            closed = true;
            if (ownership == ClientOwnership.OWNED) {
                try { client.close(); }
                catch (RuntimeException e) {
                    throw new SessionException(SessionException.Reason.RESOURCE_FAILURE,
                            "Failed to release the model client", e);
                }
            }
        } finally {
            mutations.unlock();
        }
    }

    private Session loadSession(String id) {
        validateId(id);
        Session existing = sessions.get(id);
        if (existing != null) return existing;
        SessionSnapshot stored;
        try {
            stored = repository.load(id);
        } catch (SessionStorageException e) {
            if (e.getReason() == SessionStorageException.Reason.NOT_FOUND) {
                throw new SessionException(SessionException.Reason.NOT_FOUND, "Session not found: " + id, e);
            }
            throw e;
        }
        SessionRecovery.RecoveryResult restored = recovery.recover(stored, clock.instant());
        Session session = new Session(restored.snapshot());
        sessions.put(id, session);
        if (restored.changed()) persist(session);
        return session;
    }

    private void savePending(Session session) {
        if (!session.unsaved) return;
        session.apply(recovery.recover(session.published, now(session)).snapshot());
        persist(session);
    }

    private void persist(Session session) {
        session.unsaved = true;
        repository.save(session.publish());
        session.unsaved = false;
        session.publish();
    }

    private Instant now(Session session) {
        Instant now = clock.instant();
        if (now.isBefore(session.updatedAt)) now = session.updatedAt;
        if (session.lastRun != null) {
            if (now.isBefore(session.lastRun.startedAt())) now = session.lastRun.startedAt();
            if (session.lastRun.endedAt() != null && now.isBefore(session.lastRun.endedAt())) {
                now = session.lastRun.endedAt();
            }
        }
        return now;
    }

    private void markStorageFailure(Session session) {
        session.lastRun = session.lastRun.finish(SessionRun.Status.STORAGE_ERROR, now(session),
                "Checkpoint failed; retry saving before continuing. Completed file changes are retained.");
        session.unsaved = true;
        touch(session);
        session.publish();
    }

    private void touch(Session session) { session.updatedAt = now(session); }

    private String titleFor(String message) {
        String line = message.stripLeading().lines().findFirst().orElse("新会话").stripTrailing();
        return line.substring(0, line.offsetByCodePoints(0, Math.min(40, line.codePointCount(0, line.length()))));
    }

    private void ensureOpen() {
        if (closed) throw new SessionException(SessionException.Reason.CLOSED, "Session service is closed");
    }

    private void enterMutation() {
        ensureOpen();
        if (mutations.isHeldByCurrentThread() || !mutations.tryLock()) {
            throw new SessionException(SessionException.Reason.BUSY, "Session service is busy");
        }
        if (closed) {
            mutations.unlock();
            ensureOpen();
        }
    }

    private void validateId(String id) {
        try { SessionSnapshot.requireId(id); }
        catch (IllegalArgumentException e) {
            throw new SessionException(SessionException.Reason.INVALID_INPUT, "Session ID must be a canonical UUID", e);
        }
    }
}
