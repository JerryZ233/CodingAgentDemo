package com.demo.session;

import com.demo.agent.AgentEvent;
import com.demo.execution.CancellationSource;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

final class ActiveRun implements RunHandle {
    private final String sessionId;
    private final String id = UUID.randomUUID().toString();
    final CancellationSource cancellation = new CancellationSource();
    final CancellationSource memoryCancellation = new CancellationSource();
    final CountDownLatch drained = new CountDownLatch(1);
    final RunEventBuffer events;
    private final CompletableFuture<SessionSnapshot> result = new CompletableFuture<>();
    private final java.util.concurrent.atomic.AtomicReference<State> state = new java.util.concurrent.atomic.AtomicReference<>(State.STARTING);
    final com.demo.tools.process.ApprovalCoordinator approvals;
    public java.util.Optional<com.demo.tools.process.ApprovalCoordinator.PendingApproval> pendingApproval() { return approvals.pending(); }
    public boolean submitApproval(long generation, com.demo.tools.process.ApprovalCoordinator.Decision decision) {
        if (cancellation.token().isCancellationRequested() || state.get()==State.FINISHED || state.get()==State.FINISHING) return false;
        return approvals.submit(generation,decision);
    }
    public void revokeApprovals() { approvals.revoke(); }


    ActiveRun(String sessionId, Clock clock) {
        this(sessionId, clock, RunOptions.defaults());
    }
    ActiveRun(String sessionId, Clock clock, RunOptions options) {
        this.sessionId = sessionId;
        cancellation.token().onCancellation(memoryCancellation::cancel);
        events = new RunEventBuffer(sessionId, id, clock);
        approvals = new com.demo.tools.process.ApprovalCoordinator(options.approvalChannelAvailable(), event -> {
            if (event.kind().equals("requested") && !cancellation.token().isCancellationRequested()) state.compareAndSet(State.RUNNING, State.WAITING_APPROVAL);
            else state.compareAndSet(State.WAITING_APPROVAL,State.RUNNING);
            events.append(SessionEvent.Type.APPROVAL, new SessionEvent.Approval(event.kind()));
        });
        events.append(SessionEvent.Type.RUN_STARTED, new SessionEvent.Progress(0));
    }

    public String sessionId() { return sessionId; }
    public String runId() { return id; }
    public synchronized State state() { return state.get(); }
    public CompletionStage<SessionSnapshot> completion() { return result.minimalCompletionStage(); }
    public List<SessionEvent> eventsAfter(long sequence) { return events.after(sequence); }

    public synchronized CancelResult cancel() {
        if (state.get() == State.FINISHED) return CancelResult.ALREADY_FINISHED;
        if (state.get() == State.FINISHING) return CancelResult.TOO_LATE;
        if (state.get() == State.CANCELLING) return CancelResult.ALREADY_REQUESTED;
        // The token flag and cancellation event are linearized against step admission.
        cancellation.cancel(() -> {
            state.set(State.CANCELLING);
            com.demo.logging.Logging.logger(ActiveRun.class).info("Cancellation requested session_id={} run_id={}", sessionId, id);
            events.append(SessionEvent.Type.CANCEL_REQUESTED, new SessionEvent.Progress(0));
        });
        approvals.close();
        return CancelResult.REQUESTED;
    }

    synchronized void running() { if (state.get() == State.STARTING) state.set(State.RUNNING); }

    synchronized boolean beginFinishing() {
        boolean cancelled = cancellation.token().isCancellationRequested();
        state.set(State.FINISHING);
        return cancelled;
    }

    void progress(AgentEvent event) {
        switch (event.getType()) {
            case ITERATION_STARTED -> events.append(SessionEvent.Type.ITERATION_STARTED, new SessionEvent.Progress(event.getIteration()));
            case CONTEXT_PREPARED -> events.append(SessionEvent.Type.CONTEXT_PREPARED,
                    new SessionEvent.ContextPrepared(event.getContextBudget()));
            case LLM_REQUEST_STARTED -> events.append(SessionEvent.Type.LLM_REQUEST_STARTED, new SessionEvent.Progress(event.getIteration()));
            case LLM_RETRY -> events.append(SessionEvent.Type.LLM_RETRY,
                    new SessionEvent.ModelFailure(event.getIteration(), event.getFailure()));
            case LLM_ERROR -> {
                if (event.getFailure() != null) events.append(SessionEvent.Type.LLM_FAILED,
                        new SessionEvent.ModelFailure(event.getIteration(), event.getFailure()));
            }
            case LLM_RESPONSE -> events.append(SessionEvent.Type.ASSISTANT_MESSAGE,
                    new SessionEvent.AssistantMessage(event.getIteration(), event.getMessage(), event.isFinalResponse()));
            case TOOL_STARTED -> events.append(SessionEvent.Type.TOOL_STARTED,
                    new SessionEvent.ToolStarted(event.getIteration(), event.getToolCallId(), event.getToolName(), event.getArguments()));
            case TOOL_RESULT -> events.append(SessionEvent.Type.TOOL_RESULT,
                    new SessionEvent.ToolFinished(event.getIteration(), event.getToolCallId(), event.getToolName(), event.getToolResult()));
            default -> { } // Legacy loop lifecycle/final callbacks are not displayed a second time.
        }
    }

    synchronized void finish(SessionSnapshot snapshot, Throwable failure) {
        SessionRun run = snapshot.lastRun();
        String diagnostic = run.status() == SessionRun.Status.COMPLETED ? null : run.message();
        events.append(SessionEvent.Type.RUN_FINISHED,
                new SessionEvent.RunFinished(run.status(), run.iterations(), snapshot.unsaved(), diagnostic));
        approvals.close();
        state.set(State.FINISHED);
    }

    void publishCompletion(SessionSnapshot snapshot, Throwable failure) {
        // User completion callbacks never execute on the dedicated agent worker.
        CompletableFuture.runAsync(() -> {
            if (failure == null) result.complete(snapshot); else result.completeExceptionally(failure);
        });
    }
}
