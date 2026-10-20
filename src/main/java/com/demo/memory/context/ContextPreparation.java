package com.demo.memory.context;

import com.demo.execution.CancellationToken;
import com.demo.model.Message;
import java.util.List;
import java.util.Optional;

/** Explicit request preparation boundary; implementations may assemble or compact memory. */
@FunctionalInterface
public interface ContextPreparation {
    Input prepare(Request request, CancellationToken token);

    /** A detached snapshot captured once before preparation, including compaction retries. */
    record Request(String systemPrompt, String toolsDescription, List<Message> messages, long contentRevision) {
        public Request {
            messages = messages.stream().map(Message::copyOf).toList();
            if (contentRevision < messages.size()) throw new IllegalArgumentException("Invalid content revision");
        }
    }

    record Input(List<Message> messages, String toolsDescription, Optional<ContextBudget.Report> budget) {
        public Input {
            messages = messages.stream().map(Message::copyOf).toList();
            java.util.Objects.requireNonNull(budget);
        }
    }
}
