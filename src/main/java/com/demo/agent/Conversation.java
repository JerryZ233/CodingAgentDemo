package com.demo.agent;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.model.ToolResult;
import java.util.ArrayList;
import java.util.List;

/** Raw conversation state. Request preparation and persistence belong to callers. */
public final class Conversation {
    private final List<Message> messages = new ArrayList<>();
    private long contentRevision;

    /** Restore caller-owned history; persistence belongs to SessionService. */
    public static Conversation fromMessages(List<Message> history) {
        return fromMessages(history, history.size());
    }

    public static Conversation fromMessages(List<Message> history, long contentRevision) {
        if (contentRevision < history.size()) throw new IllegalArgumentException("Invalid content revision");
        Conversation context = new Conversation();
        context.messages.addAll(history.stream().map(Message::copyOf).toList());
        context.contentRevision = contentRevision;
        return context;
    }

    public long getContentRevision() { return contentRevision; }

    private void append(Message message) {
        long next = Math.incrementExact(contentRevision);
        messages.add(message);
        contentRevision = next;
    }

    /**
     * Adds a user message to the conversation history.
     *
     * @param content The content of the user message
     */
    public void addUserMessage(String content) {
        append(Message.user(content));
    }

    /**
     * Adds an assistant message to the conversation history.
     *
     * @param content The content of the assistant message
     */
    public void addAssistantMessage(String content) {
        append(Message.assistant(content));
    }

    public void addAssistantToolCalls(String content, List<ToolCall> toolCalls) {
        append(Message.assistantToolCalls(content, toolCalls));
    }

    public void addToolResult(ToolCall toolCall, String content) {
        append(Message.tool(toolCall.getId(), toolCall.getToolName(), content));
    }

    public void addToolResult(ToolCall toolCall, ToolResult result) {
        String error = result.isSuccess() ? null : result.getOutput();
        append(Message.tool(
                toolCall.getId(),
                toolCall.getToolName(),
                result.getOutput(),
                result.isSuccess(),
                error));
    }

    /**
     * Returns the list of messages in the conversation history.
     *
     * @return The message list
     */
    public List<Message> getMessages() {
        return messages.stream().map(Message::copyOf).toList();
    }

    /**
     * Clears all messages from the conversation history.
     */
    public void clear() {
        if (messages.isEmpty()) return;
        long next = Math.incrementExact(contentRevision);
        messages.clear();
        contentRevision = next;
    }

}
