package com.demo.session;

import java.nio.file.Path;
import java.util.Optional;

/** UI-independent session backend. At most one admitted turn, no implicit task queue. */
public interface SessionService extends AutoCloseable {
    /** Creation succeeds only after the empty session is saved. */
    SessionSnapshot createSession(Path workspace);
    /** Sorted summaries and per-file errors; does not repair or rewrite sessions. */
    SessionCatalog listSessions();
    /** Lazily restores and checkpoints crash recovery; loaded sessions can be read during a turn. */
    SessionSnapshot getSession(String id);
    /** Errors in model execution are outcomes; storage failures throw and block further turns. */
    SessionSnapshot runTurn(String id, String message);
    RunHandle submitTurn(String id, String message);
    default RunHandle submitTurn(String id, String message, RunOptions options) { return submitTurn(id,message); }
    Optional<RunHandle> getActiveRun();
    default java.util.Map<com.demo.llm.RequestPurpose,com.demo.memory.MemoryTelemetry.Usage> modelUsage() { return java.util.Map.of(); }
    default com.demo.memory.MemoryTelemetry.Page memoryEventsAfter(String id,long sequence) { return com.demo.memory.MemoryTelemetry.Page.empty(); }
    default String memoryCommand(String id, String command) { return "记忆维护未开启。"; }
    default boolean resolveMemoryReply(String id, String reply) { return false; }
    default String nextMemoryCandidate(String id) { return ""; }
    default boolean cancelMemoryMaintenance() { return false; }
    default java.util.concurrent.CompletionStage<String> requestMemoryMaintenance(String id, String operation) {
        return java.util.concurrent.CompletableFuture.completedFuture("记忆维护未开启。");
    }
    default com.google.gson.JsonObject searchHistory(String id, String query) {
        throw new com.demo.memory.history.HistoryAccessException(com.demo.memory.history.HistoryAccessException.Reason.DISABLED);
    }
    default com.google.gson.JsonObject readHistory(String id, String historyId, boolean includeSources) {
        throw new com.demo.memory.history.HistoryAccessException(com.demo.memory.history.HistoryAccessException.Reason.DISABLED);
    }
    /** Repairs interrupted protocol blocks and saves without resuming the failed turn. */
    SessionSnapshot retrySave(String id);
    /** Cancels active work and waits up to five seconds. Failed saving remains retryable. */
    @Override void close();
}
