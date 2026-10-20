package com.demo.memory;

import com.demo.model.Message;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Shared protocol boundaries for selection, budgeting and future compaction. */
public final class ConversationBlockBuilder {
    public record Block(int start, int end, List<Message> messages, boolean complete) {
        public Block { messages = List.copyOf(messages); }
    }

    /** A trailing unfinished tool block is legal in a checkpoint, but not in a request. */
    public List<Block> build(List<Message> messages) {
        List<Block> result = new ArrayList<>();
        for (int index = 0; index < messages.size();) {
            int start = index;
            Message message = messages.get(index++);
            if ("tool".equals(message.getRole())) throw new IllegalArgumentException("Orphan tool result");
            var pending = new LinkedHashMap<String, String>();
            if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
                if (!"assistant".equals(message.getRole())) throw new IllegalArgumentException("Tool calls require assistant role");
                for (var call : message.getToolCalls()) {
                    if (call.getId() == null || call.getId().isBlank()
                            || pending.putIfAbsent(call.getId(), call.getToolName()) != null) {
                        throw new IllegalArgumentException("Invalid tool call identity");
                    }
                }
                while (index < messages.size() && "tool".equals(messages.get(index).getRole())) {
                    Message tool = messages.get(index++);
                    String name = pending.remove(tool.getToolCallId());
                    if (name == null || !name.equals(tool.getToolName())) {
                        throw new IllegalArgumentException("Unmatched tool result");
                    }
                }
                if (!pending.isEmpty() && index != messages.size()) {
                    throw new IllegalArgumentException("Incomplete tool block inside history");
                }
            }
            result.add(new Block(start, index, messages.subList(start, index), pending.isEmpty()));
        }
        return List.copyOf(result);
    }
}
