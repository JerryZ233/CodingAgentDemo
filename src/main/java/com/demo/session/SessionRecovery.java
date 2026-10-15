package com.demo.session;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Repairs protocol structure without replaying any model request or filesystem operation. */
public final class SessionRecovery {
    public static final String UNKNOWN_TOOL_RESULT =
            "Program interrupted; execution outcome is unknown. This tool call was not replayed.";
    public static final String INTERRUPTED_MESSAGE = "Previous turn was interrupted; no operations were replayed.";

    public RecoveryResult recover(SessionSnapshot source, Instant now) {
        List<Message> history = new ArrayList<>();
        Map<String, ToolCall> pending = new LinkedHashMap<>();
        int repaired = 0;
        for (Message message : source.messages()) {
            if (!"tool".equals(message.getRole())) {
                repaired += appendUnknownResults(history, pending);
            }
            history.add(message);
            if (message.getToolCalls() != null) {
                message.getToolCalls().forEach(call -> pending.put(call.getId(), call));
            }
            if ("tool".equals(message.getRole())) pending.remove(message.getToolCallId());
        }
        repaired += appendUnknownResults(history, pending);
        SessionRun run = source.lastRun();
        boolean interrupted = run != null && (run.status() == SessionRun.Status.RUNNING
                || run.status() == SessionRun.Status.STORAGE_ERROR);
        if (!interrupted && repaired == 0) return new RecoveryResult(source, false, null);
        Instant updated = now.isBefore(source.updatedAt()) ? source.updatedAt() : now;
        if (run == null) run = SessionRun.started(source.createdAt());
        run = run.finish(SessionRun.Status.INTERRUPTED, updated, INTERRUPTED_MESSAGE);
        return new RecoveryResult(new SessionSnapshot(source.id(), source.title(), source.workspace(),
                source.createdAt(), updated, history, run, true), true, INTERRUPTED_MESSAGE);
    }

    private int appendUnknownResults(List<Message> history, Map<String, ToolCall> pending) {
        int count = pending.size();
        for (ToolCall call : pending.values()) {
            history.add(Message.tool(call.getId(), call.getToolName(), UNKNOWN_TOOL_RESULT,
                    false, UNKNOWN_TOOL_RESULT));
        }
        pending.clear();
        return count;
    }

    public record RecoveryResult(SessionSnapshot snapshot, boolean changed, String explanation) { }
}
