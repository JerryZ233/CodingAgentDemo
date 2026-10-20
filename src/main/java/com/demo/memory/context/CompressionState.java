package com.demo.memory.context;

import com.demo.model.Message;
import java.util.List;

/** A committed prefix replacement. Raw messages remain in the session document. */
public record CompressionState(String coveredThroughMessageId, String historyId,
                               String continuationSummary, long version) {
    public CompressionState {
        if (version < 0) throw new IllegalArgumentException("Negative compression version");
        if (coveredThroughMessageId == null) {
            if (historyId != null || continuationSummary == null || !continuationSummary.isEmpty() || version != 0) {
                throw new IllegalArgumentException("Invalid empty compression state");
            }
        } else {
            Message.requireId(coveredThroughMessageId);
            if (historyId == null || historyId.isBlank()
                    || continuationSummary == null || continuationSummary.isBlank() || version == 0) {
                throw new IllegalArgumentException("Incomplete compression state");
            }
        }
    }

    public static CompressionState empty() { return new CompressionState(null, null, "", 0); }

    /** Returns the exclusive end of the covered prefix and validates its protocol boundary. */
    public int coveredCount(List<Message> messages) {
        if (coveredThroughMessageId == null) return 0;
        return coveredCount(messages, new ConversationBlockBuilder().build(messages));
    }

    int coveredCount(List<Message> messages, List<ConversationBlockBuilder.Block> blocks) {
        if (coveredThroughMessageId == null) return 0;
        for (var block : blocks) {
            for (Message message : block.messages()) {
                if (coveredThroughMessageId.equals(message.getId())) {
                    if (!block.complete() || !message.getId().equals(messages.get(block.end() - 1).getId())) {
                        throw new IllegalArgumentException("Compression splits a tool block");
                    }
                    return block.end();
                }
            }
        }
        throw new IllegalArgumentException("Compression boundary is not in session history");
    }
}
