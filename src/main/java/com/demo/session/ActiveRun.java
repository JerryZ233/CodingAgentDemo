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
    final CountDownLatch drained = new CountDownLatch(1);
    final RunEventBuffer events;
    private final CompletableFuture<SessionSnapshot> result = new CompletableFuture<>();
    private State state = State.STARTING;

    ActiveRun(String sessionId, Clock clock) {
        this.sessionId = sessionId;
        events = new RunEventBuffer(sessionId, id, clock);
        events.append(SessionEvent.Type.RUN_STARTED, new SessionEvent.Progress(0));
    }

    public String sessionId() { return sessionId; }
    public String runId() { return id; }
    public synchronized State state() { return state; }
    public CompletionStage<SessionSnapshot> completion() { return result.minimalCompletionStage(); }
    public List<SessionEvent> eventsAfter(long sequence) { return events.after(sequence); }

    public synchronized CancelResult cancel() {
        if (state == State.FINISHED) return CancelResult.ALREADY_FINISHED;
        if (state == State.FINISHING) return CancelResult.TOO_LATE;
        if (state == State.CANCELLING) return CancelResult.ALREADY_REQUESTED;
        // The token flag and cancellation event are linearized against step admission.
        cancellation.cancel(() -> {
            state = State.CANCELLING;
            events.append(SessionEvent.Type.CANCEL_REQUESTED, new SessionEvent.Progress(0));
        });
        return CancelResult.REQUESTED;
    }

    synchronized void running() { if (state == State.STARTING) state = State.RUNNING; }

    synchronized boolean beginFinishing() {
        boolean cancelled = state == State.CANCELLING;
        state = State.FINISHING;
        return cancelled;
    }

    void progress(AgentEvent event) {
        switch (event.getType()) {
            case ITERATION_STARTED -> events.append(SessionEvent.Type.ITERATION_STARTED, new SessionEvent.Progress(event.getIteration()));
            case LLM_REQUEST_STARTED -> events.append(SessionEvent.Type.LLM_REQUEST_STARTED, new SessionEvent.Progress(event.getIteration()));
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
        state = State.FINISHED;
    }

    void publishCompletion(SessionSnapshot snapshot, Throwable failure) {
        // User completion callbacks never execute on the dedicated agent worker.
        CompletableFuture.runAsync(() -> {
            if (failure == null) result.complete(snapshot); else result.completeExceptionally(failure);
        });
    }
}
