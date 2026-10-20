package com.demo.memory.context;

import com.demo.model.Message;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Shared protocol boundaries for selection, budgeting and future compaction. */
public final class ConversationBlockBuilder {
    public record Block(int start, int end, List<Message> messages, boolean complete) {
        public Block { messages = List.copyOf(messages); }
    }

    /** One preparation-local analysis; no cache survives a new request or summary commit. */
    public record Analysis(List<Block> blocks, int covered, List<Long> blockTokens) {
        public Analysis {
            blocks = List.copyOf(blocks);
            blockTokens = List.copyOf(blockTokens);
        }
        public List<Block> recentBlocks() {
            return blocks.stream().filter(block -> block.start() >= covered).toList();
        }
        public List<Long> recentBlockTokens() {
            int first = 0;
            while (first < blocks.size() && blocks.get(first).start() < covered) first++;
            return blockTokens.subList(first, blockTokens.size());
        }
        public long recentTokens() {
            long total = 0;
            for (int i = 0; i < blocks.size(); i++) if (blocks.get(i).start() >= covered) total += blockTokens.get(i);
            return total;
        }
        public long largestRecentBlock() {
            long largest = 0;
            for (int i = 0; i < blocks.size(); i++) if (blocks.get(i).start() >= covered) largest = Math.max(largest, blockTokens.get(i));
            return largest;
        }
    }

    public Analysis analyze(List<Message> messages, CompressionState compression, TokenEstimator estimator) {
        var parsed = build(messages);
        int covered = compression.coveredCount(messages, parsed);
        return new Analysis(parsed, covered, parsed.stream().map(block -> estimator.messages(block.messages())).toList());
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
