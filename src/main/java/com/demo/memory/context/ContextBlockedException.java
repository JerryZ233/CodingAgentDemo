package com.demo.memory.context;

import java.util.Optional;

/** Expected preparation outcome, never appended as an assistant transcript message. */
public final class ContextBlockedException extends RuntimeException {
    public enum Reason { BUDGET_EXCEEDED, INVALID_HISTORY, UNCOMMITTED_SUMMARY, MEMORY_UNAVAILABLE, COMPACTION_FAILED }
    private final Reason reason;
    private final ContextBudget.Report report;

    public ContextBlockedException(Reason reason, ContextBudget.Report report) {
        this(reason, report, null);
    }

    public ContextBlockedException(Reason reason, ContextBudget.Report report, Throwable cause) {
        super("Conversation preparation blocked: " + reason + (report == null ? ""
                : "; estimated_input=" + report.estimatedInputTokens() + "; input_budget="
                + report.inputBudgetTokens() + "; exceeded=" + report.exceeded()), cause);
        this.reason = java.util.Objects.requireNonNull(reason);
        this.report = report;
    }

    public Reason reason() { return reason; }
    public Optional<ContextBudget.Report> report() { return Optional.ofNullable(report); }
}
