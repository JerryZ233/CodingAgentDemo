package com.demo.llm;

import com.demo.execution.CancellationToken;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import java.util.List;

/**
 * Client for communicating with Large Language Models.
 * 
 * This interface defines the contract for:
 * 1. Sending messages to the LLM
 * 2. Receiving responses (text and/or tool calls)
 * 3. Managing the conversation history
 */
public interface LLMClient extends AutoCloseable {

    @Override
    default void close() { }
    
    /**
     * Sends messages to the LLM and receives a response.
     * 
     * @param messages Conversation history
     * @param toolsDescription Description of available tools
     * @return Response containing text and/or tool calls
     */
    LLMResponse sendMessage(List<Message> messages, String toolsDescription);

    default LLMResponse sendMessage(List<Message> messages, String toolsDescription, CancellationToken token) {
        token.throwIfCancellationRequested();
        LLMResponse response = sendMessage(messages, toolsDescription);
        token.throwIfCancellationRequested();
        return response;
    }

    default LLMResponse sendMessage(List<Message> messages, String toolsDescription, CancellationToken token,
                                   java.util.function.Consumer<LLMFailure> onRetry) {
        return sendMessage(messages, toolsDescription, token);
    }

    /** Compatible entry point: simple scripted clients remain functional interfaces. */
    default LLMResponse sendMessage(List<Message> messages, String toolsDescription, CancellationToken token,
                                   java.util.function.Consumer<LLMFailure> onRetry, RequestPurpose purpose) {
        java.util.Objects.requireNonNull(purpose);
        return sendMessage(messages, toolsDescription, token, onRetry);
    }
    
    /**
     * Response from the LLM containing text and/or tool calls.
     */
    class LLMResponse {
        private final String text;
        private final List<ToolCall> toolCalls;
        private final boolean error;
        private final LLMFailure failure;
        private final TokenUsage usage;

        public LLMResponse(String text, List<ToolCall> toolCalls, TokenUsage usage) {
            this(text, toolCalls, false, null, usage);
        }

        public java.util.Optional<TokenUsage> getUsage() { return java.util.Optional.ofNullable(usage); }
        
        public LLMResponse(String text, List<ToolCall> toolCalls) {
            this(text, toolCalls, false);
        }

        private LLMResponse(String text, List<ToolCall> toolCalls, boolean error) {
            this(text, toolCalls, error, null);
        }

        private LLMResponse(String text, List<ToolCall> toolCalls, boolean error, LLMFailure failure) {
            this(text, toolCalls, error, failure, null);
        }

        private LLMResponse(String text, List<ToolCall> toolCalls, boolean error, LLMFailure failure, TokenUsage usage) {
            this.text = text;
            this.toolCalls = toolCalls;
            this.error = error;
            this.failure = failure;
            this.usage = usage;
        }

        public static LLMResponse error(LLMFailure failure) {
            return new LLMResponse(LLMErrorFormatter.format(failure), null, true, failure);
        }

        public java.util.Optional<LLMFailure> getFailure() { return java.util.Optional.ofNullable(failure); }

        public static LLMResponse error(String message) {
            return new LLMResponse(message, null, true);
        }
        
        public String getText() {
            return text;
        }
        
        public List<ToolCall> getToolCalls() {
            return toolCalls;
        }
        
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }

        public boolean isError() {
            return error;
        }
    }
}
