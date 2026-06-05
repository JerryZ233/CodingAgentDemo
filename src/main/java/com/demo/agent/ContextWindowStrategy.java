package com.demo.agent;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Selects recent conversation history while keeping tool-call/result blocks intact.
 */
public class ContextWindowStrategy {

    public static final int DEFAULT_MAX_HISTORY_CHARS = 64_000;
    public static final int DEFAULT_MAX_HISTORY_MESSAGES = 100;

    private final int maxHistoryChars;
    private final int maxHistoryMessages;

    public ContextWindowStrategy() {
        this(DEFAULT_MAX_HISTORY_CHARS, DEFAULT_MAX_HISTORY_MESSAGES);
    }

    public ContextWindowStrategy(int maxHistoryChars, int maxHistoryMessages) {
        if (maxHistoryChars <= 0) {
            throw new IllegalArgumentException("maxHistoryChars must be positive");
        }
        if (maxHistoryMessages <= 0) {
            throw new IllegalArgumentException("maxHistoryMessages must be positive");
        }
        this.maxHistoryChars = maxHistoryChars;
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public List<Message> selectHistory(List<Message> messages) {
        List<List<Message>> blocks = buildAtomicHistoryBlocks(messages);
        List<Message> selected = new ArrayList<>();
        int selectedChars = 0;
        int selectedMessages = 0;

        for (int i = blocks.size() - 1; i >= 0; i--) {
            List<Message> block = blocks.get(i);
            int blockChars = estimateMessagesChars(block);
            int blockMessages = block.size();
            boolean fits = selectedMessages + blockMessages <= maxHistoryMessages
                    && selectedChars + blockChars <= maxHistoryChars;

            if (fits || selected.isEmpty()) {
                selected.addAll(0, block);
                selectedChars += blockChars;
                selectedMessages += blockMessages;
            } else {
                break;
            }
        }

        return selected;
    }

    private List<List<Message>> buildAtomicHistoryBlocks(List<Message> messages) {
        List<List<Message>> blocks = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            List<Message> block = new ArrayList<>();
            block.add(message);

            if (hasToolCalls(message)) {
                Set<String> expectedToolCallIds = new HashSet<>();
                for (ToolCall toolCall : message.getToolCalls()) {
                    expectedToolCallIds.add(toolCall.getId());
                }

                while (i + 1 < messages.size()) {
                    Message next = messages.get(i + 1);
                    if (!"tool".equals(next.getRole()) || !expectedToolCallIds.contains(next.getToolCallId())) {
                        break;
                    }
                    block.add(next);
                    i++;
                }
            }

            blocks.add(block);
        }

        return blocks;
    }

    private boolean hasToolCalls(Message message) {
        return message.getToolCalls() != null && !message.getToolCalls().isEmpty();
    }

    private int estimateMessagesChars(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += estimateMessageChars(message);
        }
        return total;
    }

    private int estimateMessageChars(Message message) {
        int total = length(message.getRole()) + length(message.getContent())
                + length(message.getToolCallId()) + length(message.getToolName());

        if (message.getToolCalls() != null) {
            for (ToolCall toolCall : message.getToolCalls()) {
                total += length(toolCall.getId()) + length(toolCall.getToolName()) + length(toolCall.getArguments());
            }
        }

        return total;
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
