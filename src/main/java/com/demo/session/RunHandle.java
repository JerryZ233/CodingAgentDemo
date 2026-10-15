package com.demo.session;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface RunHandle {
    enum State { STARTING, RUNNING, CANCELLING, FINISHING, FINISHED }
    enum CancelResult { REQUESTED, ALREADY_REQUESTED, TOO_LATE, ALREADY_FINISHED }
    String sessionId();
    String runId();
    State state();
    CompletionStage<SessionSnapshot> completion();
    List<SessionEvent> eventsAfter(long sequence);
    CancelResult cancel();
}
