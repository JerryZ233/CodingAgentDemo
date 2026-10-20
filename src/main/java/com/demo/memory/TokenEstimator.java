package com.demo.memory;

import com.demo.model.Message;
import java.util.List;

/** An estimate, not a model tokenizer. Counts only fields sent by the chat adapter. */
public class TokenEstimator {
    public static final int REQUEST_OVERHEAD = 3;
    public static final int MESSAGE_OVERHEAD = 6;

    public long text(String value) {
        if (value == null || value.isEmpty()) return 0;
        long ascii = 0, other = 0;
        for (int index = 0; index < value.length();) {
            int point = value.codePointAt(index);
            if (point < 128) ascii++; else other++;
            index += Character.charCount(point);
        }
        return (ascii + 2) / 3 + other;
    }

    public long message(Message message) {
        long tokens = MESSAGE_OVERHEAD + text(message.getRole()) + text(message.getContent());
        if ("tool".equals(message.getRole())) tokens += text(message.getToolCallId());
        if (message.getToolCalls() != null) for (var call : message.getToolCalls()) {
            tokens += 12 + text(call.getId()) + text(call.getToolName()) + text(call.getArguments());
        }
        return tokens;
    }

    public long messages(List<Message> messages) {
        return messages.stream().mapToLong(this::message).sum();
    }

    public long tools(String description) {
        if (description == null || description.isBlank() || description.strip().equals("[]")) return 0;
        return 8 + text(description);
    }
}
