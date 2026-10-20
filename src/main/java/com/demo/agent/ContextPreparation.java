package com.demo.agent;

import com.demo.execution.CancellationToken;
import com.demo.memory.ContextBudget;
import com.demo.model.Message;
import java.util.List;
import java.util.Optional;

/** Host-owned request boundary. Stage 4 can compact/checkpoint here before reassembling. */
@FunctionalInterface
public interface ContextPreparation {
    Input prepare(Context context, CancellationToken token);

    record Input(List<Message> messages, String toolsDescription, Optional<ContextBudget.Report> budget) {
        public Input {
            messages = messages.stream().map(Message::copyOf).toList();
            java.util.Objects.requireNonNull(budget);
        }
    }
}
