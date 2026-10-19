package com.demo.session;

import com.demo.agent.AgentObserver;
import com.demo.agent.AgentRunResult;
import com.demo.agent.CodingAgent;
import com.demo.config.Config;
import com.demo.execution.ResourceCleanupException;
import com.demo.llm.LLMClient;
import com.demo.llm.LLMClientFactory;
import com.demo.session.storage.JsonSessionRepository;
import com.demo.session.storage.SessionRepository;
import com.demo.session.storage.SessionStorageException;
import com.demo.tools.ToolRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** One admitted background turn. Only that worker mutates its session; readers see checkpoints. */
public final class DefaultSessionService implements SessionService {
    private final org.apache.logging.log4j.Logger log = com.demo.logging.Logging.logger(DefaultSessionService.class);
    public enum ClientOwnership { OWNED, BORROWED }
    private enum Lifecycle { OPEN, CLOSING, CLOSED }
    private final Config config;
    private final LLMClient client;
    private final ClientOwnership ownership;
    private final SessionRepository repository;
    private final Clock clock;
    private final ExecutorService executor;
    private final Duration closeTimeout;
    private final WorkspaceResolver workspaces = new WorkspaceResolver();
    private final SessionRecovery recovery = new SessionRecovery();
    private final Map<String, Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final ReentrantLock mutations = new ReentrantLock();
    private final ReentrantLock closeGate = new ReentrantLock();
    private volatile Lifecycle lifecycle = Lifecycle.OPEN;
    private volatile ActiveRun active;
    private volatile Thread worker;
    private volatile ResourceCleanupException cleanupFailure;

    public DefaultSessionService(Config config) { this(config, JsonSessionRepository.defaultDirectory()); }
    public DefaultSessionService(Config config, Path directory) { this(config, new JsonSessionRepository(directory)); }
    private DefaultSessionService(Config config, SessionRepository repository) {
        this(config, createOwnedClient(config), repository, Clock.systemUTC(), ClientOwnership.OWNED);
    }
    private static LLMClient createOwnedClient(Config config) {
        Objects.requireNonNull(config, "config");
        if (config.getMaxIterations() <= 0) throw new IllegalArgumentException("maxIterations must be positive");
        return LLMClientFactory.create(config, AgentObserver.noop());
    }
    /** The injected client is borrowed; the service always owns its execution thread. */
    public DefaultSessionService(Config config, LLMClient client, SessionRepository repository, Clock clock) {
        this(config, client, repository, clock, ClientOwnership.BORROWED);
    }
    public DefaultSessionService(Config config, LLMClient client, SessionRepository repository, Clock clock,
                                 ClientOwnership ownership) {
        this(config, client, repository, clock, ownership, Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "coding-agent-runner");
            thread.setDaemon(false);
            return thread;
        }), Duration.ofSeconds(5));
    }
    DefaultSessionService(Config config, LLMClient client, SessionRepository repository, Clock clock,
                          ClientOwnership ownership, ExecutorService executor, Duration closeTimeout) {
        this.config = Objects.requireNonNull(config);
        com.demo.logging.LogData.registerSecret(config.getApiKey());
        this.client = Objects.requireNonNull(client);
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        this.ownership = Objects.requireNonNull(ownership);
        this.executor = Objects.requireNonNull(executor);
        this.closeTimeout = Objects.requireNonNull(closeTimeout);
        if (config.getMaxIterations() <= 0 || closeTimeout.isNegative() || closeTimeout.isZero()) {
            throw new IllegalArgumentException("Iteration limit and close timeout must be positive");
        }
    }

    @Override
    public SessionSnapshot createSession(Path workspace) {
        enterMutation();
        try {
            Path real = workspaces.resolve(workspace);
            Instant now = clock.instant();
            Session session = new Session(new SessionSnapshot(UUID.randomUUID().toString(), "新会话", real,
                    now, now, List.of(), null, true));
            persist(session);
            sessions.put(session.id, session);
            log.info("Session created session_id={} workspace={}", session.id, com.demo.logging.LogData.quote(real));
            return session.published;
        } finally { mutations.unlock(); }
    }

    @Override
    public SessionCatalog listSessions() {
        ensureReadable();
        SessionCatalog disk;
        try { disk = repository.list(); }
        catch (SessionStorageException e) {
            log.error("Session catalog load failed exception={}", com.demo.logging.LogData.exception(e));
            throw e;
        }
        for (var issue : disk.issues()) log.warn("Session catalog entry could not be loaded file={} reason={} diagnostic={}",
                com.demo.logging.LogData.quote(issue.fileName()), issue.reason(), com.demo.logging.LogData.quote(issue.message()));
        Map<String, SessionSummary> summaries = new HashMap<>();
        disk.sessions().forEach(summary -> summaries.put(summary.id(), summary));
        sessions.values().forEach(session -> summaries.put(session.id, SessionSummary.from(session.published)));
        return new SessionCatalog(new ArrayList<>(summaries.values()), disk.issues());
    }

    @Override
    public SessionSnapshot getSession(String id) {
        ensureReadable();
        validateId(id);
        Session loaded = sessions.get(id);
        if (loaded != null) return loaded.published;
        enterMutation();
        try { return loadSession(id).published; }
        finally { mutations.unlock(); }
    }

    @Override
    public Optional<RunHandle> getActiveRun() {
        ensureReadable();
        return Optional.ofNullable(active);
    }

    @Override
    public RunHandle submitTurn(String id, String message) {
        enterMutation();
        try {
            if (message == null || message.isBlank()) {
                throw new SessionException(SessionException.Reason.INVALID_INPUT, "Message must not be blank");
            }
            if (cleanupFailure != null) throw new SessionException(SessionException.Reason.RESOURCE_FAILURE,
                    "A prior resource has not been cleaned up; close the service to retry cleanup");
            Session session = loadSession(id);
            if (sessions.values().stream().anyMatch(item -> item.unsaved)) {
                throw new SessionException(SessionException.Reason.UNSAVED_SESSION,
                        "Retry saving unsaved sessions before starting another turn");
            }
            workspaces.validateStored(session.workspace);
            if (session.agent == null) session.agent = new CodingAgent(client,
                    ToolRegistry.fromConfig(config, session.workspace), config.getMaxIterations());
            ActiveRun run = new ActiveRun(session.id, clock);
            active = run;
            try {
                executor.execute(() -> executeTurn(session, message, run));
            } catch (RejectedExecutionException e) {
                active = null;
                throw new SessionException(SessionException.Reason.RESOURCE_FAILURE, "Task could not be scheduled", e);
            }
            return run;
        } finally { mutations.unlock(); }
    }

    @Override
    public SessionSnapshot runTurn(String id, String message) {
        try { return submitTurn(id, message).completion().toCompletableFuture().join(); }
        catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            if (e.getCause() instanceof Error cause) throw cause;
            throw e;
        }
    }

    private void executeTurn(Session session, String message, ActiveRun run) {
        try (var loggingScope = org.apache.logging.log4j.CloseableThreadContext.put("session_id", session.id)
                .put("run_id", run.runId())) {
            executeLoggedTurn(session, message, run);
        }
    }

    private void executeLoggedTurn(Session session, String message, ActiveRun run) {
        log.info("Turn started");
        if (log.isDebugEnabled()) log.debug("User message text={}", com.demo.logging.LogData.quote(message));
        worker = Thread.currentThread();
        Throwable failure = null;
        if (session.context.getMessages().isEmpty()) session.title = titleFor(message);
        session.lastRun = SessionRun.started(run.runId(), now(session));
        session.context.addUserMessage(message);
        touch(session);
        try {
            persist(session);
            run.running();
            AgentRunResult result = session.agent.runTurn(session.context, (context, iteration) -> {
                session.lastRun = session.lastRun.atIteration(iteration);
                if (run.cancellation.token().isCancellationRequested()) session.lastRun = session.lastRun.cancelling();
                touch(session);
                persist(session);
            }, run.cancellation.token(), run::progress);
            if (run.beginFinishing()) result = AgentRunResult.cancelled(result.getIterations());
            if (!run.cancellation.callbackFailures().isEmpty()) {
                throw new ResourceCleanupException("A cancellation resource callback failed", run.cancellation::retryFailedCallbacks);
            }
            session.lastRun = session.lastRun.finish(result, now(session));
            touch(session);
            persist(session);
        } catch (SessionStorageException e) {
            run.beginFinishing();
            markStorageFailure(session);
            failure = e;
        } catch (Throwable e) {
            log.error("Turn failed exception={}", com.demo.logging.LogData.exception(e));
            run.beginFinishing();
            if (e instanceof ResourceCleanupException resource) cleanupFailure = resource;
            session.apply(recovery.recover(session.publish(), now(session)).snapshot());
            session.lastRun = session.lastRun.finish(SessionRun.Status.FAILED, now(session),
                    e instanceof ResourceCleanupException ? "Resource cleanup could not be confirmed."
                            : "Turn failed unexpectedly; no operations were replayed.");
            touch(session);
            try { persist(session); }
            catch (SessionStorageException storage) { markStorageFailure(session); failure = storage; }
            if (failure == null && e instanceof ResourceCleanupException) {
                failure = new SessionException(SessionException.Reason.RESOURCE_FAILURE, session.lastRun.message(), e);
            } else if (failure == null && e instanceof Error) failure = e;
        } finally {
            // Storage errors must not hide a failed cancellation cleanup callback.
            if (!run.cancellation.callbackFailures().isEmpty()) {
                ResourceCleanupException prior = cleanupFailure;
                cleanupFailure = new ResourceCleanupException("Cancellation cleanup remains incomplete", () -> {
                    if (prior != null) prior.retryCleanup();
                    run.cancellation.retryFailedCallbacks();
                });
            }
            // All state writes and resource cleanup precede terminal publication and releasing admission.
            run.finish(session.published, failure);
            mutations.lock();
            try { active = null; worker = null; }
            finally { mutations.unlock(); }
            run.drained.countDown();
            run.publishCompletion(session.published, failure);
            log.info("Turn finished status={} iterations={} unsaved={}", session.lastRun.status(),
                    session.lastRun.iterations(), session.unsaved);
        }
    }

    @Override
    public SessionSnapshot retrySave(String id) {
        enterMutation();
        try {
            Session session = loadSession(id);
            savePending(session);
            log.info("Session save retried session_id={}", id);
            return session.published;
        } finally { mutations.unlock(); }
    }

    @Override
    public void close() {
        if (lifecycle == Lifecycle.CLOSED) return;
        if (Thread.currentThread() == worker || !closeGate.tryLock()) {
            throw new SessionException(SessionException.Reason.BUSY, "Cannot close from the running task or concurrent close");
        }
        long deadline = System.nanoTime() + closeTimeout.toNanos();
        try {
            if (!mutations.tryLock()) throw new SessionException(SessionException.Reason.BUSY, "Session service is busy");
            ActiveRun run;
            try {
                if (lifecycle == Lifecycle.CLOSED) return;
                lifecycle = Lifecycle.CLOSING;
                run = active;
            } finally { mutations.unlock(); }
            if (run != null) {
                run.cancel();
                if (!run.drained.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) throw shutdownTimeout();
            }
            if (cleanupFailure != null) {
                try { cleanupFailure.retryCleanup(); cleanupFailure = null; }
                catch (RuntimeException e) {
                    throw new SessionException(SessionException.Reason.RESOURCE_FAILURE, "Resource cleanup is still incomplete", e);
                }
            }
            SessionStorageException failure = null;
            for (Session session : sessions.values()) {
                try { savePending(session); }
                catch (SessionStorageException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            if (failure != null) {
                lifecycle = Lifecycle.OPEN;
                throw failure;
            }
            executor.shutdown();
            if (!executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) throw shutdownTimeout();
            if (ownership == ClientOwnership.OWNED) {
                try { client.close(); }
                catch (RuntimeException e) {
                    throw new SessionException(SessionException.Reason.RESOURCE_FAILURE, "Failed to release the model client", e);
                }
            }
            lifecycle = Lifecycle.CLOSED;
            log.info("Session service closed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Session service close interrupted");
            throw shutdownTimeout();
        } catch (RuntimeException e) {
            if (!(e instanceof SessionStorageException))
                log.error("Session service close failed exception={}", com.demo.logging.LogData.exception(e));
            throw e;
        } finally { closeGate.unlock(); }
    }

    private SessionException shutdownTimeout() {
        return new SessionException(SessionException.Reason.SHUTDOWN_TIMEOUT,
                "Service is still closing; active resources have not been released. Retry close after completion.");
    }

    private Session loadSession(String id) {
        validateId(id);
        Session existing = sessions.get(id);
        if (existing != null) return existing;
        SessionSnapshot stored;
        try { stored = repository.load(id); }
        catch (SessionStorageException e) {
            log.log(e.getReason() == SessionStorageException.Reason.NOT_FOUND
                            ? org.apache.logging.log4j.Level.WARN : org.apache.logging.log4j.Level.ERROR,
                    "Session load failed session_id={} exception={}", id, com.demo.logging.LogData.exception(e));
            if (e.getReason() == SessionStorageException.Reason.NOT_FOUND) {
                throw new SessionException(SessionException.Reason.NOT_FOUND, "Session not found: " + id, e);
            }
            throw e;
        }
        SessionRecovery.RecoveryResult restored = recovery.recover(stored, clock.instant());
        log.info("Session restored session_id={} workspace={}", id, com.demo.logging.LogData.quote(stored.workspace()));
        if (restored.changed()) log.warn("Interrupted session recovered without replay session_id={}", id);
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
        try {
            repository.save(session.publish());
        } catch (RuntimeException e) {
            log.error("Checkpoint save failed session_id={} exception={}", session.id, com.demo.logging.LogData.exception(e));
            throw e;
        }
        session.unsaved = false;
        session.publish();
        log.debug("Checkpoint saved session_id={}", session.id);
    }
    private Instant now(Session session) {
        Instant now = clock.instant();
        if (now.isBefore(session.updatedAt)) now = session.updatedAt;
        if (session.lastRun != null) {
            if (now.isBefore(session.lastRun.startedAt())) now = session.lastRun.startedAt();
            if (session.lastRun.endedAt() != null && now.isBefore(session.lastRun.endedAt())) now = session.lastRun.endedAt();
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
    private void ensureReadable() {
        if (lifecycle == Lifecycle.CLOSED) throw new SessionException(SessionException.Reason.CLOSED, "Session service is closed");
    }
    private void ensureOpen() {
        ensureReadable();
        if (lifecycle == Lifecycle.CLOSING) throw new SessionException(SessionException.Reason.CLOSING, "Session service is closing");
    }
    private void enterMutation() {
        ensureOpen();
        if (mutations.isHeldByCurrentThread() || !mutations.tryLock()) {
            throw new SessionException(SessionException.Reason.BUSY, "Session service is busy");
        }
        try {
            ensureOpen();
            if (active != null) throw new SessionException(SessionException.Reason.BUSY, "A turn is already active");
        } catch (RuntimeException e) { mutations.unlock(); throw e; }
    }
    private void validateId(String id) {
        try { SessionSnapshot.requireId(id); }
        catch (IllegalArgumentException e) {
            throw new SessionException(SessionException.Reason.INVALID_INPUT, "Session ID must be a canonical UUID", e);
        }
    }
}
