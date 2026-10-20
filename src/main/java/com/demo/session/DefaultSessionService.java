package com.demo.session;

import com.demo.agent.AgentObserver;
import com.demo.agent.AgentRunResult;
import com.demo.agent.CodingAgent;
import com.demo.config.Config;
import com.demo.execution.ResourceCleanupException;
import com.demo.llm.LLMClient;
import com.demo.llm.LLMClientFactory;
import com.demo.memory.MemoryTelemetry;
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
    private final com.demo.memory.MemoryHarness memoryHarness;
    private final MemoryTelemetry telemetry;
    private final WorkspaceResolver workspaces = new WorkspaceResolver();
    private final SessionRecovery recovery = new SessionRecovery();
    private final Map<String, Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final ReentrantLock mutations = new ReentrantLock();
    private final ReentrantLock closeGate = new ReentrantLock();
    private volatile Lifecycle lifecycle = Lifecycle.OPEN;
    private volatile ActiveRun active;
    private volatile Thread worker;
    private volatile ResourceCleanupException cleanupFailure;
    private final com.demo.memory.MemoryMaintenance maintenance;
    private final Map<String, com.demo.memory.consolidation.ConsolidationService.Display> displayedMemory = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Path, ReentrantLock> projectReservations = new java.util.concurrent.ConcurrentHashMap<>();
    private record MemoryRetry(int attempts, Instant retryAt) { }
    private final Map<Path, MemoryRetry> consolidationFailures = new java.util.concurrent.ConcurrentHashMap<>();

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
        this(config, client, repository, clock, ownership, newExecutor(), Duration.ofSeconds(5));
    }

    /** Explicit harness injection isolates global memory in tests and embedded applications. */
    public DefaultSessionService(Config config, LLMClient client, SessionRepository repository, Clock clock,
                                 ClientOwnership ownership, com.demo.memory.MemoryHarness memoryHarness) {
        this(config, client, repository, clock, ownership, newExecutor(), Duration.ofSeconds(5), memoryHarness);
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "coding-agent-runner");
            thread.setDaemon(false);
            return thread;
        });
    }
    DefaultSessionService(Config config, LLMClient client, SessionRepository repository, Clock clock,
                          ClientOwnership ownership, ExecutorService executor, Duration closeTimeout) {
        this(config, client, repository, clock, ownership, executor, closeTimeout, null);
    }

    private DefaultSessionService(Config config, LLMClient client, SessionRepository repository, Clock clock,
                          ClientOwnership ownership, ExecutorService executor, Duration closeTimeout,
                          com.demo.memory.MemoryHarness memoryHarness) {
        this.config = Objects.requireNonNull(config);
        com.demo.logging.LogData.registerSecret(config.getApiKey());
        this.telemetry = new MemoryTelemetry(clock);
        this.client = new com.demo.llm.ObservedLLMClient(Objects.requireNonNull(client), telemetry::record);
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        this.ownership = Objects.requireNonNull(ownership);
        this.executor = Objects.requireNonNull(executor);
        this.closeTimeout = Objects.requireNonNull(closeTimeout);
        this.memoryHarness = !config.getMemory().enabled() ? null : memoryHarness != null ? memoryHarness
                : new com.demo.memory.MemoryHarness(config.getMemory(), config.getMaxTokens(),
                        com.demo.memory.MemoryHarness.defaultGlobalDirectory());
        if (config.getMaxIterations() <= 0 || closeTimeout.isNegative() || closeTimeout.isZero()) {
            throw new IllegalArgumentException("Iteration limit and close timeout must be positive");
        }
        maintenance = this.memoryHarness != null
                ? new com.demo.memory.MemoryMaintenance(repository, clock,
                    Duration.ofHours(config.getMemory().maintenance().minIntervalHours()),
                    config.getMemory().maintenance().scanIntervalSeconds(), this::organizeSnapshot,
                    config.getMemory().maintenance().enabled(), (snapshot, token) -> {
                        if (config.getMemory().consolidation().enabled()) consolidateProject(snapshot.workspace(), false, token,
                                snapshot.id(), MemoryTelemetry.Origin.BACKGROUND);
                    }) : null;
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
    public RunHandle submitTurn(String id, String message) { return submitTurn(id,message,RunOptions.defaults()); }
    @Override public RunHandle submitTurn(String id, String message, RunOptions options) {
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
            if (session.agent == null) {
                var tools = ToolRegistry.fromConfig(config, session.workspace);
                if (memoryHarness != null) tools = tools.withHistory(new com.demo.memory.history.HistorySearchService(session.workspace, repository))
                        .withMemoryProposals(consolidation(session.workspace), session::publish, () -> {
                            var display = displayedMemory.get(session.id);
                            if (display == null) return false;
                            String user = session.context.getMessages().stream().filter(m -> m.getRole().equals("user"))
                                    .reduce((a,b) -> b).orElseThrow().getContent();
                            boolean resolved = consolidation(session.workspace).resolve(display, user, session.id, active.cancellation.token());
                            if (resolved) displayedMemory.remove(session.id);
                            return resolved;
                        });
                session.agent = new CodingAgent(client, tools, config.getMaxIterations());
            }
            ActiveRun run = new ActiveRun(session.id, clock, Objects.requireNonNull(options));
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

    @Override public com.google.gson.JsonObject searchHistory(String id, String query) {
        ensureReadable();
        if (memoryHarness == null) throw new com.demo.memory.history.HistoryAccessException(com.demo.memory.history.HistoryAccessException.Reason.DISABLED);
        return new com.demo.memory.history.HistorySearchService(getSession(id).workspace(), repository)
                .search(query, com.demo.memory.history.HistorySearchService.DEFAULT_RESULTS, com.demo.execution.CancellationToken.none());
    }

    @Override public com.google.gson.JsonObject readHistory(String id, String historyId, boolean includeSources) {
        ensureReadable();
        if (memoryHarness == null) throw new com.demo.memory.history.HistoryAccessException(com.demo.memory.history.HistoryAccessException.Reason.DISABLED);
        return new com.demo.memory.history.HistorySearchService(getSession(id).workspace(), repository)
                .read(historyId, includeSources, com.demo.execution.CancellationToken.none());
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
        var prompt = new com.demo.agent.PromptBuilder();
        prompt.setWorkspace(session.workspace);
        com.demo.memory.context.ContextPreparation preparation = memoryHarness == null
                ? new com.demo.memory.context.ContextWindowStrategy() : memoryHarness.preparation(
                session.id, session.workspace, () -> session.memoryState,
                new SessionArchiveCoordinator(session, repository),
                new com.demo.memory.compaction.MemoryModel(client, config.getMemory(), config.getMaxTokens()), clock,
                phase -> {
                    run.events.append(SessionEvent.Type.MEMORY_COMPACTION, new SessionEvent.MemoryCompaction(phase));
                    telemetry.event(MemoryTelemetry.projectKey(projectRoot(session.workspace)),session.id,MemoryTelemetry.Origin.FOREGROUND,
                            MemoryTelemetry.Operation.COMPACTION,switch(phase) {
                                case STARTED -> MemoryTelemetry.Phase.STARTED;
                                case COMPLETED -> MemoryTelemetry.Phase.SUCCEEDED;
                                case CANCELLED -> MemoryTelemetry.Phase.CANCELLED;
                                case FAILED -> run.cancellation.token().isCancellationRequested() ? MemoryTelemetry.Phase.CANCELLED : MemoryTelemetry.Phase.FAILED;
                            });
                },
                failureInfo -> run.progress(com.demo.agent.AgentEvent.llmRetry(0, failureInfo)));
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
            }, new com.demo.tools.ToolExecutionContext(run.cancellation.token(), run.approvals,
                    repository instanceof JsonSessionRepository json ? json.directory().resolve("command-output") : null,
                    text -> run.events.append(SessionEvent.Type.COMMAND_OUTPUT,new SessionEvent.CommandOutput(text)),run.runId()), run::progress, prompt, preparation);
            if (run.beginFinishing()) result = AgentRunResult.cancelled(result.getIterations());
            if (!run.cancellation.callbackFailures().isEmpty()) {
                throw new ResourceCleanupException("A cancellation resource callback failed", run.cancellation::retryFailedCallbacks);
            }
            session.lastRun = session.lastRun.finish(result, now(session));
            touch(session);
            persist(session);
            if (memoryHarness != null && config.getMemory().consolidation().enabled()) {
                try { consolidateProject(session.workspace, false, run.memoryCancellation.token(),session.id,MemoryTelemetry.Origin.FOREGROUND); }
                catch (RuntimeException maintenanceFailure) {
                    log.warn("Turn saved; memory extraction deferred exception={}", com.demo.logging.LogData.exception(maintenanceFailure));
                }
            }
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
            if (!run.cancellation.callbackFailures().isEmpty() || !run.memoryCancellation.callbackFailures().isEmpty()) {
                ResourceCleanupException prior = cleanupFailure;
                cleanupFailure = new ResourceCleanupException("Cancellation cleanup remains incomplete", () -> {
                    if (prior != null) prior.retryCleanup();
                    run.cancellation.retryFailedCallbacks();
                    run.memoryCancellation.retryFailedCallbacks();
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
                run.memoryCancellation.cancel();
                run.cancel();
                if (!run.drained.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) throw shutdownTimeout();
            }
            if (maintenance != null && !maintenance.close(deadline)) throw shutdownTimeout();
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

    private com.demo.memory.consolidation.ConsolidationService consolidation(Path workspace) {
        if (memoryHarness == null) throw new com.demo.memory.history.HistoryAccessException(com.demo.memory.history.HistoryAccessException.Reason.DISABLED);
        return new com.demo.memory.consolidation.ConsolidationService(workspace, memoryHarness.globalDirectory(), config.getMemory(),
                new com.demo.memory.consolidation.ConsolidationModel(client, config.getMemory(), config.getMaxTokens()), clock);
    }
    @Override public java.util.concurrent.CompletionStage<String> requestMemoryMaintenance(String id, String operation) {
        ensureOpen();
        if (maintenance == null) return java.util.concurrent.CompletableFuture.completedFuture("记忆功能未开启。");
        if (!java.util.Set.of("compact", "consolidate").contains(operation)) throw new IllegalArgumentException("Unknown maintenance operation");
        var snapshot = getSession(id);
        return maintenance.request(token -> {
            if (operation.equals("compact")) {
                organizeSnapshot(snapshot, token, MemoryTelemetry.Origin.MANUAL);
                return "会话整理结束；被并发修改的快照未提交。";
            }
            return "已处理新增历史 " + consolidateProject(snapshot.workspace(), true, token,snapshot.id(),MemoryTelemetry.Origin.MANUAL) + " 条。";
        });
    }
    @Override public boolean cancelMemoryMaintenance() {
        var run = active;
        boolean foreground = run != null && run.memoryCancellation.cancel();
        return (maintenance != null && maintenance.cancelManual()) || foreground;
    }
    @Override public String nextMemoryCandidate(String id) {
        if (memoryHarness == null || displayedMemory.containsKey(id) || active != null || lifecycle != Lifecycle.OPEN) return "";
        var value = memoryCommand(id, "pending");
        return displayedMemory.containsKey(id) ? value : "";
    }
    private Path projectRoot(Path workspace) {
        try { return new com.demo.memory.ProjectLocator().locate(workspace).root(); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Project unavailable", failure); }
    }
    private int consolidateProject(Path workspace, boolean force, com.demo.execution.CancellationToken token,
                                   String sessionId, MemoryTelemetry.Origin origin) {
        var root = projectRoot(workspace);
        var lock = projectReservations.computeIfAbsent(root, ignored -> new ReentrantLock());
        if (!lock.tryLock()) return 0;
        try {
            var failed = consolidationFailures.get(root);
            if (!force && failed != null && clock.instant().isBefore(failed.retryAt())) return 0;
            var service = consolidation(workspace);
            // Idle scans below threshold should not generate progress spam.
            if (!force && !service.needsRecovery() && service.unprocessed(token) < config.getMemory().consolidation().newHistoryThreshold()) return 0;
            int result = activity(workspace,sessionId,origin,MemoryTelemetry.Operation.CONSOLIDATION,
                    () -> service.consolidate(force, token), count -> count > 0);
            consolidationFailures.remove(root); return result;
        } catch (com.demo.execution.OperationCancelledException cancelled) { throw cancelled; }
        catch (RuntimeException failure) {
            var old = consolidationFailures.get(root); int attempts = old == null ? 1 : Math.min(5, old.attempts() + 1);
            consolidationFailures.put(root, new MemoryRetry(attempts, clock.instant().plusSeconds(Math.min(3600, 300L << (attempts - 1)))));
            throw failure;
        }
        finally { lock.unlock(); }
    }
    /** Requests run outside the mutation lock; only a fresh, idle snapshot may commit. */
    private void organizeSnapshot(SessionSnapshot expected, com.demo.execution.CancellationToken token) {
        organizeSnapshot(expected,token,MemoryTelemetry.Origin.BACKGROUND);
    }
    private void organizeSnapshot(SessionSnapshot expected, com.demo.execution.CancellationToken token, MemoryTelemetry.Origin origin) {
        boolean committed = activity(expected.workspace(),expected.id(),origin,MemoryTelemetry.Operation.COMPACTION,
                () -> organizeSnapshotCore(expected,token), Boolean::booleanValue);
        if (committed && config.getMemory().consolidation().enabled()) consolidateProject(expected.workspace(),false,token,expected.id(),origin);
    }
    private <T> T activity(Path workspace,String sessionId,MemoryTelemetry.Origin origin,MemoryTelemetry.Operation operation,
                           java.util.function.Supplier<T> work,java.util.function.Predicate<T> succeeded) {
        String key = MemoryTelemetry.projectKey(projectRoot(workspace));
        telemetry.event(key,sessionId,origin,operation,MemoryTelemetry.Phase.STARTED);
        try {
            T result = work.get();
            telemetry.event(key,sessionId,origin,operation,succeeded.test(result)?MemoryTelemetry.Phase.SUCCEEDED:MemoryTelemetry.Phase.SKIPPED);
            return result;
        } catch (com.demo.execution.OperationCancelledException cancelled) {
            telemetry.event(key,sessionId,origin,operation,MemoryTelemetry.Phase.CANCELLED); throw cancelled;
        } catch (RuntimeException failure) {
            telemetry.event(key,sessionId,origin,operation,MemoryTelemetry.Phase.FAILED); throw failure;
        }
    }
    private boolean organizeSnapshotCore(SessionSnapshot expected, com.demo.execution.CancellationToken token) {
        if (expected.lastRun() != null && expected.lastRun().endedAt() == null) return false;
        mutations.lock();
        try {
            if (lifecycle != Lifecycle.OPEN || active != null && active.sessionId().equals(expected.id())) return false;
            var live = sessions.get(expected.id());
            if (live != null && (live.unsaved || !live.published.samePersistedState(expected))) return false;
        } finally { mutations.unlock(); }
        var root = projectRoot(expected.workspace());
        var directory = root.resolve(".coding-agent");
        var compression = expected.memoryState().compression();
        int covered = compression.coveredCount(expected.messages());
        var blocks = new com.demo.memory.context.ConversationBlockBuilder().build(expected.messages());
        int end = blocks.stream().filter(com.demo.memory.context.ConversationBlockBuilder.Block::complete)
                .mapToInt(com.demo.memory.context.ConversationBlockBuilder.Block::end).max().orElse(covered);
        // An interrupted trailing tool call needs explicit session recovery before it is eligible.
        if (end != expected.messages().size()) return false;
        com.demo.memory.model.HistoryEntry entry = null;
        if (end > covered) {
            var selected = expected.messages().subList(covered, end);
            var summary = new com.demo.memory.compaction.MemoryModel(client, config.getMemory(), config.getMaxTokens())
                    .summarize(selected, compression.continuationSummary(), token, ignored -> {});
            entry = new com.demo.memory.model.HistoryEntry(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    expected.id(), expected.contentRevision(), selected.stream().map(com.demo.model.Message::getId).toList(),
                    compression.historyId(), summary.archiveSummary(), summary.continuationSummary(), summary.keywords(),
                    summary.sourceRefs(), clock.instant().isBefore(expected.updatedAt()) ? expected.updatedAt() : clock.instant(), com.demo.memory.compaction.MemoryModel.PROMPT_VERSION);
        }
        token.throwIfCancellationRequested();
        mutations.lock();
        try {
            if (lifecycle != Lifecycle.OPEN || active != null && active.sessionId().equals(expected.id())) return false;
            if (!projectRoot(expected.workspace()).equals(root)) throw new IllegalStateException("Project changed");
            var live = sessions.get(expected.id());
            if (live != null && (live.unsaved || !live.published.samePersistedState(expected))) return false;
            if (!repository.load(expected.id()).samePersistedState(expected)) return false;
            Instant now = clock.instant().isBefore(expected.updatedAt()) ? expected.updatedAt() : clock.instant();
            SessionSnapshot updated;
            var committer = new com.demo.memory.storage.ArchiveCommitter(repository, new com.demo.memory.storage.HistoryRepository(directory));
            committer.recover(expected.id());
            if (entry != null) {
                var target = live != null ? live : new Session(expected);
                new SessionArchiveCoordinator(target, repository).commit(directory, expected, entry, now);
                updated = target.published;
            }
            else {
                updated = new SessionSnapshot(expected.id(), expected.title(), expected.workspace(), expected.createdAt(), now,
                        expected.messages(), expected.lastRun(), false, expected.contentRevision(),
                        new com.demo.memory.context.SessionMemoryState(expected.contentRevision(), now, compression));
                repository.compareAndSave(expected, updated);
            }
            if (live != null) {
                live.memoryState = updated.memoryState(); live.updatedAt = updated.updatedAt();
                live.persisted = updated; live.publish();
            }
        } finally { mutations.unlock(); }
        return true;
    }
    @Override public String memoryCommand(String id, String command) {
        ensureOpen();
        var snapshot = getSession(id);
        if (command.equals("status")) {
            var phases=memoryHarness == null ? List.<MemoryTelemetry.Event>of()
                    : telemetry.eventsAfter(MemoryTelemetry.projectKey(projectRoot(snapshot.workspace())),0).events();
            String last=phases.isEmpty()?"暂无维护记录":phases.get(phases.size()-1).origin()+" / "+phases.get(phases.size()-1).operation()+" / "+phases.get(phases.size()-1).phase();
            return "分层记忆：" + (memoryHarness == null ? "已关闭" : "已开启")
                    + "\n后台扫描：" + (memoryHarness != null && config.getMemory().maintenance().enabled() ? "已开启" : "已关闭")
                    + "\n当前会话：" + (snapshot.dirty()?"有新增消息":"已整理")
                    + "\n最近维护："+last
                    + "\n" + telemetry.usageText();
        }
        if (memoryHarness == null) return "记忆功能未开启。";
        if (command.equals("consolidate") || command.equals("compact")) {
            // Model maintenance belongs on the dedicated cancellable executor.
            throw new IllegalArgumentException("Use requestMemoryMaintenance for model maintenance");
        }
        enterMutation();
        try {
            var service = consolidation(snapshot.workspace());
            if (command.equals("pending")) {
                var display = service.display();
                if (display.isEmpty()) { displayedMemory.remove(id); return "当前项目没有待确认记忆。"; }
                displayedMemory.put(id, display.get());
                var candidate = display.get();
                return "待确认记忆（" + candidate.proposal().scope() + "）\n已有：" + candidate.currentBody()
                        + "\n提案：" + candidate.proposal().edit().action() + " " + Objects.toString(candidate.proposal().edit().body(), "")
                        + "\n依据：" + candidate.proposal().reason()
                        + "\n回复“保留旧的”或“采用新的”；全局提案须回复“批准全局记忆”。";
            }
            if (command.equals("show")) {
                var project = new com.demo.memory.storage.LongTermMemoryRepository(projectRoot(snapshot.workspace()).resolve(".coding-agent"));
                var global = new com.demo.memory.storage.LongTermMemoryRepository(memoryHarness.globalDirectory());
                return "全局记忆（"+localProvenance(global)+"）：\n" + global.read().text()
                        + "\n项目记忆（"+localProvenance(project)+"）：\n" + project.read().text();
            }
            throw new IllegalArgumentException("Unknown memory command");
        } finally { mutations.unlock(); }
    }
    private String localProvenance(com.demo.memory.storage.LongTermMemoryRepository repository) {
        var items=repository.read().items();
        if(items.isEmpty())return "无条目";
        var ids=repository.changes().stream().flatMap(c -> c.update().edits().stream())
                .map(com.demo.memory.model.MemoryEdit::itemId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        return items.stream().allMatch(item -> ids.contains(item.id())) ? "本地审计可用，原始来源需校验" : "部分或全部本地来源不可用";
    }
    @Override public boolean resolveMemoryReply(String id, String reply) {
        ensureOpen(); var display = displayedMemory.get(id);
        if (display == null) return false;
        enterMutation();
        try {
            boolean resolved = consolidation(getSession(id).workspace()).resolve(display, reply, id, com.demo.execution.CancellationToken.none());
            if (resolved) displayedMemory.remove(id);
            return resolved;
        } finally { mutations.unlock(); }
    }
    @Override public Map<com.demo.llm.RequestPurpose,MemoryTelemetry.Usage> modelUsage() { return telemetry.usage(); }
    @Override public MemoryTelemetry.Page memoryEventsAfter(String id,long cursor) {
        ensureReadable();
        if (memoryHarness == null) return MemoryTelemetry.Page.empty();
        return telemetry.eventsAfter(MemoryTelemetry.projectKey(projectRoot(getSession(id).workspace())),cursor);
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
        session.persisted = stored;
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
            if (memoryHarness != null && session.persisted != null) repository.compareAndSave(session.persisted, session.publish());
            else repository.save(session.publish());
        } catch (UnsupportedOperationException unsupported) {
            throw new SessionStorageException(SessionStorageException.Reason.IO_FAILURE,
                    "Memory-enabled sessions require conditional checkpoints", unsupported);
        } catch (RuntimeException e) {
            log.error("Checkpoint save failed session_id={} exception={}", session.id, com.demo.logging.LogData.exception(e));
            throw e;
        }
        session.unsaved = false;
        session.publish();
        session.persisted = session.published;
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
