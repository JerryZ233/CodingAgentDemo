package com.demo.session;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface RunHandle {
    enum State { STARTING, RUNNING, WAITING_APPROVAL, CANCELLING, FINISHING, FINISHED }
    enum CancelResult { REQUESTED, ALREADY_REQUESTED, TOO_LATE, ALREADY_FINISHED }
    String sessionId();
    String runId();
    State state();
    CompletionStage<SessionSnapshot> completion();
    List<SessionEvent> eventsAfter(long sequence);
    CancelResult cancel();
    default java.util.Optional<com.demo.tools.process.ApprovalCoordinator.PendingApproval> pendingApproval() { return java.util.Optional.empty(); }
    default boolean submitApproval(long generation, com.demo.tools.process.ApprovalCoordinator.Decision decision) { return false; }
    default void revokeApprovals() { }

}
