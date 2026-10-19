package com.demo.tools;

import com.demo.execution.CancellationToken;
import com.demo.tools.process.ApprovalCoordinator;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Host-owned turn services, never decoded from tool arguments. */
public record ToolExecutionContext(CancellationToken token, ApprovalCoordinator approvals,
        Path outputDirectory, Consumer<String> outputEvents, String runIdentity) {
    public ToolExecutionContext(CancellationToken token, ApprovalCoordinator approvals, Path outputDirectory, Consumer<String> outputEvents) {
        this(token,approvals,outputDirectory,outputEvents,null);
    }
    public static ToolExecutionContext unavailable(CancellationToken token) {
        return new ToolExecutionContext(token, new ApprovalCoordinator(false, event -> {}), null, text -> {});
    }
}
